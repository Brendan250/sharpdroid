// a static x86-64 guest that goes through a real libc.
//
// the step up from hello-nostdlib: a libc's startup does far more than call main. it reads the
// auxv the loader built, sets up thread-local storage through arch_prctl, brings up the heap
// via brk or mmap, and stats stdout to decide how to buffer it. every one of those is a piece
// of the host layer being exercised for real rather than in isolation.

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <time.h>
#include <unistd.h>

// thread-local, so the guest's TLS actually has to work rather than merely be set up.
static __thread int TLSWitness = 0x5AFE;

int main(int argc, char** argv) {
  printf("[guest] hello from x86-64 libc under FEXCore on android\n");
  printf("[guest] argc = %d\n", argc);
  for (int i = 0; i < argc; ++i) {
    printf("[guest]   argv[%d] = %s\n", i, argv[i]);
  }

  printf("[guest] getpid = %d, getuid = %d\n", (int)getpid(), (int)getuid());
  printf("[guest] AT_PAGESZ = %lu, AT_ENTRY = 0x%lx\n", (unsigned long)getauxval(AT_PAGESZ), (unsigned long)getauxval(AT_ENTRY));

  // TLS: reading this back correctly means arch_prctl(ARCH_SET_FS) took effect and FS-relative
  // addressing in the JIT resolves through CPUState::fs_cached.
  printf("[guest] TLS witness = 0x%X (expected 0x5AFE)\n", TLSWitness);

  // the heap: brk or mmap depending on the allocator, and either way our syscall layer's.
  const size_t Size = 1 << 20;
  char* Block = malloc(Size);
  if (!Block) {
    printf("[guest] malloc failed\n");
    return 1;
  }
  memset(Block, 0xA5, Size);
  int Sum = 0;
  for (size_t i = 0; i < Size; i += 4096) {
    Sum += (unsigned char)Block[i];
  }
  printf("[guest] touched %zu bytes of heap, checksum %d (expected %d)\n", Size, Sum, 0xA5 * (int)(Size / 4096));
  free(Block);

  struct timespec Now;
  clock_gettime(CLOCK_MONOTONIC, &Now);
  printf("[guest] clock_gettime(CLOCK_MONOTONIC) = %lld.%09lld\n", (long long)Now.tv_sec, (long long)Now.tv_nsec);

  printf("[guest] done, exiting with 0\n");
  return 0;
}
