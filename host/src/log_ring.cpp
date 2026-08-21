#include "log_ring.h"

#include <mutex>

namespace HostLayer::LogRing {

namespace {

// **the same number of lines the desktop log viewer keeps**, so that what a person can scroll back
// to is the same on both platforms and a report from one is comparable with a report from the other.
constexpr int Capacity = 4000;

// and a second bound, on bytes, because the first one alone is not a bound at all. the pump breaks a
// line at 3800 bytes so that `logcat` does not drop it, and a .NET stack trace does reach that -- so
// a ring counted only in lines has a worst case of fifteen megabytes sitting in a game's process.
// whichever bound is reached first is the one that drops.
constexpr size_t MaxBytes = 4u * 1024 * 1024;

std::mutex Lock;

// allocated on the first line rather than at load, so that the shell binary -- which has no log pump
// and never calls in here -- carries the declaration and none of the memory.
std::vector<std::string> Lines;

// the half-open range of sequences the ring holds. `First` is the oldest still here and `End` is one
// past the newest, so `End - First` is how many there are and both only ever rise.
int64_t First {};
int64_t End {};
size_t Bytes {};

std::string& Slot(int64_t Sequence) {
  return Lines[static_cast<size_t>(Sequence % Capacity)];
}

} // namespace

void Push(const char* Line, size_t Length) {
  std::lock_guard<std::mutex> Held(Lock);
  if (Lines.empty()) {
    Lines.resize(Capacity);
  }

  // the slot about to be written is the oldest one exactly when the ring is full, which is what
  // makes overwriting and dropping one operation rather than two.
  if (End - First == Capacity) {
    Bytes -= Slot(End).size();
    ++First;
  }
  Slot(End).assign(Line, Length);
  Bytes += Length;
  ++End;

  // one line is always kept, however long it is: a ring that answers with nothing because the only
  // line in it is over the budget would hide the very line somebody is looking for.
  while (Bytes > MaxBytes && End - First > 1) {
    Bytes -= Slot(First).size();
    ++First;
  }
}

int64_t Next() {
  std::lock_guard<std::mutex> Held(Lock);
  return End;
}

int64_t Oldest() {
  std::lock_guard<std::mutex> Held(Lock);
  return First;
}

int Range(int64_t From, int64_t To, std::vector<std::string>& Out) {
  std::lock_guard<std::mutex> Held(Lock);
  const int64_t Begin = From < First ? First : From;
  const int64_t Stop = To > End ? End : To;
  int Taken = 0;
  for (int64_t At = Begin; At < Stop; ++At) {
    Out.push_back(Slot(At));
    ++Taken;
  }
  return Taken;
}

} // namespace HostLayer::LogRing
