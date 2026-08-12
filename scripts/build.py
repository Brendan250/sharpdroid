# builds everything, in the order the pieces actually need each other.
#
#   py scripts/build.py                 build whatever is missing
#   py scripts/build.py --list          what the steps are and which are up to date
#   py scripts/build.py --force         build every step again
#   py scripts/build.py --only host     one step and the ones it needs
#   py scripts/build.py --clean         wipe what the native steps write, then build
#
# **the order is real rather than editorial**, and each link in it is an actual refusal by the step
# that comes after:
#
#   the host project will not configure without libadrenotools
#   the test guests refuse without the generated guest-side thunk libraries
#   the APK refuses without the host layer
#
# **a step is skipped when what it produces is already there.** that is a byte-count question and not
# a timestamp one wherever it can be, because the failure this repository keeps paying for is a step
# that returned zero having produced nothing, and the second-most common is one that was skipped
# because a stale file of the right name was sitting where its output goes.
#
# each step is run as the command a person would type, rather than imported and called. so what this
# prints is a transcript somebody can rerun a line of, and a step's own refusals arrive intact.

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import paths, vocabulary
from sharpemu import toolchain as tc
from sharpemu.shell import Refusal, Timer, main, say, step, wipe
from sharpemu.vocabulary import Parser

HERE = Path(__file__).resolve().parent

# name, what it runs, what it produces, and one line saying why it is where it is in the order.
STEPS = [
    ("guest-libs", ["fetch-guest-libs.py"],
     [paths.GUEST_LIBS_X86_64 / "libc.so.6"],
     "the x86-64 glibc set the guest's own linker searches"),
    ("adrenotools", ["build-adrenotools.py"],
     [paths.ADRENOTOOLS_LIBRARY],
     "the host project imports this as a static library at configure time"),
    ("thunks", ["build-thunks.py"],
     [paths.GUEST_VULKAN, paths.GUEST_AAUDIO],
     "the guest halves of the vulkan and audio thunks, staged beside glibc"),
    ("host", ["build-host.py"],
     [paths.HOST_LIBRARY, paths.HOST_SHELL],
     "the library the app loads, and the same thing reached from a shell"),
    ("guests", ["build-guests.py"],
     [paths.BUILD_GUESTS / "hello-libc", paths.BUILD_GUESTS / "vkswap"],
     "the x86-64 test guests the regression gate runs"),
    ("apk", ["build-apk.py"],
     None,  # the APK's own name depends on the identity, so the step decides
     "the app, which collects everything above"),
]

# what --clean wipes. the source tree is untouched: everything a build step writes goes here.
CLEANED = [paths.BUILD_ADRENOTOOLS, paths.BUILD_HOST, paths.BUILD_GUESTS, paths.BUILD_VULKAN,
           paths.BUILD_BUNDLE]


def entry():
    parser = Parser(description="build everything in dependency order")
    parser.add_argument("--list", action="store_true",
                        help="print the steps and whether each is up to date, build nothing.")
    parser.add_argument("--force", action="store_true",
                        help="run every step even if what it produces is already there.")
    parser.add_argument("--only", metavar="STEP", nargs="+", default=None,
                        help="run only these: " + ", ".join(name for name, _, _, _ in STEPS))
    parser.add_argument("--clean", action="store_true",
                        help="wipe what the native steps write before building. the downloaded "
                             "guest libraries are left alone; they are a fetch, not a build.")
    parser.add_argument("--install", action="store_true",
                        help="install the APK when the APK step is reached.")
    vocabulary.add_package(parser)
    vocabulary.add_sharpemu(parser, help_suffix=" passed through to the APK step.")
    arguments = parser.parse_args()

    toolchain = tc.resolve()
    known = {name for name, _, _, _ in STEPS}
    wanted = arguments.only or list(known)
    unknown = [name for name in wanted if name not in known]
    if unknown:
        raise Refusal("no such step: {}. there is {}".format(
            ", ".join(unknown), ", ".join(name for name, _, _, _ in STEPS)))

    if arguments.list:
        for name, command, artefacts, why in STEPS:
            state = "up to date" if _done(artefacts) else "to build"
            say("  {:<14} {:<12} {}".format(name, state, why))
        say("")
        check_generated(toolchain)
        return

    if arguments.clean:
        step("cleaning")
        for directory in CLEANED:
            if directory.exists():
                say("  wiping {}".format(paths.relative(directory)))
                wipe(directory)

    check_generated(toolchain)

    ran = 0
    with Timer() as whole:
        for name, command, artefacts, why in STEPS:
            if name not in wanted:
                continue
            # the APK step decides for itself whether it is up to date, because its own name depends
            # on the identity it is being built under and because it is what installs.
            if _done(artefacts) and not arguments.force and not arguments.clean:
                say("")
                say("== {} is up to date".format(name))
                continue
            step(name + " -- " + why)
            with Timer() as one:
                _run_step(command + _extra_for(name, arguments))
            say("  {} took {}".format(name, one))
            ran += 1

    say("")
    say("{} step(s) built in {}".format(ran, whole))


def _extra_for(name, arguments):
    """the arguments this driver passes through, and only to the step they mean something to."""
    if name != "apk":
        return []
    passed = ["--install"] if arguments.install else []
    if arguments.sharpemu is not None:
        passed += ["--sharpemu", arguments.sharpemu]
    if arguments.package:
        passed += ["--package", arguments.package]
    elif arguments.release:
        passed.append("--release")
    return passed


def _run_step(command):
    argv = [sys.executable, str(HERE / command[0])] + command[1:]
    say("  $ py scripts\\{}".format(" ".join(command)))
    code = subprocess.run(argv, cwd=str(paths.ROOT)).returncode
    if code != 0:
        raise Refusal("{} exited {}".format(command[0], code))


def _done(artefacts):
    """whether a step's output is there at all. `None` means the step decides for itself."""
    if artefacts is None:
        return False
    return all(path.exists() and path.stat().st_size > 0 for path in artefacts)


def check_generated(toolchain):
    """say when the committed thunk sources no longer match the NDK's headers.

    **a report rather than a step.** the generated sources are committed and regenerating them is a
    change to the repository, so it is something a person decides to do -- but finding out that the
    NDK moved underneath them should not require anyone to think of asking.
    """
    argv = [sys.executable, str(HERE / "gen-thunks.py"), "--check"]
    result = subprocess.run(argv, cwd=str(paths.ROOT),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding="utf-8", errors="replace")
    if result.returncode == 0:
        say("the generated thunk sources match the NDK's headers")
    else:
        say("the generated thunk sources no longer match the NDK's headers.")
        say("run: py scripts/gen-thunks.py")


if __name__ == "__main__":
    main(entry)
