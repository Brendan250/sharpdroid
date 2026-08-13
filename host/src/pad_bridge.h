// sharpemu-android host layer — android gamepad state and rumble, across the guest boundary.
//
// this rides the same syscall boundary the vulkan and audio thunks do, in a magic range one along
// again, and it is **not** a thunk in the sense either of those is: there is no NDK library at the
// far end being forwarded to. the host layer answers these itself. what it borrows is the boundary,
// because the boundary is the only place a guest thread's registers are all in CPUState and the only
// mechanism this project has for the guest to ask the host a question.
//
// **the direction is what makes this its own file rather than a third thunk.** pad state originates
// in java — a KeyEvent and a MotionEvent delivered to the activity — and has to reach C# running as
// guest x86-64. that is host to guest, which is the direction a thunk refuses. so it is inverted into
// a pull: the app pushes state down into SetState below, the host holds the latest, and the guest
// *asks* for it. no host thread ever enters guest code, which is the invariant the other two hold and
// the reason neither of them accepts a guest callback.
//
// the alternative was a page of host memory whose address the guest is handed at startup, which is a
// load rather than a trap. measured on the device, a trap here is 34.3 ns against a load's 0.79 —
// forty-three times, and both nothing: the pad is sampled at most once a millisecond per polling
// guest thread, so the trap costs about 34 µs per second of one core, roughly two thousandths of one
// frame per second at 60 fps. what the trap buys for that is three things the page cannot:
//
//   - **no structure layout shared between two repositories.** the wire format below is checked by
//     the call itself — a version and a byte count go in, and a mismatch is refused and said out
//     loud. a mirrored struct is checked by nobody and yields plausible wrong values.
//   - **rumble.** that direction is guest to host, which is what this already is. a page needs a
//     second mechanism invented for it, plus something polling the page to notice.
//   - **the guest never receives a host address to dereference.** a stale one is a segfault inside
//     the emulator at a moment nothing is watching.
//
// **rumble is not delivered on the guest's thread**, and that is the hardest constraint here rather
// than an implementation detail. there is no NDK vibrator, so it is a JNI call into the app, and a
// binder round trip to the system server takes long enough that making a guest thread wait for it is
// the same mistake that stopped audio dead partway into runs — the host layer delivers asynchronous
// signals at syscall exits only, and CoreCLR suspends every thread with SIGRTMIN to collect, so a
// guest thread parked in a platform call is one that cannot acknowledge a GC suspension. so the
// guest's call records the request and returns, and one host thread of ours does the waiting.

#pragma once

#include <FEXCore/Core/CoreState.h>
#include <FEXCore/HLE/SyscallHandler.h>

#include <cstdint>

typedef struct _JavaVM JavaVM;

namespace HostLayer::PadBridge {

// "PD" in the top 16 bits, one range along from audio's "SA". the same reasoning as both of theirs:
// real linux x86-64 syscall numbers are all below 1000 and this FEXCore has no table indexed by
// syscall number, so the whole upper range is free and an unrecognised number can never be mistaken
// for one of ours. distinct from the other two so all three stay decodable apart in a trace.
inline constexpr uint64_t Magic = 0x50440000;
inline constexpr uint64_t MagicMask = 0xFFFF0000;

inline bool IsThunkCall(uint64_t SyscallNumber) {
  return (SyscallNumber & MagicMask) == Magic;
}

// the two commands. read is a poll and rumble is a request; nothing else crosses.
enum Command : uint32_t {
  Command_Read = 0,
  Command_Rumble = 1,
  CommandCount,
};

// **the wire format, and the only place it is written down.** the guest passes this version and the
// byte count it expects; a mismatch on either is refused rather than read, because the two sides of
// this are in different repositories with different release cadences and no compiler ever sees both.
// that check is the whole reason a trap was chosen over shared memory, so it is not optional.
//
// bump the version when a field's meaning changes. appending a field changes the size and is caught
// by the size check on its own.
inline constexpr uint32_t WireVersion = 1;

// sticks are 0..255 with 128 centred and Y growing downward, and triggers are 0..255. that is the
// seam the fork's own gamepad snapshot already uses, so nothing is converted on either side of this.
struct WireState {
  uint32_t Buttons;
  uint8_t LeftX;
  uint8_t LeftY;
  uint8_t RightX;
  uint8_t RightY;
  uint8_t LeftTrigger;
  uint8_t RightTrigger;
  uint8_t Connected;
  uint8_t Reserved;
};
static_assert(sizeof(WireState) == 12, "the wire format is a fixed 12 bytes and the guest checks it");

// enabled by --pad, in the shape --vulkan and --audio have. off by default, so a run that does not
// ask for it is the argument vector every earlier measurement was taken on and none of them stops
// being comparable.
void SetEnabled(bool Enabled);
bool Enabled();
void SetTrace(bool Enabled);

// **--pad-selftest: request one rumble the moment the guest first polls, so that the delivery path can
// be shown to work on a title that never asks for one.**
//
// it exists because the two directions here fail independently and only one of them is exercised by
// an ordinary run. a game that polls the pad proves the read path every frame; rumble is proven by
// nothing at all unless the game happens to vibrate, and "it compiles" is not evidence. this fires
// the real path — the guest's own trap is the only link it substitutes for, and that link is the same
// call the proven read makes.
//
// off by default, and tied to the first read rather than to a timer: a fixed delay would fire during
// a boot whose length varies, and a rumble nobody was watching for is worth nothing.
void SetSelfTest(bool Enabled);

// resolved once from JNI_OnLoad, with the app's class loader in scope. without it rumble has nowhere
// to go and is dropped; reads are unaffected, since state comes from the app pushing rather than
// from the host layer asking. the shell binary never calls this, so a guest run outside an app sees a
// bridge that answers reads with "no pad" — which is honest rather than a failure.
void OnLoad(JavaVM* VM);

// the app's push, off a KeyEvent or a MotionEvent. the latest wins and nothing is queued: a poll
// wants the current position of a stick, and a backlog of stick positions is a backlog of wrong
// answers. connected false is what a pad going away looks like.
void SetState(const WireState& State);

// the dispatch entry, called from LinuxSyscallHandler::Dispatch for any magic number.
uint64_t Handle(FEXCore::Core::CpuStateFrame* Frame, FEXCore::HLE::SyscallArguments* Args);

// counters, for the run summary. **Reads is the liveness counter and it is the point of the set**: a
// pad that is never touched and a bridge the guest never asks look identical in every other way, and
// a zero here separates them. ConnectedReads is how many of those polls found a pad.
uint64_t ReadCount();
uint64_t ConnectedReadCount();
// asked and delivered are separate counts on purpose. rumble is delivered asynchronously, so "the
// guest asked" and "the device buzzed" are different claims and a gap between them is exactly what a
// broken delivery thread looks like.
uint64_t RumbleRequestCount();
uint64_t RumbleCount();
uint64_t RefusedCount();

// what the run summary prints. named rather than inlined at the call site so that the shell binary
// and the app produce the same line.
void Report();

} // namespace HostLayer::PadBridge
