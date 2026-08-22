# the shared half of this repository's scripts.
#
# the entry points beside this package are one job each; everything two of them would otherwise
# each know lives here. **one rule lives in one place** is the whole organising idea: where an
# artefact is written, what the app is called, how a build is named on a device and which values an
# argument accepts are each resolved by one function that every caller shares. two copies of one
# rule drift silently, and this project has watched them do it.
#
#   shell       how a script talks, how it runs things, and how it refuses
#   paths       where everything in this repository is
#   toolchain   the compilers and SDKs, and the only place that knows a version number
#   vocabulary  the argument scheme every script shares
#   device      adb, the app's identity, and the app's directories on a device
#   builds      what a SharpEmu build is, on disk and on a device
