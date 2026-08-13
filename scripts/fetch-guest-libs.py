# stages an x86-64 glibc set for the guest to link against.
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

import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import paths
from sharpemu.shell import Refusal, ensure, main, produced, say, size, step, wipe, write_text
from sharpemu.vocabulary import Parser

MIRROR = "https://deb.debian.org/debian/pool/main"

# `wanted` names the archive prefixes to take out of each package. everything else in these -- the
# character-set modules, the locales, the documentation, the configuration -- is weight the guest's
# linker never looks at. the character-set modules would matter if glibc's own conversion were ever
# used; the runtime carries its own internationalisation library instead.
#
# `source` names the debian source package these came out of, and `licence` what they are under. both
# are there for NOTICE below rather than for the fetch, which is why every entry carries them
# including the one that contributes no library.
PACKAGES = [
    {"name": "libc6", "url": MIRROR + "/g/glibc/libc6_2.36-9+deb12u14_amd64.deb",
     "wanted": ["./lib/x86_64-linux-gnu/"], "libraries": "lib/x86_64-linux-gnu",
     "source": "glibc/2.36-9+deb12u14", "licence": "LGPL-2.1-or-later, and others"},
    {"name": "libc-bin", "url": MIRROR + "/g/glibc/libc-bin_2.36-9+deb12u14_amd64.deb",
     "wanted": ["./usr/bin/"], "libraries": None,
     "source": "glibc/2.36-9+deb12u14", "licence": "GPL-2.0-or-later, and others"},
    {"name": "libgcc-s1", "url": MIRROR + "/g/gcc-12/libgcc-s1_12.2.0-14+deb12u1_amd64.deb",
     "wanted": ["./lib/x86_64-linux-gnu/"], "libraries": "lib/x86_64-linux-gnu",
     "source": "gcc-12/12.2.0-14+deb12u1",
     "licence": "GPL-3.0-or-later WITH GCC-exception-3.1"},
    {"name": "libstdc++6", "url": MIRROR + "/g/gcc-12/libstdc++6_12.2.0-14+deb12u1_amd64.deb",
     "wanted": ["./usr/lib/x86_64-linux-gnu/"], "libraries": "usr/lib/x86_64-linux-gnu",
     "source": "gcc-12/12.2.0-14+deb12u1",
     "licence": "GPL-3.0-or-later WITH GCC-exception-3.1"},
    # the runtime's cryptography is a thin shim over OpenSSL: it opens libssl by soname and fails
    # hard with "no usable version of libssl was found" if it is not there. SharpEmu reaches that
    # path while constructing its runtime, before it opens anything of the game's, so this is not
    # optional the way a root filesystem's package would be.
    {"name": "libssl3", "url": MIRROR + "/o/openssl/libssl3_3.0.17-1~deb12u2_amd64.deb",
     "wanted": ["./usr/lib/x86_64-linux-gnu/"], "libraries": "usr/lib/x86_64-linux-gnu",
     "source": "openssl/3.0.17-1~deb12u2", "licence": "Apache-2.0"},
]

# what the notice beside the libraries is called. **it is written by the fetch because the fetch is
# what knows what it fetched**, and the packaging step refuses without it.
NOTICE = "licences.txt"

# the test binaries taken out of the packages rather than cross-compiled. they are dynamically
# linked x86-64 glibc executables built against the exact set staged here, which is a glibc the NDK
# cannot produce. this one starts, resolves, prints and exits without needing the character-set
# modules a conversion tool would.
TEST_BINARIES = ["getent"]

# the two files in the staged directory that debian did not put there. the thunk guest halves are
# assembled into the same directory, so a fetch has no business deleting them -- and one that did
# would break the next build several steps downstream of the cause.
GENERATED = (paths.GUEST_VULKAN.name, paths.GUEST_VULKAN_SONAME.name, paths.GUEST_AAUDIO.name)


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
        archive = downloads / package["url"].rsplit("/", 1)[-1]
        if archive.exists():
            say("  have {}".format(archive.name))
        else:
            _download(package["url"], archive)
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

    write_notice(output)

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


def write_notice(output):
    """the notice that travels with the libraries, wherever they go.

    **the APK carries these binaries, so it distributes them.** they are unmodified debian packages
    under licences that ask for a notice and for the corresponding source to be available, and both
    are answered by naming the exact package version and where debian keeps its source -- which is
    the same thing a person would need in order to build a replacement set themselves, since the
    whole point of a directory the guest's linker searches is that any compatible set works.

    **it is written into the library directory rather than beside it** so that it cannot be separated
    from what it describes: everything that moves this set -- the packaging step, `stage.py
    --guest-libs`, `stage.py --shell` -- moves whole directories.
    """
    lines = [
        "the x86-64 shared objects in this directory are what the guest's own dynamic linker",
        "searches. most of them are unmodified binaries from debian 12, redistributed unchanged.",
        "",
        "they are not part of sharpemu-android. the emulator and the game it runs link against them",
        "at runtime the way any linux program links against a system library, and this directory can",
        "be replaced with any compatible set.",
        "",
    ]
    for package in PACKAGES:
        lines.append("{}".format(package["name"]))
        lines.append("  {}".format(package["licence"]))
        lines.append("  binary  {}".format(package["url"]))
        lines.append("  source  https://sources.debian.org/src/{}/".format(package["source"]))
        lines.append("")
    lines += [
        "libvulkan.so, libvulkan.so.1 and libaaudio.so are not debian's. they are the guest halves",
        "of this project's own thunks, they are generated from the android NDK's headers by",
        "scripts/gen-thunks.py, and they are GPL-2.0-or-later like the rest of sharpemu-android.",
        "",
        "scripts/fetch-guest-libs.py writes this file and is where the list above lives.",
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


def _download(url, target):
    say("  fetching {}".format(url))
    with urllib.request.urlopen(url) as response, open(str(target), "wb") as handle:
        shutil.copyfileobj(response, handle)
    produced(target, target.name)


def _unpack(archive, into, wanted):
    """a debian package is an `ar` container holding one compressed tar of the files.

    **the ar container is read here rather than shelled out to.** windows ships an archiver that can
    read one, but it cannot create the symlinks debian puts in these packages under an unprivileged
    account, and it fails the whole extraction on the first one. reading the container directly and
    extracting only regular files under the wanted prefixes survives debian rearranging which paths
    happen to be links, and needs no external tool at all.
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
