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
from pathlib import Path

from . import paths
from .shell import Refusal, write_text

# the app's declaration of which contracts it speaks.
_CONTRACT_SOURCE = paths.APP / "src" / "main" / "java" / "com" / "mircowuffwuff" / "sharpemu" / "SharpEmuBuild.java"

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
        """the two things that make a build runnable, refused here rather than on a device."""
        if not self.payload.exists():
            raise Refusal("{} declares a payload it does not contain: {}".format(
                self.directory, self.payload.name))
        if not (self.directory / "plugins").is_dir():
            raise Refusal("{} has no plugins/".format(self.directory))
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


def write_contents(directory, target):
    """the listing packaging puts beside a bundled build: a size and a path per line, tab-separated.

    it is packaging's file rather than part of the build format -- it is never extracted, and a
    build that is not an APK asset has no reason to carry one. what it buys is an unpack that knows
    how much it is about to write before it starts writing.
    """
    directory = Path(directory)
    lines = []
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            relative = path.relative_to(directory).as_posix()
            lines.append("{}\t{}".format(path.stat().st_size, relative))
    write_text(target, "\n".join(lines) + "\n")
    return len(lines)
