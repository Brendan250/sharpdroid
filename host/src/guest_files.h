// sharpdroid host layer -- a game directory the guest reaches by path, on a volume android
// will not open by path.
//
// android hands an app a *grant* on a directory the user picked, never a path to it. everything
// behind that grant is reached through a content provider, across binder, by document id. the guest
// is x86-64 .NET: File.Open becomes guest libc, becomes openat, becomes a syscall on an ordinary
// string -- so there is no call site of ours to put a branch in. every emulator that solves this
// problem solves it by owning its own file class and branching inside it, and none of them is
// running a multi-file game directory in place underneath a binary issuing raw syscalls.
//
// so the branch goes where we do own one: the syscall layer. the guest is handed an invented
// absolute path -- /game/eboot.bin -- and the calls that take a path are answered here, out of the
// provider, instead of being forwarded to bionic. guest_procfs.cpp already does exactly this in
// miniature for /proc/self, with Substitute and OpenSynthetic; this is the same idea one level up,
// with a real directory behind it rather than one invented file.
//
// **what is deliberately not intercepted is the whole design.** a descriptor the provider hands back
// is a real kernel descriptor on a real file, so read, pread, lseek, mmap and fstat on one are left
// alone and cost exactly what they cost on a staged path. only the calls that take a *path* become
// lookups -- and the guest's path-taking is over by the end of boot, measured on two titles, after
// which this layer is not on any path at all. docs/guest-files.md has the counts and the prices.
//
// the layer is read-only, which was measured rather than assumed: neither title writes into its own
// dump. a write that arrives anyway is refused with EROFS and says so loudly, because the
// alternative is a design assumption failing silently.

#pragma once

#include <cstddef>
#include <cstdint>
#include <sys/stat.h>

namespace HostLayer {

namespace GuestFiles {

// mounts the provider at an invented absolute prefix, and switches the whole layer on. everything
// below is inert until this is called, so a run that does not name a mount takes exactly the code
// path every measurement so far was taken on. false means the prefix was refused, and the caller
// should refuse the run rather than carry on with a mount that is not there.
bool SetMount(const char* Prefix);

// the gate, and the only thing on the fast path. a relaxed load and a branch that predicts
// perfectly, ahead of everything else in every entry point below.
bool Enabled();

// true when this call is ours to answer: an absolute path inside the mount, or a relative path
// against a directory descriptor this layer handed out. DirFD may be AT_FDCWD.
bool OwnsAt(int DirFD, const char* Path);

// each of these answers one syscall in full, and returns what that syscall returns: a descriptor,
// or 0, or a negative errno. none of them can fall through to bionic -- a path inside the mount does
// not exist as far as the kernel is concerned, so an answer here is the only answer there is.
int64_t Open(int DirFD, const char* Path, uint64_t GuestFlags);
int64_t Stat(int DirFD, const char* Path, int AtFlags, struct stat* Out);
int64_t Access(int DirFD, const char* Path, int Mode);
int64_t ReadLink(int DirFD, const char* Path);

// the descriptor side, which is only ever a *directory*: a file's descriptor is a real one and
// nothing here has to know it exists. a directory has no descriptor the provider can hand back at
// all, so one is invented -- see the implementation for what it is made of.
bool OwnsFD(int FD);
int64_t FStat(int FD, struct stat* Out);
int64_t GetDents(int FD, void* Buffer, size_t Size);
void Close(int FD);

} // namespace GuestFiles

} // namespace HostLayer
