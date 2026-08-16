# builds the APK, with exactly one SharpEmu build and the guest's x86-64 libraries inside it.
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
# **the guest libraries always ship and there is no argument to say otherwise.** a build is chosen
# because a person picks between several; the x86-64 set the guest's own linker searches is the one
# right set for a given APK, so the only question it could answer is "should this APK be able to run
# a game at all". missing, it is a refusal naming the fetch.
#
# **the native libraries are not built here.** build the host layer and libadrenotools first; the
# gradle build collects their output.

import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import builds, device, paths, vocabulary
from sharpemu import toolchain as tc
from sharpemu.shell import (Refusal, capture, ensure, fresh, main, produced, run, say, size, step,
                            tree_size, wipe, write_text)
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
    # **emptied once, here, rather than by whichever step runs first.** two asset trees are staged
    # into it and each would otherwise wipe the other's work depending on the order they were called
    # in -- a failure that would show up as an APK missing whichever one was packed first.
    fresh(paths.BUILD_BUNDLE)
    bundled = stage_bundle(arguments.sharpemu, package)
    guest_libraries = stage_guest_libs()

    write_local_properties(toolchain)
    keystore(toolchain)

    step("gradle")
    build_with_gradle(toolchain, package, label, arguments.offline)
    collect(apk)
    verify(apk, bundled, guest_libraries)

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


def stage_bundle(wanted, package):
    """assemble the asset tree for the one build that ships inside this APK.

    **exactly one ships, and it is a plain directory tree rather than a zip.** a zip inside an APK is
    an archive inside an archive that already is one, so the payload would be compressed twice and
    the device would pay to undo both.

    **the tree is staged under `build/` and never into the source tree**, so "which build is in this
    APK" is answered by the argument and never by what somebody left lying around. it is emptied on
    every build, which is what stops yesterday's bundle riding along in today's APK.
    """
    step("the bundled build")
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
    count = write_contents(asset)

    total = tree_size(asset)
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


# --- the guest libraries ---------------------------------------------------------------------------


def stage_guest_libs():
    """assemble the asset tree for the x86-64 shared objects the guest's own linker searches.

    **the APK is where these come from, and there is no way to build one without them.** they used to
    reach a device over `adb` alone, which meant a release install could not start a game and a data
    wipe put a working install in that same state -- the platform's own wipe takes the external files
    directory, which is where they were. so the refusal here is what keeps the failure on this
    machine, where the fetch that fixes it can be named.

    **the identity is computed here rather than on the device.** the set has no natural version:
    twenty-five files come from fixed debian packages and the rest are the thunks' guest halves that
    this repository builds, so nothing but the content names the whole of it. a hash of 12 MB
    measures 15 to 20 ms on the device warm, and the app has to answer "is what is unpacked what we
    ship" on every single launch -- so it is computed once, here, and shipped beside the tree as one
    short string the app compares against the stamp its own unpack wrote.
    """
    step("the guest libraries")
    asset = paths.BUILD_BUNDLE / "guest-libs"

    source = paths.GUEST_LIBS_X86_64
    libraries = sorted(p for p in source.iterdir() if p.is_file()) if source.is_dir() else []
    if not libraries:
        raise Refusal(
            "nothing to bundle: no x86-64 guest libraries under {}. without them an installed app "
            "cannot start a game at all.\n"
            "  py scripts/fetch-guest-libs.py      fetches the glibc set out of debian packages\n"
            "  py scripts/build-thunks.py          builds the two the thunks contribute".format(
                paths.relative(source)))
    for needed in ("ld-linux-x86-64.so.2", "libc.so.6"):
        if not (source / needed).exists():
            raise Refusal("{} is not in {} -- the guest cannot start without it. run: py "
                          "scripts/fetch-guest-libs.py".format(needed, paths.relative(source)))
    # **the notice and the texts are required rather than packed if they happen to be there.** most
    # of this set is unmodified debian binaries under licences that ask that a recipient be given the
    # terms and be able to get the source, and an APK is where they are distributed -- so a set
    # assembled before the fetch started writing these must not quietly ship without them.
    #
    # the index alone is not enough to check for: it is one small file, and the directory beside it
    # is where the copyright statements and the full licence texts are. a set carrying the index and
    # an empty directory would pass a test that only asked about the index.
    missing = [name for name in ("licences.txt", "licences/glibc.copyright",
                                 "licences/gcc-12.copyright", "licences/openssl.copyright",
                                 "licences/LGPL-2.1", "licences/GPL-3", "licences/Apache-2.0")
               if not (source / name).exists()]
    if missing:
        raise Refusal(
            "{} is missing {} -- most of this set is redistributed debian binaries and the APK is "
            "what distributes them, so it ships the terms and the source pointers with them. run: "
            "py scripts/fetch-guest-libs.py".format(
                paths.relative(source), ", ".join(missing)))

    # **the whole directory rather than the files in it.** the set is not only shared objects -- the
    # notice sits beside them and `licences/` under it -- and a copy that took files would drop the
    # subdirectory silently, which is how the terms these binaries are redistributed under would go
    # missing without anything failing. what the fetch writes is what ships.
    ensure(asset)
    shutil.copytree(str(source), str(asset), dirs_exist_ok=True)
    drop_unpackable(asset)

    # the listing first, then the identity -- so the identity is not in the listing, is not extracted,
    # and is not hashing itself.
    count = write_contents(asset)
    identity = content_hash(asset)
    write_text(asset / "identity", identity + "\n")

    total = tree_size(asset)
    say("  {} in {} files, from {}".format(size(total), count, paths.relative(source)))
    say("  identity {}".format(identity[:16]))
    return count


def content_hash(asset):
    """one hex string naming the whole content of an asset tree.

    every file's path and byte count go into the digest beside its bytes, so a renamed file is a
    different set rather than the same one -- which matters here, where the linker finds a library by
    the name it is filed under and two of the names are sonames that nothing else spells out.
    """
    digest = hashlib.sha256()
    for path in sorted(asset.rglob("*")):
        if not path.is_file():
            continue
        digest.update(path.relative_to(asset).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(path.stat().st_size).encode("ascii"))
        digest.update(b"\0")
        with open(str(path), "rb") as handle:
            for block in iter(lambda: handle.read(1 << 20), b""):
                digest.update(block)
    return digest.hexdigest()


def write_contents(asset):
    """the listing an unpack reads before it starts: a size and a path per line, tab-separated.

    **it is packaging's file rather than part of anything's format**, and it is what buys an unpack
    that knows how much it is about to write before it writes any of it -- an asset in the APK has no
    length to ask for, and `AssetManager.list` returns names without saying which are directories.

    it is written after the walk, so it never appears in its own listing and is therefore never
    extracted. anything else that must stay in the APK and off the device is written after this.
    """
    lines = []
    for path in sorted(asset.rglob("*")):
        if path.is_file():
            lines.append("{}\t{}".format(path.stat().st_size,
                                         path.relative_to(asset).as_posix()))
    write_text(asset / "contents", "\n".join(lines) + "\n")
    return len(lines)


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

    # the build-tools revision, for the same reason as the STL above: it is declared in
    # toolchain.json, and AGP's own default is a different revision that gradle downloads on demand.
    # unstated, the APK is packaged by a revision this repository never asked for and the fetched one
    # sits unused.
    arguments = [str(launcher), ":app:assembleDebug",
                 "-PsharpemuStlSo=" + str(stl),
                 "-PsharpemuBuildTools=" + toolchain.build_tools_version,
                 "-PsharpemuBundleAssets=" + str(paths.BUILD_BUNDLE),
                 "-PsharpemuApplicationId=" + package,
                 "-PsharpemuAppLabel=" + label,
                 "-PsharpemuCommit=" + repository_commit(),
                 "-PsharpemuFexVersion=" + fex_version()]
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


def repository_commit():
    """the commit this APK is built from, short, with a marker when the tree is not clean.

    the app puts it beside its version on the About screen, and what it is for is a bug report: the
    version alone names a fortnight of commits, so a report against one is a report against whichever
    of them the reporter happened to install.

    **an empty answer is a supported state and not a failure.** a source archive carries no `.git`, and
    a machine may have no `git` on its path at all -- neither is a reason to refuse a build, and the
    screen is written to show the version alone when this says nothing. so every way of not knowing
    lands on the same empty string rather than on an exception or on a word like "unknown", which would
    be a string somebody quotes into a report and then tries to resolve.

    **the dirty marker is the half that earns this.** an APK built from a working tree with edits in it
    is not the commit it names, and it is the ordinary case during development -- so a commit printed
    without one would be the most confident wrong answer this project could put on a screen.
    """
    head = subprocess.run(["git", "-C", str(paths.ROOT), "rev-parse", "--short", "HEAD"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          encoding="utf-8", errors="replace")
    if head.returncode != 0:
        return ""
    commit = head.stdout.strip()
    if not commit:
        return ""

    # `status --porcelain` over `diff --quiet`, because the second answers about tracked files that
    # differ and says nothing about a file that was added and never committed. the submodules are
    # excluded: a submodule at a commit other than the recorded one is a real thing to know about and
    # it is not what this line claims, which is what the app's own sources were.
    dirt = subprocess.run(["git", "-C", str(paths.ROOT), "status", "--porcelain",
                           "--ignore-submodules=all"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          encoding="utf-8", errors="replace")
    if dirt.returncode == 0 and dirt.stdout.strip():
        commit += "-dirty"
    return commit


def fex_version():
    """the FEXCore the host layer is linked against, in FEX's own release naming.

    FEX tags a release a month and names it for the month -- `FEX-2607` -- so `describe` against the
    tags is the version a person would quote, and it degrades by itself: a commit past a tag is that
    tag with a count and an abbreviated hash after it, which is still the right answer and still
    readable.

    **the dirty marker is the one this repository should never see.** FEX is a pinned submodule that
    is never modified, so a suffix here is not a stale working tree -- it is that rule having been
    broken, and the About screen is a reasonable place for it to surface.

    empty when there is no git, no submodule or no tags, for the reason `repository_commit` is: not
    knowing is a state the screen is written for, and a placeholder is a string somebody would try to
    resolve.
    """
    described = subprocess.run(["git", "-C", str(paths.ROOT / "external" / "FEX"),
                                "describe", "--tags", "--dirty"],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               encoding="utf-8", errors="replace")
    return described.stdout.strip() if described.returncode == 0 else ""


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


def verify(apk, bundled, guest_libraries):
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

    # **the guest libraries, before the build.** an APK without them installs, lists games and starts
    # nothing -- the guest's own interpreter is missing, so the failure is several layers below
    # anything the app prints, which is exactly how this shipped unnoticed for as long as it did.
    for name in ("assets/guest-libs/contents", "assets/guest-libs/identity",
                 "assets/guest-libs/ld-linux-x86-64.so.2", "assets/guest-libs/libc.so.6",
                 "assets/guest-libs/libvulkan.so.1", "assets/guest-libs/libaaudio.so",
                 # and the terms they are redistributed under, asserted in the archive rather than
                 # only in the tree it was assembled from.
                 "assets/guest-libs/licences.txt",
                 "assets/guest-libs/licences/glibc.copyright",
                 "assets/guest-libs/licences/gcc-12.copyright",
                 "assets/guest-libs/licences/openssl.copyright",
                 "assets/guest-libs/licences/LGPL-2.1", "assets/guest-libs/licences/GPL-3",
                 "assets/guest-libs/licences/Apache-2.0"):
        if name not in sizes or sizes[name] <= 0:
            raise Refusal("packaging failed: {} is missing or empty in the APK".format(name))
    check_listing(sizes, "guest-libs")
    say("  {:>12,}  the guest libraries, {} files".format(
        sum(count for name, count in sizes.items() if name.startswith("assets/guest-libs/")),
        guest_libraries))

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

    check_listing(sizes, "sharpemu")
    say("  {:>12,}  the bundled build, {} entries".format(
        sum(count for name, count in sizes.items() if name.startswith("assets/sharpemu/")),
        len(assets)))


def check_listing(sizes, tree):
    """**every line of one tree's listing, against what the archive actually holds.**

    the listing is what the app walks on the first launch that needs the tree, so a name in it that
    was never packaged is an unpack that aborts after writing most of the files -- a failure the
    device would find and this machine can.
    """
    listing = (paths.BUILD_BUNDLE / tree / "contents").read_text(encoding="utf-8")
    for line in listing.splitlines():
        if not line.strip():
            continue
        name = "assets/{}/{}".format(tree, line.split("\t", 1)[1])
        if name not in sizes:
            raise Refusal(
                "packaging failed: {}'s listing names {} and the APK has no such entry".format(
                    tree, name))


if __name__ == "__main__":
    main(entry)
