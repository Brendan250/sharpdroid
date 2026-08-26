#include "vma_tracker.h"
#include "guest_threads.h"

#include <FEXCore/Core/Context.h>
#include <FEXCore/Debug/InternalThreadState.h>
#include <FEXCore/Utils/WritePriorityMutex.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <map>
#include <mutex>
#include <shared_mutex>
#include <sys/mman.h>
#include <unistd.h>

namespace HostLayer::VMA {

namespace {

FEXCore::Context::Context* CTX {};
SMCMode CurrentMode {SMCMode::MTrack};

uint64_t PageSize() {
  // the device is a 4k-page kernel and FEXCore's FEX_PAGE_SIZE is 4096, so these agree today. they
  // are asked separately on purpose: FEXCore marks and invalidates in *its* page units, and the
  // host layer has to mprotect in the *kernel's*. on a 16k-page device the seal would cover four
  // FEX pages, which is coarse but not wrong -- it seals more than asked, never less.
  static const uint64_t Size = static_cast<uint64_t>(::sysconf(_SC_PAGESIZE));
  return Size;
}

uint64_t AlignDown(uint64_t Value, uint64_t Alignment) {
  return Value & ~(Alignment - 1);
}

uint64_t AlignUp(uint64_t Value, uint64_t Alignment) {
  return AlignDown(Value + Alignment - 1, Alignment);
}

struct Entry {
  uint64_t Length;
  int Prot; ///< what the guest asked for, PROT_EXEC included
};

// keyed by base address; entries never overlap, and adjacent entries with the same protection are
// merged. merging is not just tidiness -- Query hands the range straight to the decoder, which
// caches it and only re-asks when decoding walks out of it, so a fragmented map means a syscall
// handler's worth of work per block boundary.
std::map<uint64_t, Entry> VMAs;

// held shared by Query, MarkExecutable and the fault path, exclusively by the syscall layer.
//
// **the lock order is code-invalidation before VMA, never the other way round.** FEXCore calls
// MarkGuestExecutableRange from inside block compilation, where it may already hold the code
// invalidation mutex, and that call takes this lock. so every path here that has to invalidate
// releases this lock first and takes it again afterwards if it still needs it -- which is exactly
// what FEX's own SyscallsSMCTracking.cpp does, and for the same reason.
std::shared_mutex MapLock;

std::atomic<uint64_t> SMCFaults {};
std::atomic<uint64_t> Invalidations {};

// a new mapping laid over guest memory the map already holds, which is what MAP_FIXED does: the
// kernel replaces the mapping and no munmap is issued, so nothing here is told the old bytes are
// gone. Record does not invalidate, so any translation FEXCore holds for that range outlives the
// code it was compiled from -- and FEX's own linux frontend invalidates on every mmap, which is
// the one place the host layer's tracking differs from the implementation it was written against.
//
// Records is the denominator and exists so a zero can be read as a measurement rather than as
// silence. Overlaps counts every landing on live memory; OverlapsExecutable counts the ones that
// land on a range the guest declared executable, which is the only kind that can strand a
// translation.
std::atomic<uint64_t> Records {};
std::atomic<uint64_t> Overlaps {};
std::atomic<uint64_t> OverlapsExecutable {};

// how often to say it. the count matters as much as the first one -- a handful over a start-up is
// a different claim from four hundred a second -- and a run cut off at a fixed number of seconds
// never reaches the exit summary, so the log is the only place a total can appear. every
// occurrence would both flood and change what is being measured, since this project has already
// measured heavy logging suppressing the failure it was hunting. powers of ten cost at most a
// handful of lines and give the order of magnitude and, with the timestamps, the rate.
bool IsFirstOrPowerOfTen(uint64_t Count) {
  for (uint64_t At = 1; At <= Count; At *= 10) {
    if (At == Count) {
      return true;
    }
  }
  return false;
}

std::map<uint64_t, Entry>::iterator FindLocked(uint64_t Address) {
  auto It = VMAs.upper_bound(Address);
  if (It == VMAs.begin()) {
    return VMAs.end();
  }
  --It;
  return Address < It->first + It->second.Length ? It : VMAs.end();
}

struct OverlapScan {
  bool Any;
  bool Executable;
  uint64_t Base;
  uint64_t Length;
  int Prot;
};

// what [Begin, End) lands on. reports the first *executable* entry it meets in preference to the
// first entry, since that is the one worth naming in a log line.
OverlapScan ScanOverlapLocked(uint64_t Begin, uint64_t End) {
  OverlapScan Found {};

  // the same walk MarkExecutable does: FindLocked answers "which entry contains Begin" and returns
  // end() when nothing does, which is not the same as "nothing overlaps".
  auto It = FindLocked(Begin);
  if (It == VMAs.end()) {
    It = VMAs.lower_bound(Begin);
  }

  for (; It != VMAs.end() && It->first < End; ++It) {
    const bool Executable = (It->second.Prot & PROT_EXEC) != 0;
    if (!Found.Any || (Executable && !Found.Executable)) {
      Found.Base = It->first;
      Found.Length = It->second.Length;
      Found.Prot = It->second.Prot;
    }
    Found.Any = true;
    Found.Executable = Found.Executable || Executable;
    if (Executable) {
      break;
    }
  }
  return Found;
}

// remove [Base, End) from the map, splitting whichever entries straddle either edge.
void CarveLocked(uint64_t Base, uint64_t End) {
  auto It = VMAs.lower_bound(Base);

  // an entry starting before Base may reach into the range, or straight over it.
  if (It != VMAs.begin()) {
    auto Prev = std::prev(It);
    const uint64_t PrevEnd = Prev->first + Prev->second.Length;
    if (PrevEnd > Base) {
      if (PrevEnd > End) {
        VMAs.emplace(End, Entry {PrevEnd - End, Prev->second.Prot});
      }
      // lower_bound guarantees Prev->first < Base, so this length is never zero.
      Prev->second.Length = Base - Prev->first;
    }
  }

  while (It != VMAs.end() && It->first < End) {
    const uint64_t ItEnd = It->first + It->second.Length;
    if (ItEnd <= End) {
      It = VMAs.erase(It);
    } else {
      const Entry Tail {ItEnd - End, It->second.Prot};
      VMAs.erase(It);
      VMAs.emplace(End, Tail);
      break;
    }
  }
}

void RecordLocked(uint64_t Base, uint64_t End, int Prot) {
  CarveLocked(Base, End);

  uint64_t NewBase = Base;
  uint64_t NewEnd = End;

  auto At = VMAs.lower_bound(NewBase);
  if (At != VMAs.begin()) {
    auto Prev = std::prev(At);
    if (Prev->first + Prev->second.Length == NewBase && Prev->second.Prot == Prot) {
      NewBase = Prev->first;
      VMAs.erase(Prev);
    }
  }
  auto Next = VMAs.find(NewEnd);
  if (Next != VMAs.end() && Next->second.Prot == Prot) {
    NewEnd += Next->second.Length;
    VMAs.erase(Next);
  }

  VMAs.emplace(NewBase, Entry {NewEnd - NewBase, Prot});
}

// drop every translation FEXCore holds for a range, and -- while still holding the invalidation
// mutex -- optionally put a protection back.
//
// the two have to happen together. unsealing after releasing the mutex leaves a window in which
// another thread compiles the page, seals it again, and then has its seal removed by us: the
// block would survive the next guest write undetected.
//
// @return false only when a requested restore failed, which is the fault handler's evidence that
// the write it is trying to rescue was never going to succeed.
bool InvalidateAndRestore(FEXCore::Core::InternalThreadState* Thread, uint64_t Base, uint64_t Length, int RestoreProt) {
  if (!CTX) {
    return true;
  }

  struct Range {
    uint64_t Base, Length;
  } R {Base, Length};

  std::lock_guard InvalidationLock {CTX->GetCodeInvalidationMutex()};

  CTX->InvalidateCodeBuffersCodeRange(Base, Length);
  // and every thread's own lookup cache: a block lives in the shared code buffers *and* in the
  // cache of whichever thread compiled it, and dropping only the first leaves the second pointing
  // at host code that is about to be reused for something else. the registry lock is taken inside
  // the invalidation mutex and is a leaf -- nothing is ever acquired while holding it.
  Threads::ForEachLive(
    [](GuestThread& T, void* User) {
      const auto* Which = static_cast<const Range*>(User);
      CTX->InvalidateThreadCachedCodeRange(T.Thread, Which->Base, Which->Length);
    },
    &R);

  Invalidations.fetch_add(1, std::memory_order_relaxed);

  return RestoreProt < 0 || ::mprotect(reinterpret_cast<void*>(Base), Length, RestoreProt) == 0;
}

} // namespace

int HostProt(int GuestProt) {
  int Prot = GuestProt & ~PROT_EXEC;
  if (GuestProt & PROT_EXEC) {
    Prot |= PROT_READ;
  }
  return Prot;
}

void Initialize(FEXCore::Context::Context* Context, SMCMode SMC) {
  CTX = Context;
  CurrentMode = SMC;
}

SMCMode Mode() {
  return CurrentMode;
}

void Record(uint64_t Base, uint64_t Length, int GuestProt) {
  if (!Length) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  OverlapScan Over {};
  bool Report = false;
  uint64_t Reported = 0;
  {
    std::unique_lock Lock {MapLock};

    Records.fetch_add(1, std::memory_order_relaxed);
    Over = ScanOverlapLocked(Begin, End);
    if (Over.Any) {
      Overlaps.fetch_add(1, std::memory_order_relaxed);
    }
    if (Over.Executable) {
      Reported = OverlapsExecutable.fetch_add(1, std::memory_order_relaxed) + 1;
      Report = IsFirstOrPowerOfTen(Reported);
    }

    RecordLocked(Begin, End, GuestProt);
  }

  // a mapping laid over a range the guest had declared executable replaces bytes FEXCore may have
  // compiled from, and no munmap was issued to say so -- MAP_FIXED does this silently. FEX's own
  // linux frontend invalidates on every mmap; this invalidates on the ones that can matter, which
  // is the same guarantee for the cost of a scan the counters above already pay for. a range
  // recorded non-executable cannot hold a translation, since Query refuses it to the decoder.
  //
  // after the map is updated rather than before, which is Reprotect's order and for its reason: a
  // thread that compiles out of this range in between compiles the bytes that are now there.
  // outside the lock, because the lock order is code-invalidation before VMA and never the reverse.
  if (Over.Executable) {
    InvalidateAndRestore(Threads::Current() ? Threads::Current()->Thread : nullptr, Begin, End - Begin, -1);
  }

  // said as it happens rather than only in the exit summary, because a run cut off at a fixed
  // number of seconds never reaches one. printed outside the lock: the log pump drains stdout
  // through a pipe, and a full pipe blocks the writer.
  if (Report) {
    std::printf("[vma] invalidated a mapping laid over executable memory (%llu so far): 0x%llx+0x%llx prot=0x%x lands on "
                "0x%llx+0x%llx prot=0x%x\n",
                static_cast<unsigned long long>(Reported), static_cast<unsigned long long>(Begin),
                static_cast<unsigned long long>(End - Begin), GuestProt, static_cast<unsigned long long>(Over.Base),
                static_cast<unsigned long long>(Over.Length), Over.Prot);
    std::fflush(stdout);
  }
}

void Forget(uint64_t Base, uint64_t Length) {
  if (!Length) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  {
    std::unique_lock Lock {MapLock};
    CarveLocked(Begin, End);
  }

  // after the map says the range is gone, not before: a thread compiling out of these pages
  // between the two would put the block back. the map is what stops it -- Query now refuses.
  InvalidateAndRestore(Threads::Current() ? Threads::Current()->Thread : nullptr, Begin, End - Begin, -1);
}

void Reprotect(uint64_t Base, uint64_t Length, int GuestProt) {
  if (!Length) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  {
    std::unique_lock Lock {MapLock};
    // mprotect only succeeds if the whole range was mapped, and we are only called after it did,
    // so overwriting the range wholesale is exactly right: nothing is being invented here.
    RecordLocked(Begin, End, GuestProt);
  }

  // unconditionally, not only when PROT_EXEC goes away. a page going writable has to lose its
  // seal, a page losing PROT_EXEC must stop being decodable, and a page that merely changed
  // between two executable protections may still have been re-protected around a rewrite.
  InvalidateAndRestore(Threads::Current() ? Threads::Current()->Thread : nullptr, Begin, End - Begin, -1);
}

void Remap(uint64_t OldBase, uint64_t OldLength, uint64_t NewBase, uint64_t NewLength) {
  int Prot = PROT_READ | PROT_WRITE;
  {
    std::shared_lock Lock {MapLock};
    if (auto It = FindLocked(OldBase); It != VMAs.end()) {
      Prot = It->second.Prot;
    }
  }

  if (OldBase != NewBase || NewLength < OldLength) {
    // MREMAP_DONTUNMAP aside, the old range either moved away entirely or shrank in place. either
    // way the bytes FEXCore compiled are no longer reachable at the addresses it compiled them for.
    Forget(OldBase, OldLength);
  }
  Record(NewBase, NewLength, Prot);
}

FEXCore::HLE::ExecutableRangeInfo Query(uint64_t Address) {
  std::shared_lock Lock {MapLock};

  auto It = FindLocked(Address);
  if (It == VMAs.end() || !(It->second.Prot & PROT_EXEC)) {
    // a Size of 0 is the decoder's signal to refuse. this is the whole point of the file: before
    // it existed the host layer answered "the entire address space is executable", and a guest
    // that jumped into a data page got that page translated instead of a fault.
    return {0, 0, false};
  }
  return {It->first, It->second.Length, (It->second.Prot & PROT_WRITE) != 0};
}

void MarkExecutable(uint64_t Base, uint64_t Length) {
  if (CurrentMode != SMCMode::MTrack) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  std::shared_lock Lock {MapLock};

  // FindLocked answers "which entry contains Begin", and returns end() when nothing does -- which
  // is not the same as "no entry overlaps the range", since one may start partway into it.
  auto First = FindLocked(Begin);
  if (First == VMAs.end()) {
    First = VMAs.lower_bound(Begin);
  }

  for (auto It = First; It != VMAs.end() && It->first < End; ++It) {
    if (!(It->second.Prot & PROT_WRITE)) {
      // read-only guest text -- the common case for a normal ELF. nothing can rewrite it without
      // an mprotect first, and mprotect invalidates.
      continue;
    }
    const uint64_t SealBegin = std::max(It->first, Begin);
    const uint64_t SealEnd = std::min(It->first + It->second.Length, End);
    ::mprotect(reinterpret_cast<void*>(SealBegin), SealEnd - SealBegin, HostProt(It->second.Prot) & ~PROT_WRITE);
  }
}

void Invalidate(FEXCore::Core::InternalThreadState* Thread, uint64_t Base, uint64_t Length) {
  InvalidateAndRestore(Thread, Base, Length, -1);
}

WriteFault HandleWriteFault(FEXCore::Core::InternalThreadState* Thread, uint64_t FaultAddress, uint64_t HostPC) {
  if (CurrentMode != SMCMode::MTrack) {
    return WriteFault::NotOurs;
  }

  const uint64_t Page = PageSize();
  const uint64_t FaultBase = AlignDown(FaultAddress, Page);

  int Prot;
  {
    std::shared_lock Lock {MapLock};
    auto It = FindLocked(FaultAddress);
    if (It == VMAs.end() || !(It->second.Prot & PROT_WRITE)) {
      // either not guest memory at all, or memory the guest never asked to write. a fault there
      // is the guest's own, and belongs to the guest's handler.
      return WriteFault::NotOurs;
    }
    Prot = It->second.Prot;
  }

  // a write spanning two pages faults once per page, and each is handled on its own.
  //
  // the return value is the guard against looping here forever. everything above is inference --
  // "the guest asked for this to be writable, so the only thing that can have taken the
  // permission away is us" -- and if that inference is wrong, handing back a protection the kernel
  // refuses is the one place it shows. resuming then would re-fault at the same instruction
  // immediately, and again, inside a signal handler, with nothing to break the cycle.
  if (!InvalidateAndRestore(Thread, FaultBase, Page, HostProt(Prot))) {
    return WriteFault::NotOurs;
  }
  SMCFaults.fetch_add(1, std::memory_order_relaxed);

  // if the guest is rewriting code inside the block it is currently executing, re-running the
  // faulting instruction is not enough -- the rest of that block is the translation we have just
  // dropped, and control would run straight into it. the caller re-enters the dispatcher asking
  // for a single-instruction block, so any further modification is picked up immediately.
  if (CTX && CTX->IsAddressInCodeBuffer(Thread, HostPC) && !CTX->IsCurrentBlockSingleInst(Thread) &&
      CTX->IsAddressInCurrentBlock(Thread, FaultBase, Page)) {
    return WriteFault::SingleStep;
  }
  return WriteFault::Resume;
}

uint64_t EntryCount() {
  std::shared_lock Lock {MapLock};
  return VMAs.size();
}

uint64_t SMCFaultCount() {
  return SMCFaults.load(std::memory_order_relaxed);
}

uint64_t InvalidationCount() {
  return Invalidations.load(std::memory_order_relaxed);
}

MappingReport MappingsRecorded() {
  return {Records.load(std::memory_order_relaxed), Overlaps.load(std::memory_order_relaxed),
          OverlapsExecutable.load(std::memory_order_relaxed)};
}

} // namespace HostLayer::VMA
