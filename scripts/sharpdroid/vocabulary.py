# the argument vocabulary every script shares, defined once so that parity is structural.
#
# **a value names a source; a flag never does.** `--game`, `--sharpemu` and `--driver` all read the
# same six answers, and each script accepts the ones that mean something for it and **refuses the
# rest by name** -- a script that quietly ignored a value it had no use for would be a script that
# ran something other than what it was told to.
#
#   existing              use what is already on the device. creates nothing, which is the whole
#                         difference from `build`
#   build                 produce one from the fork checkout now
#   none                  name nothing. for a driver that pins the platform's own over whatever the
#                         app has stored
#   <a PC path>           staged if the device has not got those bytes already, reused if it has
#   /storage/emulated/0/  used where it lies. nothing is staged and nothing is copied
#   omitted               the script names nothing, and whatever is downstream answers. for a game
#                         that means the app's own list
#
# **a bare name is not a value.** an id like `android` names a family rather than an artefact, so
# answering with the newest of that id would let an older build beat one that was just staged. the
# path is the artefact and the id is not.
#
# the arguments are added to a parser by the helpers below rather than spelled out per script,
# because the standing requirement is that no two arguments mean anything different across the set,
# and a convention that has to be remembered is one that drifts.

import argparse

from .shell import Refusal

EXISTING = "existing"
BUILD = "build"
NONE = "none"
OMITTED = "omitted"
PC_PATH = "pc path"
DEVICE_PATH = "device path"

# where android puts the shared storage a user can see. a value starting with one of these is a
# place on the device rather than a place here, and nothing about it is staged.
_DEVICE_PREFIXES = ("/storage/", "/sdcard/", "/data/")


class Source:
    """one parsed value: what kind of answer it is, and the path if it carries one."""

    def __init__(self, kind, raw=None):
        self.kind = kind
        self.raw = raw

    @property
    def is_path(self):
        return self.kind in (PC_PATH, DEVICE_PATH)

    @property
    def names_nothing(self):
        return self.kind in (NONE, OMITTED)

    def __str__(self):
        return self.raw if self.raw is not None else self.kind

    def __repr__(self):
        return "Source({}, {!r})".format(self.kind, self.raw)


def read(value):
    """turn one command-line value into a Source. never refuses -- a script decides what it takes."""
    if value is None or value == "":
        return Source(OMITTED)
    lowered = value.strip().lower()
    if lowered in (EXISTING, BUILD, NONE):
        return Source(lowered)
    text = value.strip().strip('"')
    if text.replace("\\", "/").startswith(_DEVICE_PREFIXES):
        return Source(DEVICE_PATH, text)
    return Source(PC_PATH, text)


def accept(source, kinds, argument):
    """refuse a value this script has no meaning for, by name and with the ones it does take.

    the message lists what is accepted rather than what is not, because the reader's next action is
    to pick one and a list of rejects does not help them do it.
    """
    if source.kind in kinds:
        return source
    accepted = ", ".join(k for k in (EXISTING, BUILD, NONE) if k in kinds)
    if PC_PATH in kinds and DEVICE_PATH in kinds:
        accepted += ", a path here or a path on the device" if accepted else "a path"
    elif PC_PATH in kinds:
        accepted += ", a path here" if accepted else "a path here"
    elif DEVICE_PATH in kinds:
        accepted += ", a path on the device" if accepted else "a path on the device"
    if OMITTED in kinds:
        accepted += ", or nothing at all"
    raise Refusal("{} does not take {!r}. it takes {}".format(argument, str(source), accepted))


# --- the arguments themselves --------------------------------------------------------------------
#
# one definition per argument, shared by every script that has one. the help text is written once
# for the same reason: two descriptions of one flag are two chances to describe it differently.

def add_game(parser, help_suffix=""):
    parser.add_argument(
        "--game", metavar="VALUE", default=None,
        help="which game to run: existing, none, a dump directory here, or one on the device. "
             "omitted names nothing, so the app shows its list." + help_suffix)


def add_sharpemu(parser, help_suffix=""):
    parser.add_argument(
        "--sharpemu", metavar="VALUE", default=None,
        help="which SharpEmu build: existing, build, none, a build directory or zip here, or one "
             "on the device." + help_suffix)


def add_driver(parser, help_suffix=""):
    parser.add_argument(
        "--driver", metavar="VALUE", default=None,
        help="which GPU driver: existing, none to pin the platform's own, or a driver package "
             "here." + help_suffix)


def add_package(parser):
    """which app everything is done to.

    **the debug identity is the default everywhere.** it is a separate app to android, with its own
    storage and its own save data, so nothing done while developing can disturb a personal install.
    the release identity is the thing you ask for.
    """
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--package", metavar="ID", default=None,
        help="the application id to act on. defaults to the debug app.")
    group.add_argument(
        "--release", action="store_true",
        help="act on the release app rather than the debug one.")


def add_common(parser):
    """what every script carries, so that none of them is the odd one out."""
    parser.add_argument(
        "--serial", metavar="SERIAL", default=None,
        help="which device, when more than one is attached. defaults to the only one.")


class Parser(argparse.ArgumentParser):
    """an ArgumentParser that refuses the way everything else here does.

    argparse's own error path prints usage and exits 2 with a message that does not read like the
    rest of the output. routing it through a refusal keeps one voice, and keeps the exit code
    meaning the same thing everywhere: this refused and told you why.
    """

    def error(self, message):
        raise Refusal("{}\n\n{}".format(message, self.format_usage().strip()))
