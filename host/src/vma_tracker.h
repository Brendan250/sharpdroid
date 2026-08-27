// sharpdroid host layer -- guest VMA tracking, and self-modifying code.
//
// FEXCore asks the host layer two questions about guest memory and expects to be told when the
// answers change:
//
//   "is there executable guest code at this address, and how far does it run?"
//        Decoder::CheckRangeExecutable, via SyscallHandler::QueryGuestExecutableRange. a Size of 0
//        is how the host layer says "not executable", and it is what makes the decoder refuse to
//        translate a data page a guest jumped into by accident.
//
//   "this page now holds code i have compiled."
//        SyscallHandler::MarkGuestExecutableRange, once per page. under SMCChecks=mtrack this is
//        an invitation to arrange for a fault the next time the guest writes there -- which is what
//        MarkExecutable below does, by taking PROT_WRITE off the host mapping.
//
// and the other direction: whenever guest memory stops holding what FEXCore compiled -- unmapped,
// re-protected, moved, discarded, or simply written to -- the host layer must call back into
// Context::InvalidateCodeBuffersCodeRange / InvalidateThreadCachedCodeRange before the guest can
// reach the stale translation.
//
// **we cannot ask the kernel any of this, and that is the whole reason this file exists.**
// TranslateProt drops PROT_EXEC before any guest mapping reaches bionic, because an android
// app is denied `execute` on its own app_data_file. so the host kernel does not know which guest
// pages the guest believes are executable, and /proc/self/maps will never say. the guest's
// *requested* protection exists only here, recorded at the moment the syscall arrives.
//
// a mapping that is not recorded here is not executable as far as FEXCore is concerned, so every
// producer of guest memory has to report in -- not just mmap, but the ELF loader, the guest stack,
// the sigreturn trampoline and the spike's hand-assembled page.

#pragma once

#include <FEXCore/HLE/SyscallHandler.h>

#include <cstdint>

namespace FEXCore::Context {
class Context;
}
namespace FEXCore::Core {
struct InternalThreadState;
}

namespace HostLayer::VMA {

// mirrors FEXCore::Config::ConfigSMCChecks, restated so callers do not have to include the
// FEXCore config machinery to name a mode.
enum class SMCMode {
  None,   ///< no self-modifying-code detection at all. fast, and wrong for anything with a JIT.
  MTrack, ///< write-protect compiled pages and invalidate from the fault. FEXCore's default.
  Full,   ///< FEXCore emits a byte-comparison guard into every block. correct, and slower.
};

void Initialize(FEXCore::Context::Context* CTX, SMCMode Mode);
SMCMode Mode();

/**
 * @brief The guest's requested protection, as bionic is allowed to see it.
 *
 * PROT_EXEC never reaches the host kernel. FEX does not execute guest memory -- it reads those
 * bytes and emits arm64 into its own code buffers -- so nothing is lost by mapping guest text
 * read-only, and something is gained: an android app is denied `execute` on its own
 * app_data_file, so inside an app -- which is where this runs -- a guest ld.so mapping a library's
 * text segment PROT_EXEC would be refused outright. dropping the bit here is what lets a
 * conventional dynamic linker work in a place that forbids executable file mappings.
 *
 * it lives here rather than in the syscall layer because the tracker has to reproduce it exactly
 * when it seals and unseals a page, and two copies of this rule would eventually disagree.
 */
int HostProt(int GuestProt);

// --- recording what the guest has ------------------------------------------------------------
//
// Prot throughout is what the *guest* asked for, PROT_EXEC included -- not what was passed to
// bionic. ranges are page-aligned by the tracker, so callers may pass byte lengths.

///< a new mapping. replaces whatever was recorded over the same range, as MAP_FIXED does -- and
///< invalidates when that range was executable, since no munmap said the old bytes were going.
void Record(uint64_t Base, uint64_t Length, int GuestProt);

///< munmap. the range simply stops existing.
void Forget(uint64_t Base, uint64_t Length);

///< mprotect. splits entries as needed; the range keeps its identity, only Prot changes.
void Reprotect(uint64_t Base, uint64_t Length, int GuestProt);

///< mremap. the old range is forgotten and the new one recorded with the old protection.
void Remap(uint64_t OldBase, uint64_t OldLength, uint64_t NewBase, uint64_t NewLength);

// --- what FEXCore asks -------------------------------------------------------------------------

FEXCore::HLE::ExecutableRangeInfo Query(uint64_t Address);

///< SyscallHandler::MarkGuestExecutableRange. under mtrack, seals the page against guest writes.
void MarkExecutable(uint64_t Base, uint64_t Length);

/**
 * @brief Drop every translation FEXCore holds for a guest range.
 *
 * takes the code invalidation mutex and walks every live guest thread, because a block compiled
 * by one thread sits in that thread's lookup cache as well as in the shared code buffers.
 *
 * @param Thread the calling thread, or nullptr from a context that has none.
 */
void Invalidate(FEXCore::Core::InternalThreadState* Thread, uint64_t Base, uint64_t Length);

enum class WriteFault {
  NotOurs,    ///< a real fault. carry on treating it as one.
  Resume,     ///< handled; return from the signal handler and re-run the faulting instruction.
  SingleStep, ///< handled, but the write landed inside the block that is currently executing.
};

/**
 * @brief A write fault on a page we sealed for SMC tracking, or somebody else's problem.
 *
 * called from the host SIGSEGV handler before anything else looks at the fault. if the address
 * lies in a mapping the guest asked to be writable, the only thing that can have made it fault is
 * MarkExecutable -- so drop the translations for that page, hand the write permission back, and let
 * the faulting instruction re-run.
 *
 * SingleStep is the case where the guest is rewriting code inside the very block it is executing.
 * re-running the faulting instruction there would carry straight on into the translation that was
 * just dropped, so the caller has to re-enter the dispatcher and ask for a one-instruction block
 * instead. that is host-context surgery and belongs to the fault handler, not here.
 *
 * @param HostPC where the *host* faulted, which is what says whether we are inside a JIT block.
 */
WriteFault HandleWriteFault(FEXCore::Core::InternalThreadState* Thread, uint64_t FaultAddress, uint64_t HostPC);

// --- reporting ---------------------------------------------------------------------------------

uint64_t EntryCount();
uint64_t SMCFaultCount();
uint64_t InvalidationCount();

/**
 * @brief New mappings, and how many of them landed on memory the map already held.
 *
 * a mapping can only be laid over live guest memory by MAP_FIXED, which replaces what was there
 * without a munmap -- so Forget never runs, and Record does not invalidate. a translation FEXCore
 * holds for such a range therefore outlives the code it was compiled from.
 *
 * `Total` is the denominator, so a zero in either other column is a measurement rather than
 * silence. `OverExecutable` is the column to read: a landing on a range the guest declared
 * executable is the only kind that can strand a translation, and the first one is named in the log
 * as it happens.
 */
/**
 * @brief Called from a host signal handler, before it decides anything.
 *
 * the invalidation walk in Invalidate is not atomic against a signal, where FEX's own frontend
 * wraps the equivalent section in a deferred-signal guard. a handler that redirects the thread out
 * of that walk leaves every thread after it in the list holding translations the call was meant to
 * drop -- so whether a signal ever lands there decides whether that difference can matter at all.
 */
void NoteSignal(int Signal);

///< signal deliveries, and how many arrived inside a code invalidation. the first is the
///< denominator: a zero in the second means nothing until the first is large.
struct SignalReport {
  uint64_t Total;
  uint64_t InsideInvalidation;
};
SignalReport SignalsDuringInvalidation();

struct MappingReport {
  uint64_t Total;
  uint64_t Overlapping;
  uint64_t OverExecutable;
};
MappingReport MappingsRecorded();

} // namespace HostLayer::VMA
