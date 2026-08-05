// a static x86-64 guest that signals its own threads.
//
// no libc, deliberately, for the same reason `signals.c` has none: what is under test is the host
// layer against the raw kernel ABI, and a libc in between would hide which side got something
// wrong. so this guest issues `clone` itself too.
//
// the three cases are three genuinely different code paths through the host layer, not the same
// one three times:
//
//   1. the target is spinning in translated guest code. the host interrupt lands inside a JIT
//      block, guest state is scattered across host arm64 registers, and it has to be gathered up
//      before a frame can be built.
//   2. the target is parked in a blocking syscall. the interrupt brings the host call back with
//      EINTR and delivery happens on the way out — and because the handler asks for SA_RESTART,
//      the guest must never see that EINTR.
//   3. the signal is blocked when it is raised. nothing may be delivered until the target unblocks
//      it itself, and then it must be delivered immediately.
//
// this is what .NET needs. CoreCLR suspends threads for GC by sending SIGRTMIN to them with
// pthread_kill and hijacking the context its handler is given, which is case 1 with cases 2 and 3
// happening to whichever threads were elsewhere at the time.

typedef unsigned long u64;
typedef unsigned int u32;
typedef long i64;

#define SYS_write 1
#define SYS_mmap 9
#define SYS_rt_sigaction 13
#define SYS_rt_sigprocmask 14
#define SYS_rt_sigreturn 15
#define SYS_nanosleep 35
#define SYS_getpid 39
#define SYS_clone 56
#define SYS_exit 60
#define SYS_gettid 186
#define SYS_futex 202
#define SYS_exit_group 231
#define SYS_tgkill 234

#define SA_SIGINFO 0x00000004
#define SA_RESTART 0x10000000
#define SA_RESTORER 0x04000000

#define SIG_BLOCK 0
#define SIG_UNBLOCK 1

#define CLONE_VM 0x00000100
#define CLONE_FS 0x00000200
#define CLONE_FILES 0x00000400
#define CLONE_SIGHAND 0x00000800
#define CLONE_THREAD 0x00010000

#define FUTEX_WAIT 0
#define FUTEX_WAKE 1

// the two signals under test. 34 is what glibc calls SIGRTMIN and what CoreCLR injects activations
// with; 10 is SIGUSR1, used for the blocked-then-unblocked case so the two cannot be confused.
#define SIG_SPIN 34
#define SIG_BLOCKED 10

static long Syscall6(long Number, long A, long B, long C, long D, long E, long F) {
  long Result;
  register long R10 __asm__("r10") = D;
  register long R8 __asm__("r8") = E;
  register long R9 __asm__("r9") = F;
  __asm__ volatile("syscall"
                   : "=a"(Result)
                   : "a"(Number), "D"(A), "S"(B), "d"(C), "r"(R10), "r"(R8), "r"(R9)
                   : "rcx", "r11", "memory");
  return Result;
}

static long Syscall4(long Number, long A, long B, long C, long D) {
  return Syscall6(Number, A, B, C, D, 0, 0);
}

static long Syscall3(long Number, long A, long B, long C) {
  return Syscall6(Number, A, B, C, 0, 0, 0);
}

static u64 StringLength(const char* Text) {
  u64 Length = 0;
  while (Text[Length]) {
    ++Length;
  }
  return Length;
}

static void Write(const char* Text, u64 Length) {
  Syscall4(SYS_write, 1, (long)Text, (long)Length, 0);
}

static void Print(const char* Text) {
  Write(Text, StringLength(Text));
}

static void PrintDec(u64 Value) {
  char Buffer[21];
  int Index = 20;
  Buffer[Index] = 0;
  do {
    Buffer[--Index] = (char)('0' + (Value % 10));
    Value /= 10;
  } while (Value);
  Print(&Buffer[Index]);
}

static int SelfTID(void) {
  return (int)Syscall4(SYS_gettid, 0, 0, 0, 0);
}

// --- the guest's own view of the kernel ABI ------------------------------------------------------

struct KernelSigAction {
  u64 Handler;
  u64 Flags;
  u64 Restorer;
  u64 Mask;
};

struct TimeSpec {
  i64 Seconds;
  i64 Nanoseconds;
};

extern void Restorer(void);
__asm__(".globl Restorer\n"
        "Restorer:\n"
        "  mov $15, %eax\n"
        "  syscall\n");

static void Install(int Signal, void* Handler, u64 ExtraFlags) {
  struct KernelSigAction Action;
  Action.Handler = (u64)Handler;
  Action.Flags = SA_SIGINFO | SA_RESTORER | ExtraFlags;
  Action.Restorer = (u64)&Restorer;
  Action.Mask = 0;
  if (Syscall4(SYS_rt_sigaction, Signal, (long)&Action, 0, 8) != 0) {
    Print("[guest] rt_sigaction failed\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }
}

// --- shared state ---------------------------------------------------------------------------------

static volatile int WorkerTID = 0;
static volatile u64 SpinCounter = 0;
static volatile int SpinHandlerRan = 0;
static volatile int SpinHandlerTID = 0;
///< si_code as the spinning worker's handler saw it. SI_TKILL is -6, and it is how a guest tells a
///< signal another thread sent it from one its own code raised.
static volatile i64 SpinHandlerSiCode = 0;
///< the counter as it stood when the handler ran, so the main thread can prove the worker really
///< was mid-loop rather than parked somewhere.
static volatile u64 SpinCounterAtHandler = 0;

static volatile int SleeperTID = 0;
static volatile int SleeperHandlerRan = 0;
///< set only when the futex actually returns to the guest. with SA_RESTART it must not: an
///< interrupted call is re-entered rather than failed, so the guest never sees the EINTR at all.
static volatile int SleeperFinished = 0;
static volatile u32 SleeperFutexWord = 0;

static volatile int BlockedHandlerRan = 0;
///< set just before the guest unblocks the signal, and read by the handler. if the handler ran
///< early — that is, if the host layer delivered a blocked signal — this reads 0.
static volatile int UnblockRequested = 0;
static volatile int HandlerSawUnblockRequest = 0;

static void SpinHandler(int Signal, void* InfoPtr, void* ContextPtr) {
  (void)ContextPtr;
  SpinHandlerTID = SelfTID();
  SpinCounterAtHandler = SpinCounter;
  // siginfo_t on x86-64: si_signo, si_errno, si_code as three 32-bit words.
  SpinHandlerSiCode = *(int*)((char*)InfoPtr + 8);
  SpinHandlerRan = Signal;
}

static void SleeperHandler(int Signal, void* InfoPtr, void* ContextPtr) {
  (void)InfoPtr;
  (void)ContextPtr;
  SleeperHandlerRan = Signal;
}

static void BlockedHandler(int Signal, void* InfoPtr, void* ContextPtr) {
  (void)InfoPtr;
  (void)ContextPtr;
  HandlerSawUnblockRequest = UnblockRequested;
  BlockedHandlerRan = Signal;
}

// --- threads ---------------------------------------------------------------------------------------

#define THREAD_STACK_SIZE (256 * 1024)

// clone(2) with a function to run, done by hand. the child comes back from the syscall with rax
// zero and rsp pointing at the stack we chose, and has no return address to use — so the entry
// point is parked on that stack for it to pop, and it exits rather than returning here.
//
// the 24 is the whole of the stack arithmetic and is not arbitrary: popping the parked pointer
// leaves rsp 16-byte aligned, which is what the ABI wants immediately *before* a call. get it
// wrong by eight and the first aligned SSE store in the callee faults.
extern long StartThread(u64 Flags, void* StackTop, void* Entry);
__asm__(".globl StartThread\n"
        "StartThread:\n"
        "  mov %rdx, %r9\n"   // the entry point, out of the way of the syscall ABI
        "  mov %rsi, %r10\n"  // the child stack
        "  sub $24, %r10\n"
        "  mov %r9, (%r10)\n" // and park the entry point on it, for the child to find
        "  mov %rdi, %r11\n"  // clone flags
        "  mov $56, %eax\n"   // SYS_clone
        "  mov %r11, %rdi\n"
        "  mov %r10, %rsi\n"
        "  xor %rdx, %rdx\n"  // parent_tid
        "  xor %r10, %r10\n"  // child_tid
        "  xor %r8, %r8\n"    // tls
        "  syscall\n"
        "  test %rax, %rax\n"
        "  jnz 1f\n"
        "  pop %rax\n"        // in the child: the entry point we parked
        "  xor %rbp, %rbp\n"
        "  call *%rax\n"
        "  mov $60, %eax\n"   // SYS_exit, this thread only
        "  xor %edi, %edi\n"
        "  syscall\n"
        "  hlt\n"
        "1:\n"
        "  ret\n");

static void* AllocateStack(void) {
  const long Result = Syscall6(SYS_mmap, 0, THREAD_STACK_SIZE, 3 /* PROT_READ|PROT_WRITE */,
                               0x22 /* MAP_PRIVATE|MAP_ANONYMOUS */, -1, 0);
  if (Result <= 0) {
    return 0;
  }
  return (void*)(u64)(Result + THREAD_STACK_SIZE);
}

static const u64 ThreadFlags = CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD;

// case 1's worker: nothing but translated guest code, no syscalls at all once it has published its
// tid. the only way into it is an interrupt landing inside a JIT block.
static void SpinWorker(void) {
  WorkerTID = SelfTID();
  while (!SpinHandlerRan) {
    SpinCounter = SpinCounter + 1;
  }
}

// case 2's worker: parked in futex, which is where nearly every idle guest thread in a real .NET
// process is.
static void SleepWorker(void) {
  SleeperTID = SelfTID();
  // FUTEX_WAIT on a word that still holds the expected value blocks until someone wakes it. the
  // main thread only does that once the case is over, so in the meantime the signal is the only
  // thing that touches this thread at all.
  Syscall6(SYS_futex, (long)&SleeperFutexWord, FUTEX_WAIT, 0, 0, 0, 0);
  SleeperFinished = 1;
}

static void SleepMilliseconds(long Milliseconds) {
  struct TimeSpec Request;
  Request.Seconds = Milliseconds / 1000;
  Request.Nanoseconds = (Milliseconds % 1000) * 1000000;
  Syscall4(SYS_nanosleep, (long)&Request, 0, 0, 0);
}

///< spin on a flag with a deadline, so a host layer that never delivers fails the test instead of
///< hanging the regression run.
static int WaitFor(volatile int* Flag, int Milliseconds) {
  for (int i = 0; i < Milliseconds / 10; ++i) {
    if (*Flag) {
      return 1;
    }
    SleepMilliseconds(10);
  }
  return *Flag != 0;
}

// --- the tests -------------------------------------------------------------------------------------

__attribute__((used)) static void StartC(u64* Stack) {
  (void)Stack;
  const int Pid = (int)Syscall4(SYS_getpid, 0, 0, 0, 0);
  int Ok = 1;

  Install(SIG_SPIN, (void*)&SpinHandler, SA_RESTART);
  Install(SIG_BLOCKED, (void*)&BlockedHandler, SA_RESTART);

  // --- case 1: a thread spinning in translated code ---------------------------------------------
  Print("[guest] case 1: signalling a thread spinning in guest code\n");
  void* SpinStack = AllocateStack();
  if (!SpinStack || StartThread(ThreadFlags, SpinStack, (void*)&SpinWorker) <= 0) {
    Print("[guest] clone failed\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }
  if (!WaitFor(&WorkerTID, 2000)) {
    Print("[guest] case 1 FAIL: the worker never published a tid\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }

  const u64 CounterBefore = SpinCounter;
  const long SpinRaise = Syscall3(SYS_tgkill, Pid, WorkerTID, SIG_SPIN);
  const int SpinDelivered = WaitFor(&SpinHandlerRan, 3000);

  Print("[guest]   tgkill returned ");
  PrintDec((u64)SpinRaise);
  Print(", handler ran = ");
  PrintDec((u64)SpinHandlerRan);
  Print(" (expected 34)\n");
  Print("[guest]   handler tid = ");
  PrintDec((u64)SpinHandlerTID);
  Print(", worker tid = ");
  PrintDec((u64)WorkerTID);
  Print("\n");
  Print("[guest]   si_code negated = ");
  PrintDec((u64)(-SpinHandlerSiCode));
  Print(" (expected 6, i.e. SI_TKILL)\n");
  Print("[guest]   counter moved from ");
  PrintDec(CounterBefore);
  Print(" to ");
  PrintDec(SpinCounterAtHandler);
  Print(" before the handler ran\n");

  const int Case1 = SpinRaise == 0 && SpinDelivered && SpinHandlerRan == SIG_SPIN && SpinHandlerTID == WorkerTID &&
                    SpinHandlerSiCode == -6 && SpinCounterAtHandler > CounterBefore;
  Print(Case1 ? "[guest] case 1 PASS: a thread in guest code was interrupted and ran its handler\n" :
                "[guest] case 1 FAIL: see above\n");
  Ok = Ok && Case1;

  // --- case 2: a thread parked in a blocking syscall ---------------------------------------------
  //
  // the interesting half is not that the handler runs, it is what the futex does afterwards.
  // SA_RESTART says an interrupted call is re-entered rather than failed, so the guest must see the
  // thread still blocked — SleeperFinished stays 0 — and never an EINTR.
  Print("[guest] case 2: signalling a thread parked in futex\n");
  Install(SIG_SPIN, (void*)&SleeperHandler, SA_RESTART);

  void* SleepStack = AllocateStack();
  if (!SleepStack || StartThread(ThreadFlags, SleepStack, (void*)&SleepWorker) <= 0) {
    Print("[guest] clone failed\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }
  if (!WaitFor(&SleeperTID, 2000)) {
    Print("[guest] case 2 FAIL: the sleeper never published a tid\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }
  // long enough that the thread is certainly inside the futex rather than on its way to it.
  SleepMilliseconds(100);

  const long SleepRaise = Syscall3(SYS_tgkill, Pid, SleeperTID, SIG_SPIN);
  const int SleepDelivered = WaitFor(&SleeperHandlerRan, 3000);
  // and give a wrongly-failed futex time to be seen returning, if it did.
  SleepMilliseconds(100);

  Print("[guest]   tgkill returned ");
  PrintDec((u64)SleepRaise);
  Print(", handler ran = ");
  PrintDec((u64)SleeperHandlerRan);
  Print(" (expected 34)\n");
  Print("[guest]   futex still blocked = ");
  PrintDec((u64)(SleeperFinished == 0));
  Print(" (expected 1, SA_RESTART)\n");

  const int Case2 = SleepRaise == 0 && SleepDelivered && SleeperHandlerRan == SIG_SPIN && SleeperFinished == 0;
  Print(Case2 ? "[guest] case 2 PASS: a parked thread took the signal and its syscall was restarted\n" :
                "[guest] case 2 FAIL: see above\n");
  Ok = Ok && Case2;

  // let it go, so the process is not held up by a thread waiting forever.
  SleeperFutexWord = 1;
  Syscall6(SYS_futex, (long)&SleeperFutexWord, FUTEX_WAKE, 0x7FFFFFFF, 0, 0, 0);

  // --- case 3: a signal raised while it is blocked ------------------------------------------------
  //
  // raised on this thread, and on purpose: the point is not which thread it lands on but that a
  // blocked signal waits rather than being delivered or dropped. a host layer that delivered it
  // immediately would run the handler before UnblockRequested is set, and one that dropped it would
  // never run the handler at all.
  Print("[guest] case 3: raising a signal that is blocked, then unblocking it\n");
  u64 Mask = 1ULL << (SIG_BLOCKED - 1);
  Syscall4(SYS_rt_sigprocmask, SIG_BLOCK, (long)&Mask, 0, 8);

  Syscall3(SYS_tgkill, Pid, SelfTID(), SIG_BLOCKED);
  SleepMilliseconds(50);
  const int RanWhileBlocked = BlockedHandlerRan;

  UnblockRequested = 1;
  Syscall4(SYS_rt_sigprocmask, SIG_UNBLOCK, (long)&Mask, 0, 8);
  const int BlockedDelivered = WaitFor(&BlockedHandlerRan, 2000);

  Print("[guest]   ran while blocked = ");
  PrintDec((u64)RanWhileBlocked);
  Print(" (expected 0)\n");
  Print("[guest]   handler ran after unblocking = ");
  PrintDec((u64)BlockedHandlerRan);
  Print(" (expected 10)\n");

  const int Case3 = RanWhileBlocked == 0 && BlockedDelivered && BlockedHandlerRan == SIG_BLOCKED && HandlerSawUnblockRequest == 1;
  Print(Case3 ? "[guest] case 3 PASS: the signal waited for the mask and arrived the moment it lifted\n" :
                "[guest] case 3 FAIL: see above\n");
  Ok = Ok && Case3;

  // --- and the errors ------------------------------------------------------------------------------
  //
  // a tid nothing owns is ESRCH, and signal 0 raises nothing and only reports existence. glibc's
  // pthread_kill leans on both.
  const long Missing = Syscall3(SYS_tgkill, Pid, 0x7FFFFFF0, SIG_SPIN);
  const long Probe = Syscall3(SYS_tgkill, Pid, SelfTID(), 0);
  Print("[guest] tgkill to a tid that does not exist, negated = ");
  PrintDec((u64)(-Missing));
  Print(" (expected 3, i.e. ESRCH), probe with signal 0 = ");
  PrintDec((u64)Probe);
  Print(" (expected 0)\n");
  const int Case4 = Missing == -3 && Probe == 0;
  Ok = Ok && Case4;

  Print(Ok ? "[guest] ALL PASS\n" : "[guest] FAILED\n");
  Syscall4(SYS_exit_group, Ok ? 0 : 1, 0, 0, 0);
  __builtin_unreachable();
}

__asm__(".globl _start\n"
        "_start:\n"
        "  xor %rbp, %rbp\n"
        "  mov %rsp, %rdi\n"
        "  and $-16, %rsp\n"
        "  call StartC\n"
        "  hlt\n");
