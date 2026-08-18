// the host layer as a library — the JNI surface the app calls, and nothing else.
//
// deliberately thin. everything the app can ask for is expressed as the argument vector RunMain
// already took, so the app passes the same flags a shell would and no measurement stops being
// comparable to the ones every earlier milestone recorded. the one thing that is *not* an
// argument is the window, because it is a live object rather than a string.

#include "boot_progress.h"
#include "host_layer.h"
#include "pad_bridge.h"
#include "saf_bridge.h"
#include "vulkan_thunk.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <pthread.h>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char* LogTag = "sharpemu";

ANativeWindow* Window {};

// everything the host layer and the guest print goes to stdout and stderr, and in an app both are
// /dev/null. rather than convert a few thousand printf calls — several of which are load-bearing
// milestone evidence quoted verbatim in the docs — put a pipe under the two descriptors and pump
// it into logcat. a run in the app then produces exactly the log a run in a shell does, which is
// the only reason `adb logcat` output can be compared against every earlier milestone's.
int LogPipe[2] {-1, -1};

void* LogPump(void*) {
  std::string Line;
  char Buffer[1024];
  for (;;) {
    const ssize_t Got = ::read(LogPipe[0], Buffer, sizeof(Buffer));
    if (Got <= 0) {
      break;
    }
    for (ssize_t i = 0; i < Got; ++i) {
      if (Buffer[i] == '\n') {
        // **the boot tap goes here and not under the write syscall**, which is the other place every
        // guest line passes. that one runs on the guest's own thread, so its cost would sit in the
        // boot's critical path; this thread exists to drain a pipe and is already off it. it sees
        // the host layer's own lines as well as the guest's, which costs a few comparisons and
        // means a checkpoint could be either side's without this loop knowing the difference.
        HostLayer::BootProgress::Observe(Line.data(), Line.size());
        __android_log_write(ANDROID_LOG_INFO, LogTag, Line.c_str());
        Line.clear();
      } else {
        Line.push_back(Buffer[i]);
        // logcat drops a message past ~4000 bytes. the guest can produce a line longer than that
        // — a .NET stack trace does — and losing it silently is exactly the sort of thing that
        // costs a debugging round, so break it up rather than let it vanish.
        if (Line.size() >= 3800) {
          __android_log_write(ANDROID_LOG_INFO, LogTag, Line.c_str());
          Line.clear();
        }
      }
    }
  }
  return nullptr;
}

// **the redirection happens on the caller's thread and only the draining is the pump's**, which is
// the whole reason it is not simply the first thing the pump does. anything printed between
// pthread_create and a new thread reaching dup2 goes to the original stdout, and in an app that is
// /dev/null — a window a caller printing seconds later never notices, and a total loss for one that
// asks the host layer a question and ends the process on the answer.
void StartLogPump() {
  static bool Started = false;
  if (Started) {
    return;
  }
  Started = true;
  if (::pipe(LogPipe) != 0) {
    return;
  }
  ::dup2(LogPipe[1], STDOUT_FILENO);
  ::dup2(LogPipe[1], STDERR_FILENO);
  ::close(LogPipe[1]);
  pthread_t Thread {};
  ::pthread_create(&Thread, nullptr, LogPump, nullptr);
  ::pthread_detach(Thread);
}

} // namespace

extern "C" {

// the one place a class can be looked up by name, and the reason this function exists at all.
//
// the host layer has always been one-way — the app calls down and nothing calls back, because a
// window is an ANativeWindow* and audio is pure NDK. the guest file layer is the first thing that
// has to ask java a question, and a guest thread cannot ask it: FindClass on a thread this process
// attached itself searches the *system* class loader, which has never heard of anything in the APK.
// JNI_OnLoad runs with the app's own class loader in scope, so this is where the class and its
// method ids are resolved and held.
//
// it runs whether or not a run will ever mount anything. resolving three method ids costs
// microseconds once, and a mount that discovered here that it had nothing to talk to would have
// discovered it far too late.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* VM, void*) {
  // **first, because the two bridges below can fail and say so on stdout.** the redirection used to
  // be a run's business, which meant everything printed before a run started went to the original
  // stdout — /dev/null in an app. what is printed here is precisely a bridge that could not resolve
  // its java side, so the lines that vanished were the ones explaining a capability that is now
  // missing for the rest of the process. redirecting at library load costs a pipe and a thread once
  // and gives every later caller a stdout that goes somewhere.
  StartLogPump();
  HostLayer::SafBridge::OnLoad(VM);
  // the pad bridge resolves its own helper here for the same reason and at the same moment. it calls
  // *up* only for rumble; state comes down through nativeSetPadState below, so a failure to resolve
  // costs rumble and leaves the reading half working.
  HostLayer::PadBridge::OnLoad(VM);
  return JNI_VERSION_1_6;
}

// called from the SurfaceHolder callback, with null when the surface goes away. the window is
// handed straight to the thunk, which is what turns a presented swapchain image into pixels on
// the panel; the thunk is also where the surface *size* now comes from, so that the extent the
// guest is told about and the buffer it ends up in cannot disagree. that disagreement is the
// bug, and the constant that papered over it is what this retires.
JNIEXPORT void JNICALL Java_com_mircowuffwuff_sharpemu_HostLayer_nativeSetSurface(JNIEnv* Env, jclass, jobject Surface) {
  ANativeWindow* Next = Surface ? ::ANativeWindow_fromSurface(Env, Surface) : nullptr;
  HostLayer::VulkanThunk::SetAndroidWindow(Next);
  if (Window) {
    ::ANativeWindow_release(Window);
  }
  Window = Next;
}

// the app's pad state, pushed from wherever it reads a KeyEvent or a MotionEvent. scalars rather than
// a structure on purpose: the guest checks a version and a byte count for the one layout that does
// cross, and adding a second layout across the JNI boundary as well would be a second thing to keep
// in step for no gain.
//
// **cheap enough to call on every event.** it takes one uncontended lock and copies twelve bytes; the
// guest's poll takes the same lock. nothing here allocates, throws or blocks, which is what lets it be
// called straight from the input dispatch on the UI thread.
JNIEXPORT void JNICALL Java_com_mircowuffwuff_sharpemu_HostLayer_nativeSetPadState(
  JNIEnv*, jclass, jint Buttons, jint LeftX, jint LeftY, jint RightX, jint RightY, jint LeftTrigger,
  jint RightTrigger, jboolean Connected) {
  HostLayer::PadBridge::WireState State {};
  State.Buttons = static_cast<uint32_t>(Buttons);
  State.LeftX = static_cast<uint8_t>(LeftX);
  State.LeftY = static_cast<uint8_t>(LeftY);
  State.RightX = static_cast<uint8_t>(RightX);
  State.RightY = static_cast<uint8_t>(RightY);
  State.LeftTrigger = static_cast<uint8_t>(LeftTrigger);
  State.RightTrigger = static_cast<uint8_t>(RightTrigger);
  State.Connected = Connected ? 1 : 0;
  HostLayer::PadBridge::SetState(State);
}

// whether the GPU driver the app chose is the one this process would render through, asked *before*
// a guest is started so that a launch can be refused rather than ended.
//
// **this opens the driver, and that is the point rather than a side effect.** it is the same
// `std::call_once` the guest's first vulkan call would have run, so the load happens once and simply
// happens earlier — what is checked is the load that the run will use, not a rehearsal of it in some
// other process or namespace. see vulkan_thunk.h for why the answer cannot be had any other way.
//
// the two strings outlive the call because the thunk keeps the pointers rather than copying them, and
// RunMain later sets the same two from its own argument vector.
JNIEXPORT jboolean JNICALL Java_com_mircowuffwuff_sharpemu_HostLayer_nativeDriverLoads(
  JNIEnv* Env, jclass, jstring Driver, jstring Hooks) {
  static std::string DriverPath;
  static std::string HookLibDir;
  const char* Chars = Env->GetStringUTFChars(Driver, nullptr);
  DriverPath = Chars ? Chars : "";
  Env->ReleaseStringUTFChars(Driver, Chars);
  Chars = Env->GetStringUTFChars(Hooks, nullptr);
  HookLibDir = Chars ? Chars : "";
  Env->ReleaseStringUTFChars(Hooks, Chars);

  HostLayer::VulkanThunk::SetDriver(DriverPath.c_str());
  HostLayer::VulkanThunk::SetHookLibDir(HookLibDir.c_str());
  return HostLayer::VulkanThunk::ChosenDriverLoads() ? JNI_TRUE : JNI_FALSE;
}

// blocks for the whole run, so the app must call it off the UI thread. a guest that calls
// exit_group never returns from here — it calls _exit, which is what the syscall means and the only
// safe answer once the other guest threads are inside translated code. that is the process this
// library was loaded into, so a caller that has anything to lose runs a guest in a process it is
// willing to lose; the app gives one to each run.
JNIEXPORT jint JNICALL Java_com_mircowuffwuff_sharpemu_HostLayer_nativeRun(JNIEnv* Env, jclass, jobjectArray Args) {
  std::vector<std::string> Storage;
  Storage.emplace_back("sharpemu-host-layer");
  const jsize Count = Args ? Env->GetArrayLength(Args) : 0;
  for (jsize i = 0; i < Count; ++i) {
    auto Text = static_cast<jstring>(Env->GetObjectArrayElement(Args, i));
    const char* Chars = Env->GetStringUTFChars(Text, nullptr);
    Storage.emplace_back(Chars ? Chars : "");
    Env->ReleaseStringUTFChars(Text, Chars);
    Env->DeleteLocalRef(Text);
  }

  std::vector<char*> Argv;
  Argv.reserve(Storage.size());
  for (auto& Entry : Storage) {
    Argv.push_back(Entry.data());
  }

  return HostLayer::RunMain(static_cast<int>(Argv.size()), Argv.data());
}

} // extern "C"
