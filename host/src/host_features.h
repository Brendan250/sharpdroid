// what this CPU can do, in the shape FEXCore's code generator asks for.
//
// FEXCore::HostFeatures is "backend features that change how codegen is generated from IR", so a
// field left false is a longer instruction sequence than this host needs -- and a field set true on
// a CPU that lacks the extension is SIGILL wherever the emitter first uses it. the probe is
// therefore built out of the CPU's own ID registers rather than out of a guess.
#pragma once

#include <FEXCore/Core/HostFeatures.h>

namespace HostLayer {
namespace HostFeatures {

// how the feature set handed to the context is arrived at.
enum class Mode {
  // read the ID registers and describe what they say.
  Probe,
  // the conservative set: four extensions off AT_HWCAP, the AVX decode table, and the MIDRs.
  // it is what the probe is measured against, and the answer for a device the probe gets wrong.
  Minimal,
};

// resolves `probe` and `minimal`. anything else is refused, which is what makes a typo on the
// command line a refusal rather than a silent fallback to the other arm.
bool ParseMode(const char* Text, Mode& Out);

FEXCore::HostFeatures Build(Mode Mode);

// two lines: the raw ID registers, and every feature the set carries. the first is what a report
// about wrong codegen is worth having, since it lets the second be recomputed off the device.
void Report(const FEXCore::HostFeatures& Features, Mode Mode);

} // namespace HostFeatures
} // namespace HostLayer
