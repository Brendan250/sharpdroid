# what a SharpEmu build is, on disk and on a device.
#
# `docs/build-format.md` is the format's definition and this is its reader. nothing here decides
# anything the document does not already say -- the point of the module is that one reader exists,
# rather than a packaging script and a staging script each parsing `meta.json` their own way and
# disagreeing about which build is newer.
#
# **the on-device name comes from the metadata, never from the directory.** a build packaged twice
# keeps the same directory name here, so `adb push` naming its target after the source is how
# yesterday's bytes end up under today's name. every caller asks for `folder_name` instead.
#
# **the contract range is read out of the app's own source**, so that a script cannot bless a build
# the app will refuse. two copies of that range would drift, and the failure would appear on a
# device rather than in a build.

import json
import re
import subprocess
from pathlib import Path

from . import paths
from .shell import Refusal

# the app's declaration of which contracts it speaks.
_CONTRACT_SOURCE = paths.APP / "src" / "main" / "java" / "com" / "mircowuffwuff" / "sharpdroid" / "SharpEmuBuild.java"

# **the marker a build's `commit` carries when the checkout it was published from had uncommitted
# changes in it**, which is the same one the APK's own commit wears. it is a suffix on the commit
# rather than a field beside it so that everything already reading one carries it without being
# taught to: the launch log, the build list, the About screen and this module's own comparisons.
DIRTY = "-dirty"

# absent fields and what they mean, straight out of the format document.
_DEFAULTS = {
    "name": None,        # the id
    "sharpemuVersion": "0",
    "packagedAt": 0,
    "hostContract": 0,   # refused
    "payload": "SharpEmu",
    "env": {},
    "notes": "",
    "author": "",
    "commit": "",
    "source": "",
}


class Build:
    """one build directory, and what its metadata says about it."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.meta_path = self.directory / "meta.json"
        if not self.meta_path.exists():
            raise Refusal("{} is not a build: no meta.json".format(self.directory))
        try:
            self.meta = json.loads(self.meta_path.read_text(encoding="utf-8"))
        except ValueError as bad:
            raise Refusal("{} has an unreadable meta.json: {}".format(self.directory, bad))

    def field(self, name):
        value = self.meta.get(name)
        if value in (None, ""):
            if name == "name":
                return self.id
            return _DEFAULTS.get(name)
        return value

    @property
    def id(self):
        return self.meta.get("id") or self.directory.name

    @property
    def version(self):
        return str(self.field("sharpemuVersion"))

    @property
    def packaged_at(self):
        return int(self.field("packagedAt") or 0)

    @property
    def contract(self):
        return int(self.field("hostContract") or 0)

    @property
    def commit(self):
        return str(self.field("commit") or "")

    @property
    def from_dirty_tree(self):
        """whether this build was published from a checkout with uncommitted changes in it.

        **no commit names such a build's source**, so nothing can reconstruct it from a clone -- which
        is why a shippable APK refuses one and a development APK only says so. it is a different
        answer to a build that records no commit at all: that one came from a published archive and
        there was never a checkout to ask.
        """
        return self.commit.endswith(DIRTY)

    @property
    def payload(self):
        """the executable inside the directory, which is what identity is measured against."""
        return self.directory / str(self.field("payload"))

    @property
    def folder_name(self):
        """what this build is called wherever it lands. never the directory's own name."""
        return "{}-{}-{}".format(self.id, self.version, self.packaged_at)

    @property
    def identity(self):
        return "{} {} (build {}, contract {})".format(
            self.id, self.version, self.packaged_at, self.contract)

    def check(self):
        """what makes a build runnable and what makes it a build, refused here rather than on a device."""
        if not self.payload.exists():
            raise Refusal("{} declares a payload it does not contain: {}".format(
                self.directory, self.payload.name))
        if not (self.directory / "plugins").is_dir():
            raise Refusal("{} has no plugins/".format(self.directory))
        # **an archive inside a build directory is a category error rather than clutter.** a zip is
        # how a build is distributed and a directory is how it runs, so one nested in the other is
        # the distribution format inside the runnable form. it matters because **everything that
        # copies a build copies it whole** -- the APK bundles it, a stage pushes it, and neither has
        # any reason to look at what it is carrying. a zip of a build directory left inside that
        # directory doubles the size of the APK bundling it, and nothing anywhere reports it.
        stray = sorted(path for path in self.directory.rglob("*.zip") if path.is_file())
        if stray:
            names = ", ".join(path.relative_to(self.directory).as_posix() for path in stray)
            raise Refusal(
                "{} contains an archive, and no build does: {}\n"
                "  a zip is how a build is distributed and the directory is how it runs. move it "
                "out of the build directory or delete it -- everything that copies a build copies "
                "this with it.".format(self.directory.name, names))
        low, high = contract_range()
        if not low <= self.contract <= high:
            raise Refusal(
                "{} declares host contract {} and this app speaks {}..{}".format(
                    self.directory.name, self.contract, low, high))
        return self

    def payload_size(self):
        return self.payload.stat().st_size


def contract_range():
    """the contract generations the app speaks, read from the app rather than repeated here."""
    if not _CONTRACT_SOURCE.exists():
        raise Refusal("cannot read the contract range: {} is missing".format(_CONTRACT_SOURCE))
    text = _CONTRACT_SOURCE.read_text(encoding="utf-8", errors="replace")
    low = re.search(r"CONTRACT_MIN\s*=\s*(\d+)", text)
    high = re.search(r"CONTRACT_MAX\s*=\s*(\d+)", text)
    if not low or not high:
        raise Refusal("cannot read CONTRACT_MIN and CONTRACT_MAX out of {}".format(
            _CONTRACT_SOURCE.name))
    return int(low.group(1)), int(high.group(1))


def find(directory):
    """every build directory directly under one place, newest first.

    newest is `packagedAt`, which is what the app orders by too. a directory without a `meta.json`
    is not a build and is passed over rather than refused -- the packaging output directory holds
    zips beside the directories they were made from.
    """
    directory = Path(directory)
    if not directory.is_dir():
        return []
    found = []
    for child in sorted(directory.iterdir()):
        if child.is_dir() and (child / "meta.json").exists():
            try:
                found.append(Build(child))
            except Refusal:
                continue
    return sorted(found, key=lambda b: b.packaged_at, reverse=True)


def newest(directory=None):
    """the most recently packaged build, or a refusal naming what to do about there being none."""
    directory = Path(directory) if directory else paths.BUILD_BUILDS
    found = find(directory)
    if not found:
        raise Refusal(
            "no build under {}. run: py scripts/package-build.py".format(paths.relative(directory)))
    return found[0]


def open_build(where):
    """a build from a directory or from a zip of one, whichever was named."""
    where = Path(where)
    if where.is_dir():
        return Build(where)
    if where.is_file() and where.suffix.lower() == ".zip":
        raise Refusal("{} is a zip. name the build directory beside it, or unpack it first".format(
            where.name))
    raise Refusal("not a build directory: {}".format(where))


# --- staleness, with three verdicts ---------------------------------------------------------------
#
# **"could not look" is an answer of its own.** an empty result that meant both "they agree" and "the
# comparison did not run" once reported silence as success for a whole round of testing, so the
# comparison returns one of three words and a caller has to handle all three.

MATCH = "match"
STALE = "stale"
UNKNOWN = "unknown"


def checkout_commit(fork):
    """what a build published from [fork] right now would record as its `commit`, or empty.

    **it is here, in one function, because two places need the identical string.** the packaging step
    writes it into a build's metadata and the staleness comparison computes it again to compare
    against -- and a marker that one of them appends and the other does not is a difference that
    reads as a stale build, or a stale build that reads as the checkout. neither could be told apart
    from the real thing by the person the answer is printed to.

    an empty answer means the checkout would not say, and is a *different* thing to a clean tree at
    no commit: every way of failing lands on it, and the caller decides what that is worth.
    """
    head = subprocess.run(["git", "-C", str(fork), "rev-parse", "--short", "HEAD"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          encoding="utf-8", errors="replace")
    if head.returncode != 0:
        return ""
    commit = (head.stdout or "").strip()
    if not commit:
        return ""

    # `status --porcelain` over `diff --quiet`, which answers about tracked files that differ and
    # says nothing about a file that was added and never committed -- and an added file is most of
    # what this fork's own work looks like before it is committed.
    dirt = subprocess.run(["git", "-C", str(fork), "status", "--porcelain"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          encoding="utf-8", errors="replace")
    if dirt.returncode == 0 and (dirt.stdout or "").strip():
        commit += DIRTY
    return commit


def split_commit(commit):
    """a recorded commit as the hash and the marker after it, either of which may be empty.

    a hash carries no hyphen, so the first one is where it ends -- the same split the app makes when
    it shortens a commit for a bug report.
    """
    if commit.endswith(DIRTY):
        return commit[:-len(DIRTY)], DIRTY
    return commit, ""


def compare_payload(build, remote_size):
    """whether the bytes on the device are the ones in this build.

    **the byte count, never the name.** a locally rebuilt build keeps its directory name, so a
    staging step that took "the folder is already there" for "the right bytes are already there"
    silently ran yesterday's build. one stat is the whole fix.
    """
    if remote_size is None:
        return UNKNOWN
    try:
        local = build.payload_size()
    except OSError:
        return UNKNOWN
    return MATCH if local == remote_size else STALE
