# generates both halves of both thunks from the NDK's own headers.
#
#   py scripts/gen-thunks.py                # both
#   py scripts/gen-thunks.py --what vulkan
#   py scripts/gen-thunks.py --check        # regenerate into memory and report what would change
#
# **the output is committed and this is run when the NDK headers move**, not on every build. that is
# what lets the host half be compiled into the host layer and the guest half be assembled into a
# library without either being a build-time dependency on a header parser.
#
# **the two halves have to agree on a command id for every entry point**, so they are generated
# together from one list in one pass, and neither is hand-editable. the host half is included
# several times with different definitions of its macro to build an id enum and a dispatch table;
# the guest half is one 16-byte stub per command in the same order, so that the host resolves any
# guest entry point as the table's base plus sixteen times the id.
#
# what each produces:
#
#   vulkan/generated/vulkan_commands.inc     one VKCMD(name) per line
#   vulkan/generated/vulkan_stubs.S          623 stubs, an attach call and an .init_array entry
#   audio/generated/aaudio_commands.inc      one AACMD(name) per line
#   audio/generated/aaudio_protos.inc        one function pointer type per entry point
#   audio/generated/aaudio_stubs.S           72 stubs, and no attach call

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpdroid import paths
from sharpdroid import toolchain as tc
from sharpdroid.shell import Refusal, ensure, main, read_text, say, step, write_text
from sharpdroid.vocabulary import Parser

# the magic syscall numbers. two bytes of tag in the top half, the command id in the bottom -- see
# the thunk headers beside the generated files for why a syscall is the trap and why this range is
# free. the two tags are deliberately one range apart so that vulkan and audio stay decodable apart
# in a trace and in a crash.
VULKAN_MAGIC = 0x564B0000
AUDIO_MAGIC = 0x53410000

# the attach call's id, which is the top of the range rather than one past the commands: the guest's
# own ld.so runs it out of .init_array to hand the host the address of the stub table, and nothing
# else can, because only the guest knows where its dynamic linker put the library.
VULKAN_ATTACH = 0xFFFF


def entry():
    parser = Parser(description="generate both halves of the vulkan and audio thunks")
    parser.add_argument("--what", choices=("vulkan", "audio", "both"), default="both",
                        help="which thunk to generate.")
    parser.add_argument("--check", action="store_true",
                        help="generate into memory and report what would change, writing nothing.")
    arguments = parser.parse_args()

    toolchain = tc.resolve().require("ndk")
    changed = 0
    if arguments.what in ("vulkan", "both"):
        step("vulkan")
        changed += _emit(generate_vulkan(toolchain), arguments.check)
    if arguments.what in ("audio", "both"):
        step("audio")
        changed += _emit(generate_audio(toolchain), arguments.check)

    say("")
    if arguments.check:
        say("{} file(s) would change".format(changed))
        if changed:
            sys.exit(1)
    else:
        say("{} file(s) written".format(changed))


# --- vulkan ---------------------------------------------------------------------------------------


def generate_vulkan(toolchain):
    """the command list comes from the prototype declarations, not from the function pointer types.

    the header has ten more `PFN_` typedefs than it has commands, and the difference is callbacks --
    an allocation function, a debug callback -- which are not entry points at all.
    """
    header = toolchain.ndk_sysroot / "usr" / "include" / "vulkan" / "vulkan_core.h"
    if not header.exists():
        raise Refusal("missing vulkan_core.h: {}".format(header))
    text = read_text(header)

    version = re.search(r"#define\s+VK_HEADER_VERSION\s+(\d+)", text)
    if not version:
        raise Refusal("could not read VK_HEADER_VERSION from {}".format(header))
    version = version.group(1)

    # every command is declared exactly once, at the start of a line, in this shape:
    #   VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(
    commands = _unique(re.findall(
        r"(?m)^VKAPI_ATTR\s+[\w\*\s]+?\s+VKAPI_CALL\s+(vk\w+)\s*\(", text))
    if not commands:
        raise Refusal("no vulkan commands found in {}".format(header))
    if len(commands) >= VULKAN_ATTACH:
        raise Refusal("too many commands for a 16-bit id")

    say("  vulkan_core.h VK_HEADER_VERSION {}, {} commands".format(version, len(commands)))
    banner = _banner("vulkan_core.h, VK_HEADER_VERSION {}".format(version),
                     "{} commands".format(len(commands)))
    generated = ensure(paths.VULKAN_THUNK / "generated")

    listing = [banner, "// VKCMD(name) is defined by the includer."]
    listing += ["VKCMD({})".format(name) for name in commands]

    stubs = [banner, _VULKAN_PREAMBLE]
    stubs += [_stub(name, VULKAN_MAGIC + index) for index, name in enumerate(commands)]
    stubs.append(_VULKAN_ATTACH_STUB.format(magic=_immediate(VULKAN_MAGIC + VULKAN_ATTACH)))

    return {
        generated / "vulkan_commands.inc": _lines(listing),
        generated / "vulkan_stubs.S": _lines(stubs),
    }


# --- audio ----------------------------------------------------------------------------------------


def generate_audio(toolchain):
    """the same shape with a different header, and one extra output.

    **the function pointer types are generated here and vulkan's are not.** the vulkan header ships
    one per command, so that generator only has to emit the list. this header ships none, and taking
    the address of the declarations instead would drag in their availability attributes -- the host
    layer builds at API 28 and eleven of these arrive later than that, so referencing the
    declarations is a diagnostic waiting to happen and linking against them is impossible. a typedef
    carries the signature and never names the symbol, which is also why the host half resolves
    everything at runtime rather than linking it.
    """
    header = toolchain.ndk_sysroot / "usr" / "include" / "aaudio" / "AAudio.h"
    if not header.exists():
        raise Refusal("missing AAudio.h: {}".format(header))
    text = read_text(header)

    # every entry point is declared exactly once, starting at the beginning of a line and running to
    # the first semicolon, across as many lines as the prototype takes.
    blocks = re.findall(r"(?ms)^AAUDIO_API\s+(.*?);", text)
    if not blocks:
        raise Refusal("no AAUDIO_API declarations found in {}".format(header))

    commands, seen = [], set()
    for block in blocks:
        # one line, no nullability qualifiers, no availability attribute. the first two are clang
        # extensions that mean nothing to a function pointer typedef; the third is the whole reason
        # these are typedefs rather than references to the declarations.
        declaration = re.sub(r"__INTRODUCED_IN\s*\([^)]*\)", "", block)
        declaration = re.sub(r"\b_Nonnull\b|\b_Nullable\b", "", declaration)
        declaration = " ".join(declaration.split())

        # the first "(" starts the parameter list: no return type contains the text AAudio, and no
        # parameter is a bare function pointer -- the three callbacks are named typedefs.
        parsed = re.match(r"^(?P<ret>.*?)\s*(?P<name>AAudio\w+)\s*\((?P<params>.*)\)$", declaration)
        if not parsed:
            raise Refusal("could not parse declaration: {}".format(declaration))
        name = parsed.group("name")
        if name in seen:
            continue
        seen.add(name)
        commands.append((name,
                         re.sub(r"\s+\*", "*", parsed.group("ret")).strip(),
                         parsed.group("params").strip()))

    if len(commands) >= 0xFFFF:
        raise Refusal("too many commands for a 16-bit id")

    say("  AAudio.h, {} entry points".format(len(commands)))
    banner = _banner("aaudio/AAudio.h, NDK {}".format(toolchain.ndk_version),
                     "{} entry points".format(len(commands)))
    generated = ensure(paths.AUDIO_THUNK / "generated")

    listing = [banner, "// AACMD(name) is defined by the includer."]
    listing += ["AACMD({})".format(name) for name, _, _ in commands]

    protos = [banner,
              "// one function pointer type per entry point, so that the marshaller can deduce",
              "// the signature without the header's declarations ever being referenced."]
    protos += ["typedef {} (*PFN_{})({});".format(returns, name, params)
               for name, returns, params in commands]

    stubs = [banner, _AUDIO_PREAMBLE]
    stubs += [_stub(name, AUDIO_MAGIC + index)
              for index, (name, _, _) in enumerate(commands)]
    stubs.append('    .section .note.GNU-stack,"",@progbits')

    return {
        generated / "aaudio_commands.inc": _lines(listing),
        generated / "aaudio_protos.inc": _lines(protos),
        generated / "aaudio_stubs.S": _lines(stubs),
    }


# --- the shapes -------------------------------------------------------------------------------------
#
# each stub is identical except for one immediate, which is the entire point: the host reads the
# guest's arguments out of the spilled register state, so no stub ever has to know its own signature.
# the move into r10 is there because the syscall instruction destroys rcx, which is where the C
# calling convention put the fourth argument -- the same move every libc syscall wrapper makes.

_STUB = """    .balign 16
    .globl {name}
    .type {name},@function
{name}:
    movq %rcx, %r10
    movl ${magic}, %eax
    syscall
    ret
    .size {name}, .-{name}"""

_VULKAN_PREAMBLE = """// each stub is exactly 16 bytes and they are emitted in command-id order, so the host layer
// resolves any guest entry point as __sharpdroid_vk_stubs + 16 * id. that is what lets
// vkGetInstanceProcAddr return an address the guest may actually call.

    .text
    .balign 16
    .globl __sharpdroid_vk_stubs
    .hidden __sharpdroid_vk_stubs
__sharpdroid_vk_stubs:"""

_VULKAN_ATTACH_STUB = """    .balign 16
    .globl __sharpdroid_vk_attach
    .hidden __sharpdroid_vk_attach
    .type __sharpdroid_vk_attach,@function
__sharpdroid_vk_attach:
    leaq __sharpdroid_vk_stubs(%rip), %rdi
    movl ${magic}, %eax
    syscall
    ret
    .size __sharpdroid_vk_attach, .-__sharpdroid_vk_attach

    .section .init_array,"aw",@init_array
    .balign 8
    .quad __sharpdroid_vk_attach

    .section .note.GNU-stack,"",@progbits"""

# no attach call and no .init_array here, unlike vulkan: nothing indexes these, because AAudio has
# no procedure-address API. the guest's own ld.so resolving these names is the whole mechanism. the
# stubs are still sixteen bytes each, so that a disassembly of one is a disassembly of all of them
# and an id is readable straight out of the immediate.
_AUDIO_PREAMBLE = """// the stubs are emitted in command-id order and each is exactly 16 bytes, the same as vulkan's --
// not because anything indexes them, but because a disassembly of one is then a disassembly of
// all of them and an id is readable straight out of the immediate.

    .text
    .balign 16
    .globl __sharpdroid_aaudio_stubs
    .hidden __sharpdroid_aaudio_stubs
__sharpdroid_aaudio_stubs:"""


def _stub(name, magic):
    return _STUB.format(name=name, magic=_immediate(magic))


def _immediate(value):
    return "0x{:08X}".format(value)


def _banner(source, count):
    return ("// generated by scripts/gen-thunks.py from {}.\n"
            "// {}. do not edit; re-run the generator.".format(source, count))


def _lines(blocks):
    """the blocks joined into a file, LF throughout and one trailing newline."""
    return "\n".join(blocks) + "\n"


def _unique(names):
    seen, ordered = set(), []
    for name in names:
        if name not in seen:
            seen.add(name)
            ordered.append(name)
    return ordered


def _emit(files, check_only):
    changed = 0
    for path, text in files.items():
        existing = read_text(path) if path.exists() else None
        if existing == text:
            say("  {}  unchanged".format(paths.relative(path)))
            continue
        changed += 1
        if check_only:
            say("  {}  would change".format(paths.relative(path)))
        else:
            write_text(path, text)
            say("  {}  written, {:,} bytes".format(paths.relative(path), len(text)))
    return changed


if __name__ == "__main__":
    main(entry)
