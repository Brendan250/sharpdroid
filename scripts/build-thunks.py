# assembles the guest halves of both thunks into x86-64 shared objects.
#
#   py scripts/build-thunks.py
#   py scripts/build-thunks.py --what vulkan
#
# these are the libraries the guest's own ld.so finds on its library path when the payload asks for
# vulkan or for AAudio, so they are staged beside the glibc set rather than built into anything.
#
# **they link nothing at all** -- no libc, no startup files, no dependencies -- which is why the
# NDK's x86-64 clang can build them even though its target is bionic and the guest's set is glibc.
# what comes out is a table of stubs, a soname, and for vulkan an .init_array entry, and glibc's
# loader has no opinion about any of that.
#
# **the generated sources are committed**, so this needs no header parsing. regenerating them is
# `gen-thunks.py`, and it is what you run when the NDK moves.

import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import paths
from sharpemu import toolchain as tc
from sharpemu.shell import Refusal, capture, ensure, main, produced, run, say, step
from sharpemu.vocabulary import Parser

# the guest set is glibc and predates every android API, so the level here only chooses which of the
# NDK's compiler wrappers is used. nothing it implies reaches the output, which links no bionic.
API_LEVEL = 21

_THUNKS = {
    "vulkan": {
        "source": lambda: paths.VULKAN_THUNK / "generated" / "vulkan_stubs.S",
        "target": lambda: paths.GUEST_VULKAN,
        "soname": "libvulkan.so.1",
        "exports": r"\bvk\w+$",
        "what": "vk entry points",
    },
    "audio": {
        "source": lambda: paths.AUDIO_THUNK / "generated" / "aaudio_stubs.S",
        "target": lambda: paths.GUEST_AAUDIO,
        "soname": "libaaudio.so",
        "exports": r"\bAAudio\w+$",
        "what": "AAudio entry points",
    },
}


def entry():
    parser = Parser(description="assemble the guest halves of the vulkan and audio thunks")
    parser.add_argument("--what", choices=("vulkan", "audio", "both"), default="both",
                        help="which thunk to assemble.")
    arguments = parser.parse_args()

    toolchain = tc.resolve().require("ndk")
    ensure(paths.GUEST_LIBS_X86_64)

    for name in ("vulkan", "audio"):
        if arguments.what in (name, "both"):
            step(name)
            build_one(toolchain, name)


def build_one(toolchain, name):
    thunk = _THUNKS[name]
    source = thunk["source"]()
    target = thunk["target"]()
    if not source.exists():
        raise Refusal("missing generated stubs: {}. run: py scripts/gen-thunks.py --what {}"
                      .format(paths.relative(source), name))

    compiler = toolchain.cross_compiler("x86_64", API_LEVEL)
    run([compiler, "-o", str(target), str(source),
         "-shared", "-nostdlib", "-fPIC",
         "-Wl,-soname," + thunk["soname"],
         # both hash tables, because the guest's loader and the payload's own probing disagree about
         # which one to read and producing both costs a few kilobytes.
         "-Wl,--hash-style=both",
         "-Wl,-z,noexecstack"])
    produced(target, thunk["soname"])

    if name == "vulkan":
        # the payload asks for the versioned soname and anything probing by the bare one asks for
        # the other. same file, since there is no versioning to speak of on this side.
        shutil.copyfile(str(target), str(paths.GUEST_VULKAN_SONAME))
        say("  {}  copied from {}".format(paths.GUEST_VULKAN_SONAME.name, target.name))

    describe(toolchain, target, thunk)


def describe(toolchain, target, thunk):
    """what the library actually turned out to be, rather than that the compiler exited zero.

    the export count is the assertion that matters: a stub table that assembled but resolved to
    nothing is a library the guest's loader accepts and every call through then traps into an id
    the host layer has no entry for.
    """
    header = capture([toolchain.readelf, "-h", str(target)], check=False)
    dynamic = capture([toolchain.readelf, "-d", str(target)], check=False)
    symbols = capture([toolchain.readelf, "--dyn-syms", str(target)], check=False)

    for line in header.splitlines():
        if re.search(r"\b(Type|Machine):", line):
            say("  " + line.strip())
    for line in dynamic.splitlines():
        if re.search(r"SONAME|NEEDED|INIT_ARRAY", line):
            say("  " + line.strip())

    exports = len([line for line in symbols.splitlines()
                   if re.search(thunk["exports"], line.strip())])
    if exports == 0:
        raise Refusal("{} exports no {}".format(target.name, thunk["what"]))
    say("  exported {}: {}".format(thunk["what"], exports))


if __name__ == "__main__":
    main(entry)
