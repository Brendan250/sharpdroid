# fetches the build toolchain into this repository, and never anywhere else.
#
#   py scripts/fetch-toolchain.py                       what is present, what is missing, download nothing
#   py scripts/fetch-toolchain.py --install             fetch everything missing, about 1 GB
#   py scripts/fetch-toolchain.py --install --what jdk  one piece
#   py scripts/fetch-toolchain.py --install --force     fetch it again even if it is there
#
# **nothing here is installed machine-wide and `PATH` is never modified.** everything lands under
# `toolchain/`, which is what makes two checkouts on one machine independent and what makes the
# versions in `toolchain.json` mean something. a machine that would rather use a JDK, an android SDK,
# an NDK or a .NET SDK it already has points `SHARPEMU_ANDROID_JDK`, `SHARPEMU_ANDROID_SDK`,
# `SHARPEMU_ANDROID_NDK` or `SHARPEMU_ANDROID_DOTNET` at it, and this script leaves that piece alone.
#
# **the only prerequisite is Python itself.** it has to be, since something has to run this -- which
# is also why the download path here is `urllib` rather than anything fetched first.

import platform
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import paths, toolchain as tc
from sharpemu.shell import Refusal, ensure, main, run, say, size, step, warn, wipe
from sharpemu.vocabulary import Parser

_WINDOWS = platform.system() == "Windows"


def entry():
    parser = Parser(description="fetch the build toolchain into this repository")
    parser.add_argument("--install", action="store_true",
                        help="download and install. without it nothing is fetched and the plan is "
                             "printed instead.")
    parser.add_argument("--what", metavar="PIECE", nargs="+", default=None,
                        help="install only these: " + ", ".join(_PIECES))
    parser.add_argument("--force", action="store_true",
                        help="install a piece again even if it is already present.")
    arguments = parser.parse_args()

    tc.check_interpreter()
    toolchain = tc.Toolchain()

    wanted = arguments.what or list(_PIECES)
    unknown = [name for name in wanted if name not in _PIECES]
    if unknown:
        raise Refusal("no such piece: {}. there is {}".format(
            ", ".join(unknown), ", ".join(_PIECES)))

    say("toolchain: {}".format(paths.relative(paths.TOOLCHAIN)))
    say("versions:  {}".format(paths.relative(paths.TOOLCHAIN_JSON)))
    say("python:    {} at {}".format(platform.python_version(), sys.executable))
    say("")

    plan = []
    for name in wanted:
        piece = _PIECES[name]
        present = piece["present"](toolchain)
        marker = "present" if present else "missing"
        if present and arguments.force:
            marker = "present, reinstalling"
        say("  {:<16} {:<24} {}".format(name, piece["version"](toolchain), marker))
        if not present or arguments.force:
            plan.append(name)

    if not arguments.install:
        say("")
        if plan:
            say("to install: py scripts/fetch-toolchain.py --install")
        else:
            say("nothing missing")
        return

    if not plan:
        say("")
        say("nothing to do")
        return

    # the order is real: the android command-line tools need a JDK to run at all, and every SDK
    # package is installed by the tool they contain.
    for name in _PIECES:
        if name in plan:
            step("installing {}".format(name))
            _PIECES[name]["install"](toolchain, arguments.force)
            if not _PIECES[name]["present"](toolchain):
                raise Refusal(
                    "installing {} reported success and produced nothing. the usual cause is a "
                    "download that returned a page rather than an archive".format(name))

    step("what resolved")
    for label, where in toolchain.describe():
        say("  {:<24} {}".format(label, where))


# --- the pieces ----------------------------------------------------------------------------------


def _has_jdk(toolchain):
    return _quietly(lambda: toolchain.jdk)


def _has_cmdline_tools(toolchain):
    return _quietly(lambda: toolchain.sdkmanager)


def _has_ndk(toolchain):
    return _quietly(lambda: toolchain.ndk_bin)


def _has_cmake(toolchain):
    return _quietly(lambda: toolchain.cmake)


def _has_build_tools(toolchain):
    return _quietly(lambda: toolchain.build_tools)


def _has_platform(toolchain):
    return _quietly(lambda: toolchain.platform_jar)


def _has_platform_tools(toolchain):
    return _quietly(lambda: toolchain.adb)


def _has_dotnet(toolchain):
    return _quietly(lambda: toolchain.dotnet)


def _install_jdk(toolchain, force):
    """a JDK from Adoptium, unwrapped by one level.

    the archive holds a single `jdk-<version>` directory, and what everything else here expects is
    a fixed name -- otherwise the resolver would have to glob for a directory whose name changes
    with every security release.
    """
    target = paths.TOOLCHAIN / "jdk-{}-temurin".format(toolchain.jdk_version)
    archive = _download(toolchain.source("jdk"), "jdk-{}".format(toolchain.jdk_version))
    _unpack(archive, target, strip_one=True)


def _install_cmdline_tools(toolchain, force):
    """the android command-line tools, which must land at `cmdline-tools/latest/`.

    the archive unpacks as a bare `cmdline-tools/`, and `sdkmanager` refuses to run from there:
    it derives the SDK root from its own location and expects one more level naming the channel.
    """
    sdk = ensure(paths.TOOLCHAIN / "android-sdk")
    target = sdk / "cmdline-tools" / "latest"
    archive = _download(toolchain.source("cmdlineTools"), "cmdline-tools")
    _unpack(archive, target, strip_one=True)


def _install_sdk_package(package_name):
    def install(toolchain, force):
        _sdkmanager(toolchain, package_name(toolchain))
    return install


def _install_dotnet(toolchain, force):
    """the .NET SDK, through Microsoft's own installer script.

    the installer rather than a direct archive URL because the per-version download links move and
    the script is the supported way to ask for an exact version. it is pointed at `toolchain/` like
    everything else, so it installs nothing machine-wide and touches no `PATH`.
    """
    target = ensure(paths.TOOLCHAIN / "dotnet-sdk")
    if not _WINDOWS:
        raise Refusal(
            "installing the .NET SDK is only wired up for Windows here. fetch it yourself and "
            "point SHARPEMU_ANDROID_DOTNET at it")
    installer = _download(toolchain.source("dotnetInstall"), "dotnet-install")
    run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(installer),
         "-Version", toolchain.dotnet_version, "-InstallDir", str(target), "-NoPath"])


# the order is the dependency order, and the dictionary keeps it.
_PIECES = {}
_PIECES["jdk"] = {
    "version": lambda t: "temurin {}".format(t.jdk_version),
    "present": _has_jdk, "install": _install_jdk,
}
_PIECES["cmdline-tools"] = {
    "version": lambda t: "latest",
    "present": _has_cmdline_tools, "install": _install_cmdline_tools,
}
_PIECES["platform-tools"] = {
    "version": lambda t: "latest",
    "present": _has_platform_tools,
    "install": _install_sdk_package(lambda t: "platform-tools"),
}
_PIECES["ndk"] = {
    "version": lambda t: t.ndk_version,
    "present": _has_ndk,
    "install": _install_sdk_package(lambda t: "ndk;" + t.ndk_version),
}
_PIECES["cmake"] = {
    "version": lambda t: t.cmake_version,
    "present": _has_cmake,
    "install": _install_sdk_package(lambda t: "cmake;" + t.cmake_version),
}
_PIECES["build-tools"] = {
    "version": lambda t: t.build_tools_version,
    "present": _has_build_tools,
    "install": _install_sdk_package(lambda t: "build-tools;" + t.build_tools_version),
}
_PIECES["platform"] = {
    "version": lambda t: t.platform_version,
    "present": _has_platform,
    "install": _install_sdk_package(lambda t: "platforms;" + t.platform_version),
}
_PIECES["dotnet"] = {
    "version": lambda t: t.dotnet_version,
    "present": _has_dotnet, "install": _install_dotnet,
}


# --- fetching and unpacking ------------------------------------------------------------------------


def _download(url, label):
    """fetch one archive to a temporary file, with a progress line and a size assertion.

    a download that returns a redirect page rather than an archive is the failure that looks most
    like success here, so what comes back is checked for being an archive at all before it is
    unpacked, and the byte count is printed either way.
    """
    # **not every python can reach an https URL.** an interpreter built without `_ssl` -- the one
    # inside the NDK is one, which matters because it is the interpreter somebody reaches for once
    # the toolchain is already there -- imports `urllib` happily and then fails inside it, several
    # frames from the thing that is actually wrong. so it is named here instead.
    try:
        import ssl
    except ImportError:
        raise Refusal(
            "this python cannot fetch anything: it was built without the ssl module. run this with "
            "a python.org install ({} is at {})".format(platform.python_version(), sys.executable))

    say("  fetching {}".format(url))
    # the suffix is carried over from the URL, because one of these is a script that powershell
    # will only run under its own extension.
    suffix = "".join(Path(url.split("?")[0]).suffixes[-1:])
    target = Path(tempfile.gettempdir()) / "sharpemu-{}{}".format(label, suffix)
    context = ssl.create_default_context()
    request = urllib.request.Request(url, headers={"User-Agent": "sharpemu-android"})
    with urllib.request.urlopen(request, context=context) as response:
        total = int(response.headers.get("Content-Length") or 0)
        written = 0
        with open(str(target), "wb") as handle:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                handle.write(chunk)
                written += len(chunk)
                if total:
                    print("\r  {:>3}%  {}".format(written * 100 // total, size(written)),
                          end="", flush=True)
    print("\r  {}          ".format(size(written)), flush=True)
    if written == 0:
        raise Refusal("{} downloaded nothing".format(url))
    return target


def _unpack(archive, target, strip_one=False):
    """unpack into a directory that did not exist before, then move it into place.

    into a scratch directory first so that an interrupted unpack cannot leave a half toolchain
    under a name the resolver would accept. `strip_one` drops the single wrapper directory these
    archives are built with, which is what lets the resolver expect a fixed name.
    """
    target = Path(target)
    scratch = target.parent / (target.name + ".partial")
    wipe(scratch)
    ensure(scratch)
    if zipfile.is_zipfile(str(archive)):
        with zipfile.ZipFile(str(archive)) as zipped:
            zipped.extractall(str(scratch))
    else:
        import tarfile
        with tarfile.open(str(archive)) as tarred:
            tarred.extractall(str(scratch))

    source = scratch
    if strip_one:
        children = [child for child in scratch.iterdir()]
        if len(children) == 1 and children[0].is_dir():
            source = children[0]
        else:
            warn("expected one directory in the archive, found {}".format(len(children)))

    wipe(target)
    ensure(target.parent)
    shutil.move(str(source), str(target))
    wipe(scratch)
    if not _WINDOWS:
        _restore_executable_bits(target)
    say("  unpacked to {}".format(paths.relative(target)))


def _restore_executable_bits(root):
    """zip archives do not carry a unix mode, so the tools come out unrunnable elsewhere."""
    for path in Path(root).rglob("*"):
        if path.is_file() and (path.suffix in ("", ".sh") or path.parent.name == "bin"):
            path.chmod(path.stat().st_mode | 0o111)


def _sdkmanager(toolchain, package_name):
    """install one SDK package, and accept the licences it asks about.

    **`sdkmanager` exits zero having installed nothing** when a licence has not been accepted, which
    is the single most common shape of failure in this repository. the caller asserts the artefact
    afterwards; feeding the acceptance in is what stops it happening in the first place. running
    `--install` is the acceptance -- there is nowhere else for a person to give it, and a prompt
    here would read EOF under anything driving this script and take that for a yes.
    """
    environment = tc.java_home_environment(toolchain)
    say("  accepting SDK licences")
    _feed(toolchain, ["--licenses"], environment)
    say("  installing {}".format(package_name))
    _feed(toolchain, [package_name], environment)


def _feed(toolchain, arguments, environment):
    import subprocess
    argv = [str(toolchain.sdkmanager),
            "--sdk_root={}".format(toolchain.android_sdk)] + arguments
    process = subprocess.Popen(argv, stdin=subprocess.PIPE, env=environment)
    process.communicate(input=b"y\n" * 64)
    if process.returncode != 0:
        raise Refusal("sdkmanager exited {} for {}".format(
            process.returncode, " ".join(arguments)))


def _quietly(resolve):
    try:
        resolve()
        return True
    except Refusal:
        return False


if __name__ == "__main__":
    main(entry)
