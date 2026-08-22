#include "saf_bridge.h"

#include <jni.h>

#include <cerrno>
#include <cstdio>

namespace HostLayer {

namespace SafBridge {

namespace {

// the java side of the file layer. it holds the tree grant and turns a relative path into a document
// id; nothing below this line knows what a URI is.
constexpr const char* HelperClass = "com/mircowuffwuff/sharpdroid/GuestFiles";

JavaVM* VM {};
jclass Helper {};
jmethodID OpenFdMethod {};
jmethodID StatOneMethod {};
jmethodID ListChildrenMethod {};

// attaching a guest thread to the runtime, and detaching it again when it ends.
//
// **the destructor is not optional.** ART aborts the process when a thread that is still attached
// exits -- "native thread exited without detaching" -- so an attachment that outlives its thread would
// turn the end of a guest thread into a crash, at a point with nothing to do with files. a
// thread_local with a destructor is what makes the detach happen wherever the thread ends, including
// the paths through FEXCore that this file never sees.
struct Attachment {
  JNIEnv* Env {};
  bool Attached {};

  ~Attachment() {
    if (Attached && VM) {
      VM->DetachCurrentThread();
    }
  }
};

thread_local Attachment Current;

JNIEnv* Env() {
  if (!VM) {
    return nullptr;
  }
  if (Current.Env) {
    return Current.Env;
  }
  JNIEnv* Found {};
  const jint Status = VM->GetEnv(reinterpret_cast<void**>(&Found), JNI_VERSION_1_6);
  if (Status == JNI_OK) {
    // already attached -- the UI thread and anything else the app calls us on. it is not ours to
    // detach, hence the flag rather than an unconditional detach in the destructor.
    Current.Env = Found;
    return Found;
  }
  if (Status != JNI_EDETACHED) {
    return nullptr;
  }
  // a guest thread. named so that a thread dump during a boot says which threads are guest ones,
  // since by then there are a dozen of them and the runtime would otherwise call them all Thread-N.
  JavaVMAttachArgs Args {JNI_VERSION_1_6, "sharpdroid-guest", nullptr};
  if (VM->AttachCurrentThread(&Found, &Args) != JNI_OK) {
    return nullptr;
  }
  Current.Env = Found;
  Current.Attached = true;
  return Found;
}

// an exception crossing back into the syscall layer would be delivered at the next JNI call, which
// could be a different file entirely -- so every call site clears it here and reports the errno the
// syscall should have returned. the java side is written not to throw; this is for the provider
// dying underneath it, which is a real thing when a grant is revoked mid-run.
bool Failed(JNIEnv* E, const char* What) {
  if (!E->ExceptionCheck()) {
    return false;
  }
  E->ExceptionDescribe();
  E->ExceptionClear();
  std::printf("[files] %s threw. the grant may have been revoked\n", What);
  std::fflush(stdout);
  return true;
}

} // namespace

void OnLoad(JavaVM* Vm) {
  VM = Vm;
  JNIEnv* E = Env();
  if (!E) {
    return;
  }

  // **FindClass here and nowhere else.** JNI_OnLoad runs with the app's class loader in scope; on a
  // thread this file attached itself, FindClass uses the *system* class loader instead, which knows
  // nothing about the APK and answers ClassNotFoundException for a class sitting right there. that
  // is the classic two-way JNI trap, and resolving eagerly at load time is the standard answer.
  jclass Local = E->FindClass(HelperClass);
  if (!Local) {
    E->ExceptionClear();
    std::printf("[files] %s not found -- the guest file layer cannot mount\n", HelperClass);
    std::fflush(stdout);
    return;
  }
  // a global reference, because a local one dies at the end of this call and the whole point is to
  // still have it when a guest thread asks in ten seconds' time.
  Helper = static_cast<jclass>(E->NewGlobalRef(Local));
  E->DeleteLocalRef(Local);

  OpenFdMethod = E->GetStaticMethodID(Helper, "openFd", "(Ljava/lang/String;)I");
  StatOneMethod = E->GetStaticMethodID(Helper, "statOne", "(Ljava/lang/String;)[J");
  ListChildrenMethod = E->GetStaticMethodID(Helper, "listChildren", "(Ljava/lang/String;)[Ljava/lang/String;");
  if (!OpenFdMethod || !StatOneMethod || !ListChildrenMethod) {
    E->ExceptionClear();
    Helper = nullptr;
    std::printf("[files] %s is missing a method -- the guest file layer cannot mount\n", HelperClass);
    std::fflush(stdout);
  }
}

bool Available() {
  return VM != nullptr && Helper != nullptr;
}

int OpenFile(const std::string& Relative) {
  JNIEnv* E = Env();
  if (!E || !Helper) {
    return -EIO;
  }
  jstring Path = E->NewStringUTF(Relative.c_str());
  if (!Path) {
    E->ExceptionClear();
    return -ENOMEM;
  }
  const jint FD = E->CallStaticIntMethod(Helper, OpenFdMethod, Path);
  E->DeleteLocalRef(Path);
  if (Failed(E, "openFd")) {
    return -EIO;
  }
  // the java side already turned "no such document" into a negative errno rather than an exception,
  // so a negative here is an answer and not a failure to get one.
  return FD;
}

bool StatOne(const std::string& Relative, StatResult* Out) {
  JNIEnv* E = Env();
  if (!E || !Helper) {
    return false;
  }
  jstring Path = E->NewStringUTF(Relative.c_str());
  if (!Path) {
    E->ExceptionClear();
    return false;
  }
  auto Packed = static_cast<jlongArray>(E->CallStaticObjectMethod(Helper, StatOneMethod, Path));
  E->DeleteLocalRef(Path);
  if (Failed(E, "statOne") || !Packed) {
    return false;
  }

  // three values in one array rather than one bit-packed long. dolphin packs size and is-a-directory
  // together to make one binder call do the work of two, and that reasoning does not apply here: the
  // query on the other side already returns all three columns at once, so packing would only cost
  // legibility.
  jlong Values[3] {};
  E->GetLongArrayRegion(Packed, 0, 3, Values);
  E->DeleteLocalRef(Packed);
  if (Failed(E, "statOne")) {
    return false;
  }

  Out->Exists = true;
  Out->Size = Values[0] < 0 ? 0 : static_cast<uint64_t>(Values[0]);
  Out->Directory = Values[1] != 0;
  Out->ModifiedMillis = Values[2];
  return true;
}

bool ListChildren(const std::string& Relative, std::vector<Child>* Out) {
  JNIEnv* E = Env();
  if (!E || !Helper) {
    return false;
  }
  jstring Path = E->NewStringUTF(Relative.c_str());
  if (!Path) {
    E->ExceptionClear();
    return false;
  }
  auto Names = static_cast<jobjectArray>(E->CallStaticObjectMethod(Helper, ListChildrenMethod, Path));
  E->DeleteLocalRef(Path);
  if (Failed(E, "listChildren") || !Names) {
    return false;
  }

  const jsize Count = E->GetArrayLength(Names);
  Out->reserve(static_cast<size_t>(Count));
  for (jsize i = 0; i < Count; ++i) {
    auto Entry = static_cast<jstring>(E->GetObjectArrayElement(Names, i));
    if (!Entry) {
      continue;
    }
    const char* Chars = E->GetStringUTFChars(Entry, nullptr);
    if (Chars && Chars[0]) {
      // one string per child, kind as the first character, rather than a parallel boolean array:
      // one array to walk and one local reference to release per entry.
      Out->push_back({std::string(Chars + 1), Chars[0] == 'd'});
    }
    if (Chars) {
      E->ReleaseStringUTFChars(Entry, Chars);
    }
    E->DeleteLocalRef(Entry);
  }
  E->DeleteLocalRef(Names);
  return true;
}

} // namespace SafBridge

} // namespace HostLayer
