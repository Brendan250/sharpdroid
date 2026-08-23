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

import hashlib
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

# **and a digest of those changes after it**, `-dirty.a1b2c3d4`, which is what makes a dirty tree an
# identity rather than a word. without it every uncommitted state spells itself the same way, so two
# builds published from two different sets of edits compare equal -- and that comparison is the whole
# of what stops a bundle being the payload from before the edit you are about to test. it is a
# suffix on a suffix for the same reason the marker is one: nothing has to be taught to carry it.
_CHANGES = 8

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
        is why a release APK refuses one and a development APK only says so. it is a different
        answer to a build that records no commit at all: that one came from a published archive and
        there was never a checkout to ask.
        """
        return bool(split_commit(self.commit)[1])

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

    **it is here, in one function, because three places need the identical string.** the packaging
    step writes it into a build's metadata, and both the bundling step and the staleness comparison
    compute it again to compare against -- and a marker that one of them appends and another does not
    is a difference that reads as a stale build, or a stale build that reads as the checkout. neither
    could be told apart from the real thing by the person the answer is printed to.

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
    if dirt.returncode != 0 or not (dirt.stdout or "").strip():
        return commit
    return commit + DIRTY + "." + changes_digest(fork)


def changes_digest(fork):
    """one short hex string naming everything uncommitted in [fork].

    **the diff and the untracked files, and nothing that is ignored.** `git diff HEAD` covers what is
    modified, staged, or deleted against the commit; a file added and never told to git is in none of
    that and is most of what new work looks like here, so it goes in by name and by content. what
    `.gitignore` covers stays out, since the publish output lands inside this tree and hashing it
    would make every build its own snowflake.

    **the diff is read as bytes.** decoding it would fold newline conventions together and replace
    anything that is not text, which is a change to a file reading as no change at all.
    """
    digest = hashlib.sha256()
    diff = subprocess.run(["git", "-C", str(fork), "diff", "HEAD"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    digest.update(diff.stdout or b"")

    others = subprocess.run(["git", "-C", str(fork), "ls-files", "--others", "--exclude-standard",
                             "-z"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    names = sorted(name for name in (others.stdout or b"").split(b"\0") if name)
    for name in names:
        digest.update(name)
        digest.update(b"\0")
        path = Path(str(fork)) / name.decode("utf-8", "replace")
        try:
            with open(str(path), "rb") as handle:
                for block in iter(lambda: handle.read(1 << 20), b""):
                    digest.update(block)
        except OSError:
            # a name git lists and this cannot open is itself a state worth being distinct.
            digest.update(b"?")
    return digest.hexdigest()[:_CHANGES]


def split_commit(commit):
    """a recorded commit as the hash and the marker after it, either of which may be empty.

    a hash carries no hyphen, so the first one is where the marker begins -- the same split the app
    makes when it shortens a commit for a bug report.
    """
    mark = commit.find("-")
    if mark < 0:
        return commit, ""
    return commit[:mark], commit[mark:]


def compare_commit(recorded, checkout, fork, subject="the staged build"):
    """whether a build recording [recorded] is the checkout that answers [checkout].

    **both sides are the same string or the comparison means nothing**, which is why they come from
    one function: `rev-parse` alone makes a tree with edits in it indistinguishable from the commit it
    sits on, in both directions -- a build published before the edits reads as current, and a build
    published from them reads as current too.

    **and the marker is compared rather than noticed.** two sets of uncommitted changes are the same
    tree only when they are the same changes, which is exactly what the digest after `-dirty` says;
    without comparing it, the one state a person developing the fork is in all day would be the one
    state this cannot answer, and an answer nobody can act on is one they learn to skip.
    """
    was, was_dirty = split_commit(recorded)
    now, now_dirty = split_commit(checkout)
    # one of the two is abbreviated and which one is not fixed: a build records whatever `--short`
    # gave the machine that packaged it, and a hand-written `meta.json` may carry all forty.
    if not (now.startswith(was) or was.startswith(now)):
        return STALE, "{} was cut from {} and {} is at {}".format(subject, recorded, fork, checkout)
    if was_dirty == now_dirty:
        # both clean at one commit, or both carrying the same uncommitted changes.
        return MATCH, "{} is the fork checkout, {}".format(subject, checkout)
    if was_dirty and now_dirty:
        return STALE, (
            "{} was published from a different set of uncommitted changes to the ones in {} now, so "
            "the payload does not contain what you are about to test".format(subject, fork))
    if was_dirty:
        return STALE, (
            "{} was published from a working tree with changes in it and {} is clean at {}, so the "
            "payload holds changes the checkout does not".format(subject, fork, now))
    return STALE, (
        "{} is {} and {} has uncommitted changes on top of it, so the payload does not contain "
        "them".format(subject, recorded, fork))


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
