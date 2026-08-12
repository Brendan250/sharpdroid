# builds the APK, with exactly one SharpEmu build inside it.
#
#   py scripts/build-apk.py                              build as the debug app, bundling the newest build
#   py scripts/build-apk.py --install                    build, then install over whatever is there
#   py scripts/build-apk.py --release                    build under the manifest's own id and label
#   py scripts/build-apk.py --sharpemu none              no build ships; the APK carries no asset
#   py scripts/build-apk.py --sharpemu build\builds\...  bundle that one
#
# **this is the entry point and not a wrapper you may skip.** it resolves the SDK and the JDK, writes
# `local.properties` from what it found, and passes the identity this build should carry. gradle left
# alone finds its own SDK through the environment, which on a machine with two installs is exactly
# the silent disagreement the resolver exists to prevent.
#
# **the native libraries are not built here.** build the host layer and libadrenotools first; the
# gradle build collects their output.

import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import builds, device, paths, vocabulary
from sharpemu import toolchain as tc
from sharpemu.shell import (Refusal, capture, ensure, fresh, main, produced, run, say, size, step,
                            wipe, write_text)
from sharpemu.vocabulary import Parser

# what the app's own asset packer silently drops out of `assets/`. they are dropped here instead, so
# that the listing the app walks describes the tree the APK actually carries -- a name in the listing
# that was never packaged is a first launch that aborts part way through unpacking, on the device,
# with everything it needed to know available here.
def _ignored_by_the_packer(name, is_directory):
    return name.startswith(".") or name.endswith("~") or (is_directory and name.startswith("_"))


def entry():
    parser = Parser(description="build the APK")
    parser.add_argument("--install", action="store_true",
                        help="install the APK over whatever is on the device afterwards.")
    parser.add_argument("--name", metavar="LABEL", default=None,
                        help="the name under the launcher icon. defaults to the identity's own.")
    parser.add_argument("--offline", action="store_true",
                        help="hand gradle --offline, so everything resolves from its cache or the "
                             "build fails. the way to find out whether a dependency was fetched "
                             "that nobody declared.")
    vocabulary.add_package(parser)
    vocabulary.add_sharpemu(parser)
    vocabulary.add_common(parser)
    arguments = parser.parse_args()

    toolchain = tc.resolve().require("android_sdk", "jdk", "ndk", "build_tools")
    package = device.application_id(arguments.package, arguments.release)
    label = arguments.name or device.application_label(arguments.package, arguments.release)
    apk = paths.apk(package)

    say("application id: {}".format(package))
    say("label:          {}".format(label))
    say("APK:            {}".format(paths.relative(apk)))

    check_sdk_levels(toolchain)
    bundled = stage_bundle(toolchain, arguments.sharpemu, package)

    write_local_properties(toolchain)
    keystore(toolchain)

    step("gradle")
    build_with_gradle(toolchain, package, label, arguments.offline)
    collect(apk)
    verify(apk, bundled)

    say("")
    say("built: {}  {}".format(paths.relative(apk), size(apk.stat().st_size)))

    if arguments.install:
        step("installing")
        attached = device.Device(toolchain, arguments.serial).require()
        attached.install(apk, package)


# --- the assertions that belong on this machine ----------------------------------------------------


def check_sdk_levels(toolchain):
    """the one place two declarations of an SDK level overlap, asserted rather than assumed.

    `toolchain.json` decides which platform is installed and the app's own build file decides what
    it compiles against. a build file compiling against a platform that was never installed fails
    inside gradle with a message about a missing platform, a long way from either declaration.
    """
    build_file = paths.APP / "build.gradle.kts"
    compile_sdk = re.search(r"compileSdk\s*=\s*(\d+)", build_file.read_text(encoding="utf-8"))
    if not compile_sdk:
        raise Refusal("no compileSdk in {}".format(paths.relative(build_file)))
    installed = tc.sdk_version_of(toolchain)
    if int(compile_sdk.group(1)) != installed:
        raise Refusal(
            "{} compiles against SDK {} and the toolchain installs android-{}. bump one to match "
            "the other".format(paths.relative(build_file), compile_sdk.group(1), installed))


# --- the bundled build -----------------------------------------------------------------------------


def stage_bundle(toolchain, wanted, package):
    """assemble the asset tree for the one build that ships inside this APK.

    **exactly one ships, and it is a plain directory tree rather than a zip.** a zip inside an APK is
    an archive inside an archive that already is one, so the payload would be compressed twice and
    the device would pay to undo both.

    **the tree is staged under `build/` and never into the source tree**, so "which build is in this
    APK" is answered by the argument and never by what somebody left lying around. it is emptied on
    every build, which is what stops yesterday's bundle riding along in today's APK.
    """
    step("the bundled build")
    fresh(paths.BUILD_BUNDLE)
    asset = paths.BUILD_BUNDLE / "sharpemu"

    source = vocabulary.read(wanted)
    vocabulary.accept(source, (vocabulary.EXISTING, vocabulary.NONE, vocabulary.PC_PATH,
                               vocabulary.OMITTED), "--sharpemu")

    if source.kind == vocabulary.NONE:
        say("  no build ships in this APK, so a launch will use whatever is staged on the device")
        return None

    if source.kind in (vocabulary.EXISTING, vocabulary.OMITTED):
        # **omitted and `existing` are the same request here.** a script that never touches a device
        # has one place to look, and it is worth saying rather than assuming: this is the one word in
        # the vocabulary whose larder is on this machine rather than on the phone.
        #
        # **and nothing to bundle is a refusal.** producing a bundle-less APK quietly is the exact
        # failure that bundling by default exists to remove, so it must not happen by accident.
        found = builds.find(paths.BUILD_BUILDS)
        if not found:
            raise Refusal(
                "nothing to bundle: no packaged build under {}.\n"
                "  py scripts/package-build.py         packages the fork checkout\n"
                "  --sharpemu <build directory>        bundles one you already have\n"
                "  --sharpemu none                     ships an APK with no build in it".format(
                    paths.relative(paths.BUILD_BUILDS)))
        build = found[0]
        say("  {} is the most recent under {}".format(
            build.directory.name, paths.relative(paths.BUILD_BUILDS)))
    else:
        build = builds.open_build(Path(source.raw))

    build.check()
    check_provenance(build, package)

    ensure(asset)
    shutil.copytree(str(build.directory), str(asset), dirs_exist_ok=True)
    drop_unpackable(asset)
    write_bundle_meta(build, asset)
    count = builds.write_contents(asset, asset / "contents")

    total = sum(p.stat().st_size for p in asset.rglob("*") if p.is_file())
    say("  bundling {} {} {}, contract {}".format(
        build.id, build.version, build.commit or "no commit", build.contract))
    say("  {} in {} files, from {}".format(size(total), count, paths.relative(build.directory)))
    return build


def check_provenance(build, package):
    """the recorded submodule pointer has to name the commit a shippable APK's build was cut from.

    that pointer is the only thing that makes an APK reproducible from a clone: the build's own
    metadata records a commit from whatever the packager had checked out, while the submodule is what
    this repository claims shipped. shipping a build the pointer does not name publishes an APK whose
    contents nothing can reconstruct.

    **the debug app is not held to that, and the difference is publishability rather than rigour.**
    it installs under its own application id, reaches nobody, and there is no clone to reproduce it
    from -- and holding it to a release-grade gate is what would make bundling by default impossible,
    since every development build would stop until somebody had committed, pushed and moved the
    pointer. the mismatch is printed instead, so it is still known which commit is on the phone.
    """
    shippable = package != device.DEBUG_ID

    if not build.commit:
        if shippable:
            raise Refusal(
                "{} records no commit, so it was packaged from an archive and cannot say what "
                "source it came from. an APK bundling it is not reproducible from a clone -- "
                "package the build from the fork instead".format(build.directory.name))
        say("  the bundled build records no commit, so this APK is not reproducible from a clone")
        return

    pointer = recorded_submodule_commit()
    if pointer is None:
        if shippable:
            raise Refusal(
                "external/sharpemu is not a submodule of this repository, so there is no recorded "
                "commit to check {} against. run: git submodule update --init --recursive".format(
                    build.commit))
        say("  external/sharpemu is not a submodule here, so the bundled commit is unchecked")
        return

    if not pointer.startswith(build.commit):
        if shippable:
            raise Refusal(
                "{} was cut from {} and external/sharpemu is recorded at {}. an APK bundling this "
                "build would ship a commit this repository does not name. move the submodule onto "
                "it and stage the pointer:\n"
                "  git -C external/sharpemu fetch origin\n"
                "  git -C external/sharpemu checkout {}\n"
                "  git add external/sharpemu".format(
                    build.directory.name, build.commit, pointer[:7], build.commit))
        say("  the bundled build is {}; external/sharpemu is recorded at {} -- fine for a "
            "development APK, a shippable one would refuse this".format(build.commit, pointer[:7]))
    else:
        say("  the bundled build is {}, which external/sharpemu is recorded at".format(build.commit))


def recorded_submodule_commit():
    """the commit the submodule pointer names, read out of the index.

    the index entry rather than the object, because a submodule's commits do not live in this
    repository's own object database -- looking one up says nothing useful and writes to the error
    stream while doing it.
    """
    entry = capture(["git", "-C", str(paths.ROOT), "ls-files", "-s", "--", "external/sharpemu"],
                    check=False)
    found = re.match(r"^160000\s+([0-9a-f]{40})", entry.strip())
    return found.group(1) if found else None


def drop_unpackable(asset):
    """remove the names the asset packer would have declined, and say which went.

    removing them rather than filtering the listing keeps the extracted tree equal to what the APK
    carries. a build directory picks these up from whatever produced it, so this is not hypothetical.
    """
    for path in sorted(asset.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if _ignored_by_the_packer(path.name, path.is_dir()):
            say("  dropping {} -- the asset packer would not have shipped it".format(
                path.relative_to(asset).as_posix()))
            if path.is_dir():
                wipe(path)
            else:
                path.unlink()


def write_bundle_meta(build, asset):
    """the bundled build's identity, regenerated rather than copied.

    **what it drops is the packaging timestamp**, because exactly one of this build exists and
    nothing orders it against anything -- the field was provably doing no work for it, and its
    version, to a person, is its commit. **the name and the author are the other two the bundle
    decides for itself**: this build is the one the app came with, which is what a person needs to
    know and is exactly what it does not share with a staged copy of the same commit, so it is named
    for that here rather than in the build it was cut from, where the name would travel to every
    copy. the author is whoever produced the app, which the app's own screens say once.

    every value is given a default rather than allowed through as a JSON null, because the reader on
    the other side hands a null back as the four-character string "null".
    """
    meta = {
        "id": build.id,
        "name": "Bundled build",
        "sharpemuVersion": build.version,
        "hostContract": build.contract,
        "payload": build.payload.name,
        "env": build.field("env") or {},
        "notes": build.field("notes") or "",
        # the commit is how the app tells whether an app update brought a new build, so a bundle
        # without one re-extracts on any metadata change instead. that is the format's own answer
        # rather than a special case invented here.
        "commit": build.commit,
        "source": build.field("source") or "",
    }
    write_text(asset / "meta.json", json.dumps(meta, indent=4))


# --- gradle -----------------------------------------------------------------------------------------


def write_local_properties(toolchain):
    """gradle's own way of being told where the SDK is, written from what the resolver found.

    it is ignored by git: it holds a path that is true on one machine.
    """
    write_text(paths.ROOT / "local.properties", "\n".join([
        "# written by scripts/build-apk.py from what the toolchain resolver found. do not edit:",
        "# it is regenerated on every build, and it is ignored by git because it is true on one",
        "# machine.",
        "sdk.dir=" + str(toolchain.android_sdk).replace("\\", "\\\\"),
    ]) + "\n")


def keystore(toolchain):
    """a throwaway debug key, generated once and kept out of git.

    the app's build file names it rather than letting gradle use the per-user one, because that one
    is per machine: a device that already has this app refuses an update signed by a different key,
    and recovering costs an uninstall, which takes the app's save data with it.
    """
    path = paths.APP / "debug.keystore"
    if path.exists():
        return
    say("generating a debug keystore")
    run([toolchain.jdk / "bin" / ("keytool.exe" if tc.IS_WINDOWS else "keytool"),
         "-genkeypair", "-keystore", str(path), "-alias", "sharpemu",
         "-storepass", "android", "-keypass", "android",
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
         "-dname", "CN=sharpemu-android"])
    produced(path, "the debug keystore")


def gradle_environment(toolchain):
    """the environment gradle needs, including the one variable without which it does not start.

    **the temporary directory is not tidiness.** gradle's client talks to its daemon through a
    selector, and on windows the JDK builds that selector's wakeup pipe out of a unix-domain socket
    placed at an automatically assigned address under the temporary directory. on at least one
    machine this project is developed on, connecting to such a socket under the user's local
    application data fails, and the JDK reports it as being unable to establish a loopback
    connection -- which names TCP loopback and is the wrong component entirely: ordinary loopback
    sockets work fine there.

    it has to be the environment variable. the path is chosen by a native call, so neither of the
    two JVM properties that look like they would move it does. setting it costs nothing on a machine
    that does not have the problem.
    """
    temporary = ensure(paths.BUILD / "gradle" / "tmp")
    return tc.java_home_environment(toolchain, {"TEMP": temporary, "TMP": temporary})


def build_with_gradle(toolchain, package, label, offline):
    launcher = paths.ROOT / ("gradlew.bat" if tc.IS_WINDOWS else "gradlew")
    if not launcher.exists():
        raise Refusal("missing {}".format(paths.relative(launcher)))

    # the C++ runtime, from the resolver rather than from gradle's own idea of which NDK is
    # installed. the host layer links it dynamically and the copy in the APK has to be the one it
    # was linked against.
    stl = toolchain.ndk_sysroot / "usr" / "lib" / "aarch64-linux-android" / "libc++_shared.so"
    if not stl.exists():
        raise Refusal("libc++_shared.so is not where the NDK should have it: {}".format(stl))

    arguments = [str(launcher), ":app:assembleDebug",
                 "-PsharpemuStlSo=" + str(stl),
                 "-PsharpemuBundleAssets=" + str(paths.BUILD_BUNDLE),
                 "-PsharpemuApplicationId=" + package,
                 "-PsharpemuAppLabel=" + label]
    if offline:
        arguments.append("--offline")

    # **the previous APK is deleted so that a whole new archive is written rather than that one
    # edited.** the packaging step updates the archive in place, and an entry that changes size is
    # appended while the old bytes are left where they were -- so an APK rebuilt all day accumulates
    # holes nothing ever reads. measured once at ten megabytes of dead space in a file whose entries
    # come to twenty-nine. it installs and runs perfectly, which is why it went unnoticed; it costs
    # a third of every install in the deploy loop, and it makes a recorded APK size depend on how
    # many times the APK was built. deleting one file costs a repackage and no recompilation.
    written = gradle_output()
    if written.exists():
        written.unlink()

    run(arguments, cwd=paths.ROOT, env=gradle_environment(toolchain))


def gradle_output():
    return paths.BUILD / "gradle" / "app" / "outputs" / "apk" / "debug" / "app-debug.apk"


def collect(apk):
    """copy the APK to the path every other script predicts.

    one rule for where an APK is, shared, rather than eight scripts each taught the build system's
    own output layout.
    """
    written = gradle_output()
    if not written.exists():
        raise Refusal("gradle reported success and {} is not there".format(
            paths.relative(written)))
    ensure(apk.parent)
    shutil.copyfile(str(written), str(apk))
    produced(apk, "the APK", quiet=True)


# --- what is actually in it ---------------------------------------------------------------------------


def verify(apk, bundled):
    """open the APK and assert what it holds, while the artefact is still on this machine.

    an APK missing its dex installs and then dies at a missing class; one missing the host layer
    installs and dies at a missing library. a missing driver hook is worse than either, because
    nothing fails: the driver loader falls back to the platform's own quietly, so a comparison
    between two drivers would measure one of them twice and report a difference of zero.
    """
    step("what is in the APK")
    with zipfile.ZipFile(str(apk)) as archive:
        sizes = {entry.filename: entry.file_size for entry in archive.infolist()}

    required = ["classes.dex",
                "lib/arm64-v8a/libsharpemu-host-layer.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libmain_hook.so",
                "lib/arm64-v8a/libhook_impl.so"]
    for name in required:
        if name not in sizes:
            raise Refusal("packaging failed: {} is not in the APK".format(name))
        if sizes[name] <= 0:
            raise Refusal("packaging failed: {} is empty".format(name))
        say("  {:>12,}  {}".format(sizes[name], name))

    # **the native libraries have to be stored rather than deflated**, and that is a hard requirement
    # of the driver path rather than a size preference: the driver loader opens its hooks by soname
    # out of the app's native library directory, which only holds real files when the installer
    # extracts them. getting it wrong fails by quietly falling back to the platform's driver.
    for name, count in sizes.items():
        if name.startswith("lib/arm64-v8a/") and count <= 0:
            raise Refusal("packaging failed: {} is empty".format(name))

    assets = [name for name in sizes if name.startswith("assets/sharpemu/")]
    if bundled is None:
        if assets:
            raise Refusal("packaging failed: no build was named and the APK carries one anyway")
        say("  no bundled build, as asked")
        return

    for name in ("assets/sharpemu/meta.json", "assets/sharpemu/contents",
                 "assets/sharpemu/" + bundled.payload.name):
        if name not in sizes or sizes[name] <= 0:
            raise Refusal("packaging failed: {} is missing or empty in the APK".format(name))
    if not any(name.startswith("assets/sharpemu/plugins/") for name in sizes):
        raise Refusal("packaging failed: the bundled build has no plugins/ in the APK")

    # **every line of the listing, against what the archive actually holds.** the listing is what the
    # app walks on first launch, so a name in it that was never packaged is a launch that aborts
    # after writing most of the tree -- a failure the device would find and this machine can.
    listing = (paths.BUILD_BUNDLE / "sharpemu" / "contents").read_text(encoding="utf-8")
    for line in listing.splitlines():
        if not line.strip():
            continue
        name = "assets/sharpemu/" + line.split("\t", 1)[1]
        if name not in sizes:
            raise Refusal(
                "packaging failed: the bundled build's listing names {} and the APK has no such "
                "entry".format(name))
    say("  {:>12,}  the bundled build, {} entries".format(
        sum(count for name, count in sizes.items() if name.startswith("assets/sharpemu/")),
        len(assets)))


if __name__ == "__main__":
    main(entry)
