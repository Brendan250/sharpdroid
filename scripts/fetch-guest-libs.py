# stages an x86-64 glibc set for the guest to link against, with the licences it travels under.
#
#   py scripts/fetch-guest-libs.py
#   py scripts/fetch-guest-libs.py --keep-packages
#
# SharpEmu published for linux-x64 links against a system glibc, so the guest's own
# ld-linux-x86-64.so.2 needs a directory of x86-64 shared objects to find. this builds that
# directory out of debian amd64 packages.
#
# **this is not a root filesystem.** it is a set of files one dynamic linker searches. there is still
# one process, one address space, no second root and no boundary crossing -- these libraries run
# under the CPU emulator exactly as the payload does, and their syscalls arrive at the host layer's
# dispatch exactly as the payload's do.
#
# **why an older debian rather than a newer one: the version rule is at-least, never exactly.** glibc
# symbol-versions everything, so any set newer than the highest version node the payload references
# works. measured on the published launcher those are well below what this set provides. taking the
# oldest set that clears them means the fewest new syscalls for the host layer to chase -- newer
# glibc leans harder on the newer clone and its relatives -- and it is the same set .NET's own
# support matrix is built against.
#
# **the whole library directory is taken rather than the eight names the payload references.** it is
# about 4 MB, and glibc opens its name-service modules behind the calls the runtime makes to find a
# home directory during startup, so a hand-picked list would fail late and obscurely rather than not
# at all.
#
# **the packages come from snapshot.debian.org, addressed by content hash.** the ordinary mirror
# keeps only the versions a suite currently references, so the exact filenames pinned below are
# retired the day debian publishes its next point release -- which would break the first step of
# every clone build, and would break it for a reason nobody would look for. snapshot keeps every
# version forever and addresses a file by its SHA-1, so a pin here cannot rot and cannot be
# ambiguous. it is slower than the mirror; it runs once per checkout.
#
# **the APK ships these binaries, so it distributes them.** most of the set is unmodified debian
# packages under the LGPL, the GPL with the GCC runtime exception, and Apache-2.0 -- all of which ask
# that a recipient be given the terms and be able to get the source. so the fetch also lays down
# `licences.txt` and a `licences/` directory holding each source package's own debian `copyright`
# statement and the full text of every licence they reference; `scripts/build-apk.py` refuses to
# package a set without them. the source itself is on snapshot at the URLs `licences.txt` names,
# permanently, for the same reason the binaries are.

import hashlib
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import paths
from sharpemu.shell import Refusal, ensure, main, produced, say, size, step, wipe, write_text
from sharpemu.vocabulary import Parser

# a file, by its SHA-1. that is snapshot's own address for one, so the URL and the integrity check
# are the same fact -- and the SHA-256 beside it is what says the archive handed back what debian
# published, rather than only what we asked for.
SNAPSHOT_FILE = "https://snapshot.debian.org/file/{}"

# the human-readable page for one source package at one version, listing its binaries *and its
# source*. this is what `licences.txt` points a recipient at, and it is permanent.
SNAPSHOT_PACKAGE = "https://snapshot.debian.org/package/{}/{}/"

# where this project's own source is, named in the notice because that is the other half of what a
# GPL notice is for: the recipient of an APK has to be able to get at what built it.
HOME = "https://github.com/sharpemu-android/sharpemu-android"

# snapshot is an archive rather than a CDN and can be slow to first byte. the whole set is under
# 6 MB, so a generous ceiling costs nothing and a stall that hangs a clone build forever costs a lot.
TIMEOUT_SECONDS = 300

# `wanted` names the archive prefixes to take out of each package. everything else in these -- the
# character-set modules, the locales, the documentation, the configuration -- is weight the guest's
# linker never looks at. the character-set modules would matter if glibc's own conversion were ever
# used; the runtime carries its own internationalisation library instead.
#
# `copyright` is debian's own statement of the copyright holders and terms for a source package, and
# `texts` are the licences those statements refer to. **two packages here contribute neither a
# library nor a binary and exist only for those**: `gcc-12-base`, because debian points every gcc
# runtime package's documentation directory at it by symlink and so neither `libstdc++6` nor
# `libgcc-s1` contains a `copyright` of its own -- the two strictest licences in the set, with no
# statement in their own archives -- and `base-files`, which is where debian keeps the licence texts
# that every `copyright` file refers to by path rather than quoting.
PACKAGES = [
    {"name": "libc6", "source": "glibc", "version": "2.36-9+deb12u14",
     "file": "libc6_2.36-9+deb12u14_amd64.deb",
     "sha1": "c534c6c7f912b61ca26b7cf4082235a0343b8751",
     "sha256": "ba4f88f73dbc3ae9055f3c20f4523bfdbaf1ad13ff95e258924f77d20b4fbedf",
     "wanted": ["./lib/x86_64-linux-gnu/", "./usr/share/doc/libc6/copyright"],
     "libraries": "lib/x86_64-linux-gnu",
     "copyright": "usr/share/doc/libc6/copyright",
     "licence": "LGPL-2.1-or-later, with parts under other terms"},
    {"name": "libc-bin", "source": "glibc", "version": "2.36-9+deb12u14",
     "file": "libc-bin_2.36-9+deb12u14_amd64.deb",
     "sha1": "2b3d6ee6b798b30a8b52ab719dc9cd59e79584e3",
     "sha256": "e667401af91fad95f15b3ebd25d1abd8373fd18b00dc32219678413170544e84",
     "wanted": ["./usr/bin/"], "libraries": None,
     "licence": "GPL-2.0-or-later, with parts under other terms",
     # it contributes one test binary and no library, so nothing of it reaches an APK.
     "shipped": False},
    {"name": "libgcc-s1", "source": "gcc-12", "version": "12.2.0-14+deb12u1",
     "file": "libgcc-s1_12.2.0-14+deb12u1_amd64.deb",
     "sha1": "c72798568b9086ecacf3d46e420c6d90b78ce50d",
     "sha256": "3016e62cb4b7cd8038822870601f5ed131befe942774d0f745622cc77d8a88f7",
     "wanted": ["./lib/x86_64-linux-gnu/"], "libraries": "lib/x86_64-linux-gnu",
     "licence": "GPL-3.0-or-later WITH GCC-exception-3.1"},
    {"name": "libstdc++6", "source": "gcc-12", "version": "12.2.0-14+deb12u1",
     "file": "libstdc++6_12.2.0-14+deb12u1_amd64.deb",
     "sha1": "b1d2dc0cd7bcd57fa4b1ea6dd27a40384d45b937",
     "sha256": "5cd3171216d4ab0fc911cfe9c35509bf2dd8f47761c43b7f6a4296701551a24d",
     "wanted": ["./usr/lib/x86_64-linux-gnu/"], "libraries": "usr/lib/x86_64-linux-gnu",
     "licence": "GPL-3.0-or-later WITH GCC-exception-3.1"},
    {"name": "gcc-12-base", "source": "gcc-12", "version": "12.2.0-14+deb12u1",
     "file": "gcc-12-base_12.2.0-14+deb12u1_amd64.deb",
     "sha1": "715f9e66f5f45930246f5881c6dfe75ab8c6f32c",
     "sha256": "1896a2aacf4ad681ff5eacc24a5b0ca4d5d9c9b9c9e4b6de5197bc1e116ea619",
     "wanted": ["./usr/share/doc/gcc-12-base/copyright"], "libraries": None,
     "copyright": "usr/share/doc/gcc-12-base/copyright",
     "shipped": False},
    # the runtime's cryptography is a thin shim over OpenSSL: it opens libssl by soname and fails
    # hard with "no usable version of libssl was found" if it is not there. SharpEmu reaches that
    # path while constructing its runtime, before it opens anything of the game's, so this is not
    # optional the way a root filesystem's package would be.
    {"name": "libssl3", "source": "openssl", "version": "3.0.17-1~deb12u2",
     "file": "libssl3_3.0.17-1~deb12u2_amd64.deb",
     "sha1": "90b136b407fad99f2a54656c060e57cfae5aa560",
     "sha256": "d97c29db9d9d1d125580be5d7b2e1170adb47e5a8b4481841718be95fa652e68",
     "wanted": ["./usr/lib/x86_64-linux-gnu/", "./usr/share/doc/libssl3/copyright"],
     "libraries": "usr/lib/x86_64-linux-gnu",
     "copyright": "usr/share/doc/libssl3/copyright",
     "licence": "Apache-2.0"},
    {"name": "base-files", "source": "base-files", "version": "12.4+deb12u15",
     "file": "base-files_12.4+deb12u15_amd64.deb",
     "sha1": "9127e8b37cee2d2f4be081488fc45c1aa155efb7",
     "sha256": "3eb1ea6d85488f488cc2a163b98ad640ef88cee4c79287cf14e361aaf6206f47",
     "wanted": ["./usr/share/common-licenses/"], "libraries": None,
     "texts": ["Apache-2.0", "GPL-2", "GPL-3", "LGPL-2.1"],
     "shipped": False},
]

# the test binaries taken out of the packages rather than cross-compiled. they are dynamically
# linked x86-64 glibc executables built against the exact set staged here, which is a glibc the NDK
# cannot produce. this one starts, resolves, prints and exits without needing the character-set
# modules a conversion tool would.
TEST_BINARIES = ["getent"]

# the two files in the staged directory that debian did not put there. the thunk guest halves are
# assembled into the same directory, so a fetch has no business deleting them -- and one that did
# would break the next build several steps downstream of the cause.
GENERATED = (paths.GUEST_VULKAN.name, paths.GUEST_VULKAN_SONAME.name, paths.GUEST_AAUDIO.name)

# the notice, and the directory of full texts it indexes. both travel with the libraries: everything
# that moves this set moves the directory, and `scripts/build-apk.py` refuses a set missing either.
NOTICE = "licences.txt"
LICENCES = "licences"


def entry():
    parser = Parser(description="stage an x86-64 glibc set for the guest")
    parser.add_argument("--keep-packages", action="store_true",
                        help="keep the downloaded packages. they are inputs; the staged tree is "
                             "what matters.")
    arguments = parser.parse_args()

    downloads = ensure(paths.GUEST_LIBS / "packages")
    work = ensure(paths.GUEST_LIBS / "work")
    output = ensure(paths.GUEST_LIBS_X86_64)
    binaries = ensure(paths.GUEST_LIBS / "bin")

    _clear_except(output, GENERATED)
    _clear_except(binaries, ())

    for package in PACKAGES:
        step(package["name"])
        archive = downloads / package["file"]
        if archive.exists():
            say("  have {}".format(archive.name))
        else:
            _download(package, archive)
        # **the cached copy is checked too, and that is not belt and braces**: a download interrupted
        # on a previous run leaves a short file that exists, and "it is already here" would then
        # unpack a truncated archive rather than fetch it again.
        _verify(package, archive)
        _unpack(archive, ensure(work / package["name"]), package["wanted"])

    step("the libraries")
    staged = 0
    for package in PACKAGES:
        if not package["libraries"]:
            continue
        source = work / package["name"] / package["libraries"]
        if not source.is_dir():
            raise Refusal("expected a library directory that is not there: {}".format(source))
        for path in sorted(source.iterdir()):
            if path.is_file() and ".so" in path.name:
                shutil.copyfile(str(path), str(output / path.name))
                staged += 1

    # the C++ runtime ships as a fully versioned file with the soname beside it as a symlink, and
    # the symlink is what was skipped above. the guest's linker searches by soname, so the name it
    # will actually ask for has to exist as a file.
    #
    # **renamed onto the soname rather than copied to it.** the soname is the only name anything ever
    # asks for -- a binary's DT_NEEDED carries it and never the fully versioned file -- so keeping
    # both was two megabytes of the set duplicated, which is a seventh of what the APK ships.
    for path in sorted(output.glob("libstdc++.so.6.*")):
        path.replace(output / "libstdc++.so.6")

    step("the licences")
    write_licences(output, work)

    step("the test binaries")
    for name in TEST_BINARIES:
        found = list((work).rglob("bin/" + name))
        if not found:
            raise Refusal("expected a test binary that is not there: {}".format(name))
        shutil.copyfile(str(found[0]), str(binaries / name))
        produced(binaries / name, name)

    wipe(work)
    if not arguments.keep_packages:
        wipe(downloads)

    total = sum(path.stat().st_size for path in output.iterdir() if path.is_file())
    say("")
    say("staged {} shared objects, {} -> {}".format(
        staged, size(total), paths.relative(output)))
    for name in GENERATED:
        if (output / name).exists():
            say("  kept {}, which is generated rather than fetched".format(name))


# --- the licences ------------------------------------------------------------------------------------


def write_licences(output, work):
    """debian's own copyright statements and the full text of every licence they refer to.

    **debian's `copyright` file is taken rather than a list written here.** it is the authoritative
    statement of who holds what and under which terms for a source package, it is already inside the
    archive being unpacked, and it cannot drift out of step with the binaries the way a table
    maintained by hand would. one file per *source* package, because that is the granularity debian
    writes them at -- `libstdc++6` and `libgcc-s1` come out of one statement, which is also the one
    neither of their own archives contains.

    **the texts are debian's too.** a `copyright` file refers to a licence by the path it lives at on
    a debian system rather than quoting it, so on its own it is a pointer to a file the recipient of
    an APK does not have. `base-files` is where those live and is fetched for exactly this.
    """
    licences = ensure(output / LICENCES)

    taken = {}
    for package in PACKAGES:
        if not package.get("copyright"):
            continue
        found = work / package["name"] / package["copyright"]
        if not found.is_file():
            raise Refusal(
                "{} was supposed to carry {} and does not. debian points some documentation "
                "directories at another package by symlink, which an unpack of regular files "
                "skips -- find the package that holds the statement and pin that one "
                "too".format(package["name"], package["copyright"]))
        target = licences / "{}.copyright".format(package["source"])
        shutil.copyfile(str(found), str(target))
        taken[package["source"]] = target.name

    texts = []
    for package in PACKAGES:
        for name in package.get("texts", []):
            found = work / package["name"] / "usr/share/common-licenses" / name
            if not found.is_file():
                raise Refusal("{} was supposed to carry the {} text and does not".format(
                    package["name"], name))
            shutil.copyfile(str(found), str(licences / name))
            texts.append(name)

    # every source package that actually ships a library has to have a statement, checked here
    # rather than trusted -- the whole failure this guards is one going missing quietly.
    for package in PACKAGES:
        if package.get("shipped", True) and package["source"] not in taken:
            raise Refusal("{} ships libraries and no copyright statement was taken for {}".format(
                package["name"], package["source"]))

    write_notice(output, texts)
    say("  {} copyright statement(s), {} licence text(s) -> {}".format(
        len(taken), len(texts), paths.relative(licences)))


def write_notice(output, texts):
    """the index over `licences/`, and the one file a person is likely to open first.

    it says what the set is, what each part is under, where the source of each is kept permanently,
    and -- the thing an LGPL recipient actually wants to know -- that the whole directory is
    replaceable with a set of their own, which is a property this design has anyway.
    """
    lines = [
        "the x86-64 shared objects in this directory are what the guest's own dynamic linker",
        "searches. most of them are unmodified binaries from debian 12, redistributed unchanged.",
        "",
        "they are not part of sharpemu-android. the emulator and the game it runs link against them",
        "at runtime the way any linux program links against a system library; nothing in the",
        "application itself links against them, and nothing could -- the application is arm64 code",
        "and these are x86-64.",
        "",
        "this directory can be replaced with any compatible set, and that is supported rather than",
        "merely possible. it is a plain directory of files searched by name: a set placed on the",
        "application's external storage is preferred over the one inside it, which is what",
        "`scripts/stage.py --guest-libs` writes, and the launch log names whichever answered. so a",
        "recipient who builds their own glibc can run the emulator against it without rebuilding",
        "anything of ours.",
        "",
        "each source package below names its own copyright statement, as debian writes it, and the",
        "place its source is kept. those URLs are permanent: snapshot.debian.org archives every",
        "version debian has ever published, and the binaries here were fetched from it by hash.",
        "",
    ]

    seen = set()
    for package in PACKAGES:
        if package["source"] in seen:
            continue
        seen.add(package["source"])
        binaries = [p for p in PACKAGES
                    if p["source"] == package["source"] and p.get("shipped", True)]
        if not binaries:
            continue
        lines.append("{} {}".format(package["source"], package["version"]))
        for one in binaries:
            lines.append("  package   {}  --  {}".format(one["name"], one["licence"]))
        lines.append("  copyright {}/{}.copyright".format(LICENCES, package["source"]))
        lines.append("  source    " + SNAPSHOT_PACKAGE.format(
            package["source"], package["version"]))
        lines.append("")

    lines += [
        "the full text of every licence those statements refer to is beside them:",
        "",
    ]
    for name in texts:
        lines.append("  {}/{}".format(LICENCES, name))
    lines += [
        "",
        "libvulkan.so, libvulkan.so.1 and libaaudio.so are not debian's. they are the guest halves",
        "of this project's own thunks, generated from the android NDK's headers by",
        "scripts/gen-thunks.py, and they are GPL-2.0-or-later like the rest of sharpemu-android.",
        "its source, including scripts/fetch-guest-libs.py, which writes this file and holds the",
        "pins above:",
        "",
        "  " + HOME,
    ]
    write_text(output / NOTICE, "\n".join(lines) + "\n")


def _clear_except(directory, keep):
    for path in Path(directory).iterdir():
        if path.name in keep:
            continue
        if path.is_dir():
            wipe(path)
        else:
            path.unlink()


def _download(package, target):
    url = SNAPSHOT_FILE.format(package["sha1"])
    say("  fetching {}".format(package["file"]))
    say("           {}".format(url))
    with urllib.request.urlopen(url, timeout=TIMEOUT_SECONDS) as response:
        with open(str(target), "wb") as handle:
            shutil.copyfileobj(response, handle)
    produced(target, target.name)


def _verify(package, archive):
    """the bytes on disk against the pin, refused rather than reported.

    snapshot addresses a file by its SHA-1, so asking for one is already most of an integrity check;
    the SHA-256 here is the half that answers a different question -- whether the archive handed back
    what debian published rather than merely something with the right SHA-1.
    """
    digest = hashlib.sha256()
    with open(str(archive), "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    if digest.hexdigest() != package["sha256"]:
        archive.unlink()
        raise Refusal(
            "{} is not the pinned package: expected SHA-256 {}, got {}. the file has been removed; "
            "run this again to fetch it".format(
                package["file"], package["sha256"], digest.hexdigest()))


def _unpack(archive, into, wanted):
    """a debian package is an `ar` container holding one compressed tar of the files.

    **the ar container is read here rather than shelled out to.** windows ships an archiver that can
    read one, but it cannot create the symlinks debian puts in these packages under an unprivileged
    account, and it fails the whole extraction on the first one. reading the container directly and
    extracting only regular files under the wanted prefixes survives debian rearranging which paths
    happen to be links, and needs no external tool at all.

    **skipping symlinks is not free of consequences and one of them is answered elsewhere**: debian
    points a package's documentation directory at another package's with one, so a `copyright` that
    is reached that way is simply absent here rather than an error. `write_licences` refuses when one
    it expected did not land.
    """
    member, data = _read_ar(archive)
    say("  {} holds {}, {}".format(archive.name, member, size(len(data))))

    import io
    extracted = 0
    with tarfile.open(fileobj=io.BytesIO(data)) as tarred:
        for entry in tarred:
            if not entry.isfile():
                continue
            if not any(entry.name.startswith(prefix) for prefix in wanted):
                continue
            source = tarred.extractfile(entry)
            if source is None:
                continue
            target = Path(into) / entry.name.lstrip("./")
            target.parent.mkdir(parents=True, exist_ok=True)
            with open(str(target), "wb") as handle:
                shutil.copyfileobj(source, handle)
            extracted += 1
    if extracted == 0:
        raise Refusal("nothing in {} matched {}".format(archive.name, ", ".join(wanted)))
    say("  extracted {} file(s)".format(extracted))


def _read_ar(archive):
    """the data member of an `ar` container, decompressed.

    the format is eight magic bytes and then, per member, a sixty-byte header whose last fields are
    a decimal size and a two-byte terminator, followed by the bytes, padded to an even length.
    """
    raw = Path(archive).read_bytes()
    if not raw.startswith(b"!<arch>\n"):
        raise Refusal("{} is not a debian package: no ar header".format(archive.name))
    offset = 8
    while offset + 60 <= len(raw):
        header = raw[offset:offset + 60]
        name = header[0:16].decode("ascii", "replace").strip()
        try:
            length = int(header[48:58].decode("ascii", "replace").strip())
        except ValueError:
            raise Refusal("{} has an unreadable ar member header".format(archive.name))
        body = raw[offset + 60:offset + 60 + length]
        if name.startswith("data.tar"):
            if name.endswith(".zst"):
                raise Refusal(
                    "{} uses zstd compression, which python cannot read. an older debian "
                    "release is what this set is pinned to".format(archive.name))
            return name, body
        offset += 60 + length + (length % 2)
    raise Refusal("{} has no data member".format(archive.name))


if __name__ == "__main__":
    main(entry)
