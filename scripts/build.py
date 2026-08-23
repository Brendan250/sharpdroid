# builds everything, in the order the pieces actually need each other.
#
#   py scripts/build.py                 build whatever is missing
#   py scripts/build.py --list          what the steps are and what each will do
#   py scripts/build.py --force         fetch again as well, not only build
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
# **existence is a reason to skip a fetch and never a reason to skip a build**, and the difference is
# who knows about the inputs. what a fetch produces either is there or is not; what a build produces
# is stale the moment a source changes, and this driver cannot see that -- it would have to know every
# `.cpp`, every header, every `CMakeLists.txt` and the state of a submodule, which is precisely the
# job of the tool each step already runs. so a build step is entered every time and cmake, ninja or
# the compiler decides there is nothing to do.
#
# **that costs about six seconds across the four build steps and buys the failure they otherwise
# hide**: a step skipped because its output was already sitting there, an APK assembled around it, and
# a run whose log is missing the line the change added -- which reads as the change not working rather
# than as the change not being installed. entering a step that has nothing to do prints a line and
# returns; skipping one that does is a debugging round.
#
# the same reasoning is why a step asserts the artefact it was supposed to produce rather than
# trusting an exit code: a tool exiting cleanly having done nothing is the most common failure here.
#
# each step is run as the command a person would type, rather than imported and called. so what this
# prints is a transcript somebody can rerun a line of, and a step's own refusals arrive intact.

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpdroid import paths, vocabulary
from sharpdroid import toolchain as tc
from sharpdroid.shell import Refusal, Timer, main, say, step, wipe
from sharpdroid.vocabulary import Parser

HERE = Path(__file__).resolve().parent

# name, what it runs, what it produces, whether finding that output is on its own a reason to skip it,
# and one line saying why it is where it is in the order.
#
# **only the fetch is gated.** the four build steps run every time and let their own tool decide; see
# the note above for what that costs and what it buys. the artefacts are still listed for them,
# because that is what `--list` reports and what a step is checked against after it runs.
STEPS = [
    # the notice is in the gate beside the library, because the APK step refuses a set without one
    # -- so a checkout holding a set from before the fetch wrote it would otherwise skip the fetch
    # here and be refused four steps later for a file this step owns.
    ("guest-libs", ["fetch-guest-libs.py"],
     [paths.GUEST_LIBS_X86_64 / "libc.so.6", paths.GUEST_LIBS_X86_64 / "licenses.txt"], True,
     "the x86-64 glibc set the guest's own linker searches"),
    ("adrenotools", ["build-adrenotools.py"],
     [paths.ADRENOTOOLS_LIBRARY], False,
     "the host project imports this as a static library at configure time"),
    ("thunks", ["build-thunks.py"],
     [paths.GUEST_VULKAN, paths.GUEST_AAUDIO], False,
     "the guest halves of the vulkan and audio thunks, staged beside glibc"),
    ("host", ["build-host.py"],
     [paths.HOST_LIBRARY, paths.HOST_SHELL], False,
     "the library the app loads, and the same thing reached from a shell"),
    ("guests", ["build-guests.py"],
     [paths.BUILD_GUESTS / "hello-libc", paths.BUILD_GUESTS / "vkswap"], False,
     "the x86-64 test guests the regression gate runs"),
    ("apk", ["build-apk.py"],
     None, False,  # the APK's own name depends on the identity, so the step decides
     "the app, which collects everything above"),
]

# what --clean wipes. the source tree is untouched: everything a build step writes goes here.
CLEANED = [paths.BUILD_ADRENOTOOLS, paths.BUILD_HOST, paths.BUILD_GUESTS, paths.BUILD_VULKAN,
           paths.BUILD_BUNDLE]


def entry():
    parser = Parser(description="build everything in dependency order")
    parser.add_argument("--list", action="store_true",
                        help="print the steps and what each one will do, build nothing.")
    parser.add_argument("--force", action="store_true",
                        help="run the fetch step even though what it produces is already there. "
                             "the build steps run either way and their own tools decide.")
    parser.add_argument("--only", metavar="STEP", nargs="+", default=None,
                        help="run only these: " + ", ".join(name for name, _, _, _, _ in STEPS))
    parser.add_argument("--clean", action="store_true",
                        help="wipe what the native steps write before building. the downloaded "
                             "guest libraries are left alone; they are a fetch, not a build.")
    parser.add_argument("--install", action="store_true",
                        help="install the APK when the APK step is reached.")
    vocabulary.add_package(parser)
    vocabulary.add_sharpemu(parser, help_suffix=" passed through to the APK step.")
    arguments = parser.parse_args()

    toolchain = tc.resolve()
    known = {name for name, _, _, _, _ in STEPS}
    wanted = arguments.only or list(known)
    unknown = [name for name in wanted if name not in known]
    if unknown:
        raise Refusal("no such step: {}. there is {}".format(
            ", ".join(unknown), ", ".join(name for name, _, _, _, _ in STEPS)))

    if arguments.list:
        for name, command, artefacts, gated, why in STEPS:
            # **"always runs" rather than a freshness claim this cannot make.** for a build step the
            # honest answer is that its own tool decides, and reporting "up to date" here on the
            # strength of the output existing is the very thing that let a stale library ship.
            state = ("up to date" if _done(artefacts) else "to build") if gated else "always runs"
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
        for name, command, artefacts, gated, why in STEPS:
            if name not in wanted:
                continue
            # only a fetch is skipped on its output being there. a build step falls through to its
            # own tool, and the APK step has no fixed output name to look for anyway -- its name
            # depends on the identity it is built under, and it is also what installs.
            if gated and _done(artefacts) and not arguments.force and not arguments.clean:
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
    """whether a step's output is there at all.

    **it answers existence and never freshness**, which is why only a fetch is gated on it. `None` is
    a step with no fixed output name to look for.
    """
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
