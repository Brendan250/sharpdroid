// sharpemu-android host layer — x86-64 ELF loader.
//
// maps a static x86-64 ELF into the guest address space and builds the initial stack the
// SysV x86-64 ABI hands to _start: argc, argv, envp and the auxiliary vector.
//
// guest and host share one address space 1:1 — FEX does not translate guest addresses — so
// "mapping into the guest address space" is just mmap, and a guest pointer is a host pointer.

#pragma once

#include <cstddef>
#include <cstdint>

namespace HostLayer {

struct LoadedELF {
  bool Ok {};
  const char* Error {};

  uint64_t Entry {};    // guest RIP to begin execution at
  uint64_t LoadBias {}; // 0 for ET_EXEC; the chosen base for ET_DYN
  uint64_t PhdrAddr {}; // AT_PHDR — the program headers as seen *in the guest mapping*
  uint64_t PhEnt {};
  uint64_t PhNum {};

  uint64_t MappingBegin {};
  uint64_t MappingEnd {};
  uint64_t BrkBase {}; // first page past the last PT_LOAD: where the guest heap starts

  // PT_INTERP exactly as the image spells it, which for anything built on a normal linux distro
  // is the absolute path "/lib64/ld-linux-x86-64.so.2". empty for a static binary. LoadELF64 only
  // records it; following it is LoadProgram's job.
  char InterpPath[256] {};
};

// PIEBase is the address ET_DYN images are biased to. it is ignored for ET_EXEC, which must
// be mapped where its program headers say.
LoadedELF LoadELF64(const char* Path, uint64_t PIEBase);

struct LoadedProgram {
  bool Ok {};
  const char* Error {};

  LoadedELF Exec {};   // the program itself
  LoadedELF Interp {}; // its dynamic linker. Interp.Ok is false for a static program

  uint64_t StartRIP {};   // where execution begins: the interpreter's entry when there is one
  uint64_t InterpBase {}; // AT_BASE. 0 when static, which is how the kernel says "no interpreter"
};

// loads a program and, if it asks for one, its interpreter.
//
// InterpSearchDir exists because PT_INTERP is an absolute path into a filesystem layout android
// does not have. if the path the image names is readable it is used unchanged; otherwise the
// basename is looked for in InterpSearchDir. that keeps the redirect in one place instead of
// spreading path rewriting through the syscall layer, and it is the same directory the guest
// gets as LD_LIBRARY_PATH.
LoadedProgram LoadProgram(const char* Path, uint64_t PIEBase, uint64_t InterpBase, const char* InterpSearchDir);

struct GuestStack {
  bool Ok {};
  const char* Error {};
  uint64_t RSP {}; // 16-byte aligned, pointing at argc
  uint64_t Base {};
  uint64_t Size {};
};

GuestStack BuildGuestStack(const LoadedProgram& Program, const char* GuestPath, int Argc, const char* const* Argv,
                           const char* const* Envp, size_t StackSize);

} // namespace HostLayer
