// the host layer as a library — the JNI surface the app calls, and nothing else.
//
// deliberately thin. everything the app can ask for is expressed as the argument vector RunMain
// already took, so the app passes the same flags a shell would and no measurement stops being
// comparable to the ones every earlier milestone recorded. the one thing that is *not* an
// argument is the window, because it is a live object rather than a string.

#include "host_layer.h"
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
void* LogPump(void*) {
  int Pipe[2];
  if (::pipe(Pipe) != 0) {
    return nullptr;
  }
  ::dup2(Pipe[1], STDOUT_FILENO);
  ::dup2(Pipe[1], STDERR_FILENO);
  ::close(Pipe[1]);

  std::string Line;
  char Buffer[1024];
  for (;;) {
    const ssize_t Got = ::read(Pipe[0], Buffer, sizeof(Buffer));
    if (Got <= 0) {
      break;
    }
    for (ssize_t i = 0; i < Got; ++i) {
      if (Buffer[i] == '\n') {
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

void StartLogPump() {
  static bool Started = false;
  if (Started) {
    return;
  }
  Started = true;
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
  HostLayer::SafBridge::OnLoad(VM);
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

// blocks for the whole run, so the app must call it off the UI thread. a guest that calls
// exit_group never returns from here — it calls _exit, which is what the syscall means and the only
// safe answer once the other guest threads are inside translated code. that is the process this
// library was loaded into, so a caller that has anything to lose runs a guest in a process it is
// willing to lose; the app gives one to each run.
JNIEXPORT jint JNICALL Java_com_mircowuffwuff_sharpemu_HostLayer_nativeRun(JNIEnv* Env, jclass, jobjectArray Args) {
  StartLogPump();

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
