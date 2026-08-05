// the host layer as a process — the shell binary every milestone up to M4 was measured through,
// and the one regression.sh runs.
//
// it exists after M5 for the same reason it existed before: an app is a bad place to bisect a JIT
// problem from, and a flag typed at a shell is the cheapest experiment this project has.

#include "host_layer.h"

int main(int argc, char** argv) {
  return HostLayer::RunMain(argc, argv);
}
