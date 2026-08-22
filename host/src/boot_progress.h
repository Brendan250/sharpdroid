// sharpdroid host layer -- where a boot has got to, for something that wants to draw it.
//
// a guest takes several seconds to reach its first frame and the picture is black for all of it.
// the emulator says a great deal about what it is doing during that time, on stdout, and this is
// the part that turns that stream into a position: an ordered table of checkpoints, and how far
// along it the current run has got.
//
// **the two ends of the table belong to this project and the middle does not.** the start is the
// host layer's own; the end is the vulkan thunk's first presented frame. everything between is a
// line the emulator prints, and the emulator is free to rename any of them at any time -- so the
// design is built around that being survivable rather than around it not happening:
//
//  - **entries are optional.** the scan runs forward from the current position to the end of the
//    table, so a pattern that no longer appears is passed over the moment a later one matches. it
//    costs one label and one correction point, never the feature. matching only the *next* entry
//    would instead let a single renamed line stall the table and kill every entry behind it.
//  - **position only ever moves forward.** several lines here occur more than once in one boot --
//    `=== Execute START ===` occurs once per module -- so "the first line that matches anything"
//    and "any line that matches this" are both wrong, and neither is reachable from here.
//  - **the terminal entry is not a line at all.** the thunk advances it, and it advances to the
//    *end*, so every entry that never matched is passed over at once and the position lands on
//    complete exactly when the picture appears. that single rule is what makes every way this can
//    rot end in a full bar rather than a stuck one.
//  - **it says what no longer matches.** the first frame prints how many of the table's patterns
//    were seen and names each one that was not, because a table that has quietly stopped matching
//    and a boot that is simply fast look identical from outside.
//
// armed by `--boot-progress` and off otherwise, so a run from a shell and the regression set
// produce exactly the log they produce without it.

#pragma once

#include <cstddef>
#include <cstdint>

namespace HostLayer::BootProgress {

///< capture t=0, beside the guest log's own. every time reported here is measured from it.
void Start();

///< arm the tap. off by default; `--boot-progress` is the only thing that turns it on.
void Enable();
bool Enabled();

/**
 * @brief offer one finished log line to the table.
 *
 * called from the log pump, which is **not the guest's thread** -- it drains the pipe under fds 1
 * and 2 on a thread of its own. that placement is the whole cost argument: the work cannot land on
 * the boot's critical path, whatever it costs. the other place every guest line passes is the write
 * syscall, and that one *is* the guest's thread.
 *
 * a boot produces on the order of a thousand lines and the table has ten patterns, so the worst
 * case is ten short substring searches per line, falling as entries are passed. the arming check is
 * one relaxed load, which is what the rest of a run pays.
 *
 * @param Line the line's bytes, without its newline. not required to be null-terminated.
 */
void Observe(const char* Line, size_t Length);

/**
 * @brief the run has presented its first frame.
 *
 * moves the position to the end of the table whatever it was, which is what makes an unmatched
 * entry cost nothing, and disarms the tap so the rest of the run pays only the arming check. it
 * also prints the line naming any pattern that was never seen.
 */
void FirstFrame();

///< how many entries have been passed: 0 before the first, Count() once the picture is up.
int Reached();

///< the table itself. Id is a stable name a caller maps its own text to; an id it does not know
///< is one it draws nothing for, which is what keeps this side free to add entries.
int Count();
const char* Id(int Index);

///< elapsed milliseconds at which the entry was passed, or -1 for one that never was.
int64_t ReachedAt(int Index);

} // namespace HostLayer::BootProgress
