// sharpemu-android host layer — the first call that goes *up* into java.
//
// everything else here is one-way. the app calls down through entry_jni.cpp and the host layer never
// calls back: vulkan is handed a native ANativeWindow*, and audio is pure NDK AAudio with no java
// anywhere in it. a content provider has no NDK, so reaching one means holding a JavaVM*, a global
// reference to a helper class, its method ids, and an attachment for every guest thread that might
// ask a question — which is machinery this project did not have until the guest file layer needed it.
//
// it is a separate file from guest_files.cpp on purpose: this is the only part that cannot work
// outside an app, and keeping it apart is what lets guest_files.cpp be read as syscall semantics
// rather than as JNI. the shell binary links both, calls neither, and Available() answers false.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

typedef struct _JavaVM JavaVM;

namespace HostLayer {

namespace SafBridge {

// called once from JNI_OnLoad, on a thread the runtime attached for us and with the *app's* class
// loader in scope. that timing is the whole reason it exists as its own entry point rather than
// being resolved lazily on first use — see the implementation.
void OnLoad(JavaVM* VM);

// false in the shell binary, and false in an app whose helper class did not resolve. the file layer
// refuses to mount rather than mounting onto nothing.
bool Available();

// a real descriptor on a real file, already open, or a negative errno. the provider is allowed to
// answer with a pipe; whether it did is the caller's question and Seekable below is how it is asked.
int OpenFile(const std::string& Relative);

// one query answering size, kind and modification time together. absent is false rather than an
// error: a lookup of a file that is not there is an ordinary thing for the guest to do — a fifth of
// this workload's opens are exactly that — and it is not worth an exception crossing JNI.
struct StatResult {
  bool Exists {};
  bool Directory {};
  uint64_t Size {};
  int64_t ModifiedMillis {};
};
bool StatOne(const std::string& Relative, StatResult* Out);

struct Child {
  std::string Name;
  bool Directory {};
};
bool ListChildren(const std::string& Relative, std::vector<Child>* Out);

} // namespace SafBridge

} // namespace HostLayer
