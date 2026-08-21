#include "guest_procfs.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/syscall.h>
#include <unistd.h>

namespace HostLayer {

void GuestProcFS::SetExe(const char* Path) {
  // realpath, because the guest is usually named relatively ("./SharpEmu") and /proc/self/exe is
  // always absolute -- and because whatever comes back gets opened, so a path relative to a working
  // directory the guest may later change is a trap.
  if (::realpath(Path, Exe) == nullptr) {
    std::snprintf(Exe, sizeof(Exe), "%s", Path);
  }
}

void GuestProcFS::SetCmdline(int Argc, const char* const* Argv) {
  // /proc/<pid>/cmdline is the arguments NUL-separated, including a trailing NUL. it is not a
  // string, and reading it as one gets you argv[0] alone.
  CmdlineSize = 0;
  for (int i = 0; i < Argc; ++i) {
    const size_t Length = std::strlen(Argv[i]) + 1;
    if (CmdlineSize + Length > sizeof(Cmdline)) {
      break;
    }
    std::memcpy(Cmdline + CmdlineSize, Argv[i], Length);
    CmdlineSize += Length;
  }
}

const char* GuestProcFS::StripProcSelf(const char* Path) {
  if (!Path) {
    return nullptr;
  }
  if (std::strncmp(Path, "/proc/self/", 11) == 0) {
    return Path + 11;
  }
  // /proc/<pid>/ with our own pid means the same thing, and glibc and CoreCLR both build that
  // form from getpid() rather than using the "self" shorthand.
  char Own[32];
  const int Length = std::snprintf(Own, sizeof(Own), "/proc/%d/", ::getpid());
  if (Length > 0 && std::strncmp(Path, Own, static_cast<size_t>(Length)) == 0) {
    return Path + Length;
  }
  return nullptr;
}

const char* GuestProcFS::Substitute(const char* Path) const {
  const char* Entry = StripProcSelf(Path);
  if (!Entry) {
    return nullptr;
  }
  if (std::strcmp(Entry, "exe") == 0 && Exe[0]) {
    return Exe;
  }
  return nullptr;
}

const char* GuestProcFS::ReadLinkTarget(const char* Path) const {
  // exe is the only /proc/self symlink the guest is lied to about, and its target is the same path
  // a lookup would be redirected to.
  return Substitute(Path);
}

int GuestProcFS::OpenSynthetic(const char* Path) const {
  const char* Entry = StripProcSelf(Path);
  if (!Entry || std::strcmp(Entry, "cmdline") != 0) {
    return -1;
  }

  // memfd rather than a temporary file: nothing has to be cleaned up, nothing is visible to
  // anything else, and it works in the app's sandbox where a writable path may not exist.
  // called through syscall() because bionic only declares memfd_create from API 30 and this
  // builds against 28.
  const int FD = static_cast<int>(::syscall(__NR_memfd_create, "guest-procfs", 0));
  if (FD < 0) {
    return -1;
  }
  if (CmdlineSize && ::write(FD, Cmdline, CmdlineSize) != static_cast<ssize_t>(CmdlineSize)) {
    ::close(FD);
    return -1;
  }
  ::lseek(FD, 0, SEEK_SET);
  return FD;
}

} // namespace HostLayer
