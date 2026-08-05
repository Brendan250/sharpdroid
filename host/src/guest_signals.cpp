#include "guest_signals.h"

#include "guest_threads.h"
#include "vma_tracker.h"

#include <FEXCore/Core/CodeCache.h>
#include <FEXCore/Core/Context.h>
#include <FEXCore/Core/CoreState.h>
#include <FEXCore/Core/SignalDelegator.h>
#include <FEXCore/Debug/InternalThreadState.h>

#include <cerrno>
#include <cstring>
#include <sys/mman.h>
#include <ucontext.h>

namespace HostLayer {

namespace {

uint64_t AlignDown(uint64_t Value, uint64_t Alignment) {
  return Value & ~(Alignment - 1);
}

// the arm64 signal context carries its vector registers in a variable-length chain of records
// inside mcontext_t::__reserved, each tagged with a magic and a size. FEX assumes the FPSIMD
// record is first and asserts on the magic; walking the chain costs nothing and is correct even
// if the kernel ever puts an SVE or extension record ahead of it.
struct Arm64CtxHeader {
  uint32_t Magic;
  uint32_t Size;
};
constexpr uint32_t FPSIMD_MAGIC_ = 0x46508001;

struct Arm64FPSimdContext {
  Arm64CtxHeader Head;
  uint32_t FPSR;
  uint32_t FPCR;
  __uint128_t VRegs[32];
};

const Arm64FPSimdContext* FindFPSimdContext(const ucontext_t* Context) {
  size_t Offset = 0;
  while (Offset + sizeof(Arm64CtxHeader) <= sizeof(Context->uc_mcontext.__reserved)) {
    const auto* Header = reinterpret_cast<const Arm64CtxHeader*>(&Context->uc_mcontext.__reserved[Offset]);
    if (Header->Magic == FPSIMD_MAGIC_) {
      return reinterpret_cast<const Arm64FPSimdContext*>(Header);
    }
    if (Header->Size == 0) {
      break;
    }
    Offset += Header->Size;
  }
  return nullptr;
}

// x86 trap numbers, as they appear in ucontext_t::uc_mcontext::gregs[REG_TRAPNO]. a guest fault
// handler that inspects this — and SharpEmu's does, to tell a page fault from an illegal
// instruction — gets a wrong answer if we leave it at the signal number.
constexpr uint64_t X86_TRAPNO_UD = 6;  // invalid opcode
constexpr uint64_t X86_TRAPNO_DE = 0;  // divide error
constexpr uint64_t X86_TRAPNO_BP = 3;  // breakpoint
constexpr uint64_t X86_TRAPNO_PF = 14; // page fault

uint64_t TrapNumberForSignal(int Signal, const siginfo_t* Info) {
  switch (Signal) {
  case SIGSEGV:
  case SIGBUS: return X86_TRAPNO_PF;
  case SIGILL: return X86_TRAPNO_UD;
  case SIGFPE: return X86_TRAPNO_DE;
  case SIGTRAP: return X86_TRAPNO_BP;
  // zero, and not the signal number. everything above corresponds to a real x86 exception vector;
  // a signal that arrived because another thread sent it corresponds to none, and that is what the
  // kernel writes for one. it matters here because SharpEmu reads this field.
  default: return 0;
  }
}

// what a signal does when the guest has installed no handler for it. only the "and nothing
// happens" cases are listed — everything else terminates the process, which is what linux does
// for the real-time signals .NET raises among others.
bool DefaultIsIgnore(int Signal) {
  switch (Signal) {
  case SIGCHLD:
  case SIGURG:
  case SIGWINCH:
  case SIGCONT: return true;
  default: return false;
  }
}

} // namespace

void GuestSignals::Attach(FEXCore::Context::Context* NewCTX, const FEXCore::SignalDelegatorConfig* NewConfig) {
  CTX = NewCTX;
  Config = NewConfig;
}

// --- the guest-facing syscalls ---------------------------------------------------------------

uint64_t GuestSignals::SigAction(int Signal, const GuestABI::SigAction* New, GuestABI::SigAction* Old) {
  if (Signal <= 0 || Signal > 64 || Signal == SIGKILL || Signal == SIGSTOP) {
    return static_cast<uint64_t>(-EINVAL);
  }
  std::lock_guard Lock {ActionsLock};
  if (Old) {
    *Old = Actions[Signal];
  }
  if (New) {
    Actions[Signal] = *New;
  }
  return 0;
}

uint64_t GuestSignals::SigProcMask(GuestThread& T, int How, const uint64_t* Set, uint64_t* OldSet) {
  constexpr int SIG_BLOCK_ = 0, SIG_UNBLOCK_ = 1, SIG_SETMASK_ = 2;
  if (OldSet) {
    *OldSet = T.BlockedMask;
  }
  if (!Set) {
    return 0;
  }
  switch (How) {
  case SIG_BLOCK_: T.BlockedMask |= *Set; break;
  case SIG_UNBLOCK_: T.BlockedMask &= ~*Set; break;
  case SIG_SETMASK_: T.BlockedMask = *Set; break;
  default: return static_cast<uint64_t>(-EINVAL);
  }
  // SIGKILL and SIGSTOP can never be blocked.
  T.BlockedMask &= ~((1ULL << (SIGKILL - 1)) | (1ULL << (SIGSTOP - 1)));
  return 0;
}

uint64_t GuestSignals::SigAltStack(GuestThread& T, const GuestABI::AltStack* New, GuestABI::AltStack* Old) {
  if (Old) {
    *Old = T.AltStack;
  }
  if (New) {
    T.AltStack = *New;
  }
  return 0;
}

bool GuestSignals::HasHandler(int Signal) const {
  if (Signal <= 0 || Signal > 64) {
    return false;
  }
  std::lock_guard Lock {ActionsLock};
  const uint64_t Handler = Actions[Signal].Handler;
  return Handler != GuestABI::GuestSIG_DFL && Handler != GuestABI::GuestSIG_IGN;
}

bool GuestSignals::IsBlocked(const GuestThread& T, int Signal) const {
  return Signal > 0 && Signal <= 64 && (T.BlockedMask & (1ULL << (Signal - 1))) != 0;
}

bool GuestSignals::WantsRestart(int Signal) const {
  if (Signal <= 0 || Signal > 64) {
    return false;
  }
  std::lock_guard Lock {ActionsLock};
  return (Actions[Signal].Flags & GuestABI::GuestSA_RESTART) != 0;
}

// --- asynchronous signals -----------------------------------------------------------------------

void GuestSignals::SetPending(GuestThread& T, int Signal) {
  if (Signal <= 0 || Signal > 64) {
    return;
  }
  T.PendingSignals.fetch_or(1ULL << (Signal - 1), std::memory_order_release);
}

bool GuestSignals::HasDeliverablePending(const GuestThread& T) const {
  // the blocked mask is read without a lock, and can only be written by T's own thread. a sender
  // asking this question can therefore see a stale answer — which is harmless in both directions:
  // a signal that looks blocked stays pending and is picked up when the mask next changes, and one
  // that looks deliverable costs at most a host signal that finds nothing to do.
  return (T.PendingSignals.load(std::memory_order_acquire) & ~T.BlockedMask) != 0;
}

int GuestSignals::TakePending(GuestThread& T) {
  for (;;) {
    const uint64_t Deliverable = T.PendingSignals.load(std::memory_order_acquire) & ~T.BlockedMask;
    if (!Deliverable) {
      return 0;
    }
    // lowest first, which is the order linux delivers in.
    const int Signal = __builtin_ctzll(Deliverable) + 1;
    T.PendingSignals.fetch_and(~(1ULL << (Signal - 1)), std::memory_order_acq_rel);

    uint64_t Handler;
    {
      std::lock_guard Lock {ActionsLock};
      Handler = Actions[Signal].Handler;
    }
    if (Handler == GuestABI::GuestSIG_IGN || (Handler == GuestABI::GuestSIG_DFL && DefaultIsIgnore(Signal))) {
      continue;
    }
    return Signal;
  }
}

// --- reconstructing guest state out of a host signal context ----------------------------------

void GuestSignals::ReconstructGuestState(GuestThread& T, void* HostUContext) {
  auto* Context = static_cast<ucontext_t*>(HostUContext);
  auto* Thread = T.Thread;
  auto& State = Thread->CurrentFrame->State;
  const uint64_t HostPC = Context->uc_mcontext.pc;
  const auto* HostGPRs = reinterpret_cast<const uint64_t*>(&Context->uc_mcontext.regs[0]);

  // EFLAGS first, while the host registers still hold what the JIT left in them: FEX keeps the
  // parity and adjust flags pinned in host GPRs rather than in CPUState, so reconstructing them
  // means reading the signal context, and PSTATE supplies the rest.
  const uint32_t EFlags = CTX->ReconstructCompactedEFLAGS(Thread, true, HostGPRs, Context->uc_mcontext.pstate);

  // GPRs. SRAGPRMapping[guest index] names the host arm64 register holding that guest register.
  for (uint16_t i = 0; i < Config->SRAGPRCount && i < 16; ++i) {
    const uint8_t HostReg = Config->SRAGPRMapping[i];
    if (HostReg < 31) {
      State.gregs[i] = HostGPRs[HostReg];
    }
  }

  // XMMs, the same way but through the vector registers. this is the half that M1b left undone
  // and the half SharpEmu's SSE4a emulation depends on.
  if (const auto* FPSimd = FindFPSimdContext(Context)) {
    __uint128_t XMM[16] {};
    for (uint16_t i = 0; i < Config->SRAFPRCount && i < 16; ++i) {
      const uint8_t HostReg = Config->SRAFPRMapping[i];
      if (HostReg < 32) {
        XMM[i] = FPSimd->VRegs[HostReg];
      }
    }
    // the low 128 bits only. passing nullptr for YMM_High is not "there is no upper half" — since
    // M3b there is — it is "we did not recover one, so leave CPUState's alone". on the AVX128 path
    // FEX takes without SVE256, the upper halves live in CPUState.avx_high rather than in the 16
    // SRA-mapped host vector registers, so what is in the signal context is genuinely just the low
    // halves and this is the honest thing to write back.
    //
    // the gap that leaves: a fault taken mid-block with an upper half still live in a host register
    // loses it. narrow, and it needs a guest that both uses AVX and handles its own signals.
    CTX->SetXMMRegistersFromState(Thread, XMM, nullptr);
  }

  // and the guest instruction that was executing, recovered from the host PC through FEXCore's
  // block metadata.
  State.rip = CTX->RestoreRIPFromHostPC(Thread, HostPC);

  // write the reconstructed flags back into CPUState. everything downstream — the frame we are
  // about to build, and the dispatcher we re-enter afterwards — reads them from there.
  CTX->SetFlagsFromCompactedEFLAGS(Thread, EFlags);
}

// --- building the frame ------------------------------------------------------------------------

uint64_t GuestSignals::GuestRestorerTrampoline() {
  if (const uint64_t Existing = Trampoline.load(std::memory_order_acquire)) {
    return Existing;
  }
  // glibc and bionic both set SA_RESTORER on x86-64, so this is a fallback rather than the usual
  // path — but a guest that installs a handler without one would otherwise return from it into
  // whatever happened to be on the stack. eight bytes of guest code costs nothing and turns that
  // into correct behaviour.
  //
  //   b8 0f 00 00 00   mov eax, 15   (rt_sigreturn)
  //   0f 05            syscall
  static const unsigned char Code[] = {0xB8, 0x0F, 0x00, 0x00, 0x00, 0x0F, 0x05};

  void* Page = ::mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (Page == MAP_FAILED) {
    return 0;
  }
  std::memcpy(Page, Code, sizeof(Code));

  // two threads can reach here at once. whoever loses the exchange drops its page and uses the
  // winner's, which costs a wasted mapping once at most and keeps every guest looking at the same
  // restorer address.
  uint64_t Expected = 0;
  if (!Trampoline.compare_exchange_strong(Expected, reinterpret_cast<uint64_t>(Page), std::memory_order_acq_rel)) {
    ::munmap(Page, 4096);
    return Expected;
  }
  // guest code, so the VMA tracker has to know it is executable — otherwise the decoder refuses
  // the moment a handler without SA_RESTORER returns, which is the one case this page exists for.
  VMA::Record(reinterpret_cast<uint64_t>(Page), 4096, PROT_READ | PROT_EXEC);
  return reinterpret_cast<uint64_t>(Page);
}

int GuestSignals::TakeGeneratedFault(GuestThread& T) {
  auto& Sync = T.Thread->CurrentFrame->SynchronousFaultData;
  if (!Sync.FaultToTopAndGeneratedException) {
    return 0;
  }

  T.Generated.Pending = true;
  T.Generated.TrapNo = Sync.TrapNo;
  T.Generated.SiCode = Sync.si_code;
  T.Generated.ErrCode = Sync.err_code;
  const int Signal = Sync.Signal;

  // clear it, or the next thing to look at this frame will believe the fault is still pending.
  Sync.FaultToTopAndGeneratedException = false;
  return Signal;
}

void GuestSignals::DeliverToGuest(GuestThread& T, int Signal, const siginfo_t* HostSigInfo) {
  auto* Thread = T.Thread;
  auto* Frame = Thread->CurrentFrame;
  auto& State = Frame->State;

  // a copy, taken under the lock, rather than a reference into the shared table: the handler
  // address, the mask to apply and the restorer must all describe one disposition, and another
  // thread calling sigaction halfway through building this frame would otherwise mix two.
  GuestABI::SigAction Action;
  {
    std::lock_guard Lock {ActionsLock};
    Action = Actions[Signal];
    if (Action.Flags & GuestABI::GuestSA_RESETHAND) {
      Actions[Signal].Handler = GuestABI::GuestSIG_DFL;
    }
  }

  const uint64_t InterruptedRIP = State.rip;
  // WasInJIT is false here on purpose: ReconstructGuestState already moved everything into
  // CPUState, so this reads from there rather than from a host context we no longer have.
  const uint32_t EFlags = CTX->ReconstructCompactedEFLAGS(Thread, false, nullptr, 0);

  uint64_t SP;
  const bool OnAltStack = (Action.Flags & GuestABI::GuestSA_ONSTACK) && T.AltStack.Sp && !(T.AltStack.Flags & GuestABI::GuestSS_DISABLE);
  if (OnAltStack) {
    SP = T.AltStack.Sp + T.AltStack.Size;
  } else {
    // step over the red zone: 128 bytes below RSP that a leaf function may be using without
    // having adjusted the stack pointer. x86-64 only; 32-bit has no such thing.
    SP = State.gregs[FEXCore::X86State::REG_RSP] - 128;
  }

  // the frame, laid out downwards. the guest's handler is entered with RSP pointing at the
  // return address, exactly as if something had called it.
  SP = AlignDown(SP - sizeof(GuestABI::FPState), 64);
  const uint64_t FPStateLocation = SP;
  SP = AlignDown(SP - sizeof(siginfo_t), 16);
  const uint64_t SigInfoLocation = SP;
  SP = AlignDown(SP - sizeof(GuestABI::UContext), 16);
  const uint64_t UContextLocation = SP;
  SP -= 8;
  const uint64_t HandlerRSP = SP;

  auto* GuestUC = reinterpret_cast<GuestABI::UContext*>(UContextLocation);
  auto* GuestFP = reinterpret_cast<GuestABI::FPState*>(FPStateLocation);
  auto* GuestSI = reinterpret_cast<siginfo_t*>(SigInfoLocation);

  std::memset(GuestUC, 0, sizeof(*GuestUC));
  std::memset(GuestFP, 0, sizeof(*GuestFP));

  if (HostSigInfo) {
    // siginfo_t happens to have the same layout on x86-64 and arm64, so it copies verbatim. the
    // fields the guest cares about here — si_signo, si_code, si_addr — all land correctly.
    std::memcpy(GuestSI, HostSigInfo, sizeof(siginfo_t));
  } else {
    // a fault the JIT generated: there is no host siginfo to copy, so synthesise the one the
    // kernel would have written. for an invalid opcode si_addr names the instruction itself.
    std::memset(GuestSI, 0, sizeof(siginfo_t));
    GuestSI->si_signo = Signal;
    GuestSI->si_code = T.Generated.SiCode;
    GuestSI->si_addr = reinterpret_cast<void*>(InterruptedRIP);
  }

  GuestUC->uc_flags = GuestABI::UC_SIGCONTEXT_SS | GuestABI::UC_STRICT_RESTORE_SS;
  GuestUC->uc_link = 0;
  GuestUC->uc_stack = T.AltStack;
  GuestUC->uc_sigmask[0] = T.BlockedMask;
  GuestUC->uc_mcontext.fpregs = FPStateLocation;

  auto* GRegs = GuestUC->uc_mcontext.gregs;
  static constexpr int Mapping[16][2] {
    {GuestABI::REG_R8, FEXCore::X86State::REG_R8},   {GuestABI::REG_R9, FEXCore::X86State::REG_R9},
    {GuestABI::REG_R10, FEXCore::X86State::REG_R10}, {GuestABI::REG_R11, FEXCore::X86State::REG_R11},
    {GuestABI::REG_R12, FEXCore::X86State::REG_R12}, {GuestABI::REG_R13, FEXCore::X86State::REG_R13},
    {GuestABI::REG_R14, FEXCore::X86State::REG_R14}, {GuestABI::REG_R15, FEXCore::X86State::REG_R15},
    {GuestABI::REG_RDI, FEXCore::X86State::REG_RDI}, {GuestABI::REG_RSI, FEXCore::X86State::REG_RSI},
    {GuestABI::REG_RBP, FEXCore::X86State::REG_RBP}, {GuestABI::REG_RBX, FEXCore::X86State::REG_RBX},
    {GuestABI::REG_RDX, FEXCore::X86State::REG_RDX}, {GuestABI::REG_RAX, FEXCore::X86State::REG_RAX},
    {GuestABI::REG_RCX, FEXCore::X86State::REG_RCX}, {GuestABI::REG_RSP, FEXCore::X86State::REG_RSP},
  };
  for (const auto& Pair : Mapping) {
    GRegs[Pair[0]] = State.gregs[Pair[1]];
  }

  GRegs[GuestABI::REG_RIP] = InterruptedRIP;
  GRegs[GuestABI::REG_EFL] = EFlags;
  // one greg holds four selectors, and not in the order the name suggests.
  GRegs[GuestABI::REG_CSGSFS] = (static_cast<uint64_t>(State.ss_idx) << 48) | (static_cast<uint64_t>(State.fs_idx) << 32) |
                                (static_cast<uint64_t>(State.gs_idx) << 16) | static_cast<uint64_t>(State.cs_idx);
  // a guest fault handler that inspects TRAPNO — and SharpEmu's does, to tell an invalid opcode
  // from a page fault — gets a wrong answer if this is left at the signal number.
  GRegs[GuestABI::REG_TRAPNO] = T.Generated.Pending ? T.Generated.TrapNo : TrapNumberForSignal(Signal, HostSigInfo);
  GRegs[GuestABI::REG_ERR] = T.Generated.Pending ? T.Generated.ErrCode : 0;
  GRegs[GuestABI::REG_OLDMASK] = 0;
  GRegs[GuestABI::REG_CR2] = reinterpret_cast<uint64_t>(GuestSI->si_addr);
  T.Generated.Pending = false;

  // FXSAVE area.
  CTX->ReconstructXMMRegisters(Thread, GuestFP->xmm, nullptr);
  GuestFP->fcw = State.FCW;
  GuestFP->ftw = State.AbridgedFTW;
  GuestFP->mxcsr = State.mxcsr;
  GuestFP->mxcsr_mask = 0xFFC0;
  GuestFP->fsw = (State.flags[FEXCore::X86State::X87FLAG_TOP_LOC] << 11) | (State.flags[FEXCore::X86State::X87FLAG_C0_LOC] << 8) |
                 (State.flags[FEXCore::X86State::X87FLAG_C1_LOC] << 9) | (State.flags[FEXCore::X86State::X87FLAG_C2_LOC] << 10) |
                 (State.flags[FEXCore::X86State::X87FLAG_C3_LOC] << 14) | State.flags[FEXCore::X86State::X87FLAG_IE_LOC];
  // the x87 stack is stored in physical register order, so the logical registers have to be
  // rotated by TOP on the way out — and rotated back on the way in.
  const uint16_t Top = State.flags[FEXCore::X86State::X87FLAG_TOP_LOC];
  for (size_t i = 0; i < FEXCore::Core::CPUState::NUM_MMS; ++i) {
    std::memcpy(&GuestFP->st[i], &State.mm[(i + Top) % 8], sizeof(State.mm[0]));
  }

  // the return address. when the handler returns, it lands on the restorer, which issues
  // rt_sigreturn.
  *reinterpret_cast<uint64_t*>(HandlerRSP) = Action.Restorer ? Action.Restorer : GuestRestorerTrampoline();

  // block what the handler asked to have blocked, plus this signal itself unless the guest
  // explicitly opted out. this is what stops a fault inside the fault handler from recursing
  // forever. per-thread, because a mask is.
  T.BlockedMask |= Action.Mask;
  if (!(Action.Flags & GuestABI::GuestSA_NODEFER)) {
    T.BlockedMask |= 1ULL << (Signal - 1);
  }

  // finally, aim the guest at its handler.
  //
  // RAX is zeroed because that is what the kernel does — a variadic handler reads it as the
  // count of vector registers used, and a stale value there makes badly written ones misbehave.
  State.gregs[FEXCore::X86State::REG_RAX] = 0;
  State.gregs[FEXCore::X86State::REG_RDI] = Signal;
  State.gregs[FEXCore::X86State::REG_RSI] = SigInfoLocation;
  State.gregs[FEXCore::X86State::REG_RDX] = UContextLocation;
  State.gregs[FEXCore::X86State::REG_RSP] = HandlerRSP;
  State.rip = Action.Handler;

  Delivered.fetch_add(1, std::memory_order_relaxed);

  if (Threads::SignalTrace()) {
    std::printf("[sig] frame tid=%d sig=%d handler=0x%llx uc=0x%llx saved_rip=0x%llx\n", T.TID, Signal,
                static_cast<unsigned long long>(Action.Handler), static_cast<unsigned long long>(UContextLocation),
                static_cast<unsigned long long>(
                  reinterpret_cast<GuestABI::UContext*>(UContextLocation)->uc_mcontext.gregs[GuestABI::REG_RIP]));
    std::fflush(stdout);
  }
}

void GuestSignals::RestoreFromFrame(GuestThread& T) {
  auto* Thread = T.Thread;
  auto& State = Thread->CurrentFrame->State;

  // the restorer's `ret` popped the return address, so RSP is sitting on the ucontext.
  const auto* GuestUC = reinterpret_cast<const GuestABI::UContext*>(State.gregs[FEXCore::X86State::REG_RSP]);
  const auto* GRegs = GuestUC->uc_mcontext.gregs;

  T.BlockedMask = GuestUC->uc_sigmask[0];
  T.AltStack = GuestUC->uc_stack;

  static constexpr int Mapping[16][2] {
    {GuestABI::REG_R8, FEXCore::X86State::REG_R8},   {GuestABI::REG_R9, FEXCore::X86State::REG_R9},
    {GuestABI::REG_R10, FEXCore::X86State::REG_R10}, {GuestABI::REG_R11, FEXCore::X86State::REG_R11},
    {GuestABI::REG_R12, FEXCore::X86State::REG_R12}, {GuestABI::REG_R13, FEXCore::X86State::REG_R13},
    {GuestABI::REG_R14, FEXCore::X86State::REG_R14}, {GuestABI::REG_R15, FEXCore::X86State::REG_R15},
    {GuestABI::REG_RDI, FEXCore::X86State::REG_RDI}, {GuestABI::REG_RSI, FEXCore::X86State::REG_RSI},
    {GuestABI::REG_RBP, FEXCore::X86State::REG_RBP}, {GuestABI::REG_RBX, FEXCore::X86State::REG_RBX},
    {GuestABI::REG_RDX, FEXCore::X86State::REG_RDX}, {GuestABI::REG_RAX, FEXCore::X86State::REG_RAX},
    {GuestABI::REG_RCX, FEXCore::X86State::REG_RCX}, {GuestABI::REG_RSP, FEXCore::X86State::REG_RSP},
  };
  for (const auto& Pair : Mapping) {
    State.gregs[Pair[1]] = GRegs[Pair[0]];
  }

  // a handler is entitled to have edited RIP in the frame — stepping over a faulting instruction
  // is the whole point of a #UD handler, and is exactly what SharpEmu's SSE4a emulation does.
  State.rip = GRegs[GuestABI::REG_RIP];

  if (Threads::SignalTrace()) {
    std::printf("[sig] sigreturn tid=%d rip=0x%llx rsp=0x%llx mask=0x%llx\n", T.TID,
                static_cast<unsigned long long>(State.rip),
                static_cast<unsigned long long>(State.gregs[FEXCore::X86State::REG_RSP]),
                static_cast<unsigned long long>(T.BlockedMask));
    std::fflush(stdout);
  }
  CTX->SetFlagsFromCompactedEFLAGS(Thread, static_cast<uint32_t>(GRegs[GuestABI::REG_EFL]));

  if (GuestUC->uc_mcontext.fpregs) {
    const auto* GuestFP = reinterpret_cast<const GuestABI::FPState*>(GuestUC->uc_mcontext.fpregs);
    // and this is the write-back that matters: whatever the handler left in the frame's XMM area
    // becomes the guest's live XMM state.
    CTX->SetXMMRegistersFromState(Thread, const_cast<__uint128_t*>(GuestFP->xmm), nullptr);

    State.mxcsr = GuestFP->mxcsr & 0xFFC0;
    State.FCW = GuestFP->fcw;
    State.AbridgedFTW = GuestFP->ftw;

    State.flags[FEXCore::X86State::X87FLAG_IE_LOC] = GuestFP->fsw & 1;
    State.flags[FEXCore::X86State::X87FLAG_C0_LOC] = (GuestFP->fsw >> 8) & 1;
    State.flags[FEXCore::X86State::X87FLAG_C1_LOC] = (GuestFP->fsw >> 9) & 1;
    State.flags[FEXCore::X86State::X87FLAG_C2_LOC] = (GuestFP->fsw >> 10) & 1;
    State.flags[FEXCore::X86State::X87FLAG_C3_LOC] = (GuestFP->fsw >> 14) & 1;
    State.flags[FEXCore::X86State::X87FLAG_TOP_LOC] = (GuestFP->fsw >> 11) & 0b111;

    const uint16_t Top = State.flags[FEXCore::X86State::X87FLAG_TOP_LOC];
    for (size_t i = 0; i < FEXCore::Core::CPUState::NUM_MMS; ++i) {
      std::memcpy(&State.mm[(i + Top) % 8], &GuestFP->st[i], sizeof(State.mm[0]));
    }
  }
}

} // namespace HostLayer
