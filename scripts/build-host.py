# builds the host layer for android arm64: the library the app loads, and the same thing as a shell
# binary.
#
#   py scripts/build-host.py
#   py scripts/build-host.py --clean
#   py scripts/build-host.py --probe          # the host vulkan probe, which is not part of the project
#
# FEXCore is statically linked in and its checkout is never modified -- a modified FEX shows dirty in
# `git status`, which is what makes that enforceable rather than a convention.
#
# **this refuses without libadrenotools.** the cmake project imports `libadrenotools.a` as a static
# library at configure time, so a missing one is a configure failure whose message is about an
# import rather than about a build step that has not been run.

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import native, paths
from sharpemu import toolchain as tc
from sharpemu.shell import Refusal, ensure, main, produced, run, say, step, wipe, write_text
from sharpemu.vocabulary import Parser

# the minimum android the app supports, and what everything native is built against.
API_LEVEL = 28


def entry():
    parser = Parser(description="build the host layer for android arm64")
    parser.add_argument("--clean", action="store_true", help="wipe the build directory first.")
    parser.add_argument("--build-type", default="Release", help="the cmake build type.")
    parser.add_argument("--probe", action="store_true",
                        help="build the host vulkan probe instead. it links nothing, takes two "
                             "seconds, and answers questions about the host's own vulkan.")
    arguments = parser.parse_args()

    toolchain = tc.resolve().require("ndk", "cmake")

    if arguments.probe:
        build_probe(toolchain)
        return

    if not paths.ADRENOTOOLS_LIBRARY.exists():
        raise Refusal(
            "the host layer needs {}. run: py scripts/build-adrenotools.py".format(
                paths.relative(paths.ADRENOTOOLS_LIBRARY)))

    if arguments.clean:
        say("wiping {}".format(paths.relative(paths.BUILD_HOST)))
        wipe(paths.BUILD_HOST)
    ensure(paths.BUILD_HOST)

    shims = write_shims(toolchain)

    step("configuring")
    native.configure(
        toolchain, paths.HOST, paths.BUILD_HOST,
        api_level=API_LEVEL, stl="c++_shared", build_type=arguments.build_type,
        path_prefix=[shims])

    step("building")
    native.build(toolchain, paths.BUILD_HOST, path_prefix=[shims])

    native.report(paths.BUILD_HOST)
    produced(paths.HOST_LIBRARY, "the host layer library")
    produced(paths.HOST_SHELL, "the host layer shell binary")


def write_shims(toolchain):
    """the two programs FEXCore's build invokes by bare name, put somewhere only this build sees.

    **on the child's PATH and never on ours.** a directory placed on this process's own PATH
    outlives the step that wanted it, and one of these comes out of a directory full of unix tools
    whose `sort` and `find` would shadow the platform's for everything downstream.
    """
    shims = ensure(paths.BUILD_HOST / "_shims")

    # FEXCore's generators invoke `python3`. on windows the real interpreter is `python.exe`, and a
    # bare `python3` hits the microsoft store's app-execution alias, which prints an install prompt
    # and exits non-zero. pointing the shim at this interpreter also means the generators run under
    # exactly the python that is running the build.
    write_text(shims / "python3.cmd", "@echo off\r\n\"{}\" %*\r\n".format(sys.executable))

    # FEXCore compresses its man page with gzip, which windows has no equivalent for and which is
    # wired into the default target. git ships one; the shim points at it rather than putting git's
    # whole tool directory on PATH.
    gzip = tc.which("gzip")
    if gzip is None:
        git = tc.which("git")
        if git:
            candidate = git.parent.parent / "usr" / "bin" / "gzip.exe"
            if candidate.exists():
                gzip = candidate
    if gzip:
        write_text(shims / "gzip.cmd", "@echo off\r\n\"{}\" %*\r\n".format(gzip))
    else:
        say("warning: no gzip found. FEXCore's man page step will fail, which is harmless in "
            "itself but stops the default target")
    return shims


def build_probe(toolchain):
    """the host-side vulkan probe.

    deliberately not part of the cmake project: it links nothing, builds in two seconds, and asks
    about the *host's* vulkan rather than the guest's -- which is a different question and a
    different lifetime from anything the host layer is made of.
    """
    source = paths.VULKAN_THUNK / "host_vk_probe.c"
    if not source.exists():
        raise Refusal("missing {}".format(paths.relative(source)))
    output = ensure(paths.BUILD_VULKAN) / "host-vk-probe"
    step("building the host vulkan probe")
    run([toolchain.clang, "--target=aarch64-linux-android{}".format(API_LEVEL),
         "-O2", "-Wall", "-o", str(output), str(source), "-ldl"])
    produced(output, "the host vulkan probe")


if __name__ == "__main__":
    main(entry)
