// force-included into every C++ translation unit of the FEXCore android build.
//
// this exists so that the FEX checkout can stay pristine. FEX bans AI-generated
// contributions, so a patch to its source could never go upstream and would become a
// permanent private delta against a fast-moving project. a force-included shim keeps the
// fix on our side of the line.

#pragma once

#if defined(__ANDROID__)

#include <malloc.h>
#include <stddef.h>
#include <unistd.h>

// bionic does not provide valloc. it was deprecated in POSIX.1-2001 and removed outright in
// POSIX.1-2008, and bionic simply never carried it.
//
// FEXCore/Source/Utils/AllocatorHooks.cpp's passthrough path (the #else branch taken when
// ENABLE_FEX_ALLOCATOR is off, which is our configuration) defines
// FEXCore::Allocator::valloc as a thin forward to ::valloc, so the global has to exist.
//
// valloc(n) is defined as "allocate n bytes aligned to a page boundary", which is exactly
// memalign against the runtime page size. note the page size is read at runtime rather than
// assumed to be 4096: this device is a 4k-page kernel, but android also ships 16k-page
// configurations and the NDK links FEXCore with -Wl,-z,max-page-size=16384 conventions.
//
// static, so each translation unit gets its own copy and unused ones are discarded. bionic
// declares no competing valloc, so there is nothing to conflict with.
static inline void* valloc(size_t size) {
  return ::memalign(static_cast<size_t>(::sysconf(_SC_PAGESIZE)), size);
}

#endif // __ANDROID__
