// sharpemu-android host layer — delivering signals to the guest.
//
// the ELF loader's fault handling caught guest faults and reported them. this is the other half:
// building the x86-64 signal
// frame the guest's own handler expects, redirecting the guest into that handler, and putting
// everything back on rt_sigreturn.
//
// it is the load-bearing piece for SharpEmu specifically. `DirectExecutionBackend.Amd64Compat.cs`
// emulates SSE4a EXTRQ/INSERTQ on #UD by *writing XMM state back through the signal context*, and
// the POSIX path carries a dedicated `_posixXmmContextBridged` flag precisely because that
// write-back has to survive sigreturn. so a signal layer that only preserves GPRs would look
// fine on a hello world and fall apart on the thing we actually want to run.

#pragma once

#include <atomic>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace FEXCore::Context {
class Context;
}
namespace FEXCore::Core {
struct InternalThreadState;
struct CpuStateFrame;
} // namespace FEXCore::Core
namespace FEXCore {
struct SignalDelegatorConfig;
}

namespace HostLayer {

struct GuestThread;

// --- the guest's own ABI ---------------------------------------------------------------------
//
// these are linux x86-64 kernel structures, not the host's. they are spelled out here rather than
// taken from any header because the host headers describe arm64, and the two disagree in ways
// that do not announce themselves: x86-64 puts `restorer` in the middle of sigaction, and its
// ucontext has a completely different mcontext.

namespace GuestABI {

// every one of these is spelled with a Guest prefix because the host's <signal.h> defines the
// unprefixed names as macros — a plain `constexpr uint64_t SA_SIGINFO` does not even parse.
constexpr uint64_t GuestSA_NOCLDSTOP = 0x00000001;
constexpr uint64_t GuestSA_SIGINFO = 0x00000004;
constexpr uint64_t GuestSA_RESTORER = 0x04000000;
constexpr uint64_t GuestSA_ONSTACK = 0x08000000;
constexpr uint64_t GuestSA_RESTART = 0x10000000;
constexpr uint64_t GuestSA_NODEFER = 0x40000000;
constexpr uint64_t GuestSA_RESETHAND = 0x80000000;

constexpr uint64_t GuestSIG_DFL = 0;
constexpr uint64_t GuestSIG_IGN = 1;

constexpr int GuestSS_ONSTACK = 1;
constexpr int GuestSS_DISABLE = 2;

// x86-64 `struct sigaction` as the *kernel* sees it: handler, flags, restorer, mask. note that
// `restorer` sits third, which is architecture-specific — arm64's kernel sigaction has no such
// field at all.
struct SigAction {
  uint64_t Handler;
  uint64_t Flags;
  uint64_t Restorer;
  uint64_t Mask; // one 64-bit word: x86-64 has 64 signals
};

struct AltStack {
  uint64_t Sp;
  int32_t Flags;
  uint32_t Padding;
  uint64_t Size;
};
static_assert(sizeof(AltStack) == 24);

// register indexes into ucontext_t::uc_mcontext::gregs, in the order glibc chose and the kernel
// therefore writes.
enum ContextReg {
  REG_R8 = 0,
  REG_R9,
  REG_R10,
  REG_R11,
  REG_R12,
  REG_R13,
  REG_R14,
  REG_R15,
  REG_RDI,
  REG_RSI,
  REG_RBP,
  REG_RBX,
  REG_RDX,
  REG_RAX,
  REG_RCX,
  REG_RSP,
  REG_RIP,
  REG_EFL,
  REG_CSGSFS,
  REG_ERR,
  REG_TRAPNO,
  REG_OLDMASK,
  REG_CR2,
};

// FXSAVE layout, which is what the x86-64 signal frame carries.
struct __attribute__((packed)) FPState {
  uint16_t fcw;
  uint16_t fsw;
  uint16_t ftw;
  uint16_t fop;
  uint64_t fip;
  uint64_t fdp;
  uint32_t mxcsr;
  uint32_t mxcsr_mask;
  __uint128_t st[8];
  __uint128_t xmm[16];
  uint32_t reserved[12];
  // linux uses the last 48 bytes to describe any XSAVE area that follows. left zeroed: without
  // AVX there is nothing beyond this structure to point at.
  uint32_t sw_magic1;
  uint32_t sw_extended_size;
  uint64_t sw_xfeatures;
  uint32_t sw_xstate_size;
  uint32_t sw_padding[7];
};
static_assert(sizeof(FPState) == 512);

struct __attribute__((packed)) MContext {
  uint64_t gregs[23];
  uint64_t fpregs; // guest pointer to FPState
  uint64_t reserved[8];
};
static_assert(sizeof(MContext) == 256);

struct __attribute__((packed)) UContext {
  uint64_t uc_flags;
  uint64_t uc_link;
  AltStack uc_stack;
  MContext uc_mcontext;
  uint64_t uc_sigmask[16];
};
static_assert(sizeof(UContext) == 424);
static_assert(offsetof(UContext, uc_mcontext) == 40);

constexpr uint64_t UC_FP_XSTATE = 1ULL << 0;
constexpr uint64_t UC_SIGCONTEXT_SS = 1ULL << 1;
constexpr uint64_t UC_STRICT_RESTORE_SS = 1ULL << 2;

} // namespace GuestABI

// --- the layer -------------------------------------------------------------------------------
//
// one instance, for the whole process — but only *some* of what it holds is process-wide. handler
// dispositions are, because `sigaction` is a process-wide call on linux and a handler installed by
// one thread is the handler every thread runs. the blocked mask and the alternate stack are not,
// and live in GuestThread; every entry point that needs them therefore names a thread.

class GuestSignals {
public:
  void Attach(FEXCore::Context::Context* CTX, const FEXCore::SignalDelegatorConfig* Config);

  ///< guest-facing syscalls.
  uint64_t SigAction(int Signal, const GuestABI::SigAction* New, GuestABI::SigAction* Old);
  uint64_t SigProcMask(GuestThread& T, int How, const uint64_t* Set, uint64_t* OldSet);
  uint64_t SigAltStack(GuestThread& T, const GuestABI::AltStack* New, GuestABI::AltStack* Old);

  ///< true if the guest installed a real handler for this signal (not default, not ignore).
  bool HasHandler(int Signal) const;
  ///< true if the signal is currently blocked by the given thread's mask.
  bool IsBlocked(const GuestThread& T, int Signal) const;
  ///< true if the disposition asks for an interrupted syscall to be restarted rather than to
  ///< return -EINTR. glibc sets it on almost everything; CoreCLR's PAL sets it too.
  bool WantsRestart(int Signal) const;

  // --- asynchronous signals --------------------------------------------------------------------
  //
  // a signal raised on a thread other than the one raising it cannot be delivered where it is
  // raised: the target's guest state is in the target's host registers, and only the target may
  // longjmp out of its own dispatch loop. so raising is just recording a bit, and delivery happens
  // on the target thread at one of the two places it can safely be redirected — see
  // guest_threads.cpp.

  ///< record a signal as pending on a thread. safe to call from any thread.
  void SetPending(GuestThread& T, int Signal);
  ///< is anything pending on this thread that its mask is not currently holding back?
  bool HasDeliverablePending(const GuestThread& T) const;

  /**
   * @brief Take the next pending signal this thread should act on.
   *
   * @return the signal number, or 0 if there is nothing to act on. the bit is cleared. signals
   * the guest is blocking stay pending, because a blocked signal is not lost — it is waited for.
   * signals the guest ignores are dropped here rather than at the raise, since a disposition can
   * become SIG_IGN after the signal was already queued and linux discards it in exactly that case.
   *
   * must be called on the thread that owns T: the blocked mask is per-thread state with no lock.
   */
  int TakePending(GuestThread& T);

  /**
   * @brief Pull guest state out of a host signal context and into CPUState.
   *
   * guest registers are not in memory while JIT'd code runs — GPRs live in host arm64 registers
   * and XMMs in host vector registers, per FEXCore's static register allocation. everything
   * downstream reads CPUState, so this has to happen first.
   */
  void ReconstructGuestState(GuestThread& T, void* HostUContext);

  /**
   * @brief Claim a fault the JIT generated rather than the CPU raising.
   *
   * an invalid opcode or a #GP is not a host fault at all: FEXCore's decoder recognises it at
   * compile time and emits a block that spills guest state, records what should be raised in
   * `SynchronousFaultData`, and branches to a dispatcher trampoline. this is the path SharpEmu's
   * SSE4a emulation arrives on, since EXTRQ/INSERTQ are #UD.
   *
   * @return the guest signal number to deliver, or 0 if nothing is pending. guest state is
   * already correct when this returns non-zero — the JIT spilled it and RIP already points at
   * the offending instruction, so ReconstructGuestState must *not* be called.
   */
  int TakeGeneratedFault(GuestThread& T);

  /**
   * @brief Build the guest's signal frame and point guest execution at its handler.
   *
   * expects guest state to be in CPUState already — either via ReconstructGuestState for a real
   * host fault, or via TakeGeneratedFault for one the JIT raised. leaves CPUState ready to be
   * dispatched: RIP at the handler, RSP at the frame, and RDI/RSI/RDX carrying the signal
   * number, siginfo and ucontext.
   *
   * @param HostSigInfo the host's siginfo, or nullptr for a generated fault, in which case one
   * is synthesised from what the JIT recorded.
   */
  void DeliverToGuest(GuestThread& T, int Signal, const siginfo_t* HostSigInfo);

  ///< rt_sigreturn: unwind the frame the guest is standing on and restore what it saved.
  void RestoreFromFrame(GuestThread& T);

  uint64_t DeliveredCount() const {
    return Delivered.load(std::memory_order_relaxed);
  }

private:
  uint64_t GuestRestorerTrampoline();

  FEXCore::Context::Context* CTX {};
  const FEXCore::SignalDelegatorConfig* Config {};

  ///< signals are 1..64; index 0 is unused and kept only so the numbering reads naturally.
  ///<
  ///< guarded because any thread may install a handler while any other is faulting. the lock is
  ///< taken on the delivery path too, which is a signal handler — not async-signal-safe in general,
  ///< but every fault we deliver is synchronous, raised by the JIT'd code this same thread is
  ///< running, so it is an ordinary call context and not a reentrant one.
  mutable std::mutex ActionsLock;
  GuestABI::SigAction Actions[65] {};

  std::atomic<uint64_t> Delivered {};
  std::atomic<uint64_t> Trampoline {};
};

} // namespace HostLayer
