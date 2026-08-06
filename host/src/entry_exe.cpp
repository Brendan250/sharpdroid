// the host layer as a process — the shell binary every milestone before the app was measured through,
// and the one regression.sh runs.
//
// it still exists, for the same reason it always did: an app is a bad place to bisect a JIT
// problem from, and a flag typed at a shell is the cheapest experiment this project has.

#include "host_layer.h"

int main(int argc, char** argv) {
  return HostLayer::RunMain(argc, argv);
}
