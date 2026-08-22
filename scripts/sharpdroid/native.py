# the two cmake builds in this repository, which are the same build with different sources.
#
# both cross-compile for android arm64 out of the project-local NDK and both are driven by ninja out
# of the project-local cmake package. the arguments are assembled here rather than in each script,
# because the pair that matters -- the NDK's own toolchain file and a make program from the same
# place cmake came from -- is exactly the pair that silently produces a build configured against one
# generator and built by another when they disagree.
#
# **arm64 only, and that is the architecture rather than a packaging choice.** FEXCore's backend
# emits arm64 and there is no other target.

import os
from pathlib import Path

from .shell import Refusal, run, say

ABI = "arm64-v8a"


def configure(toolchain, source, build, api_level, stl="c++_shared", build_type="Release",
              defines=None, path_prefix=(), environment=None):
    """configure one cmake project against the NDK.

    `path_prefix` is prepended to the child's `PATH` and nowhere else. a build that needs a tool on
    `PATH` can take it by modifying this process's own, which is a change that outlives the step
    that wanted it -- and putting a directory full of unix tools on `PATH` shadows the platform's
    own `sort` and `find` for everything downstream of it.
    """
    source, build = str(source), str(build)
    arguments = [
        str(toolchain.cmake), "-G", "Ninja", "-S", source, "-B", build,
        "-DCMAKE_TOOLCHAIN_FILE={}/build/cmake/android.toolchain.cmake".format(
            str(toolchain.ndk).replace("\\", "/")),
        "-DANDROID_ABI=" + ABI,
        "-DANDROID_PLATFORM=android-{}".format(api_level),
        "-DANDROID_STL=" + stl,
        "-DCMAKE_BUILD_TYPE=" + build_type,
        "-DCMAKE_MAKE_PROGRAM={}".format(str(toolchain.ninja).replace("\\", "/")),
    ]
    for key, value in (defines or {}).items():
        arguments.append("-D{}={}".format(key, value))
    run(arguments, env=_with_path(environment, path_prefix, toolchain))


def build(toolchain, directory, targets=(), path_prefix=(), environment=None):
    arguments = [str(toolchain.ninja), "-C", str(directory)] + list(targets)
    run(arguments, env=_with_path(environment, path_prefix, toolchain))


def report(directory):
    """what a native build produced, by name and size.

    every script here asserts the artefact it was supposed to produce; this is the listing beside
    that assertion, and it is what makes a library that came out zero-length visible at the moment
    it happened rather than on a device.
    """
    directory = Path(directory)
    found = sorted(
        [p for p in directory.rglob("*") if p.is_file() and p.suffix in (".a", ".so")],
        key=lambda p: p.name)
    if not found:
        raise Refusal("the build produced no libraries under {}".format(directory))
    say("")
    say("built:")
    for path in found:
        say("  {:>12,}  {}".format(path.stat().st_size, path.relative_to(directory).as_posix()))


def _with_path(environment, prefix, toolchain):
    """the child's environment with some directories in front of its PATH.

    cmake's own directory is always among them, because cmake invokes `ninja` by name once the
    generator has been chosen and would otherwise find whichever one the machine has.
    """
    env = dict(environment or os.environ)
    directories = [str(p) for p in prefix] + [str(toolchain.cmake_bin)]
    env["PATH"] = os.pathsep.join(directories + [env.get("PATH", "")])
    return env
