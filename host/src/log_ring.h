// sharpdroid host layer -- the last lines this process printed, for something that wants to
// show them.
//
// **everything the emulator prints and everything the host layer prints is already one stream**, and
// this is where a bounded window of it is kept. the log pump puts a pipe under fds 1 and 2 and
// drains it a line at a time, so the emulator's own logger, its raw console writes and the host
// layer's `printf` all arrive there interleaved in the order they were printed. that stream is what
// every measurement of this project reads out of `logcat`, and a viewer inside the app wants exactly
// it rather than a reconstruction of it.
//
// **it is filled by the pump and read by whoever is drawing, and the pump may not be made to wait
// for the drawer.** the pump's thread is what drains the pipe; a guest blocked writing into a full
// pipe is a guest stopped, so the reader takes the lock for as long as it takes to copy the lines it
// asked for and never for longer. the reader is also polling rather than being called: the host
// layer does not call up, for the same reason boot progress does not.
//
// **a line is addressed by a sequence number rather than by a position.** a reader that went away
// and came back can ask for everything after the last line it saw, and if the ring has since passed
// that line by, the gap is visible to it as a first sequence higher than the one it asked for --
// where an index into a buffer that has wrapped is silently the wrong line.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace HostLayer::LogRing {

/**
 * @brief keep one finished log line.
 *
 * called from the log pump, once per line, and from the app for a line of its own -- which is why
 * this takes bytes rather than reading anything itself. the two callers share the sequence, so the
 * app's lines sit where they arrived among the emulator's rather than in a list beside them.
 *
 * the steady state allocates nothing: a slot that is overwritten reuses the string it already holds,
 * and the ring reaches its full size within the first seconds of a run.
 *
 * @param Line the line's bytes, without its newline. not required to be null-terminated.
 */
void Push(const char* Line, size_t Length);

///< the sequence one past the newest line. a reader holds this and asks for what arrived after it.
int64_t Next();

///< the sequence of the oldest line still held. everything below it has been dropped.
int64_t Oldest();

/**
 * @brief copy the lines from @p From up to but not including @p To, clamped to what is still held.
 *
 * appends to @p Out and returns how many were appended. asking for a range that has been dropped is
 * an ordinary answer rather than an error -- the caller compares what it asked for against
 * @ref Oldest to learn that it missed something.
 */
int Range(int64_t From, int64_t To, std::vector<std::string>& Out);

} // namespace HostLayer::LogRing
