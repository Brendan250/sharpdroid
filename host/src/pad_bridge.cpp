#include "pad_bridge.h"

#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

namespace HostLayer::PadBridge {

namespace {

// the java side of rumble. it holds the Vibrator and the activity that owns it; nothing below this
// line knows what a VibrationEffect is.
constexpr const char* HelperClass = "com/mircowuffwuff/sharpdroid/PadRumble";

JavaVM* VM {};
jclass Helper {};
jmethodID RumbleMethod {};

bool BridgeEnabled {};
bool TraceEnabled {};
bool SelfTestEnabled {};
bool Complained {};

std::atomic<uint64_t> Reads {};
std::atomic<uint64_t> ConnectedReads {};
// requests counted where the guest makes them and deliveries where the platform takes them, because a
// gap between the two is the whole failure mode of an asynchronous path: the guest asking and the
// vibrator moving are different claims, and one number could not tell them apart.
std::atomic<uint64_t> Requests {};
std::atomic<uint64_t> Rumbles {};
std::atomic<uint64_t> Refused {};

// the latest state and nothing older. a mutex rather than a seqlock because the write rate is a
// person's thumbs and the read rate is a thousand a second at the very most, so there is no
// contention to design around and a lock is the version that is obviously correct.
std::mutex StateGate;
WireState Latest {};

// --- rumble delivery ------------------------------------------------------------------------
//
// one host thread, created on the first request and idle on a condition variable otherwise. it exists
// because the guest's thread must not do this waiting; see the header.
std::mutex RumbleGate;
std::condition_variable RumbleWake;
uint8_t WantedLarge {};
uint8_t WantedSmall {};
// a generation rather than a boolean, so that two requests arriving between deliveries collapse to
// the newest instead of the older one winning or both being sent.
uint64_t WantedGeneration {};
uint64_t DeliveredGeneration {};
bool DeliveryStarted {};

void DeliveryThread() {
  // attached once for the life of the thread and detached when it ends, rather than through the
  // per-thread attachment the file layer needs. there is exactly one of these and it is ours, so
  // there is nothing to keep in a thread_local and no guest thread to detach on an exit path this
  // file cannot see.
  JNIEnv* E {};
  JavaVMAttachArgs Args {JNI_VERSION_1_6, "sharpdroid-rumble", nullptr};
  if (!VM || VM->AttachCurrentThread(&E, &Args) != JNI_OK) {
    std::printf("[pad] the rumble thread could not attach to the runtime; rumble is dropped\n");
    std::fflush(stdout);
    return;
  }

  for (;;) {
    uint8_t Large {};
    uint8_t Small {};
    {
      std::unique_lock<std::mutex> Lock(RumbleGate);
      RumbleWake.wait(Lock, [] { return WantedGeneration != DeliveredGeneration; });
      Large = WantedLarge;
      Small = WantedSmall;
      DeliveredGeneration = WantedGeneration;
    }

    if (Helper && RumbleMethod) {
      const jboolean Took =
        E->CallStaticBooleanMethod(Helper, RumbleMethod, static_cast<jint>(Large), static_cast<jint>(Small));
      // an exception left pending would be delivered at this thread's next JNI call, which is the
      // next rumble and a different request entirely. the java side catches its own; this is for one
      // thrown before it could.
      if (E->ExceptionCheck()) {
        E->ExceptionDescribe();
        E->ExceptionClear();
      } else if (Took) {
        // **counted on the platform's answer rather than on the call returning.** a void method
        // reported success for anything that did not crash, so a request refused for want of the
        // VIBRATE permission counted as delivered -- and that permission is not consulted by any of the
        // capability checks, so nothing earlier would have contradicted it.
        if (Rumbles.fetch_add(1, std::memory_order_relaxed) == 0) {
          // said out loud, because the alternative evidence is the *absence* of the failure line
          // above, and an absence is not a measurement.
          std::printf("[pad] the platform accepted a rumble, so the delivery path works end to end\n");
          std::fflush(stdout);
        }
      }
    }
  }
}

void RequestRumble(uint8_t Large, uint8_t Small) {
  {
    std::lock_guard<std::mutex> Lock(RumbleGate);
    WantedLarge = Large;
    WantedSmall = Small;
    ++WantedGeneration;
    if (!DeliveryStarted) {
      DeliveryStarted = true;
      // detached, and never joined. it waits forever by design and the process ends by _exit on the
      // guest's own exit_group, so there is no shutdown path for it to participate in.
      std::thread(DeliveryThread).detach();
    }
  }
  RumbleWake.notify_one();
}

} // namespace

void SetEnabled(bool Enable) {
  BridgeEnabled = Enable;
}

bool Enabled() {
  return BridgeEnabled;
}

void SetTrace(bool Enable) {
  TraceEnabled = Enable;
}

void SetSelfTest(bool Enable) {
  SelfTestEnabled = Enable;
}

void OnLoad(JavaVM* Vm) {
  VM = Vm;
  JNIEnv* E {};
  if (Vm->GetEnv(reinterpret_cast<void**>(&E), JNI_VERSION_1_6) != JNI_OK || !E) {
    return;
  }

  // **FindClass here and nowhere else**, for the reason the file layer's bridge spells out: JNI_OnLoad
  // runs with the app's class loader in scope, and on a thread this library attached itself FindClass
  // searches the system loader instead and has never heard of anything in the APK.
  jclass Local = E->FindClass(HelperClass);
  if (!Local) {
    E->ExceptionClear();
    std::printf("[pad] %s not found -- rumble is dropped, and pad reads are unaffected\n", HelperClass);
    std::fflush(stdout);
    return;
  }
  Helper = static_cast<jclass>(E->NewGlobalRef(Local));
  E->DeleteLocalRef(Local);

  // boolean rather than void, so that a refusal on the platform's side is a false here and not a
  // successful call. see the delivery thread.
  RumbleMethod = E->GetStaticMethodID(Helper, "rumble", "(II)Z");
  if (!RumbleMethod) {
    E->ExceptionClear();
    Helper = nullptr;
    std::printf("[pad] %s is missing rumble(II)Z -- rumble is dropped\n", HelperClass);
    std::fflush(stdout);
  }
}

void SetState(const WireState& State) {
  std::lock_guard<std::mutex> Lock(StateGate);
  Latest = State;
}

uint64_t Handle(FEXCore::Core::CpuStateFrame*, FEXCore::HLE::SyscallArguments* Args) {
  const uint32_t Id = static_cast<uint32_t>(Args->Argument[0] & 0xFFFF);

  // an unenabled bridge answers rather than letting the magic number fall through to the syscall
  // table as an unhandled number, which is what the audio thunk does and for the same reason: the
  // guest reaches this through libc's own syscall wrapper, so there is no version of "not staged"
  // for it to discover. the fork then reports no pad and the run is the one it was before.
  if (!BridgeEnabled) {
    if (!Complained) {
      Complained = true;
      std::printf("[pad] guest asked for pad state and the bridge is not enabled (pass --pad)\n");
      std::fflush(stdout);
    }
    Refused.fetch_add(1, std::memory_order_relaxed);
    return static_cast<uint64_t>(static_cast<int64_t>(-1));
  }

  switch (Id) {
  case Command_Read: {
    const uint32_t Version = static_cast<uint32_t>(Args->Argument[1]);
    auto* Out = reinterpret_cast<WireState*>(Args->Argument[2]);
    const uint64_t Size = Args->Argument[3];

    // **the check that replaces a shared structure layout.** the guest says which format it expects
    // and how many bytes it has room for, and a disagreement is refused and named rather than
    // written into. this is what makes two repositories safe to release independently.
    if (Version != WireVersion || Size != sizeof(WireState)) {
      if (Refused.fetch_add(1, std::memory_order_relaxed) < 3) {
        std::printf("[pad] refusing a read: the guest asked for wire version %u at %llu bytes and "
                    "this host layer speaks version %u at %zu. the payload and the host layer are "
                    "out of step\n",
                    Version, static_cast<unsigned long long>(Size), WireVersion, sizeof(WireState));
        std::fflush(stdout);
      }
      return static_cast<uint64_t>(static_cast<int64_t>(-1));
    }
    if (!Out) {
      Refused.fetch_add(1, std::memory_order_relaxed);
      return static_cast<uint64_t>(static_cast<int64_t>(-1));
    }

    // no pointer translation, which is what route B buys everywhere else in here too: guest and host
    // share one address space 1:1, so the guest's buffer is written in place.
    WireState State {};
    {
      std::lock_guard<std::mutex> Lock(StateGate);
      State = Latest;
    }
    *Out = State;

    // **one line, on the first read, whether or not anything is being traced.** the run summary is
    // the only other place a count appears and it prints when the guest *returns* -- which a game
    // never does, since a run ends by exit_group or by the app killing the process. so without this
    // there is no evidence anywhere that the guest ever asked, and "the pad does nothing" would look
    // identical to "the payload never polled".
    if (Reads.fetch_add(1, std::memory_order_relaxed) == 0) {
      std::printf("[pad] the guest is polling pad state (wire version %u)\n", WireVersion);
      std::fflush(stdout);
      if (SelfTestEnabled) {
        // full strength on the strong motor, once. **this is the only place anything here fabricates a
        // request**, and it says so in the log so that a buzz can never be mistaken for a game's own.
        std::printf("[pad] self-test: requesting one rumble at full strength. a buzz now means the "
                    "delivery path works and the game did not ask for it\n");
        std::fflush(stdout);
        RequestRumble(255, 0);
      }
    }
    if (State.Connected) {
      // and one on the first read that finds a pad, which is the question the line above cannot
      // answer: a guest polling a bridge nobody has pushed to gets a valid answer of "no pad".
      if (ConnectedReads.fetch_add(1, std::memory_order_relaxed) == 0) {
        std::printf("[pad] a pad is connected and its state is reaching the guest\n");
        std::fflush(stdout);
      }
    }
    if (TraceEnabled) {
      std::printf("[pad] read: buttons=0x%05X sticks=%u,%u/%u,%u triggers=%u,%u connected=%u\n",
                  State.Buttons, State.LeftX, State.LeftY, State.RightX, State.RightY,
                  State.LeftTrigger, State.RightTrigger, State.Connected);
    }
    // how many pads were written, which is what the fork's seam asks for. one or none, since the
    // frontend has no way to name a second yet.
    return State.Connected ? 1u : 0u;
  }

  case Command_Rumble: {
    const uint8_t Large = static_cast<uint8_t>(Args->Argument[1] & 0xFF);
    const uint8_t Small = static_cast<uint8_t>(Args->Argument[2] & 0xFF);
    if (TraceEnabled) {
      std::printf("[pad] rumble: large=%u small=%u\n", Large, Small);
    }
    // the same reasoning as the first read: a game that never asks for rumble and a rumble path that
    // is broken are the same silence otherwise.
    if (Requests.fetch_add(1, std::memory_order_relaxed) == 0) {
      std::printf("[pad] the guest asked for rumble (large=%u small=%u)\n", Large, Small);
      std::fflush(stdout);
    }
    // returns as soon as the request is recorded. the waiting is the delivery thread's.
    RequestRumble(Large, Small);
    return 0;
  }

  default:
    if (Refused.fetch_add(1, std::memory_order_relaxed) < 3) {
      std::printf("[pad] call to unknown command id %u\n", Id);
      std::fflush(stdout);
    }
    return static_cast<uint64_t>(static_cast<int64_t>(-1));
  }
}

uint64_t ReadCount() {
  return Reads.load(std::memory_order_relaxed);
}

uint64_t ConnectedReadCount() {
  return ConnectedReads.load(std::memory_order_relaxed);
}

uint64_t RumbleRequestCount() {
  return Requests.load(std::memory_order_relaxed);
}

uint64_t RumbleCount() {
  return Rumbles.load(std::memory_order_relaxed);
}

uint64_t RefusedCount() {
  return Refused.load(std::memory_order_relaxed);
}

void Report() {
  if (!BridgeEnabled) {
    return;
  }
  // **the zero is the interesting reading, which is why the line prints unconditionally.** a payload
  // that never polls and a pad that was never touched produce identical silence otherwise, and this
  // project has read a zero as success before.
  std::printf("[pad] %llu reads, %llu of them with a pad connected, %llu rumbles asked and %llu "
              "delivered, %llu refused\n",
              static_cast<unsigned long long>(ReadCount()),
              static_cast<unsigned long long>(ConnectedReadCount()),
              static_cast<unsigned long long>(RumbleRequestCount()),
              static_cast<unsigned long long>(RumbleCount()),
              static_cast<unsigned long long>(RefusedCount()));
  if (ReadCount() == 0) {
    std::printf("[pad]   the guest never asked. is the payload's contract generation new enough to "
                "read SHARPEMU_HOST_INPUT?\n");
  }
  std::fflush(stdout);
}

} // namespace HostLayer::PadBridge
