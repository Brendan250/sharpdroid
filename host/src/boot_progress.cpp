#include "boot_progress.h"

#include <atomic>
#include <cstdio>
#include <ctime>
#include <string_view>

namespace HostLayer::BootProgress {

namespace {

// the table, in the order a boot reaches it.
//
// **the patterns are the structural fragment of a line and never the whole sentence.** the emulator
// prints counts in most of these -- how many type initializers were warmed, how many modules are
// being loaded -- and matching a sentence with a number in it would be a pattern that stops matching
// the first time the number changes, which is every run on a different title.
//
// what each one is worth knowing for is that the boot divides in two at `title`. everything above
// it is the emulator starting itself and takes the same time whichever game was launched; below it
// is the game's own loading, which does not. a caller that records these to predict the next boot
// wants those two halves keyed differently for that reason.
struct Checkpoint {
  const char* Id;
  // empty for the terminal, which no line can match: only FirstFrame reaches it.
  std::string_view Pattern;
};

constexpr Checkpoint Table[] {
  {"emulator-start", "SharpEmu starting"},
  {"runtime", "Creating runtime"},
  {"clock", "Kernel TSC frequency"},
  {"address-space", "host address space:"},
  {"hle-warm", "type initializers"},
  {"title", "[LOADER] Title:"},
  {"modules", "module(s)..."},
  {"execute", "=== Execute START ==="},
  {"guest-threads", "Scheduled guest thread"},
  {"video-out", "Vulkan VideoOut ready"},
  {"first-frame", ""},
};

constexpr int Entries = static_cast<int>(sizeof(Table) / sizeof(Table[0]));
// everything before the terminal, which is the number of patterns a line can be tested against and
// therefore the denominator of the line printed at the end.
constexpr int Patterns = Entries - 1;

struct timespec Origin {};
std::atomic<bool> Armed {};

// how many entries have been passed. written only by the log pump and by the guest thread that
// presents the first frame, read by whoever is drawing. release on the write and acquire on the
// read, so a reader that sees a new position also sees the time that was stored for it.
std::atomic<int> Position {};
std::atomic<int64_t> PassedAt[Entries] {};

int64_t ElapsedMillis() {
  struct timespec Now {};
  ::clock_gettime(CLOCK_MONOTONIC, &Now);
  return (static_cast<int64_t>(Now.tv_sec - Origin.tv_sec) * 1000) +
         ((static_cast<int64_t>(Now.tv_nsec) - static_cast<int64_t>(Origin.tv_nsec)) / 1000000);
}

} // namespace

void Start() {
  ::clock_gettime(CLOCK_MONOTONIC, &Origin);
  for (auto& Entry : PassedAt) {
    Entry.store(-1, std::memory_order_relaxed);
  }
}

void Enable() {
  Armed.store(true, std::memory_order_relaxed);
}

bool Enabled() {
  return Armed.load(std::memory_order_relaxed);
}

void Observe(const char* Line, size_t Length) {
  if (!Armed.load(std::memory_order_relaxed)) {
    return;
  }

  const int At = Position.load(std::memory_order_relaxed);
  if (At >= Patterns) {
    return;
  }

  const std::string_view Text(Line, Length);
  for (int Index = At; Index < Patterns; ++Index) {
    if (Text.find(Table[Index].Pattern) == std::string_view::npos) {
      continue;
    }
    // **the entries between the position and this one are left at -1 rather than filled in.** they
    // were not seen, and a reader that cannot tell "passed over" from "reached at the same instant"
    // would quietly average a rotted table into its own timings.
    PassedAt[Index].store(ElapsedMillis(), std::memory_order_relaxed);
    Position.store(Index + 1, std::memory_order_release);
    return;
  }
}

void FirstFrame() {
  if (!Armed.load(std::memory_order_relaxed)) {
    return;
  }
  if (Position.load(std::memory_order_relaxed) >= Entries) {
    return;
  }

  const int64_t Now = ElapsedMillis();
  PassedAt[Patterns].store(Now, std::memory_order_relaxed);
  Position.store(Entries, std::memory_order_release);
  // nothing after the first frame can move the table, so stop looking at lines entirely: the rest
  // of a run is where frame rate is measured and it pays one relaxed load per line from here.
  Armed.store(false, std::memory_order_relaxed);

  int Seen = 0;
  for (int Index = 0; Index < Patterns; ++Index) {
    if (PassedAt[Index].load(std::memory_order_relaxed) >= 0) {
      ++Seen;
    }
  }

  std::printf("[boot] %d of %d checkpoints, first frame at %lld.%03lld s\n", Seen, Patterns,
              static_cast<long long>(Now / 1000), static_cast<long long>(Now % 1000));

  // **named, one by one, rather than left to a count.** a pattern the emulator has renamed and a
  // boot that genuinely skipped a phase are the same number and different problems, and the only
  // moment anyone is in a position to tell them apart is with the log of the run in front of them.
  if (Seen < Patterns) {
    std::printf("[boot] never matched:");
    for (int Index = 0; Index < Patterns; ++Index) {
      if (PassedAt[Index].load(std::memory_order_relaxed) < 0) {
        std::printf(" %s", Table[Index].Id);
      }
    }
    std::printf("\n");
  }
  std::fflush(stdout);
}

int Reached() {
  return Position.load(std::memory_order_acquire);
}

int Count() {
  return Entries;
}

const char* Id(int Index) {
  if (Index < 0 || Index >= Entries) {
    return "";
  }
  return Table[Index].Id;
}

int64_t ReachedAt(int Index) {
  if (Index < 0 || Index >= Entries) {
    return -1;
  }
  return PassedAt[Index].load(std::memory_order_acquire);
}

} // namespace HostLayer::BootProgress
