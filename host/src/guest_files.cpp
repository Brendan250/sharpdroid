#include "guest_files.h"

#include "saf_bridge.h"

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace HostLayer {

namespace GuestFiles {

namespace {

// the gate. written once before any guest thread exists, read from all of them, and it is the first
// thing every entry point does -- so a run that never mounts anything pays one relaxed load and a
// predictable branch per path-taking syscall, which is the same shape the dispatcher already pays
// for `if (Trace)` on every syscall at all.
std::atomic<bool> Active {false};

// the invented prefix, without a trailing slash. "/game" -- so /game/eboot.bin is what the guest is
// told its own dump is called, and the prefix test cannot be ambiguous the way a real path would be.
std::string Mount;

// a fabricated device number. it has to be non-zero and it has to be the same for every file here,
// because a caller comparing two files for identity compares (st_dev, st_ino) -- and it must not be a
// device the guest could reach any other way, which nothing under an invented mount can be.
constexpr uint64_t MountDevice = 0x5348415250ULL;

// the guest's O_* values, which are not the host's -- see TranslateOpenFlags in linux_syscalls.cpp for
// the four bits that move. these are read out of the guest's own flag word, so they are the x86-64
// ones and never the arm64 ones.
constexpr uint64_t GuestO_WRONLY = 1;
constexpr uint64_t GuestO_RDWR = 2;
constexpr uint64_t GuestO_CREAT = 0100;
constexpr uint64_t GuestO_TRUNC = 01000;
constexpr uint64_t GuestO_APPEND = 02000;
constexpr uint64_t GuestO_DIRECTORY = 0200000;

struct Entry {
  std::string Name;
  bool Directory {};
};

struct OpenDirectory {
  std::string Relative;
  std::vector<Entry> Entries; // with "." and ".." already at the front
  size_t Cursor {};
};

struct CacheLine {
  bool Exists {};
  bool Directory {};
  uint64_t Size {};
  int64_t ModifiedMillis {};
};

std::mutex Lock;
std::unordered_map<std::string, CacheLine> Cache;
std::unordered_map<int, OpenDirectory> Directories;

// **negative answers are cached too, and that is licensed by two measurements rather than by
// optimism.** a fifth of this workload's opens are of files that are not there -- .NET probing for a
// library it might find beside an assembly -- and repeating a ~1 ms provider round trip for each of
// them, every time, is the difference between a boot that notices this layer and one that does not.
// it is safe because the layer is read-only and because the guest's path-taking finishes at the end
// of boot: nothing this process does can make an absent file appear. a user editing their library
// underneath a running game is not something the cache would be the first thing to break.
bool Lookup(const std::string& Relative, CacheLine* Out) {
  {
    std::lock_guard Guard(Lock);
    auto Found = Cache.find(Relative);
    if (Found != Cache.end()) {
      *Out = Found->second;
      return Out->Exists;
    }
  }

  // deliberately outside the lock. it is a binder round trip, and two guest threads asking about two
  // different files should not queue behind each other for it -- the worst a race can do here is ask
  // the provider the same question twice and agree about the answer.
  CacheLine Line {};
  SafBridge::StatResult Result {};
  if (SafBridge::StatOne(Relative, &Result)) {
    Line.Exists = true;
    Line.Directory = Result.Directory;
    Line.Size = Result.Size;
    Line.ModifiedMillis = Result.ModifiedMillis;
  }

  std::lock_guard Guard(Lock);
  Cache[Relative] = Line;
  *Out = Line;
  return Line.Exists;
}

// FNV-1a over the relative path. an inode number has to be stable for the life of the run and unique
// within the mount, and a document id is a string -- so it is invented from the one thing that is
// already unique per file. zero is reserved because a caller may read it as "no inode".
uint64_t InodeOf(const std::string& Relative) {
  uint64_t Hash = 1469598103934665603ULL;
  for (const char C : Relative) {
    Hash ^= static_cast<unsigned char>(C);
    Hash *= 1099511628211ULL;
  }
  return Hash ? Hash : 1;
}

void FillStat(const std::string& Relative, const CacheLine& Line, struct stat* Out) {
  std::memset(Out, 0, sizeof(*Out));
  Out->st_dev = MountDevice;
  Out->st_ino = InodeOf(Relative);
  // read-only for everyone, and executable on a directory because that bit is what makes a directory
  // searchable rather than what makes it a program.
  Out->st_mode = Line.Directory ? (S_IFDIR | 0555) : (S_IFREG | 0444);
  Out->st_nlink = Line.Directory ? 2 : 1;
  // the app's own uid, so an ownership check by a guest that asked getuid() agrees with itself.
  Out->st_uid = ::geteuid();
  Out->st_gid = ::getegid();
  Out->st_size = static_cast<off_t>(Line.Size);
  Out->st_blksize = 4096;
  Out->st_blocks = static_cast<blkcnt_t>((Line.Size + 511) / 512);
  const time_t Seconds = static_cast<time_t>(Line.ModifiedMillis / 1000);
  const long Nanos = static_cast<long>((Line.ModifiedMillis % 1000) * 1000000);
  Out->st_mtim = {Seconds, Nanos};
  // the provider knows one timestamp and there is nothing to be gained from inventing two more that
  // disagree with it. a file that has not been written since it was created has all three equal
  // anyway, which is every file in a game dump.
  Out->st_atim = Out->st_mtim;
  Out->st_ctim = Out->st_mtim;
}

// splits an absolute path inside the mount, or a path relative to one of our directories, into the
// relative form the java side takes. false means the path is not ours after all -- which a path that
// climbs out with ".." genuinely is not, since "/game/.." names the real filesystem.
bool Normalise(const std::string& Base, const char* Path, std::string* Out) {
  std::vector<std::string> Parts;
  if (!Base.empty()) {
    size_t Start = 0;
    while (Start < Base.size()) {
      const size_t Slash = Base.find('/', Start);
      const size_t End = Slash == std::string::npos ? Base.size() : Slash;
      if (End > Start) {
        Parts.emplace_back(Base, Start, End - Start);
      }
      Start = End + 1;
    }
  }

  const char* Cursor = Path;
  while (*Cursor) {
    while (*Cursor == '/') {
      ++Cursor;
    }
    const char* End = Cursor;
    while (*End && *End != '/') {
      ++End;
    }
    const size_t Length = static_cast<size_t>(End - Cursor);
    if (Length == 0) {
      break;
    }
    if (Length == 1 && Cursor[0] == '.') {
      // nothing
    } else if (Length == 2 && Cursor[0] == '.' && Cursor[1] == '.') {
      if (Parts.empty()) {
        return false; // climbs out of the mount, so it names the real filesystem and not this
      }
      Parts.pop_back();
    } else {
      Parts.emplace_back(Cursor, Length);
    }
    Cursor = End;
  }

  Out->clear();
  for (const auto& Part : Parts) {
    if (!Out->empty()) {
      Out->push_back('/');
    }
    Out->append(Part);
  }
  return true;
}

// the prefix test proper. "/game" matches "/game" and "/game/..." and nothing else -- in particular
// not "/gamesave", which a plain strncmp would accept.
bool UnderMount(const char* Path) {
  if (!Path || Mount.empty()) {
    return false;
  }
  if (std::strncmp(Path, Mount.c_str(), Mount.size()) != 0) {
    return false;
  }
  const char After = Path[Mount.size()];
  return After == '\0' || After == '/';
}

// resolves whatever a *at syscall was given into a relative path. an absolute path is measured
// against the mount; a relative one is measured against the directory descriptor, which is only ever
// ours because a descriptor from anywhere else is not in the table.
bool Resolve(int DirFD, const char* Path, std::string* Out) {
  if (!Path) {
    return false;
  }
  if (Path[0] == '/') {
    if (!UnderMount(Path)) {
      return false;
    }
    return Normalise(std::string(), Path + Mount.size(), Out);
  }
  if (DirFD == AT_FDCWD) {
    return false;
  }
  std::string Base;
  {
    std::lock_guard Guard(Lock);
    auto Found = Directories.find(DirFD);
    if (Found == Directories.end()) {
      return false;
    }
    Base = Found->second.Relative;
  }
  return Normalise(Base, Path, Out);
}

// what the guest calls a path, for a log line. the relative form is what everything below works in,
// and a log that printed only that would be unreadable against a --trace-files run.
std::string Display(const std::string& Relative) {
  return Relative.empty() ? Mount : Mount + "/" + Relative;
}

// x86-64 struct linux_dirent64, which is byte-identical on arm64 -- unlike struct stat, which is the
// one that needed translating. the name is a flexible array the kernel writes past the end of the
// header, so the header is declared and the name is appended by hand.
struct GuestDirent64 {
  uint64_t d_ino;
  int64_t d_off;
  uint16_t d_reclen;
  uint8_t d_type;
};
constexpr size_t DirentHeader = 19; // 8 + 8 + 2 + 1, and *not* sizeof, which pads to 24

constexpr uint8_t DT_DIR_VALUE = 4;
constexpr uint8_t DT_REG_VALUE = 8;

// said once, loudly, and then never again. a write into a game dump would mean the read-only
// assumption this layer is built on is wrong, and the measurement that licensed it was taken on two
// titles -- so if a third one writes, that has to arrive as a sentence in the log rather than as a
// game that mysteriously fails to save.
void ReportWrite(const std::string& Where) {
  static std::atomic<bool> Said {false};
  if (Said.exchange(true)) {
    return;
  }
  std::printf("[files] REFUSED a write to \"%s\". this layer is read-only, which was measured on two"
              " titles that never write into their own dump -- this one does, and that is a finding\n",
              Where.c_str());
  std::fflush(stdout);
}

} // namespace

bool SetMount(const char* Prefix) {
  if (!Prefix || Prefix[0] != '/') {
    std::fprintf(stderr, "[files] the mount has to be an absolute path, and '%s' is not\n", Prefix ? Prefix : "");
    return false;
  }
  if (!SafBridge::Available()) {
    // the shell binary, or an app whose helper class did not resolve. refusing here is the point:
    // mounting onto nothing would give the guest a directory in which every file is missing, and it
    // would report as a broken game dump rather than as a host layer that was never wired up.
    std::fprintf(stderr, "[files] no provider on the other side of the JNI boundary -- a mount needs the app\n");
    return false;
  }

  Mount = Prefix;
  while (Mount.size() > 1 && Mount.back() == '/') {
    Mount.pop_back();
  }
  // last, and after the string it guards is in place: from here on any guest thread may read it.
  Active.store(true, std::memory_order_relaxed);
  std::printf("[files] the guest's game directory is \"%s\", answered from a content provider\n", Mount.c_str());
  std::fflush(stdout);
  return true;
}

bool Enabled() {
  return Active.load(std::memory_order_relaxed);
}

bool OwnsAt(int DirFD, const char* Path) {
  if (!Enabled()) {
    return false;
  }
  if (Path && Path[0] == '/') {
    return UnderMount(Path);
  }
  if (DirFD == AT_FDCWD) {
    return false;
  }
  std::lock_guard Guard(Lock);
  return Directories.find(DirFD) != Directories.end();
}

int64_t Open(int DirFD, const char* Path, uint64_t GuestFlags) {
  std::string Relative;
  if (!Resolve(DirFD, Path, &Relative)) {
    return -ENOENT;
  }
  if (GuestFlags & (GuestO_WRONLY | GuestO_RDWR | GuestO_CREAT | GuestO_TRUNC | GuestO_APPEND)) {
    ReportWrite(Display(Relative));
    return -EROFS;
  }

  CacheLine Line {};
  if (!Lookup(Relative, &Line)) {
    return -ENOENT;
  }
  if ((GuestFlags & GuestO_DIRECTORY) && !Line.Directory) {
    return -ENOTDIR;
  }

  if (!Line.Directory) {
    const int FD = SafBridge::OpenFile(Relative);
    if (FD < 0) {
      return FD;
    }
    // **is it seekable, and asked once rather than assumed.** a provider is allowed to answer with a
    // pipe, and a pipe would work for exactly as long as the guest read forwards -- then fail at the
    // first seek, thousands of instructions away from here and looking like anything but this. one
    // lseek at the first open says so now. it does not move the offset: SEEK_CUR of zero is the
    // question "where am I", which is ESPIPE on a pipe and 0 on a file.
    static std::atomic<bool> Checked {false};
    if (!Checked.exchange(true) && ::lseek(FD, 0, SEEK_CUR) < 0) {
      std::printf("[files] the provider answers with a pipe rather than a file (%s). reads will work"
                  " and seeks will not, so this will fail later and elsewhere\n",
                  std::strerror(errno));
      std::fflush(stdout);
    }
    return FD;
  }

  // a directory. the provider has no descriptor to hand back for one, so a real descriptor is made
  // out of an empty memfd and the listing is kept beside it: the fd number is then unique, owned by
  // the kernel, closed by an ordinary close, and impossible to confuse with a live descriptor
  // belonging to something else. guest_procfs.cpp answers /proc/self/cmdline the same way, and for
  // the same reason -- this needs the fd, not the file.
  std::vector<SafBridge::Child> Children;
  if (!SafBridge::ListChildren(Relative, &Children)) {
    return -EIO;
  }
  const int FD = static_cast<int>(::syscall(__NR_memfd_create, "guest-files", 0));
  if (FD < 0) {
    return -errno;
  }

  OpenDirectory Listing {};
  Listing.Relative = Relative;
  // "." and ".." are not optional. the kernel returns them, glibc's readdir hands them to the caller
  // to skip, and code that counts entries to decide whether a directory is empty counts on them
  // being there.
  Listing.Entries.push_back({".", true});
  Listing.Entries.push_back({"..", true});
  for (const auto& Child : Children) {
    Listing.Entries.push_back({Child.Name, Child.Directory});
  }

  std::lock_guard Guard(Lock);
  // the listing already answered, for free, the one question a stat of a subdirectory would ask, and
  // the directory a guest has just enumerated is exactly the one it is about to stat its way
  // through. only directories: the query behind the listing does not carry a size, and a file whose
  // size was guessed would be worse than a file that was not cached.
  for (const auto& Child : Children) {
    if (!Child.Directory) {
      continue;
    }
    const std::string ChildPath = Relative.empty() ? Child.Name : Relative + "/" + Child.Name;
    Cache.emplace(ChildPath, CacheLine {true, true, 0, Line.ModifiedMillis});
  }
  Directories[FD] = std::move(Listing);
  return FD;
}

int64_t Stat(int DirFD, const char* Path, int AtFlags, struct stat* Out) {
  std::string Relative;
  if ((AtFlags & AT_EMPTY_PATH) && Path && Path[0] == '\0') {
    // fstatat with an empty path is fstat of the descriptor, which for us is only ever a directory.
    return FStat(DirFD, Out);
  }
  if (!Resolve(DirFD, Path, &Relative)) {
    return -ENOENT;
  }
  CacheLine Line {};
  if (!Lookup(Relative, &Line)) {
    return -ENOENT;
  }
  // AT_SYMLINK_NOFOLLOW needs no thought: a document provider has no symlinks to follow, so lstat
  // and stat are the same answer here rather than being made the same answer.
  FillStat(Relative, Line, Out);
  return 0;
}

int64_t Access(int DirFD, const char* Path, int Mode) {
  std::string Relative;
  if (!Resolve(DirFD, Path, &Relative)) {
    return -ENOENT;
  }
  CacheLine Line {};
  if (!Lookup(Relative, &Line)) {
    return -ENOENT;
  }
  if (Mode & W_OK) {
    ReportWrite(Display(Relative));
    return -EROFS;
  }
  // X_OK on a directory is the search bit and is granted; on a file it would mean the guest intends
  // to exec something out of its own dump, which nothing does and this layer could not support.
  if ((Mode & X_OK) && !Line.Directory) {
    return -EACCES;
  }
  return 0;
}

int64_t ReadLink(int DirFD, const char* Path) {
  std::string Relative;
  if (!Resolve(DirFD, Path, &Relative)) {
    return -ENOENT;
  }
  CacheLine Line {};
  if (!Lookup(Relative, &Line)) {
    return -ENOENT;
  }
  // EINVAL is the honest answer and the one the kernel gives: the file is there and it is not a
  // symlink. a provider has no symlinks at all, so this is true of everything under the mount.
  return -EINVAL;
}

bool OwnsFD(int FD) {
  if (!Enabled()) {
    return false;
  }
  std::lock_guard Guard(Lock);
  return Directories.find(FD) != Directories.end();
}

int64_t FStat(int FD, struct stat* Out) {
  std::string Relative;
  {
    std::lock_guard Guard(Lock);
    auto Found = Directories.find(FD);
    if (Found == Directories.end()) {
      return -EBADF;
    }
    Relative = Found->second.Relative;
  }
  CacheLine Line {};
  if (!Lookup(Relative, &Line)) {
    return -ENOENT;
  }
  // it is a directory whatever the cache says, because it was opened as one. the memfd underneath
  // would otherwise report a zero-length regular file, and a guest that checked S_ISDIR would
  // conclude the directory it is holding open is not one.
  Line.Directory = true;
  FillStat(Relative, Line, Out);
  return 0;
}

int64_t GetDents(int FD, void* Buffer, size_t Size) {
  std::lock_guard Guard(Lock);
  auto Found = Directories.find(FD);
  if (Found == Directories.end()) {
    return -EBADF;
  }
  OpenDirectory& Dir = Found->second;

  auto* Out = static_cast<uint8_t*>(Buffer);
  size_t Written = 0;
  while (Dir.Cursor < Dir.Entries.size()) {
    const Entry& Item = Dir.Entries[Dir.Cursor];
    const size_t NameSize = Item.Name.size() + 1;
    // eight-byte aligned, because the next record's d_ino is a 64-bit field the caller reads
    // directly out of the buffer.
    const size_t Length = (DirentHeader + NameSize + 7) & ~size_t(7);
    if (Written + Length > Size) {
      break;
    }

    GuestDirent64 Header {};
    // the inode of what the entry actually names, so that "." inside a directory and the directory
    // itself agree -- a caller walking a tree and remembering where it has been compares exactly that.
    std::string Full;
    if (Item.Name == ".") {
      Full = Dir.Relative;
    } else if (Item.Name == "..") {
      const size_t Slash = Dir.Relative.find_last_of('/');
      Full = Slash == std::string::npos ? std::string() : Dir.Relative.substr(0, Slash);
    } else {
      Full = Dir.Relative.empty() ? Item.Name : Dir.Relative + "/" + Item.Name;
    }
    Header.d_ino = InodeOf(Full);
    // the offset of the record *after* this one, which is what a caller stores and seeks back to.
    // an index rather than a byte offset, because these records are generated and nothing else ever
    // interprets it.
    Header.d_off = static_cast<int64_t>(Dir.Cursor + 1);
    Header.d_reclen = static_cast<uint16_t>(Length);
    Header.d_type = Item.Directory ? DT_DIR_VALUE : DT_REG_VALUE;

    std::memcpy(Out + Written, &Header, DirentHeader);
    std::memcpy(Out + Written + DirentHeader, Item.Name.c_str(), NameSize);
    // the padding between the name and the next record. it is never read, but leaving whatever the
    // guest had in its buffer there makes two identical runs produce different bytes, and that is
    // the sort of thing that costs an afternoon when something else goes wrong.
    std::memset(Out + Written + DirentHeader + NameSize, 0, Length - DirentHeader - NameSize);

    Written += Length;
    ++Dir.Cursor;
  }

  // a buffer too small for even the first record is EINVAL rather than a short read, which is what
  // the kernel does and what the caller's loop is written against.
  if (Written == 0 && Dir.Cursor < Dir.Entries.size()) {
    return -EINVAL;
  }
  return static_cast<int64_t>(Written);
}

void Close(int FD) {
  // the gate first, and it earns its place here rather than at the call site: every guest close
  // passes through this, and without it each one would take a mutex to look in an empty table.
  if (!Enabled()) {
    return;
  }
  std::lock_guard Guard(Lock);
  Directories.erase(FD);
}

} // namespace GuestFiles

} // namespace HostLayer
