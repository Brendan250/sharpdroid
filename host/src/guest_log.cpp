#include "guest_log.h"

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <sys/stat.h>
#include <unistd.h>

namespace HostLayer::GuestLog {

namespace {

// CLOCK_MONOTONIC rather than the wall clock: this measures a duration, and a duration must not
// move because something adjusted the time of day halfway through a ten-minute run.
struct timespec Origin {};
std::atomic<bool> On {};

// what fds 1 and 2 actually point at, captured once at startup.
//
// this exists because keying on the descriptor *number* does not work, which cost a round of
// debugging to discover: **.NET does not write its console output to fd 1.** it duplicates the
// standard descriptors during startup -- 51 fcntl calls in a `--help` trace, and the first
// `[DEBUG] SharpEmu starting` line comes out of a write to fd 35 -- so a stamper watching fd 1 sees
// the host layer's own output and none of the guest's. matching on the file identity behind the
// descriptor catches every route to the same stream at once: dup, dup2, fcntl(F_DUPFD),
// /dev/stdout, /proc/self/fd/1.
struct StreamIdentity {
  dev_t Device;
  ino_t Inode;
  bool Valid;
};
StreamIdentity Console[2] {};

// one flag per stream rather than per descriptor, since every duplicate of stdout is the same
// stream and shares one cursor. shared between every guest thread and deliberately not locked: the
// worst a race can do is put one stamp in the wrong place, which is cheaper than serialising fifty
// threads' output behind a mutex to prevent it.
std::atomic<bool> AtLineStart[2] {true, true};

// `[+   3.502] ` -- wide enough for a run of nearly three hours before it grows.
constexpr size_t StampLength = 12;

// anything longer goes out unstamped rather than allocating on a path the guest calls thousands of
// times. log lines are tens of bytes; this is three orders of magnitude of headroom.
constexpr size_t BufferSize = 16 * 1024;

size_t FormatStamp(char* Out) {
  struct timespec Now {};
  ::clock_gettime(CLOCK_MONOTONIC, &Now);

  int64_t Seconds = Now.tv_sec - Origin.tv_sec;
  int64_t Nanoseconds = Now.tv_nsec - Origin.tv_nsec;
  if (Nanoseconds < 0) {
    Nanoseconds += 1000000000;
    --Seconds;
  }

  const int Written = std::snprintf(Out, StampLength + 1, "[+%4lld.%03lld] ", static_cast<long long>(Seconds),
                                    static_cast<long long>(Nanoseconds / 1000000));
  // snprintf truncates rather than overflowing, so a run long enough to widen the seconds field
  // loses a digit instead of corrupting the buffer. it also stops being StampLength, hence the
  // clamp: the caller sized its buffer on that constant.
  return Written > 0 && static_cast<size_t>(Written) <= StampLength ? static_cast<size_t>(Written) : StampLength;
}

/**
 * @brief Which console stream this descriptor writes to, or -1 for anything else.
 *
 * fds 1 and 2 answer without a syscall; everything else costs one fstat. that is deliberately not
 * cached -- a cache would have to be invalidated on close and on dup2, and fd numbers get reused,
 * so a stale entry would stamp the middle of a save file. one fstat per guest write is a couple of
 * microseconds against a run measured in minutes, and this mode is off unless someone is measuring.
 */
int ConsoleSlot(int FD) {
  if (!On.load(std::memory_order_relaxed)) {
    return -1;
  }
  if (FD == 1) {
    return 0;
  }
  if (FD == 2) {
    return Console[1].Valid ? 1 : 0;
  }

  struct stat Stat {};
  if (::fstat(FD, &Stat) != 0) {
    return -1;
  }
  for (int i = 0; i < 2; ++i) {
    if (Console[i].Valid && Console[i].Device == Stat.st_dev && Console[i].Inode == Stat.st_ino) {
      return i;
    }
  }
  return -1;
}

/**
 * @brief Push a whole buffer out in as few writes as the kernel allows.
 *
 * one write is the intent, and on a regular file or a pipe with a buffer to spare that is what
 * happens. the loop is for the cases where it is not -- a signal cutting the call short, or a pipe
 * filling up -- because a stamped buffer that went out half-written would leave the descriptor's
 * line-start bookkeeping describing something that never reached the file.
 */
bool WriteAll(int FD, const char* Data, size_t Length) {
  while (Length) {
    const ssize_t Result = ::write(FD, Data, Length);
    if (Result < 0) {
      if (errno == EINTR) {
        continue;
      }
      return false;
    }
    Data += Result;
    Length -= static_cast<size_t>(Result);
  }
  return true;
}

/**
 * @brief Copy `Length` bytes into `Out`, inserting a stamp at every line start.
 *
 * @return the number of bytes written into Out, or 0 if it would not fit.
 */
size_t Stamp(int Slot, const char* Data, size_t Length, char* Out, size_t OutSize) {
  size_t Used = 0;
  bool LineStart = AtLineStart[Slot].load(std::memory_order_relaxed);

  for (size_t i = 0; i < Length; ++i) {
    if (LineStart) {
      if (Used + StampLength > OutSize) {
        return 0;
      }
      Used += FormatStamp(Out + Used);
      LineStart = false;
    }
    if (Used + 1 > OutSize) {
      return 0;
    }
    Out[Used++] = Data[i];
    // the newline itself belongs to the line it ends, so the next stamp waits for the next byte --
    // which is also what stops a trailing newline from emitting a stamp with nothing behind it.
    LineStart = Data[i] == '\n';
  }

  AtLineStart[Slot].store(LineStart, std::memory_order_relaxed);
  return Used;
}

} // namespace

void Start() {
  ::clock_gettime(CLOCK_MONOTONIC, &Origin);

  // taken before the guest exists, so these describe where *our* stdout and stderr go -- which is
  // what every duplicate the guest later makes will point at too.
  for (int i = 0; i < 2; ++i) {
    struct stat Stat {};
    if (::fstat(i + 1, &Stat) == 0) {
      Console[i] = {Stat.st_dev, Stat.st_ino, true};
    }
  }
  // `2>&1` makes them one stream, and then there is only one cursor to track. leaving the second
  // slot invalid is how ConsoleSlot folds stderr onto stdout's line-start flag.
  if (Console[0].Valid && Console[1].Valid && Console[0].Device == Console[1].Device && Console[0].Inode == Console[1].Inode) {
    Console[1].Valid = false;
  }
}

void Enable() {
  On.store(true, std::memory_order_relaxed);
}

bool Enabled() {
  return On.load(std::memory_order_relaxed);
}

ssize_t Write(int FD, const void* Data, size_t Length) {
  const int Slot = Length ? ConsoleSlot(FD) : -1;
  if (Slot < 0) {
    return ::write(FD, Data, Length);
  }

  char Buffer[BufferSize];
  const size_t Length2 = Stamp(Slot, static_cast<const char*>(Data), Length, Buffer, sizeof(Buffer));
  if (!Length2) {
    return ::write(FD, Data, Length);
  }
  if (!WriteAll(FD, Buffer, Length2)) {
    return -1;
  }
  return static_cast<ssize_t>(Length);
}

ssize_t Writev(int FD, const struct iovec* IOV, int Count) {
  const int Slot = Count > 0 ? ConsoleSlot(FD) : -1;
  if (Slot < 0) {
    return ::writev(FD, IOV, Count);
  }

  size_t Total = 0;
  for (int i = 0; i < Count; ++i) {
    Total += IOV[i].iov_len;
  }
  if (Total == 0) {
    return 0;
  }

  char Buffer[BufferSize];
  size_t Used = 0;
  for (int i = 0; i < Count; ++i) {
    const size_t Added = Stamp(Slot, static_cast<const char*>(IOV[i].iov_base), IOV[i].iov_len, Buffer + Used, sizeof(Buffer) - Used);
    if (!Added && IOV[i].iov_len) {
      // out of room part way through. the line-start flag has already moved for the vectors that
      // did fit, so this cannot fall back to a plain writev of the whole thing without printing
      // them twice -- push what we have and send the rest unstamped.
      if (Used && !WriteAll(FD, Buffer, Used)) {
        return -1;
      }
      for (int j = i; j < Count; ++j) {
        if (!WriteAll(FD, static_cast<const char*>(IOV[j].iov_base), IOV[j].iov_len)) {
          return -1;
        }
        if (IOV[j].iov_len) {
          const char* Bytes = static_cast<const char*>(IOV[j].iov_base);
          AtLineStart[Slot].store(Bytes[IOV[j].iov_len - 1] == '\n', std::memory_order_relaxed);
        }
      }
      return static_cast<ssize_t>(Total);
    }
    Used += Added;
  }

  if (!WriteAll(FD, Buffer, Used)) {
    return -1;
  }
  return static_cast<ssize_t>(Total);
}

} // namespace HostLayer::GuestLog
