// sharpdroid host layer -- timestamping what the guest prints.
//
// the reason this exists is a comparison rather than a feature. the prebuilt Windows release logs
// through a launcher that stamps every line of SharpEmu's stdout with a millisecond time of day,
// so a native boot of the same game can be read phase by phase: 1.1 s in the HLE warmup, 0.7 s in
// Vulkan setup, 3.5 s to the game's own boot banner. on android we had no such thing, and could
// only bracket the same milestone to somewhere between 135 and 180 seconds by sampling the log
// from outside. that is not a measurement, and every question about *where* the time goes was
// unanswerable because of it.
//
// so: fd 1 and fd 2 detour through here when `--timestamps` is on, and every line the guest emits
// gets an elapsed-since-start prefix. elapsed rather than time of day on purpose -- every number
// worth having is a delta from the first line, and a delta is what stays meaningful when the log
// is read a week later or next to a run from a different day.
//
// the host layer's own `[host-layer]` and `[syscall]` lines are deliberately *not* stamped. they
// come out of stdio rather than through this path, they are few, and leaving them unstamped makes
// them instantly distinguishable from the guest's own output while their position in the file
// still says when they happened.

#pragma once

#include <cstddef>
#include <sys/types.h>
#include <sys/uio.h>

namespace HostLayer::GuestLog {

///< capture t=0. called before anything else in main, so the first stamp is honest about start-up.
void Start();

///< turn stamping on. off by default, so a run that is not being measured produces exactly the log
///< every previous milestone recorded.
void Enable();
bool Enabled();

///< also stamp the host thread that wrote the line. off by default, because every scanner and the
///< boot checkpoints match on a line's own text and the wider prefix is only wanted when a guest
///< thread has to be tied to a tid an in-process counter reports.
void EnableThreadIds();

///< whether it is on, so the run says so once at the top the way --timestamps does.
bool ThreadIdsEnabled();

/**
 * @brief write(2) for a guest writing to stdout or stderr, with line stamps inserted.
 *
 * a stamp goes in at the start of the buffer if the last thing written to this descriptor ended a
 * line, and after every newline that has more data behind it. tracking line *starts* rather than
 * writes is what makes this survive a guest that emits its text and its newline separately, which
 * .NET's console writer does.
 *
 * the whole stamped buffer goes out in a single write, because fifty guest threads share this
 * descriptor and two writes per line would let their output interleave mid-sentence. the cost of
 * that choice is that a short write cannot be reported back to the guest exactly; the loop below
 * finishes the buffer instead, and the guest is told its own length went out.
 *
 * @return the number of *guest* bytes written, or -1 with errno set. never the stamped length --
 * a guest that saw its own write return more than it asked for would be entitled to be confused.
 */
ssize_t Write(int FD, const void* Data, size_t Length);

///< the same for writev(2), flattened into one stamped write so the vector cannot be torn apart by
///< another thread's output.
ssize_t Writev(int FD, const struct iovec* IOV, int Count);

} // namespace HostLayer::GuestLog
