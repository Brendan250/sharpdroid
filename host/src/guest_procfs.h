// sharpdroid host layer -- a /proc/self that describes the guest.
//
// this is the first place the host layer stops being a pass-through. everywhere else, forwarding a
// guest syscall to bionic gives an honest answer, because the guest really does live in this
// process and at these addresses. /proc/self is where that stops being true: the guest is not the
// process, and a guest that asks procfs who it is must not be told about the arm64 executable that
// loaded it.
//
// the concrete case that forces this: .NET's apphost locates its own single-file bundle with
// readlink("/proc/self/exe") and then parses a bundle header out of whatever comes back.
//
// what is deliberately NOT virtualised is as interesting as what is. guest and host share one
// address space 1:1 -- FEX does not translate guest addresses -- so /proc/self/maps read straight
// from bionic already describes the guest's mappings, at their real addresses, more accurately
// than anything synthesised could. the same goes for /proc/self/fd. those pass through.

#pragma once

#include <cstddef>

namespace HostLayer {

class GuestProcFS {
public:
  // the guest program as an absolute path. resolved once at load time, because the guest may have
  // been named relatively on the command line and /proc/self/exe is always absolute.
  void SetExe(const char* Path);
  void SetCmdline(int Argc, const char* const* Argv);

  // if Path names something under /proc/self (or /proc/<our own pid>) that should be answered with
  // a different file, returns that file's path. otherwise returns nullptr and the caller carries
  // on as before. covers open, stat, access -- everything that takes a path and wants the file.
  const char* Substitute(const char* Path) const;

  // the readlink answer for a /proc/self symlink, or nullptr if this is not one we own.
  const char* ReadLinkTarget(const char* Path) const;

  // /proc entries that are generated rather than stored need a file to hand back. returns an open
  // fd on an in-memory copy, or -1 for "not one of ours" -- distinct from a real error, which
  // cannot happen here.
  int OpenSynthetic(const char* Path) const;

private:
  // returns the part of Path after /proc/self/, or nullptr if Path is not under it.
  static const char* StripProcSelf(const char* Path);

  char Exe[512] {};
  char Cmdline[4096] {};
  size_t CmdlineSize {};
};

} // namespace HostLayer
