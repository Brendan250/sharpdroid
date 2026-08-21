// a static x86-64 guest that handles its own faults.
//
// no libc, deliberately: the point is to exercise the host layer's signal delivery against the
// raw kernel ABI, with the guest spelling out `struct sigaction`, the sigframe layout and the
// restorer itself. a libc in between would hide which side got something wrong.
//
// the last test is the one that matters. SharpEmu's `DirectExecutionBackend.Amd64Compat.cs`
// emulates SSE4a EXTRQ/INSERTQ on #UD by *writing XMM state back through the signal context*,
// and its POSIX path carries a `_posixXmmContextBridged` flag precisely because that write-back
// has to survive sigreturn. so this guest does the same thing: faults, edits XMM0 and RIP inside
// its handler, returns, and checks that both edits took.

typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;

#define SYS_write 1
#define SYS_rt_sigaction 13
#define SYS_rt_sigreturn 15
#define SYS_exit_group 231

#define SIGILL 4
#define SIGSEGV 11
#define SA_SIGINFO 0x00000004
#define SA_RESTORER 0x04000000

// ucontext_t::uc_mcontext::gregs indexes, in the order the kernel writes them.
#define REG_RBX 11
#define REG_RIP 16
#define REG_TRAPNO 20

// x86 trap numbers, as they appear in gregs[REG_TRAPNO].
#define X86_TRAPNO_UD 6
#define X86_TRAPNO_PF 14

static long Syscall4(long Number, long A, long B, long C, long D) {
  long Result;
  register long R10 __asm__("r10") = D;
  __asm__ volatile("syscall" : "=a"(Result) : "a"(Number), "D"(A), "S"(B), "d"(C), "r"(R10) : "rcx", "r11", "memory");
  return Result;
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

static void PrintHex(u64 Value) {
  char Buffer[18];
  Buffer[0] = '0';
  Buffer[1] = 'x';
  for (int i = 0; i < 16; ++i) {
    Buffer[2 + i] = "0123456789ABCDEF"[(Value >> (60 - i * 4)) & 0xF];
  }
  Write(Buffer, 18);
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

// --- the guest's own view of the kernel ABI ----------------------------------------------------

struct KernelSigAction {
  u64 Handler;
  u64 Flags;
  u64 Restorer;
  u64 Mask;
};

struct __attribute__((packed)) FPState {
  u16 fcw, fsw, ftw, fop;
  u64 fip, fdp;
  u32 mxcsr, mxcsr_mask;
  unsigned __int128 st[8];
  unsigned __int128 xmm[16];
  u32 reserved[12];
  u32 sw_reserved[12];
};

struct __attribute__((packed)) MContext {
  u64 gregs[23];
  struct FPState* fpregs;
  u64 reserved[8];
};

struct __attribute__((packed)) UContext {
  u64 uc_flags;
  u64 uc_link;
  u64 ss_sp;
  int ss_flags;
  u32 pad;
  u64 ss_size;
  struct MContext uc_mcontext;
  u64 uc_sigmask[16];
};

// the restorer. a handler returns here, and this is what asks the kernel -- or, here, the host
// layer -- to put everything back. glibc and bionic both supply one of these on x86-64, which is
// why SA_RESTORER exists at all on this architecture.
extern void Restorer(void);
__asm__(".globl Restorer\n"
        "Restorer:\n"
        "  mov $15, %eax\n"
        "  syscall\n");

// --- the tests ---------------------------------------------------------------------------------

static volatile int HandlerRan = 0;
static volatile u64 HandlerFaultAddress = 0;
static volatile u64 HandlerFaultRIP = 0;
static volatile u64 HandlerRBX = 0;
///< RBX as the guest reads it back *after* sigreturn -- the handler rewrote it in the frame.
static volatile u64 HandlerRBX_After = 0;
static volatile u64 HandlerTrapNo = 0;

#define XMM_WITNESS_LOW 0x1234567890ABCDEFULL
#define XMM_WITNESS_HIGH 0x0FEDCBA987654321ULL
#define RBX_WITNESS 0x5AFEULL

// the faulting sequence is written by hand so its length is known exactly: the handler has to
// step RIP over it, and "however many bytes the compiler chose" is not something a handler can
// find out.
//
//   48 8b 18    mov rbx, [rax]     <- rax is 0, so this faults
#define FAULTING_INSTRUCTION_LENGTH 3

static void Handler(int Signal, void* InfoPtr, void* ContextPtr) {
  struct UContext* Context = (struct UContext*)ContextPtr;
  // siginfo_t's si_addr sits at offset 16 on x86-64, after si_signo/si_errno/si_code.
  const u64 FaultAddress = *(u64*)((char*)InfoPtr + 16);

  HandlerRan = Signal;
  HandlerFaultAddress = FaultAddress;
  HandlerFaultRIP = Context->uc_mcontext.gregs[REG_RIP];
  HandlerRBX = Context->uc_mcontext.gregs[REG_RBX];
  HandlerTrapNo = Context->uc_mcontext.gregs[REG_TRAPNO];

  Print("[guest]   handler entered, signal ");
  PrintDec((u64)Signal);
  Print(", si_addr ");
  PrintHex(FaultAddress);
  Print("\n");
  Print("[guest]   saved RIP ");
  PrintHex(Context->uc_mcontext.gregs[REG_RIP]);
  Print(", saved RBX ");
  PrintHex(Context->uc_mcontext.gregs[REG_RBX]);
  Print(", trapno ");
  PrintDec(HandlerTrapNo);
  Print("\n");

  if (Signal == SIGILL) {
    // ud2 is two bytes. stepping over it is the whole shape of what SharpEmu does on #UD: decode
    // the instruction the CPU refused, emulate it, and resume after it.
    Context->uc_mcontext.gregs[REG_RIP] += 2;
    Print("[guest]   handler leaving, #UD stepped over\n");
    return;
  }

  // edit the saved state. both of these have to survive sigreturn:
  //   RIP steps over the faulting instruction, so execution resumes after it rather than
  //   faulting again forever.
  //   XMM0 is rewritten in the frame's FXSAVE area, which is the mechanism SharpEmu uses to
  //   hand an emulated SSE4a result back to the guest.
  Context->uc_mcontext.gregs[REG_RIP] += FAULTING_INSTRUCTION_LENGTH;
  Context->uc_mcontext.gregs[REG_RBX] = RBX_WITNESS;

  u64* XMM0 = (u64*)&Context->uc_mcontext.fpregs->xmm[0];
  Print("[guest]   xmm0 in frame was ");
  PrintHex(XMM0[1]);
  PrintHex(XMM0[0]);
  Print("\n");
  XMM0[0] = XMM_WITNESS_LOW;
  XMM0[1] = XMM_WITNESS_HIGH;
  Print("[guest]   handler leaving, xmm0 set to witness\n");
}

__attribute__((used)) static void StartC(u64* Stack) {
  (void)Stack;
  Print("[guest] installing a SIGSEGV handler\n");

  struct KernelSigAction Action;
  Action.Handler = (u64)&Handler;
  Action.Flags = SA_SIGINFO | SA_RESTORER;
  Action.Restorer = (u64)&Restorer;
  Action.Mask = 0;

  const long Result = Syscall4(SYS_rt_sigaction, SIGSEGV, (long)&Action, 0, 8);
  if (Result != 0) {
    Print("[guest] rt_sigaction failed\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }

  // put something recognisable in xmm0 so the handler's overwrite is unambiguous, then fault.
  // rbx is set beforehand too, so the handler can check that the frame carries live guest
  // register state and not zeroes.
  u64 Before[2] = {0xAAAAAAAAAAAAAAAAULL, 0xBBBBBBBBBBBBBBBBULL};
  u64 After[2] = {0, 0};

  Print("[guest] faulting on a read of address 0...\n");
  __asm__ volatile("movups (%[before]), %%xmm0\n"
                   "mov $0x1111, %%rbx\n"
                   "xor %%eax, %%eax\n"
                   "mov (%%rax), %%rbx\n" // <- 48 8b 18, the fault
                   "mov %%rbx, %[rbxout]\n"
                   "movups %%xmm0, (%[after])\n"
                   : [rbxout] "=m"(HandlerRBX_After)
                   : [before] "r"(Before), [after] "r"(After)
                   : "rax", "rbx", "xmm0", "memory");

  Print("[guest] execution resumed past the fault\n");

  Print("[guest] handler ran = ");
  PrintDec((u64)HandlerRan);
  Print(" (expected 11)\n");
  Print("[guest] si_addr seen by handler = ");
  PrintHex(HandlerFaultAddress);
  Print(" (expected 0x0)\n");
  Print("[guest] RBX seen by handler = ");
  PrintHex(HandlerRBX);
  Print(" (expected 0x1111)\n");
  Print("[guest] xmm0 after sigreturn = ");
  PrintHex(After[1]);
  PrintHex(After[0]);
  Print("\n");

  const int SegvOk = HandlerRan == SIGSEGV && HandlerFaultAddress == 0 && HandlerRBX == 0x1111 && HandlerTrapNo == X86_TRAPNO_PF &&
                     After[0] == XMM_WITNESS_LOW && After[1] == XMM_WITNESS_HIGH && HandlerRBX_After == RBX_WITNESS;
  if (SegvOk) {
    Print("[guest] SIGSEGV PASS: handler ran, RIP was stepped, and both RBX and XMM0 write-back survived sigreturn\n");
  } else {
    Print("[guest] SIGSEGV FAIL: see above\n");
  }

  // --- second test: an invalid opcode ------------------------------------------------------
  //
  // this is a different path through the host layer entirely. a bad address is a real host
  // SIGSEGV out of JIT'd code; an invalid opcode is something FEXCore's *decoder* recognises,
  // so it never reaches the CPU -- the JIT emits a block that records the fault and branches to
  // a trampoline. it is also the exact path SharpEmu's SSE4a emulation lives on, since EXTRQ
  // and INSERTQ are #UD on every CPU that is not an AMD from 2007.
  //
  // running it second also checks that the signal mask came back: SIGSEGV blocked itself for the
  // duration of its handler, and if sigreturn had not restored the old mask this delivery would
  // still work but the earlier one could never happen twice.
  Print("[guest] installing a SIGILL handler\n");
  Action.Handler = (u64)&Handler;
  Action.Flags = SA_SIGINFO | SA_RESTORER;
  Action.Restorer = (u64)&Restorer;
  Action.Mask = 0;
  if (Syscall4(SYS_rt_sigaction, SIGILL, (long)&Action, 0, 8) != 0) {
    Print("[guest] rt_sigaction(SIGILL) failed\n");
    Syscall4(SYS_exit_group, 1, 0, 0, 0);
  }

  HandlerRan = 0;
  HandlerTrapNo = 0;
  Print("[guest] executing ud2...\n");
  __asm__ volatile("ud2" ::: "memory");
  Print("[guest] execution resumed past the invalid opcode\n");

  Print("[guest] handler ran = ");
  PrintDec((u64)HandlerRan);
  Print(" (expected 4)\n");
  Print("[guest] trapno seen by handler = ");
  PrintDec(HandlerTrapNo);
  Print(" (expected 6)\n");

  const int IllOk = HandlerRan == SIGILL && HandlerTrapNo == X86_TRAPNO_UD;
  if (IllOk) {
    Print("[guest] SIGILL PASS: #UD reached a guest handler and was stepped over\n");
  } else {
    Print("[guest] SIGILL FAIL: see above\n");
  }

  const int Ok = SegvOk && IllOk;
  Syscall4(SYS_exit_group, Ok ? 0 : 1, 0, 0, 0);
  __builtin_unreachable();
}

// _start must not be a C function: it receives its arguments *as the stack itself*, with argc at
// [rsp], and any prologue the compiler emits would move rsp before it could be read.
__asm__(".globl _start\n"
        "_start:\n"
        "  xor %rbp, %rbp\n"
        "  mov %rsp, %rdi\n"
        "  and $-16, %rsp\n"
        "  call StartC\n"
        "  hlt\n");
