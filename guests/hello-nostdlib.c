// a static x86-64 guest with no libc at all.
//
// this is the first thing the loader should be asked to run. it touches exactly two syscalls,
// write and exit_group, so anything that goes wrong is the loader's fault and not a libc's.
//
// it also prints back what it found on its own stack -- argc, argv, envp and a few auxv keys.
// that is the point: the mapping half of the loader announces itself loudly by working or
// crashing, but a subtly wrong initial stack produces a guest that runs and then misbehaves
// hundreds of instructions later, so the guest is made to report what it was handed.

typedef unsigned long u64;

#define SYS_write 1
#define SYS_exit_group 231

static long Syscall3(long Number, long A, long B, long C) {
  long Result;
  __asm__ volatile("syscall" : "=a"(Result) : "a"(Number), "D"(A), "S"(B), "d"(C) : "rcx", "r11", "memory");
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
  Syscall3(SYS_write, 1, (long)Text, (long)Length);
}

static void Print(const char* Text) {
  Write(Text, StringLength(Text));
}

static void PrintHex(u64 Value) {
  char Buffer[19];
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

// the generic auxv keys this guest reports on.
#define AT_NULL 0
#define AT_PHDR 3
#define AT_PHNUM 5
#define AT_PAGESZ 6
#define AT_ENTRY 9
#define AT_RANDOM 25
#define AT_EXECFN 31

static const char* AuxName(u64 Key) {
  switch (Key) {
  case AT_PHDR: return "AT_PHDR   ";
  case AT_PHNUM: return "AT_PHNUM  ";
  case AT_PAGESZ: return "AT_PAGESZ ";
  case AT_ENTRY: return "AT_ENTRY  ";
  case AT_RANDOM: return "AT_RANDOM ";
  case AT_EXECFN: return "AT_EXECFN ";
  default: return 0;
  }
}

__attribute__((used)) static void StartC(u64* Stack) {
  // snapshotted into locals before anything else runs, so the values printed are what was on
  // the stack at entry and not what survived a syscall in a register.
  volatile u64 Snapshot[8];
  for (int i = 0; i < 8; ++i) {
    Snapshot[i] = Stack[i];
  }

  Print("[guest] hello from x86-64 under FEXCore on android\n");
  Print("[guest] rsp = ");
  PrintHex((u64)Stack);
  Print("\n");
  for (int i = 0; i < 8; ++i) {
    Print("[guest] [rsp+");
    PrintDec((u64)(i * 8));
    Print("] = ");
    PrintHex(Snapshot[i]);
    Print("\n");
  }

  const u64 Argc = Stack[0];
  char** Argv = (char**)&Stack[1];

  Print("[guest] argc = ");
  PrintDec(Argc);
  Print("\n");
  // capped: if the loader hands over a wrong argc, an uncapped loop buries the diagnostic
  // output that would explain why under half a megabyte of repeats.
  for (u64 i = 0; i < Argc && i < 8; ++i) {
    Print("[guest]   argv[");
    PrintDec(i);
    Print("] = ");
    Print(Argv[i]);
    Print("\n");
  }

  char** Envp = Argv + Argc + 1;
  u64 EnvCount = 0;
  while (Envp[EnvCount]) {
    ++EnvCount;
  }
  Print("[guest] envp has ");
  PrintDec(EnvCount);
  Print(" entries");
  if (EnvCount) {
    Print(", first is ");
    Print(Envp[0]);
  }
  Print("\n");

  u64* Aux = (u64*)(Envp + EnvCount + 1);
  for (; Aux[0] != AT_NULL; Aux += 2) {
    const char* Name = AuxName(Aux[0]);
    if (!Name) {
      continue;
    }
    Print("[guest]   ");
    Print(Name);
    Print(" = ");
    if (Aux[0] == AT_EXECFN) {
      Print((const char*)Aux[1]);
    } else if (Aux[0] == AT_PHNUM || Aux[0] == AT_PAGESZ) {
      PrintDec(Aux[1]);
    } else {
      PrintHex(Aux[1]);
    }
    Print("\n");
  }

  // AT_RANDOM has to be a readable guest address with 16 bytes behind it. dereferencing it is
  // the cheapest way to prove the loader put it somewhere real rather than somewhere plausible.
  for (u64* Scan = (u64*)(Envp + EnvCount + 1); Scan[0] != AT_NULL; Scan += 2) {
    if (Scan[0] == AT_RANDOM) {
      const unsigned char* Bytes = (const unsigned char*)Scan[1];
      Print("[guest]   AT_RANDOM[0..3] = ");
      for (int i = 0; i < 4; ++i) {
        char Hex[3] = {"0123456789ABCDEF"[Bytes[i] >> 4], "0123456789ABCDEF"[Bytes[i] & 0xF], ' '};
        Write(Hex, 3);
      }
      Print("\n");
    }
  }

  Print("[guest] done, exiting with 0\n");
  Syscall3(SYS_exit_group, 0, 0, 0);
  __builtin_unreachable();
}

// _start must not be a C function: it receives its arguments *as the stack itself*, with argc
// at [rsp], and any prologue the compiler emits would move rsp before it could be read.
__asm__(".globl _start\n"
        "_start:\n"
        "  xor %rbp, %rbp\n"
        "  mov %rsp, %rdi\n"
        "  and $-16, %rsp\n"
        "  call StartC\n"
        "  hlt\n");
