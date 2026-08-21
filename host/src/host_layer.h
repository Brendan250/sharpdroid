// sharpemu-android host layer -- the entry point, as a call rather than as a process.
//
// the very first build glue predicted this, and the app is where it happens: the host layer stops being an
// executable and becomes a library the app links. nothing inside it had to change to allow that,
// because nothing in it ever assumed it was a process -- the argument parsing in main.cpp is the
// only thing that did, and it becomes this.
//
// two entry translation units sit on top of this, and neither does anything else:
//   entry_exe.cpp   main(), for the regression set and every measurement recorded so far
//   entry_jni.cpp   the JNI surface the app calls, plus the ANativeWindow it hands down
//
// keeping the executable is not sentiment. every milestone before the app was measured through it,
// regression.sh runs on it, and an app is a far worse place to bisect a JIT problem from.

#pragma once

namespace HostLayer {

// argv[0] is ignored, exactly as it was when the kernel supplied it. the return value is the
// guest's exit status, truncated to 8 bits the way linux would report it.
//
// call this once per process. FEXCore::Config is initialised and shut down inside it, and a guest
// that calls exit_group never returns here at all -- it calls _exit, because the other guest threads
// are still inside FEXCore and unwinding past them would be racing them. in the app that takes the
// process with it, which is honest but is the first thing a real frontend will have to solve.
int RunMain(int argc, char** argv);

} // namespace HostLayer
