# builds libadrenotools for android arm64.
#
#   py scripts/build-adrenotools.py
#   py scripts/build-adrenotools.py --clean
#
# the checkout is never modified: cmake is pointed straight at it and writes into `build/`, so there
# is no wrapper project to keep in step with it.
#
# what comes out that this repository uses:
#
#   libadrenotools.a      adrenotools_open_libvulkan, which the host layer links
#   liblinkernsbypass.a   linked into the above
#   libmain_hook.so       the -z global shim the platform loader ends up calling
#   libhook_impl.so       the implementation behind it, holding the parameters
#
# the two shared objects are *runtime* dependencies, loaded by soname out of the app's native
# library directory rather than linked, which is why they are packaged into the APK. two more are
# built and deliberately not shipped: they back the file-redirect and GPU-mapping-import features,
# neither of which this project asks for.

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpdroid import native, paths
from sharpdroid import toolchain as tc
from sharpdroid.shell import Refusal, ensure, main, produced, say, step, wipe
from sharpdroid.vocabulary import Parser

# the API level the host layer and everything it links are built at.
API_LEVEL = 28


def entry():
    parser = Parser(description="build libadrenotools for android arm64")
    parser.add_argument("--clean", action="store_true", help="wipe the build directory first.")
    parser.add_argument("--build-type", default="Release", help="the cmake build type.")
    arguments = parser.parse_args()

    toolchain = tc.resolve().require("ndk", "cmake")
    source = find_source()

    if arguments.clean:
        say("wiping {}".format(paths.relative(paths.BUILD_ADRENOTOOLS)))
        wipe(paths.BUILD_ADRENOTOOLS)
    ensure(paths.BUILD_ADRENOTOOLS)

    step("configuring")
    native.configure(
        toolchain, source, paths.BUILD_ADRENOTOOLS,
        api_level=API_LEVEL, stl="c++_static", build_type=arguments.build_type)

    step("building")
    native.build(toolchain, paths.BUILD_ADRENOTOOLS)

    native.report(paths.BUILD_ADRENOTOOLS)
    produced(paths.ADRENOTOOLS_LIBRARY, "libadrenotools.a")


def find_source():
    """the libadrenotools checkout, and a refusal that names the command that fixes it.

    the submodule is the intended source. a workspace checkout beside the repository is accepted as
    well, which is the same fallback the host cmake project applies to FEX.
    """
    candidates = [paths.ROOT / "external" / "libadrenotools",
                  paths.ROOT.parent / "libadrenotools"]
    for candidate in candidates:
        if (candidate / "CMakeLists.txt").exists():
            # **the nested submodule is the whole namespace-bypass mechanism.** without it the
            # error cmake gives is an unhelpful one about a missing subdirectory, several steps
            # downstream of the thing that is actually missing.
            if not (candidate / "lib" / "linkernsbypass" / "CMakeLists.txt").exists():
                raise Refusal(
                    "{} has an empty lib/linkernsbypass. run: "
                    "git submodule update --init --recursive".format(candidate))
            return candidate
    raise Refusal(
        "no libadrenotools checkout at {}. run: git submodule update --init --recursive".format(
            " or ".join(str(c) for c in candidates)))


if __name__ == "__main__":
    main(entry)
