// a static x86-64 guest that rewrites its own code, and jumps somewhere it should not.
//
// this is the only regression in the set that fails against a host layer with no VMA tracking, and
// it fails in two distinct ways, which is why it is one guest and not two:
//
//   - a host layer that tells FEXCore "the whole address space is executable and never writable"
//     compiles a function once and never notices it changed. tests 1 to 3 below then return the
//     *old* answer, and the guest exits non-zero.
//   - the same host layer happily translates whatever is at a data address a guest jumps to.
//     test 4 calls into a page that was mapped without PROT_EXEC and expects SIGSEGV; without
//     tracking the bytes there are executed instead, and it returns a value.
//
// no libc, like the rest of the set: mmap, mprotect and munmap are issued directly so that what is
// being tested is the host layer's syscall path and nothing else.

typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned short u16;

#define SYS_write 1
#define SYS_mmap 9
#define SYS_mprotect 10
#define SYS_munmap 11
#define SYS_rt_sigaction 13
#define SYS_exit_group 231

#define PROT_NONE 0
#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4

#define MAP_PRIVATE 0x02
#define MAP_FIXED 0x10
#define MAP_ANONYMOUS 0x20

#define SIGSEGV 11
#define SA_SIGINFO 0x00000004
#define SA_RESTORER 0x04000000

#define REG_RSP 15
#define REG_RIP 16
#define REG_RAX 13

#define PAGE 4096

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

static u64 StringLength(const char* Text) {
  u64 Length = 0;
  while (Text[Length]) {
    ++Length;
  }
  return Length;
}

static void Write(const char* Text, u64 Length) {
  Syscall6(SYS_write, 1, (long)Text, (long)Length, 0, 0, 0);
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

// --- the body being rewritten --------------------------------------------------------------------
//
//   b8 XX XX XX XX   mov eax, imm32
//   c3               ret
//
// six bytes, and the only thing that ever changes is the immediate at offset 1. writing the whole
// body every time rather than patching four bytes would hide the case that matters most: a
// *partial* overwrite of an instruction inside a block FEXCore has already translated.
static const unsigned char BodyTemplate[6] = {0xB8, 0x00, 0x00, 0x00, 0x00, 0xC3};

typedef u32 (*BodyFn)(void);

static void WriteBody(void* Where, u32 Value) {
  unsigned char* Bytes = (unsigned char*)Where;
  for (int i = 0; i < 6; ++i) {
    Bytes[i] = BodyTemplate[i];
  }
  Bytes[1] = (unsigned char)(Value & 0xFF);
  Bytes[2] = (unsigned char)((Value >> 8) & 0xFF);
  Bytes[3] = (unsigned char)((Value >> 16) & 0xFF);
  Bytes[4] = (unsigned char)((Value >> 24) & 0xFF);
}

///< overwrite only the immediate, leaving the opcode and the `ret` where they already are.
static void PatchImmediate(void* Where, u32 Value) {
  unsigned char* Bytes = (unsigned char*)Where;
  Bytes[1] = (unsigned char)(Value & 0xFF);
  Bytes[2] = (unsigned char)((Value >> 8) & 0xFF);
  Bytes[3] = (unsigned char)((Value >> 16) & 0xFF);
  Bytes[4] = (unsigned char)((Value >> 24) & 0xFF);
}

static int Failures = 0;

static void Check(const char* Name, u64 Got, u64 Want) {
  Print(Got == Want ? "[guest]   PASS  " : "[guest]   FAIL  ");
  Print(Name);
  Print(": got ");
  PrintDec(Got);
  Print(", want ");
  PrintDec(Want);
  Print("\n");
  if (Got != Want) {
    ++Failures;
  }
}

static void* Map(void* Hint, u64 Size, long Prot, long Flags) {
  const long Result = Syscall6(SYS_mmap, (long)Hint, (long)Size, Prot, Flags | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  return (Result < 0 && Result > -4096) ? 0 : (void*)Result;
}

// --- the guest's own view of the kernel signal ABI ------------------------------------------------

struct KernelSigAction {
  u64 Handler;
  u64 Flags;
  u64 Restorer;
  u64 Mask;
};

struct __attribute__((packed)) MContext {
  u64 gregs[23];
  void* fpregs;
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

extern void Restorer(void);
__asm__(".globl Restorer\n"
        "Restorer:\n"
        "  mov $15, %eax\n"
        "  syscall\n");

static volatile int NoExecHandlerRan = 0;
static volatile u64 NoExecFaultAddress = 0;
static volatile u64 NoExecSiCode = 0;

#define NOEXEC_SENTINEL 0xBAD

// the fault lands *at the entry of the page that was jumped to*, with the call's return address
// still on the stack and nothing else done. so unwinding it is exact: put RIP back to that return
// address, pop it, and hand back a sentinel in RAX. that is precisely what the call would have
// done had it returned, which keeps the recovery out of the way of what is being measured.
static void NoExecHandler(int Signal, void* InfoPtr, void* ContextPtr) {
  struct UContext* Context = (struct UContext*)ContextPtr;
  NoExecHandlerRan = Signal;
  NoExecFaultAddress = *(u64*)((char*)InfoPtr + 16);
  NoExecSiCode = *(u32*)((char*)InfoPtr + 8);

  const u64 StackPointer = Context->uc_mcontext.gregs[REG_RSP];
  Context->uc_mcontext.gregs[REG_RIP] = *(u64*)StackPointer;
  Context->uc_mcontext.gregs[REG_RSP] = StackPointer + 8;
  Context->uc_mcontext.gregs[REG_RAX] = NOEXEC_SENTINEL;
}

// --- the tests ------------------------------------------------------------------------------------

// 1. the plain case: one RWX page, called, patched in place, called again.
static void TestRewriteInPlace(void) {
  Print("[guest] test 1: rewrite a function in an RWX page\n");
  void* Page = Map(0, PAGE, PROT_READ | PROT_WRITE | PROT_EXEC, 0);
  if (!Page) {
    Print("[guest]   FAIL  mmap RWX\n");
    ++Failures;
    return;
  }

  WriteBody(Page, 111);
  Check("first call", ((BodyFn)Page)(), 111);

  // the whole milestone in two lines: FEXCore has translated this page, and the guest now writes
  // over the instruction it translated. under SMCChecks=mtrack the write faults, because
  // MarkGuestExecutableRange took PROT_WRITE off the host mapping when the block was compiled.
  PatchImmediate(Page, 222);
  Check("after patching the immediate", ((BodyFn)Page)(), 222);

  // and again, to show the seal was put back rather than being a one-shot.
  PatchImmediate(Page, 333);
  Check("after patching it twice", ((BodyFn)Page)(), 333);

  Syscall6(SYS_munmap, (long)Page, PAGE, 0, 0, 0, 0);
}

// 2. the well-behaved case: a program that flips its own text between writable and executable
//    around every edit, the way a JIT with W^X enabled does.
static void TestMprotectRewrite(void) {
  Print("[guest] test 2: rewrite behind mprotect\n");
  void* Page = Map(0, PAGE, PROT_READ | PROT_WRITE, 0);
  if (!Page) {
    Print("[guest]   FAIL  mmap RW\n");
    ++Failures;
    return;
  }

  WriteBody(Page, 444);
  Syscall6(SYS_mprotect, (long)Page, PAGE, PROT_READ | PROT_EXEC, 0, 0, 0);
  Check("first call", ((BodyFn)Page)(), 444);

  Syscall6(SYS_mprotect, (long)Page, PAGE, PROT_READ | PROT_WRITE, 0, 0, 0);
  PatchImmediate(Page, 555);
  Syscall6(SYS_mprotect, (long)Page, PAGE, PROT_READ | PROT_EXEC, 0, 0, 0);
  Check("after mprotect, patch, mprotect", ((BodyFn)Page)(), 555);

  Syscall6(SYS_munmap, (long)Page, PAGE, 0, 0, 0, 0);
}

// 3. the nastiest of the three: the address is unmapped and something *different* is mapped back
//    over it. nothing about the page has changed as far as a permission check goes, and the only
//    thing that can invalidate the old translation is the host layer having noticed the munmap.
static void TestUnmapAndRemap(void) {
  Print("[guest] test 3: unmap a code page and map a new one at the same address\n");
  void* Page = Map(0, PAGE, PROT_READ | PROT_WRITE | PROT_EXEC, 0);
  if (!Page) {
    Print("[guest]   FAIL  mmap RWX\n");
    ++Failures;
    return;
  }

  WriteBody(Page, 666);
  Check("first call", ((BodyFn)Page)(), 666);

  Syscall6(SYS_munmap, (long)Page, PAGE, 0, 0, 0, 0);
  void* Again = Map(Page, PAGE, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_FIXED);
  if (Again != Page) {
    Print("[guest]   FAIL  could not map back over the same address\n");
    ++Failures;
    return;
  }

  WriteBody(Page, 777);
  Check("after unmap and remap", ((BodyFn)Page)(), 777);

  Syscall6(SYS_munmap, (long)Page, PAGE, 0, 0, 0, 0);
}

// 4. the other half of the tracking: a page the guest never asked to be executable. FEXCore's
//    decoder refuses to translate it and raises #PF/SEGV_ACCERR, which is what a real CPU does
//    with an NX page. a host layer that claims everything is executable runs the bytes instead.
static void TestJumpIntoDataPage(void) {
  Print("[guest] test 4: call into a page mapped without PROT_EXEC\n");

  struct KernelSigAction Action;
  Action.Handler = (u64)&NoExecHandler;
  Action.Flags = SA_SIGINFO | SA_RESTORER;
  Action.Restorer = (u64)&Restorer;
  Action.Mask = 0;
  if (Syscall6(SYS_rt_sigaction, SIGSEGV, (long)&Action, 0, 8, 0, 0) != 0) {
    Print("[guest]   FAIL  rt_sigaction\n");
    ++Failures;
    return;
  }

  void* Page = Map(0, PAGE, PROT_READ | PROT_WRITE, 0);
  if (!Page) {
    Print("[guest]   FAIL  mmap RW\n");
    ++Failures;
    return;
  }

  // a perfectly valid function, at an address the guest is not allowed to execute.
  WriteBody(Page, 888);
  Print("[guest]   calling into ");
  PrintHex((u64)Page);
  Print(", which is readable and writable but not executable\n");
  const u64 Returned = ((BodyFn)Page)();

  Check("handler ran", (u64)NoExecHandlerRan, SIGSEGV);
  Check("faulted at the page it jumped to", NoExecFaultAddress, (u64)Page);
  Check("si_code is SEGV_ACCERR", NoExecSiCode, 2);
  Check("returned the handler's sentinel", Returned, NOEXEC_SENTINEL);

  Syscall6(SYS_munmap, (long)Page, PAGE, 0, 0, 0, 0);
}

// 5. the hard one: a block that rewrites an instruction it is *itself* about to execute.
//
//   0: c6 47 05 63   mov byte ptr [rdi+5], 0x63   <- patches the immediate four bytes along
//   4: b8 2a 00 00 00  mov eax, 42
//   9: c3            ret
//
// the block is decoded before the store runs, so FEXCore translates `mov eax, 42` and only then
// seals the page. the store faults, and merely putting the write permission back and re-running it
// is not enough — control would carry straight on into the translation just dropped and the
// function would return 42. the host layer has to re-enter the dispatcher asking for a
// single-instruction block, which is the one part of mtrack that is not just bookkeeping.
static const unsigned char SelfPatchingBody[10] = {0xC6, 0x47, 0x05, 0x63, 0xB8, 0x2A, 0x00, 0x00, 0x00, 0xC3};

typedef u32 (*SelfPatchingFn)(void*);

static void TestRewriteInsideOwnBlock(void) {
  Print("[guest] test 5: a block that patches an instruction it is about to execute\n");
  void* Page = Map(0, PAGE, PROT_READ | PROT_WRITE | PROT_EXEC, 0);
  if (!Page) {
    Print("[guest]   FAIL  mmap RWX\n");
    ++Failures;
    return;
  }

  unsigned char* Bytes = (unsigned char*)Page;
  for (int i = 0; i < 10; ++i) {
    Bytes[i] = SelfPatchingBody[i];
  }

  // the first call is the whole test. by the second the block has been recompiled anyway, so it
  // would return 99 even from a host layer that got this wrong.
  Check("first call sees its own patch", ((SelfPatchingFn)Page)(Page), 99);

  Syscall6(SYS_munmap, (long)Page, PAGE, 0, 0, 0, 0);
}

__attribute__((used)) static void StartC(u64* Stack) {
  (void)Stack;
  Print("[guest] self-modifying code, and executing what should not be executed\n");

  TestRewriteInPlace();
  TestMprotectRewrite();
  TestUnmapAndRemap();
  TestJumpIntoDataPage();
  TestRewriteInsideOwnBlock();

  if (Failures) {
    Print("[guest] ");
    PrintDec((u64)Failures);
    Print(" check(s) failed\n");
    Syscall6(SYS_exit_group, 1, 0, 0, 0, 0, 0);
  }
  Print("[guest] all checks passed, exiting with 0\n");
  Syscall6(SYS_exit_group, 0, 0, 0, 0, 0, 0);
  __builtin_unreachable();
}

__asm__(".globl _start\n"
        "_start:\n"
        "  xor %rbp, %rbp\n"
        "  mov %rsp, %rdi\n"
        "  and $-16, %rsp\n"
        "  call StartC\n"
        "  hlt\n");
