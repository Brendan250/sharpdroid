# runs the host layer's regression set on a device, and exits non-zero when any mode fails.
#
#   py scripts/regression.py
#   py scripts/regression.py --no-stage      # the device is already up to date
#
# the modes themselves are `host/regression.sh`, which runs on the device. this is the half that runs
# here: it puts the freshly built binary, its guests and its libraries there first, so that a pass
# describes what was just built rather than whatever happened to be on the device.
#
# **a green result has to be a positive statement rather than the absence of a red one.** the verdict
# is the count of modes that reported passing, so an empty capture -- a device that went away, a
# binary that never started -- reads as failure and not as silence. this project has read a zero as
# success before, and the whole run looked clean while doing it.

import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpemu import device, paths, vocabulary
from sharpemu import toolchain as tc
from sharpemu.shell import main, say, step
from sharpemu.vocabulary import Parser

HERE = Path(__file__).resolve().parent


def entry():
    parser = Parser(description="run the host layer's regression set on a device")
    parser.add_argument("--no-stage", action="store_true",
                        help="run against what is already on the device.")
    vocabulary.add_common(parser)
    arguments = parser.parse_args()

    toolchain = tc.resolve().require("adb")
    attached = device.Device(toolchain, arguments.serial).require()

    if not arguments.no_stage:
        argv = [sys.executable, str(HERE / "stage.py"), "--shell"]
        if arguments.serial:
            argv += ["--serial", arguments.serial]
        say("$ py scripts/stage.py --shell")
        if subprocess.run(argv, cwd=str(paths.ROOT)).returncode != 0:
            say("")
            say("refused: staging failed, so nothing was run")
            sys.exit(2)

    step("the regression set")
    output = attached.shell("sh {}/regression.sh".format(device.SHELL_DIRECTORY), check=False)
    say(output.rstrip())

    passed = len(re.findall(r"(?m)^PASS", output))
    failed = len(re.findall(r"(?m)^FAIL", output))

    say("")
    say("regression: {} passed, {} failed".format(passed, failed))

    if failed:
        sys.exit(1)
    if passed == 0:
        say("no modes ran at all -- is the device connected, and is the binary staged?")
        sys.exit(1)


if __name__ == "__main__":
    main(entry)
