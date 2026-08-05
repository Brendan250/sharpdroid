#include "linux_syscalls.h"

#include "guest_log.h"
#include "guest_threads.h"

#include <FEXCore/Core/CoreState.h>
// for CpuStateFrame::Thread->FrontendPtr, which is how a syscall finds the guest thread issuing it.
#include <FEXCore/Debug/InternalThreadState.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/select.h>
#include <sched.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/resource.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/sysinfo.h>
#include <sys/time.h>
#include <sys/times.h>
#include <sys/uio.h>
#include <sys/utsname.h>
#include <sys/vfs.h>
#include <time.h>
#include <unistd.h>

namespace HostLayer {

namespace {

// guest linux x86-64 syscall numbers. only the ones handled below are named.
enum GuestSyscall : uint64_t {
  SYS_x64_read = 0,
  SYS_x64_write = 1,
  SYS_x64_open = 2,
  SYS_x64_close = 3,
  SYS_x64_stat = 4,
  SYS_x64_fstat = 5,
  SYS_x64_lstat = 6,
  SYS_x64_poll = 7,
  SYS_x64_lseek = 8,
  SYS_x64_mmap = 9,
  SYS_x64_mprotect = 10,
  SYS_x64_munmap = 11,
  SYS_x64_brk = 12,
  SYS_x64_rt_sigaction = 13,
  SYS_x64_rt_sigprocmask = 14,
  SYS_x64_rt_sigreturn = 15,
  SYS_x64_ioctl = 16,
  SYS_x64_pread64 = 17,
  SYS_x64_pwrite64 = 18,
  SYS_x64_readv = 19,
  SYS_x64_writev = 20,
  SYS_x64_access = 21,
  SYS_x64_msync = 26,
  SYS_x64_mincore = 27,
  SYS_x64_madvise = 28,
  SYS_x64_nanosleep = 35,
  SYS_x64_sched_yield = 24,
  SYS_x64_mremap = 25,
  SYS_x64_dup = 32,
  SYS_x64_dup2 = 33,
  SYS_x64_getpid = 39,
  SYS_x64_socket = 41,
  SYS_x64_connect = 42,
  SYS_x64_accept = 43,
  SYS_x64_sendto = 44,
  SYS_x64_recvfrom = 45,
  SYS_x64_sendmsg = 46,
  SYS_x64_recvmsg = 47,
  SYS_x64_shutdown = 48,
  SYS_x64_bind = 49,
  SYS_x64_listen = 50,
  SYS_x64_getsockname = 51,
  SYS_x64_getpeername = 52,
  SYS_x64_socketpair = 53,
  SYS_x64_setsockopt = 54,
  SYS_x64_getsockopt = 55,
  SYS_x64_accept4 = 288,
  SYS_x64_exit = 60,
  SYS_x64_kill = 62,
  SYS_x64_tkill = 200,
  SYS_x64_uname = 63,
  SYS_x64_fcntl = 72,
  SYS_x64_flock = 73,
  SYS_x64_fsync = 74,
  SYS_x64_fdatasync = 75,
  SYS_x64_truncate = 76,
  SYS_x64_ftruncate = 77,
  SYS_x64_clone = 56,
  SYS_x64_getcwd = 79,
  SYS_x64_chdir = 80,
  SYS_x64_rename = 82,
  SYS_x64_mkdir = 83,
  SYS_x64_rmdir = 84,
  SYS_x64_creat = 85,
  SYS_x64_link = 86,
  SYS_x64_unlink = 87,
  SYS_x64_symlink = 88,
  SYS_x64_chmod = 90,
  SYS_x64_fchmod = 91,
  SYS_x64_umask = 95,
  SYS_x64_readlink = 89,
  SYS_x64_gettimeofday = 96,
  SYS_x64_getrlimit = 97,
  SYS_x64_getrusage = 98,
  SYS_x64_sysinfo = 99,
  SYS_x64_times = 100,
  SYS_x64_getuid = 102,
  SYS_x64_getgid = 104,
  SYS_x64_geteuid = 107,
  SYS_x64_getegid = 108,
  SYS_x64_getppid = 110,
  SYS_x64_getpgrp = 111,
  SYS_x64_getpgid = 121,
  SYS_x64_getsid = 124,
  SYS_x64_sigaltstack = 131,
  SYS_x64_mlock = 149,
  SYS_x64_munlock = 150,
  SYS_x64_mlockall = 151,
  SYS_x64_munlockall = 152,
  SYS_x64_setrlimit = 160,
  SYS_x64_statfs = 137,
  SYS_x64_fstatfs = 138,
  SYS_x64_sched_getparam = 143,
  SYS_x64_sched_setscheduler = 144,
  SYS_x64_sched_getscheduler = 145,
  SYS_x64_sched_get_priority_max = 146,
  SYS_x64_sched_get_priority_min = 147,
  SYS_x64_mknodat = 259,
  SYS_x64_arch_prctl = 158,
  SYS_x64_prctl = 157,
  SYS_x64_gettid = 186,
  SYS_x64_time = 201,
  SYS_x64_futex = 202,
  SYS_x64_sched_setaffinity = 203,
  SYS_x64_sched_getaffinity = 204,
  SYS_x64_select = 23,
  SYS_x64_epoll_wait = 232,
  SYS_x64_epoll_ctl = 233,
  SYS_x64_pselect6 = 270,
  SYS_x64_ppoll = 271,
  SYS_x64_epoll_pwait = 281,
  SYS_x64_eventfd2 = 290,
  SYS_x64_epoll_create1 = 291,
  SYS_x64_getdents64 = 217,
  SYS_x64_set_tid_address = 218,
  SYS_x64_clock_gettime = 228,
  SYS_x64_clock_getres = 229,
  SYS_x64_clock_nanosleep = 230,
  SYS_x64_exit_group = 231,
  SYS_x64_tgkill = 234,
  SYS_x64_set_robust_list = 273,
  SYS_x64_openat = 257,
  SYS_x64_mkdirat = 258,
  SYS_x64_fchownat = 260,
  SYS_x64_newfstatat = 262,
  SYS_x64_unlinkat = 263,
  SYS_x64_renameat = 264,
  SYS_x64_linkat = 265,
  SYS_x64_symlinkat = 266,
  SYS_x64_readlinkat = 267,
  SYS_x64_fchmodat = 268,
  SYS_x64_faccessat = 269,
  SYS_x64_utimensat = 280,
  SYS_x64_fallocate = 285,
  SYS_x64_dup3 = 292,
  SYS_x64_pipe2 = 293,
  SYS_x64_prlimit64 = 302,
  SYS_x64_renameat2 = 316,
  SYS_x64_getrandom = 318,
  SYS_x64_memfd_create = 319,
  SYS_x64_membarrier = 324,
  SYS_x64_statx = 332,
  SYS_x64_rseq = 334,
  SYS_x64_clone3 = 435,
  SYS_x64_faccessat2 = 439,
};

uint64_t FromHost(long Result) {
  return Result == -1 ? static_cast<uint64_t>(-errno) : static_cast<uint64_t>(Result);
}

// x86-64's struct stat. it is not the arm64 one — the field order diverges after st_ino and
// the padding differs — so every stat-shaped syscall has to write this layout by hand.
struct GuestStat {
  uint64_t st_dev;
  uint64_t st_ino;
  uint64_t st_nlink;
  uint32_t st_mode;
  uint32_t st_uid;
  uint32_t st_gid;
  uint32_t __pad0;
  uint64_t st_rdev;
  int64_t st_size;
  int64_t st_blksize;
  int64_t st_blocks;
  // spelled st_atim_sec rather than st_atime_nsec and friends because bionic's <sys/stat.h>
  // defines those as macros expanding to st_atim.tv_nsec, which turns a field declaration here
  // into a syntax error.
  int64_t st_atim_sec;
  uint64_t st_atim_nsec;
  int64_t st_mtim_sec;
  uint64_t st_mtim_nsec;
  int64_t st_ctim_sec;
  uint64_t st_ctim_nsec;
  int64_t __unused_[3];
};
static_assert(sizeof(GuestStat) == 144, "x86-64 struct stat is 144 bytes");

void TranslateStat(const struct stat& Host, GuestStat* Guest) {
  std::memset(Guest, 0, sizeof(*Guest));
  Guest->st_dev = Host.st_dev;
  Guest->st_ino = Host.st_ino;
  Guest->st_nlink = Host.st_nlink;
  Guest->st_mode = Host.st_mode;
  Guest->st_uid = Host.st_uid;
  Guest->st_gid = Host.st_gid;
  Guest->st_rdev = Host.st_rdev;
  Guest->st_size = Host.st_size;
  Guest->st_blksize = Host.st_blksize;
  Guest->st_blocks = Host.st_blocks;
  Guest->st_atim_sec = Host.st_atim.tv_sec;
  Guest->st_atim_nsec = Host.st_atim.tv_nsec;
  Guest->st_mtim_sec = Host.st_mtim.tv_sec;
  Guest->st_mtim_nsec = Host.st_mtim.tv_nsec;
  Guest->st_ctim_sec = Host.st_ctim.tv_sec;
  Guest->st_ctim_nsec = Host.st_ctim.tv_nsec;
}

// four O_* bits sit at different values on x86-64 than on the asm-generic architectures arm64
// uses. everything below O_DSYNC agrees, so only these need moving; passing them through
// unchanged would silently turn an O_DIRECTORY open into O_LARGEFILE.
int TranslateOpenFlags(uint64_t GuestFlags) {
  constexpr uint64_t GuestO_DIRECT = 0x4000;
  constexpr uint64_t GuestO_LARGEFILE = 0x8000;
  constexpr uint64_t GuestO_DIRECTORY = 0x10000;
  constexpr uint64_t GuestO_NOFOLLOW = 0x20000;

  uint64_t Flags = GuestFlags & ~(GuestO_DIRECT | GuestO_LARGEFILE | GuestO_DIRECTORY | GuestO_NOFOLLOW);
  if (GuestFlags & GuestO_DIRECT) {
    Flags |= O_DIRECT;
  }
  if (GuestFlags & GuestO_DIRECTORY) {
    Flags |= O_DIRECTORY;
  }
  if (GuestFlags & GuestO_NOFOLLOW) {
    Flags |= O_NOFOLLOW;
  }
  // O_LARGEFILE is meaningless on a 64-bit host; bionic's is already implied.
  return static_cast<int>(Flags);
}

// PROT_EXEC never reaches the host kernel — see VMA::HostProt, which is where the rule lives now.
//
// the guest cannot tell. it never reads back its own protections, mprotect reports success, and
// as of M1g the VMA tracker remembers what was really asked for, so FEXCore is not fooled either.
int TranslateProt(uint64_t GuestProt) {
  return VMA::HostProt(static_cast<int>(GuestProt));
}

// MAP_32BIT and MAP_ABOVE4G are x86-only placement hints; those two bits mean nothing on the
// asm-generic arm64 flag set and would be rejected as unknown. everything else — down to
// MAP_FIXED_NOREPLACE at 0x100000 — holds the same value on both architectures.
int TranslateMapFlags(uint64_t GuestFlags) {
  constexpr uint64_t GuestMAP_32BIT = 0x40;
  constexpr uint64_t GuestMAP_ABOVE4G = 0x80;
  return static_cast<int>(GuestFlags & ~(GuestMAP_32BIT | GuestMAP_ABOVE4G));
}

constexpr uint64_t BrkArenaSize = 512ULL * 1024 * 1024;

uint64_t PageSize() {
  static const uint64_t Size = static_cast<uint64_t>(::sysconf(_SC_PAGESIZE));
  return Size;
}

uint64_t AlignUp(uint64_t Value, uint64_t Alignment) {
  return (Value + Alignment - 1) & ~(Alignment - 1);
}

} // namespace

LinuxSyscallHandler::LinuxSyscallHandler() {
  // OS_LINUX64 is what marshals guest RAX/RDI/... into SyscallArguments::Argument[]. OS_GENERIC
  // ("no JIT-side argument handling, spill/fill all regs") does not, and the handler then
  // receives garbage.
  OSABI = FEXCore::HLE::SyscallOSABI::OS_LINUX64;
}

void LinuxSyscallHandler::SetBrkBase(uint64_t Base) {
  std::lock_guard Lock {BrkLock};
  BrkBase = BrkCurrent = Base;
  BrkArenaEnd = Base;

  // the guest heap gets a reservation of its own rather than being grown with plain mmap on
  // demand. brk must return contiguous memory, and in a 39-bit address space shared with
  // FEXCore, .NET and bionic there is no guarantee the next page past the image will still be
  // free by the time the guest asks for it. reserving PROT_NONE up front costs address space
  // and no memory.
  void* Arena = ::mmap(reinterpret_cast<void*>(Base), BrkArenaSize, PROT_NONE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (Arena != MAP_FAILED && reinterpret_cast<uint64_t>(Arena) == Base) {
    BrkArenaEnd = Base + BrkArenaSize;
    // the whole reservation, PROT_NONE. brk hands pages out of it by mprotect, and each of those
    // re-records the range it touched — so the tracker always describes the heap as the guest
    // sees it, with the untouched tail correctly unreadable rather than quietly executable.
    VMA::Record(Base, BrkArenaSize, PROT_NONE);
  } else if (Arena != MAP_FAILED) {
    // the kernel put the reservation somewhere else; brk has to be contiguous with the image,
    // so that is no use.
    ::munmap(Arena, BrkArenaSize);
  }
}

uint64_t LinuxSyscallHandler::HandleBrk(uint64_t NewBreak) {
  std::lock_guard Lock {BrkLock};
  // brk(0) is the idiomatic "where is the break?" query, and brk always returns the resulting
  // break rather than an error — a failed request is reported by the break not having moved.
  if (NewBreak == 0 || NewBreak < BrkBase || NewBreak > BrkArenaEnd) {
    return BrkCurrent;
  }

  const uint64_t WantEnd = AlignUp(NewBreak, PageSize());
  const uint64_t HaveEnd = AlignUp(BrkCurrent, PageSize());
  if (WantEnd > HaveEnd) {
    if (::mprotect(reinterpret_cast<void*>(HaveEnd), WantEnd - HaveEnd, PROT_READ | PROT_WRITE) != 0) {
      return BrkCurrent;
    }
    VMA::Record(HaveEnd, WantEnd - HaveEnd, PROT_READ | PROT_WRITE);
  } else if (WantEnd < HaveEnd) {
    // shrinking: hand the pages back but keep the reservation, so the address range stays ours.
    ::mmap(reinterpret_cast<void*>(WantEnd), HaveEnd - WantEnd, PROT_NONE,
           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED | MAP_NORESERVE, -1, 0);
    // back to PROT_NONE reservation, and anything compiled out of the freed pages goes with it.
    VMA::Record(WantEnd, HaveEnd - WantEnd, PROT_NONE);
    VMA::Invalidate(nullptr, WantEnd, HaveEnd - WantEnd);
  }

  BrkCurrent = NewBreak;
  return BrkCurrent;
}

FEXCore::HLE::ExecutableRangeInfo LinuxSyscallHandler::QueryGuestExecutableRange(FEXCore::Core::InternalThreadState*, uint64_t Address) {
  return VMA::Query(Address);
}

void LinuxSyscallHandler::MarkGuestExecutableRange(FEXCore::Core::InternalThreadState*, uint64_t Start, uint64_t Length) {
  VMA::MarkExecutable(Start, Length);
}

void LinuxSyscallHandler::InvalidateGuestCodeRange(FEXCore::Core::InternalThreadState* Thread, uint64_t Start, uint64_t Length) {
  // FEXCore calls this from more places than the name suggests, and one of them is load-bearing
  // for SMCChecks=full: the byte-comparison guard emitted into every block calls
  // `_ThreadRemoveCodeEntry`, which lands here. leaving it as the base class's empty default does
  // not merely lose an optimisation — the guard detects the change, invalidates nothing, returns
  // to the same entrypoint, finds the same stale block and spins forever.
  VMA::Invalidate(Thread, Start, Length);
}

uint64_t LinuxSyscallHandler::HandleSyscall(FEXCore::Core::CpuStateFrame* Frame, FEXCore::HLE::SyscallArguments* Args) {
  const uint64_t Result = Dispatch(Frame, Args);

  // a syscall boundary is the host layer's one unconditionally safe delivery point: CPUState
  // describes the guest exactly, no host lock is held, and an interrupted host call is still close
  // enough to its start to be restarted. so every syscall ends by asking whether a signal was
  // raised on this thread while it was somewhere it could not be redirected from — which includes
  // the very common case of a thread parked in futex or poll, brought back with EINTR for exactly
  // this reason.
  //
  // it does not return if it delivers.
  if (auto* Self = static_cast<GuestThread*>(Frame->Thread->FrontendPtr)) {
    Threads::DeliverPendingAtSyscallExit(*Self, Args->Argument[0], Result);
  }
  return Result;
}

uint64_t LinuxSyscallHandler::Dispatch(FEXCore::Core::CpuStateFrame* Frame, FEXCore::HLE::SyscallArguments* Args) {
  const uint64_t Number = Args->Argument[0];

  // the vulkan thunk rides in on the syscall boundary rather than beside it, because the boundary
  // is what guarantees the guest's registers are all in CPUState — which is where the thunk reads
  // its arguments from. the magic range is far above any real syscall number, so this test can
  // never shadow one. see vulkan_thunk.h.
  if (VulkanThunk::IsThunkCall(Number)) [[unlikely]] {
    return VulkanThunk::Handle(Frame, Args);
  }
  // and the audio thunk on the same boundary, for the same reason and in a magic range one along.
  // see audio_thunk.h.
  if (AudioThunk::IsThunkCall(Number)) [[unlikely]] {
    return AudioThunk::Handle(Frame, Args);
  }

  const uint64_t Arg0 = Args->Argument[1];
  const uint64_t Arg1 = Args->Argument[2];
  const uint64_t Arg2 = Args->Argument[3];
  const uint64_t Arg3 = Args->Argument[4];
  const uint64_t Arg4 = Args->Argument[5];
  const uint64_t Arg5 = Args->Argument[6];

  // the guest thread making this call, taken from the frame rather than from thread-local storage:
  // the frame is the syscall's own context and cannot describe anyone else. per-thread guest state
  // — the signal mask, the alternate stack — hangs off this.
  auto* Self = static_cast<GuestThread*>(Frame->Thread->FrontendPtr);

  if (Trace) {
    // path-taking syscalls get their path printed. a trace of bare pointers answers "how many
    // opens" and never "which file", and which file is the whole question when working out what
    // a guest's dynamic linker is searching for and failing to find.
    const char* Path = nullptr;
    switch (Number) {
    case SYS_x64_open:
    case SYS_x64_access:
    case SYS_x64_stat:
    case SYS_x64_lstat:
    case SYS_x64_readlink:
    case SYS_x64_statfs: Path = reinterpret_cast<const char*>(Arg0); break;
    case SYS_x64_openat:
    case SYS_x64_newfstatat:
    case SYS_x64_readlinkat:
    case SYS_x64_faccessat:
    case SYS_x64_faccessat2:
    case SYS_x64_statx:
    case SYS_x64_utimensat: Path = reinterpret_cast<const char*>(Arg1); break;
    default: break;
    }
    // the tid, because from here on there is more than one guest thread issuing syscalls and an
    // interleaved trace without it is unreadable — two threads' library loads look like one
    // thread doing something incoherent.
    const int TID = ::gettid();
    if (Path) {
      std::printf("[syscall %d] %llu(\"%s\", 0x%llX, 0x%llX)\n", TID, static_cast<unsigned long long>(Number), Path,
                  static_cast<unsigned long long>(Arg2), static_cast<unsigned long long>(Arg3));
    } else {
      std::printf("[syscall %d] %llu(0x%llX, 0x%llX, 0x%llX, 0x%llX, 0x%llX, 0x%llX)\n", TID,
                  static_cast<unsigned long long>(Number), static_cast<unsigned long long>(Arg0),
                  static_cast<unsigned long long>(Arg1), static_cast<unsigned long long>(Arg2),
                  static_cast<unsigned long long>(Arg3), static_cast<unsigned long long>(Arg4),
                  static_cast<unsigned long long>(Arg5));
    }
  }

  switch (Number) {
  // --- guest addresses are host addresses, so buffers pass straight through -----------------
  case SYS_x64_read: return FromHost(::read(static_cast<int>(Arg0), reinterpret_cast<void*>(Arg1), Arg2));
  // stdout and stderr go through the timestamper, which is a plain write unless --timestamps is on.
  // this is the only place SharpEmu's log ever reaches the outside world, so it is the only place a
  // stamp has to be applied to get a log the shape of the Windows release's.
  case SYS_x64_write:
    return FromHost(GuestLog::Write(static_cast<int>(Arg0), reinterpret_cast<const void*>(Arg1), Arg2));
  case SYS_x64_pread64:
    return FromHost(::pread(static_cast<int>(Arg0), reinterpret_cast<void*>(Arg1), Arg2, static_cast<off_t>(Arg3)));
  // struct iovec is {void* base; size_t len} on both architectures, so no translation.
  case SYS_x64_readv:
    return FromHost(::readv(static_cast<int>(Arg0), reinterpret_cast<const struct iovec*>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_writev:
    return FromHost(GuestLog::Writev(static_cast<int>(Arg0), reinterpret_cast<const struct iovec*>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_close: return FromHost(::close(static_cast<int>(Arg0)));
  case SYS_x64_lseek: return FromHost(::lseek(static_cast<int>(Arg0), static_cast<off_t>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_dup: return FromHost(::dup(static_cast<int>(Arg0)));
  case SYS_x64_dup2: return FromHost(::dup2(static_cast<int>(Arg0), static_cast<int>(Arg1)));
  case SYS_x64_pipe2: return FromHost(::pipe2(reinterpret_cast<int*>(Arg0), static_cast<int>(Arg1)));
  // the F_* command numbers agree between the two architectures. what does not is the flag word
  // F_GETFL returns, which comes back in host O_* values — a guest that inspects O_DIRECTORY or
  // O_NOFOLLOW in the result will read the wrong bit. left alone until something needs it.
  case SYS_x64_fcntl: return FromHost(::fcntl(static_cast<int>(Arg0), static_cast<int>(Arg1), Arg2));
  case SYS_x64_getcwd: {
    // getcwd's syscall returns the length written, where the libc wrapper returns the pointer.
    if (::getcwd(reinterpret_cast<char*>(Arg0), Arg1) == nullptr) {
      return static_cast<uint64_t>(-errno);
    }
    return std::strlen(reinterpret_cast<char*>(Arg0)) + 1;
  }
  case SYS_x64_getdents64: return FromHost(::syscall(SYS_getdents64, Arg0, Arg1, Arg2));

  // --- creating and modifying files -----------------------------------------------------------
  //
  // .NET's single-file host extracts the runtime's native libraries beside itself before dlopen'ing
  // them, so the whole create/write/chmod/rename dance has to work. as with access/stat/lstat, the
  // legacy non-*at forms exist only on x86-64 and have to be routed onto the *at ones by hand.
  case SYS_x64_pwrite64:
    return FromHost(::pwrite(static_cast<int>(Arg0), reinterpret_cast<const void*>(Arg1), Arg2, static_cast<off_t>(Arg3)));
  case SYS_x64_creat:
    return FromHost(::openat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), O_CREAT | O_WRONLY | O_TRUNC,
                             static_cast<mode_t>(Arg1)));
  case SYS_x64_mkdir: return FromHost(::mkdirat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), static_cast<mode_t>(Arg1)));
  case SYS_x64_mkdirat:
    return FromHost(::mkdirat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<mode_t>(Arg2)));
  // the S_IF* type bits in the mode agree between the architectures, and so does dev_t.
  case SYS_x64_mknodat:
    return FromHost(::mknodat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<mode_t>(Arg2),
                              static_cast<dev_t>(Arg3)));
  case SYS_x64_rmdir: return FromHost(::unlinkat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), AT_REMOVEDIR));
  case SYS_x64_unlink: return FromHost(::unlinkat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), 0));
  case SYS_x64_unlinkat:
    return FromHost(::unlinkat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_rename:
    return FromHost(::renameat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), AT_FDCWD, reinterpret_cast<const char*>(Arg1)));
  case SYS_x64_renameat:
    return FromHost(::renameat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<int>(Arg2),
                               reinterpret_cast<const char*>(Arg3)));
  // RENAME_NOREPLACE and friends agree across architectures. bionic only declares renameat2 from
  // API 30, hence the raw syscall.
  case SYS_x64_renameat2: return FromHost(::syscall(SYS_renameat2, Arg0, Arg1, Arg2, Arg3, Arg4));
  case SYS_x64_link:
    return FromHost(::linkat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), AT_FDCWD, reinterpret_cast<const char*>(Arg1), 0));
  case SYS_x64_linkat:
    return FromHost(::linkat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<int>(Arg2),
                             reinterpret_cast<const char*>(Arg3), static_cast<int>(Arg4)));
  case SYS_x64_symlink:
    return FromHost(::symlinkat(reinterpret_cast<const char*>(Arg0), AT_FDCWD, reinterpret_cast<const char*>(Arg1)));
  case SYS_x64_symlinkat:
    return FromHost(::symlinkat(reinterpret_cast<const char*>(Arg0), static_cast<int>(Arg1),
                                reinterpret_cast<const char*>(Arg2)));
  case SYS_x64_chmod:
    return FromHost(::fchmodat(AT_FDCWD, reinterpret_cast<const char*>(Arg0), static_cast<mode_t>(Arg1), 0));
  case SYS_x64_fchmod: return FromHost(::fchmod(static_cast<int>(Arg0), static_cast<mode_t>(Arg1)));
  case SYS_x64_fchmodat:
    return FromHost(::fchmodat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<mode_t>(Arg2), 0));
  case SYS_x64_fchownat:
    return FromHost(::fchownat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1), static_cast<uid_t>(Arg2),
                               static_cast<gid_t>(Arg3), static_cast<int>(Arg4)));
  case SYS_x64_truncate: return FromHost(::truncate(reinterpret_cast<const char*>(Arg0), static_cast<off_t>(Arg1)));
  case SYS_x64_ftruncate: return FromHost(::ftruncate(static_cast<int>(Arg0), static_cast<off_t>(Arg1)));
  case SYS_x64_fallocate:
    return FromHost(::fallocate(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<off_t>(Arg2),
                                static_cast<off_t>(Arg3)));
  case SYS_x64_fsync: return FromHost(::fsync(static_cast<int>(Arg0)));
  case SYS_x64_fdatasync: return FromHost(::fdatasync(static_cast<int>(Arg0)));
  case SYS_x64_flock: return FromHost(::flock(static_cast<int>(Arg0), static_cast<int>(Arg1)));
  case SYS_x64_umask: return static_cast<uint64_t>(::umask(static_cast<mode_t>(Arg0)));
  case SYS_x64_chdir: return FromHost(::chdir(reinterpret_cast<const char*>(Arg0)));
  case SYS_x64_dup3: return FromHost(::dup3(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_memfd_create: return FromHost(::syscall(SYS_memfd_create, Arg0, Arg1));
  // struct statfs is all 64-bit words on both LP64 architectures.
  case SYS_x64_statfs: return FromHost(::statfs(reinterpret_cast<const char*>(Arg0), reinterpret_cast<struct statfs*>(Arg1)));
  case SYS_x64_fstatfs: return FromHost(::fstatfs(static_cast<int>(Arg0), reinterpret_cast<struct statfs*>(Arg1)));
  // arm64 has no plain access/stat/lstat — asm-generic dropped them in favour of the *at forms —
  // so on x86-64 these are the numbers glibc actually issues, and they have to be routed by hand.
  // ld.so probes /etc/ld.so.preload with access() before it does anything else.
  case SYS_x64_access:
  case SYS_x64_faccessat:
  case SYS_x64_faccessat2: {
    const int DirFD = Number == SYS_x64_access ? AT_FDCWD : static_cast<int>(Arg0);
    const char* Path = reinterpret_cast<const char*>(Number == SYS_x64_access ? Arg0 : Arg1);
    const uint64_t Mode = Number == SYS_x64_access ? Arg1 : Arg2;
    // only faccessat2 takes flags. the faccessat *syscall* is three arguments — the fourth
    // argument bionic's wrapper has is a libc-level invention — so reading Arg3 for it means
    // reading whatever the guest happened to leave in R10, and bionic rejects unknown flags
    // with EINVAL.
    const uint64_t Flags = Number == SYS_x64_faccessat2 ? Arg3 : 0;
    if (const char* Substitute = Proc.Substitute(Path)) {
      Path = Substitute;
    }
    return FromHost(::faccessat(DirFD, Path, static_cast<int>(Mode), static_cast<int>(Flags)));
  }
  case SYS_x64_utimensat:
    return FromHost(::utimensat(static_cast<int>(Arg0), reinterpret_cast<const char*>(Arg1),
                                reinterpret_cast<const struct timespec*>(Arg2), static_cast<int>(Arg3)));
#ifdef SYS_statx
  // struct statx is fixed-width and identical on every architecture, so it needs no translation
  // — unlike struct stat, which is the one that does.
  case SYS_x64_statx: return FromHost(::syscall(SYS_statx, Arg0, Arg1, Arg2, Arg3, Arg4));
#endif

  // --- paths, with /proc/self answered about the guest rather than about us ------------------
  case SYS_x64_open:
  case SYS_x64_openat: {
    const int DirFD = Number == SYS_x64_open ? AT_FDCWD : static_cast<int>(Arg0);
    const char* Path = reinterpret_cast<const char*>(Number == SYS_x64_open ? Arg0 : Arg1);
    const uint64_t Flags = Number == SYS_x64_open ? Arg1 : Arg2;
    const uint64_t Mode = Number == SYS_x64_open ? Arg2 : Arg3;

    const int Synthetic = Proc.OpenSynthetic(Path);
    if (Synthetic >= 0) {
      return static_cast<uint64_t>(Synthetic);
    }
    if (const char* Substitute = Proc.Substitute(Path)) {
      Path = Substitute;
    }
    return FromHost(::openat(DirFD, Path, TranslateOpenFlags(Flags), static_cast<mode_t>(Mode)));
  }
  case SYS_x64_readlink:
  case SYS_x64_readlinkat: {
    const int DirFD = Number == SYS_x64_readlink ? AT_FDCWD : static_cast<int>(Arg0);
    const char* Path = reinterpret_cast<const char*>(Number == SYS_x64_readlink ? Arg0 : Arg1);
    char* Buffer = reinterpret_cast<char*>(Number == SYS_x64_readlink ? Arg1 : Arg2);
    const size_t Size = Number == SYS_x64_readlink ? Arg2 : Arg3;

    if (const char* Target = Proc.ReadLinkTarget(Path)) {
      // readlink does not NUL-terminate and does not fail on truncation — it writes what fits and
      // returns that. matching that exactly matters: the caller sizes its buffer from the result.
      const size_t Length = std::strlen(Target);
      const size_t Written = Length < Size ? Length : Size;
      std::memcpy(Buffer, Target, Written);
      return Written;
    }
    return FromHost(::readlinkat(DirFD, Path, Buffer, Size));
  }

  case SYS_x64_fstat: {
    struct stat Host {};
    if (::fstat(static_cast<int>(Arg0), &Host) != 0) {
      return static_cast<uint64_t>(-errno);
    }
    TranslateStat(Host, reinterpret_cast<GuestStat*>(Arg1));
    return 0;
  }
  case SYS_x64_newfstatat: {
    struct stat Host {};
    const char* Path = reinterpret_cast<const char*>(Arg1);
    if (const char* Substitute = Proc.Substitute(Path)) {
      Path = Substitute;
    }
    // AT_EMPTY_PATH and AT_SYMLINK_NOFOLLOW hold the same values on both architectures.
    if (::fstatat(static_cast<int>(Arg0), Path, &Host, static_cast<int>(Arg3)) != 0) {
      return static_cast<uint64_t>(-errno);
    }
    TranslateStat(Host, reinterpret_cast<GuestStat*>(Arg2));
    return 0;
  }
  case SYS_x64_stat:
  case SYS_x64_lstat: {
    struct stat Host {};
    const char* Path = reinterpret_cast<const char*>(Arg0);
    // lstat of /proc/self/exe would report the symlink itself, but the substitute is not a
    // symlink — so an lstat of it answers about the guest binary. that is the more useful lie:
    // the only thing a caller learns from lstat'ing the link is its target's length, which it
    // gets from readlink anyway.
    if (const char* Substitute = Proc.Substitute(Path)) {
      Path = Substitute;
    }
    const int Flags = Number == SYS_x64_lstat ? AT_SYMLINK_NOFOLLOW : 0;
    if (::fstatat(AT_FDCWD, Path, &Host, Flags) != 0) {
      return static_cast<uint64_t>(-errno);
    }
    TranslateStat(Host, reinterpret_cast<GuestStat*>(Arg1));
    return 0;
  }

  // --- memory. PROT_* and MAP_* agree between x86-64 and arm64 apart from the two noted at
  // TranslateProt/TranslateMapFlags above ------------------------------------------------------
  //
  // every one of these tells the VMA tracker what happened, and every one of them tells it the
  // protection the *guest* asked for rather than the one bionic was given. the host kernel never
  // sees PROT_EXEC, so if the tracker is not told here the information does not exist anywhere.
  case SYS_x64_mmap: {
    void* Result = ::mmap(reinterpret_cast<void*>(Arg0), Arg1, TranslateProt(Arg2), TranslateMapFlags(Arg3),
                          static_cast<int>(static_cast<int32_t>(Arg4)), static_cast<off_t>(Arg5));
    if (Result == MAP_FAILED) {
      return static_cast<uint64_t>(-errno);
    }
    VMA::Record(reinterpret_cast<uint64_t>(Result), Arg1, static_cast<int>(Arg2));
    return reinterpret_cast<uint64_t>(Result);
  }
  case SYS_x64_mremap: {
    void* Result = ::mremap(reinterpret_cast<void*>(Arg0), Arg1, Arg2, static_cast<int>(Arg3), reinterpret_cast<void*>(Arg4));
    if (Result == MAP_FAILED) {
      return static_cast<uint64_t>(-errno);
    }
    VMA::Remap(Arg0, Arg1, reinterpret_cast<uint64_t>(Result), Arg2);
    return reinterpret_cast<uint64_t>(Result);
  }
  case SYS_x64_mprotect: {
    const uint64_t Result = FromHost(::mprotect(reinterpret_cast<void*>(Arg0), Arg1, TranslateProt(Arg2)));
    if (Result == 0) {
      VMA::Reprotect(Arg0, Arg1, static_cast<int>(Arg2));
    }
    return Result;
  }
  case SYS_x64_munmap: {
    const uint64_t Result = FromHost(::munmap(reinterpret_cast<void*>(Arg0), Arg1));
    if (Result == 0) {
      VMA::Forget(Arg0, Arg1);
    }
    return Result;
  }
  case SYS_x64_madvise: {
    const uint64_t Result = FromHost(::madvise(reinterpret_cast<void*>(Arg0), Arg1, static_cast<int>(Arg2)));
    // MADV_DONTNEED does not unmap, so the mapping and its protection stand — but the *contents*
    // are gone, and the next read of an anonymous page there gives zeroes. anything FEXCore
    // compiled out of those bytes is describing something that no longer exists.
    if (Result == 0 && static_cast<int>(Arg2) == MADV_DONTNEED) {
      VMA::Invalidate(Frame->Thread, Arg0, Arg1);
    }
    return Result;
  }
  case SYS_x64_msync: return FromHost(::msync(reinterpret_cast<void*>(Arg0), Arg1, static_cast<int>(Arg2)));
  case SYS_x64_mincore: return FromHost(::mincore(reinterpret_cast<void*>(Arg0), Arg1, reinterpret_cast<unsigned char*>(Arg2)));
  case SYS_x64_brk: return HandleBrk(Arg0);

  // CoreCLR's FlushProcessWriteBuffers wants a process-wide memory barrier. it asks membarrier
  // for one first and, if that is unavailable, falls back to touching a locked page's protection
  // — which is why mlock sits next to it here rather than anywhere near the rest of the mm calls.
  // bionic declares neither membarrier nor its constants, hence the raw syscall.
  case SYS_x64_membarrier: return FromHost(::syscall(SYS_membarrier, Arg0, Arg1, Arg2));
  case SYS_x64_mlock: return FromHost(::mlock(reinterpret_cast<const void*>(Arg0), Arg1));
  case SYS_x64_munlock: return FromHost(::munlock(reinterpret_cast<const void*>(Arg0), Arg1));
  case SYS_x64_mlockall: return FromHost(::mlockall(static_cast<int>(Arg0)));
  case SYS_x64_munlockall: return FromHost(::munlockall());

  // --- thread-local storage ------------------------------------------------------------------
  // in 64-bit mode FEX keeps the FS and GS bases in CPUState rather than in a descriptor, and
  // reads them from there for every segment-prefixed access. writing them here is the whole of
  // arch_prctl. this works from inside a syscall because the JIT spills all statically
  // allocated registers to CPUState before calling us and refills them after — so guest state
  // is genuinely live in memory for the duration of this function.
  case SYS_x64_arch_prctl: {
    constexpr uint64_t ARCH_SET_GS = 0x1001, ARCH_SET_FS = 0x1002, ARCH_GET_FS = 0x1003, ARCH_GET_GS = 0x1004;
    switch (Arg0) {
    case ARCH_SET_GS: Frame->State.gs_cached = Arg1; return 0;
    case ARCH_SET_FS: Frame->State.fs_cached = Arg1; return 0;
    case ARCH_GET_FS: *reinterpret_cast<uint64_t*>(Arg1) = Frame->State.fs_cached; return 0;
    case ARCH_GET_GS: *reinterpret_cast<uint64_t*>(Arg1) = Frame->State.gs_cached; return 0;
    default: return static_cast<uint64_t>(-EINVAL);
    }
  }

  // --- identity and misc ---------------------------------------------------------------------
  case SYS_x64_getpid: return static_cast<uint64_t>(::getpid());
  case SYS_x64_getppid: return static_cast<uint64_t>(::getppid());
  case SYS_x64_getpgrp: return static_cast<uint64_t>(::getpgrp());
  case SYS_x64_getpgid: return FromHost(::getpgid(static_cast<pid_t>(Arg0)));
  case SYS_x64_getsid: return FromHost(::getsid(static_cast<pid_t>(Arg0)));
  case SYS_x64_gettid: return static_cast<uint64_t>(::gettid());
  case SYS_x64_getuid: return static_cast<uint64_t>(::getuid());
  case SYS_x64_geteuid: return static_cast<uint64_t>(::geteuid());
  case SYS_x64_getgid: return static_cast<uint64_t>(::getgid());
  case SYS_x64_getegid: return static_cast<uint64_t>(::getegid());
  case SYS_x64_sched_yield: return FromHost(::sched_yield());
  case SYS_x64_getrandom: return FromHost(::getrandom(reinterpret_cast<void*>(Arg0), Arg1, static_cast<unsigned int>(Arg2)));
  case SYS_x64_ioctl:
    return FromHost(::syscall(SYS_ioctl, static_cast<int>(Arg0), Arg1, Arg2));
  // struct timespec, timeval and sysinfo are all plain 64-bit words on both architectures.
  case SYS_x64_clock_gettime:
    return FromHost(::clock_gettime(static_cast<clockid_t>(Arg0), reinterpret_cast<struct timespec*>(Arg1)));
  case SYS_x64_clock_getres:
    return FromHost(::clock_getres(static_cast<clockid_t>(Arg0), reinterpret_cast<struct timespec*>(Arg1)));
  case SYS_x64_clock_nanosleep:
    return FromHost(::clock_nanosleep(static_cast<clockid_t>(Arg0), static_cast<int>(Arg1),
                                      reinterpret_cast<const struct timespec*>(Arg2), reinterpret_cast<struct timespec*>(Arg3)));
  case SYS_x64_nanosleep:
    return FromHost(::nanosleep(reinterpret_cast<const struct timespec*>(Arg0), reinterpret_cast<struct timespec*>(Arg1)));
  case SYS_x64_gettimeofday:
    return FromHost(::gettimeofday(reinterpret_cast<struct timeval*>(Arg0), nullptr));
  case SYS_x64_time: {
    const time_t Now = ::time(nullptr);
    if (Arg0) {
      *reinterpret_cast<int64_t*>(Arg0) = Now;
    }
    return static_cast<uint64_t>(Now);
  }
  case SYS_x64_sysinfo: return FromHost(::sysinfo(reinterpret_cast<struct sysinfo*>(Arg0)));
  case SYS_x64_getrlimit: return FromHost(::getrlimit(static_cast<int>(Arg0), reinterpret_cast<struct rlimit*>(Arg1)));
  case SYS_x64_setrlimit: return FromHost(::setrlimit(static_cast<int>(Arg0), reinterpret_cast<const struct rlimit*>(Arg1)));
  // struct rusage and struct tms are all longs on both LP64 architectures, and the RLIMIT_* and
  // RUSAGE_* numbers agree, so these pass through.
  case SYS_x64_getrusage: return FromHost(::getrusage(static_cast<int>(Arg0), reinterpret_cast<struct rusage*>(Arg1)));
  case SYS_x64_times: return FromHost(::times(reinterpret_cast<struct tms*>(Arg0)));

  // PR_* option numbers are architecture-independent, including android's own PR_SET_VMA — and
  // since the host is android too, a guest naming its mappings gets exactly what it asked for.
  case SYS_x64_prctl: return FromHost(::prctl(static_cast<int>(Arg0), Arg1, Arg2, Arg3, Arg4));

  // scheduling. the guest is one host thread, so these are honest pass-throughs; cpu_set_t is a
  // plain bitmask with the same representation on both.
  case SYS_x64_sched_getscheduler: return FromHost(::sched_getscheduler(static_cast<pid_t>(Arg0)));
  case SYS_x64_sched_setscheduler:
    return FromHost(::sched_setscheduler(static_cast<pid_t>(Arg0), static_cast<int>(Arg1),
                                         reinterpret_cast<const struct sched_param*>(Arg2)));
  case SYS_x64_sched_getparam:
    return FromHost(::sched_getparam(static_cast<pid_t>(Arg0), reinterpret_cast<struct sched_param*>(Arg1)));
  case SYS_x64_sched_getaffinity: return FromHost(::syscall(SYS_sched_getaffinity, Arg0, Arg1, Arg2));
  case SYS_x64_sched_setaffinity: return FromHost(::syscall(SYS_sched_setaffinity, Arg0, Arg1, Arg2));
  // the SCHED_* policy numbers agree between the architectures, so the priority bounds do too.
  // .NET asks for these when it maps managed thread priorities onto the host's.
  case SYS_x64_sched_get_priority_max: return FromHost(::sched_get_priority_max(static_cast<int>(Arg0)));
  case SYS_x64_sched_get_priority_min: return FromHost(::sched_get_priority_min(static_cast<int>(Arg0)));

  // sockets. bionic's logging talks to /dev/socket/logdw over AF_UNIX, and .NET's diagnostics
  // server listens on an AF_UNIX socket of its own in TMPDIR. sockaddr, msghdr and iovec all have
  // identical layouts on the two architectures and the SOL_*/SO_* numbers agree, so these forward
  // rather than being stubbed — a guest that cannot open a socket usually just gives up quietly,
  // which hides things.
  case SYS_x64_socket: return FromHost(::socket(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_socketpair:
    return FromHost(::socketpair(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<int>(Arg2), reinterpret_cast<int*>(Arg3)));
  case SYS_x64_connect:
    return FromHost(::connect(static_cast<int>(Arg0), reinterpret_cast<const struct sockaddr*>(Arg1), static_cast<socklen_t>(Arg2)));
  case SYS_x64_bind:
    return FromHost(::bind(static_cast<int>(Arg0), reinterpret_cast<const struct sockaddr*>(Arg1), static_cast<socklen_t>(Arg2)));
  case SYS_x64_listen: return FromHost(::listen(static_cast<int>(Arg0), static_cast<int>(Arg1)));
  case SYS_x64_accept:
    return FromHost(::accept(static_cast<int>(Arg0), reinterpret_cast<struct sockaddr*>(Arg1), reinterpret_cast<socklen_t*>(Arg2)));
  case SYS_x64_accept4:
    return FromHost(::accept4(static_cast<int>(Arg0), reinterpret_cast<struct sockaddr*>(Arg1), reinterpret_cast<socklen_t*>(Arg2),
                              static_cast<int>(Arg3)));
  case SYS_x64_getsockname:
    return FromHost(::getsockname(static_cast<int>(Arg0), reinterpret_cast<struct sockaddr*>(Arg1), reinterpret_cast<socklen_t*>(Arg2)));
  case SYS_x64_getpeername:
    return FromHost(::getpeername(static_cast<int>(Arg0), reinterpret_cast<struct sockaddr*>(Arg1), reinterpret_cast<socklen_t*>(Arg2)));
  case SYS_x64_setsockopt:
    return FromHost(::setsockopt(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<int>(Arg2),
                                 reinterpret_cast<const void*>(Arg3), static_cast<socklen_t>(Arg4)));
  case SYS_x64_getsockopt:
    return FromHost(::getsockopt(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<int>(Arg2), reinterpret_cast<void*>(Arg3),
                                 reinterpret_cast<socklen_t*>(Arg4)));
  case SYS_x64_shutdown: return FromHost(::shutdown(static_cast<int>(Arg0), static_cast<int>(Arg1)));
  case SYS_x64_sendto:
    return FromHost(::sendto(static_cast<int>(Arg0), reinterpret_cast<const void*>(Arg1), Arg2, static_cast<int>(Arg3),
                             reinterpret_cast<const struct sockaddr*>(Arg4), static_cast<socklen_t>(Arg5)));
  case SYS_x64_recvfrom:
    return FromHost(::recvfrom(static_cast<int>(Arg0), reinterpret_cast<void*>(Arg1), Arg2, static_cast<int>(Arg3),
                               reinterpret_cast<struct sockaddr*>(Arg4), reinterpret_cast<socklen_t*>(Arg5)));
  case SYS_x64_sendmsg:
    return FromHost(::sendmsg(static_cast<int>(Arg0), reinterpret_cast<const struct msghdr*>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_recvmsg:
    return FromHost(::recvmsg(static_cast<int>(Arg0), reinterpret_cast<struct msghdr*>(Arg1), static_cast<int>(Arg2)));
  // struct rlimit64 is two 64-bit words on both architectures.
  case SYS_x64_prlimit64: return FromHost(::syscall(SYS_prlimit64, Arg0, Arg1, Arg2, Arg3));
  // FUTEX_* operation codes are architecture-independent.
  case SYS_x64_futex: return FromHost(::syscall(SYS_futex, Arg0, Arg1, Arg2, Arg3, Arg4, Arg5));

  case SYS_x64_uname: {
    // new_utsname is six 65-byte fields on both architectures; only the contents are ours to
    // choose. the machine string is the one that matters — a guest that reads "aarch64" here
    // will make wrong decisions about the very code it is running.
    auto* Guest = reinterpret_cast<char(*)[65]>(Arg0);
    std::memset(Guest, 0, 65 * 6);
    std::strcpy(Guest[0], "Linux");
    std::strcpy(Guest[1], "localhost");
    std::strcpy(Guest[2], "6.6.0");
    std::strcpy(Guest[3], "#1 SMP sharpemu-android");
    std::strcpy(Guest[4], "x86_64");
    std::strcpy(Guest[5], "(none)");
    return 0;
  }

  // --- signals ----------------------------------------------------------------------------------
  // these are the guest's own, tracked by the host layer and never installed on the host: the
  // host's SIGSEGV handler belongs to us, and handing it to the guest is what guest_signals.cpp
  // does. sigsetsize (Arg3) is checked because a guest passing anything but 8 is using an ABI we
  // are not implementing.
  case SYS_x64_rt_sigaction:
    if (Arg3 != 8) {
      return static_cast<uint64_t>(-EINVAL);
    }
    // process-wide: a handler installed on one thread is the handler every thread runs.
    return Guest->SigAction(static_cast<int>(Arg0), reinterpret_cast<const GuestABI::SigAction*>(Arg1),
                            reinterpret_cast<GuestABI::SigAction*>(Arg2));
  case SYS_x64_rt_sigprocmask:
    if (Arg3 != 8) {
      return static_cast<uint64_t>(-EINVAL);
    }
    // per-thread: pthread_sigmask and sigprocmask are the same syscall, and it has only ever
    // affected the calling thread.
    return Guest->SigProcMask(*Self, static_cast<int>(Arg0), reinterpret_cast<const uint64_t*>(Arg1),
                              reinterpret_cast<uint64_t*>(Arg2));
  case SYS_x64_sigaltstack:
    return Guest->SigAltStack(*Self, reinterpret_cast<const GuestABI::AltStack*>(Arg0),
                              reinterpret_cast<GuestABI::AltStack*>(Arg1));
  case SYS_x64_rt_sigreturn:
    // this one never returns a value to the guest: it *replaces* guest state wholesale, so
    // there is nothing to put in RAX and nowhere in this block to carry on from. RestartCurrent
    // re-dispatches from the restored RIP and does not come back.
    Guest->RestoreFromFrame(*Self);
    // the frame carried the mask the handler was entered with, so restoring it can unblock a
    // signal that arrived while the handler was running. that makes this a delivery point, and
    // missing it would leave the signal waiting for whatever the guest happened to do next.
    Threads::DeliverPendingNow(*Self);
    Threads::RestartCurrent();

  // --- waiting on file descriptors ---------------------------------------------------------------
  //
  // CoreCLR's PAL runs a dedicated signal-handling thread that blocks in poll() on a self-pipe
  // forever, so the very first thing the first cloned thread does is call this. struct pollfd,
  // fd_set, epoll_event and struct timeval are identical on x86-64 and arm64.
  //
  // the *p* variants take a signal mask, and it is deliberately dropped rather than forwarded.
  // guest signals are emulated entirely inside the host layer — no guest handler is ever installed
  // on the host — so the guest's mask and the host's have no relationship at all. handing the
  // guest's mask to the kernel would not change what the guest sees; it would blindfold the host
  // layer's own SIGSEGV handler, which is the one thing that must never be blocked.
  case SYS_x64_poll:
    return FromHost(::poll(reinterpret_cast<struct pollfd*>(Arg0), static_cast<nfds_t>(Arg1), static_cast<int>(Arg2)));
  case SYS_x64_ppoll:
    return FromHost(::ppoll(reinterpret_cast<struct pollfd*>(Arg0), static_cast<nfds_t>(Arg1),
                            reinterpret_cast<const struct timespec*>(Arg2), nullptr));
  case SYS_x64_select:
    return FromHost(::select(static_cast<int>(Arg0), reinterpret_cast<fd_set*>(Arg1), reinterpret_cast<fd_set*>(Arg2),
                             reinterpret_cast<fd_set*>(Arg3), reinterpret_cast<struct timeval*>(Arg4)));
  case SYS_x64_pselect6:
    return FromHost(::pselect(static_cast<int>(Arg0), reinterpret_cast<fd_set*>(Arg1), reinterpret_cast<fd_set*>(Arg2),
                              reinterpret_cast<fd_set*>(Arg3), reinterpret_cast<const struct timespec*>(Arg4), nullptr));
  case SYS_x64_eventfd2: return FromHost(::eventfd(static_cast<unsigned>(Arg0), static_cast<int>(Arg1)));
  case SYS_x64_epoll_create1: return FromHost(::epoll_create1(static_cast<int>(Arg0)));
  case SYS_x64_epoll_ctl:
    return FromHost(::epoll_ctl(static_cast<int>(Arg0), static_cast<int>(Arg1), static_cast<int>(Arg2),
                                reinterpret_cast<struct epoll_event*>(Arg3)));
  case SYS_x64_epoll_wait:
  case SYS_x64_epoll_pwait:
    return FromHost(::epoll_wait(static_cast<int>(Arg0), reinterpret_cast<struct epoll_event*>(Arg1), static_cast<int>(Arg2),
                                 static_cast<int>(Arg3)));

  // --- threads ----------------------------------------------------------------------------------
  //
  // the x86-64 argument order, which is not x86-32's: x86-32 selects CLONE_BACKWARDS in the kernel
  // and swaps tls with child_tid. taking the 32-bit order here would put a TLS pointer where the
  // CLONE_CHILD_CLEARTID word belongs, and the first pthread_join would wait on garbage.
  case SYS_x64_clone:
    return Threads::Clone(Frame, Arg0, Arg1, reinterpret_cast<int32_t*>(Arg2), reinterpret_cast<int32_t*>(Arg3), Arg4);
  // deliberately not implemented. glibc probes clone3 first and falls back to clone on ENOSYS all
  // by itself — the trace confirms it does — so implementing a second entry point into the same
  // machinery would buy nothing but a second way to get the argument marshalling wrong.
  case SYS_x64_clone3: return static_cast<uint64_t>(-ENOSYS);
  case SYS_x64_set_tid_address: return Threads::SetTidAddress(reinterpret_cast<int32_t*>(Arg0));

  // --- accepted and ignored --------------------------------------------------------------------
  // the robust futex list is glibc's own crash-recovery bookkeeping for pthread mutexes: the
  // kernel walks it if a thread dies holding one. our threads only die by asking to, so there is
  // nothing to recover, and accepting the registration silently is what a guest expects.
  case SYS_x64_set_robust_list: return 0;
  case SYS_x64_rseq: return static_cast<uint64_t>(-ENOSYS);

  // --- leaving --------------------------------------------------------------------------------
  // exit ends one guest thread; exit_group ends the process. before threads these were the same
  // thing and it did not matter which was which — now the first is how every pthread finishes.
  case SYS_x64_exit: Threads::ExitCurrent(static_cast<int>(Arg0), false);
  case SYS_x64_exit_group: Threads::ExitCurrent(static_cast<int>(Arg0), true);
  // --- raising a signal -------------------------------------------------------------------------
  //
  // raise(), abort(), pthread_kill() and CoreCLR's activation injection all arrive here. none of
  // them delivers anything at this point, self-directed or not: raising records a bit on the target
  // thread and, if that is some other thread, pokes it. the signal is delivered where the target
  // can safely take it, which for this thread is the exit check a few lines below in HandleSyscall.
  case SYS_x64_tgkill: return Threads::SignalGuestThread(static_cast<int32_t>(Arg1), static_cast<int>(Arg2));
  case SYS_x64_tkill: return Threads::SignalGuestThread(static_cast<int32_t>(Arg0), static_cast<int>(Arg1));
  case SYS_x64_kill: {
    // a process-directed signal. linux delivers it to any one thread that is not blocking it; the
    // calling thread is always an acceptable choice and is the one raise() means. if that thread
    // happens to be blocking the signal it stays pending on it rather than moving to another,
    // which is a simplification and the only one here.
    const auto Target = static_cast<int32_t>(Arg0);
    if (Target == ::getpid() || Target == 0) {
      return Threads::SignalGuestThread(Self->TID, static_cast<int>(Arg1));
    }
    // some other process, and its pid means the same thing to us as to the guest: one address
    // space, one pid namespace.
    return FromHost(::kill(static_cast<pid_t>(Target), static_cast<int>(Arg1)));
  }

  default:
    Unhandled.fetch_add(1, std::memory_order_relaxed);
    LastUnhandled.store(Number, std::memory_order_relaxed);
    std::printf("[syscall %d] UNHANDLED %llu(0x%llX, 0x%llX, 0x%llX, 0x%llX, 0x%llX, 0x%llX) rip=0x%llX\n", ::gettid(),
                static_cast<unsigned long long>(Number), static_cast<unsigned long long>(Arg0),
                static_cast<unsigned long long>(Arg1), static_cast<unsigned long long>(Arg2),
                static_cast<unsigned long long>(Arg3), static_cast<unsigned long long>(Arg4),
                static_cast<unsigned long long>(Arg5), static_cast<unsigned long long>(Frame->State.rip));
    return static_cast<uint64_t>(-ENOSYS);
  }
}

} // namespace HostLayer
