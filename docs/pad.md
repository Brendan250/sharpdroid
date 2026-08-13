# the pad bridge

android gamepad state and rumble, across the guest boundary, in the same process and the same address space.

it rides in on the syscall boundary [`host-layer.md`](host-layer.md) describes, in a magic number range of its own. that document owns the mechanism and the two invariants; this one starts where a magic number in the pad range has been recognised and owns everything outward from there — the wire format, the two commands, rumble delivery, and the counters that say whether any of it happened.

**it is not a thunk, and the difference is worth stating first.** the vulkan and audio thunks forward a guest call to a real NDK library. there is no NDK library here: android has no input API an app may read a gamepad from, so the host layer answers these calls itself out of what the app has pushed into it.

## why the direction is inverted

everything else in the host layer flows one way. the app calls down, the guest calls down, and **nothing ever calls up into guest code** — that is the invariant both thunks are built around, and it is why the audio thunk refuses AAudio's three callback setters outright.

input does not naturally fit that. it originates in java, as a `KeyEvent` or a `MotionEvent` delivered to the activity, and it has to reach C# running as guest x86-64. that is host to guest.

so it is inverted into a pull:

```
a KeyEvent or MotionEvent arrives at the activity
  -> PadState edits the one live snapshot and pushes the whole of it down through JNI
    -> the host layer holds the latest, and only the latest
      -> the guest polls for it through a syscall with a magic number
```

no host thread ever enters guest code, and the guest's own thread makes every crossing itself. the two invariants hold unchanged.

**rumble goes the other way and is therefore the easy direction** — a guest call the host answers, exactly like a thunked one.

## why a call and not shared memory

the guest and the host share one address space 1:1, so the obvious cheaper design is a page of host memory whose address the guest is handed at startup: a load instead of a trap.

**measured on the device, a trap here costs 34.3 ns and a load 0.79** — forty-three times, and both nothing. the emulator samples the pad at most once a millisecond per polling guest thread, so a trap costs about **34 µs per second** of one core: at 60 fps that is 0.57 µs of a frame against the 282 µs it would take to lose a single frame per second, or roughly two thousandths of one fps.

what the trap buys for that is three things the page cannot:

- **no structure layout shared between two repositories.** the wire format below is checked by the call that carries it — a version and a byte count go in, and a disagreement is refused and named. a mirrored struct is checked by nobody, and the failure is plausible wrong values with nothing erroring.
- **rumble.** it is guest to host, which a call already is. a page needs a second mechanism invented for it and something polling to notice a request.
- **the guest never receives a host address to dereference.** a stale one is a segfault inside the emulator at a moment nothing is watching.

**and it needs no new artefact at all.** the payload reaches the trap by P/Invoking the C library's own `syscall` wrapper, and `libc.so.6` is already among the staged x86-64 shared objects and already how the emulator reaches a dozen other things. there is no generated stub, no library to build and nothing added to `guest-libs/`.

## the two commands

`0x50440000` is the magic — one range along from audio's, deliberately distinct so all three stay decodable apart in a trace and in a crash. real linux x86-64 syscall numbers are all below 1000 and this FEXCore has no table indexed by syscall number, so the whole upper range is free and an unrecognised number can never be mistaken for one of ours. the range is tested before the syscall switch, in `LinuxSyscallHandler::Dispatch`.

| command | signature, as the payload calls it | answer |
| --- | --- | --- |
| `0` read | `(version, out, size)` | how many pads were written: 1 or 0, or negative on refusal |
| `1` rumble | `(large, small)` | 0, as soon as the request is recorded |

**the read is a poll and writes into the guest's own buffer in place**, there being no pointer translation anywhere. the rumble returns before anything has buzzed; see below.

## the wire format, and the check that replaces a shared layout

twelve bytes: a `uint32` of button bits, then six axis bytes — left X and Y, right X and Y, left trigger, right trigger — then a connected flag and one reserved byte. **sticks are 0..255 with 128 centred and Y growing downward, and triggers are 0..255**, which are the conventions the emulator's own gamepad snapshot already uses, so nothing between an android axis and the guest's pad data rescales anything.

**the version and the byte count are arguments to every read, and a mismatch on either is refused rather than read.** the two sides of this live in different repositories that release independently and no compiler ever sees both, so the check is the whole reason a call was chosen over shared memory. the refusal names both numbers and says the payload and the host layer are out of step; the payload stops asking after one, since it is not a condition that repairs itself mid-run.

the button numbering is the **emulator's own seam values**, not the guest's. the translation to `SCE_PAD_BUTTON` bits happens on the payload's side, so no PlayStation ABI value appears anywhere in this repository.

## the mapping

**positional, not by letter.** android names the face buttons after the layout most controllers are printed with, and each maps to where it physically is: A is the bottom button and becomes Cross, B the right and Circle, X the left and Square, Y the top and Triangle. that is what makes a controller with PlayStation glyphs behave the way its glyphs say.

a d-pad arrives either as four keys or as a hat axis, and both are handled — the hat rewrites the four bits whenever the device has one, so returning to centre releases them. a trigger arrives either as a key or as an axis, and again both: a key gives the bit and a full-depth value, an axis gives the depth and also presses the bit, because a game reads one or the other and a pad that only ever sent axes would never appear to press L2.

**events are taken at `dispatchKeyEvent` and `dispatchGenericMotionEvent`, before the view hierarchy**, and that is what makes the d-pad work rather than a stylistic choice: an unconsumed direction key moves focus to whatever is focusable, and the panel drawn over a running guest has a button on it. `KEYCODE_BACK` is deliberately not one of the pad's keys, so a controller's own back button opens that panel like the software one.

**one pad reaches the guest, and that is a real ceiling.** the emulator's pad exports read at most two states and take the type, motion and touch of the first, so ports are not addressable from here.

## rumble, and the thread that delivers it

there is no NDK vibrator, so rumble is a JNI call up into the app — the only thing besides the guest file layer that calls upward at all.

**it is not delivered on the guest's thread, and that is the hardest constraint in this part.** a vibrate is a binder round trip to the system server, and the host layer delivers asynchronous signals at syscall exits only while the runtime suspends every thread with `SIGRTMIN` to collect — so a guest thread parked in a platform call is one that cannot acknowledge a collection. that is the same mistake that stopped audio dead partway into runs. so the guest's call records the request and returns, and one host thread of ours does the waiting: attached to the runtime once for its whole life, idle on a condition variable, and holding **a generation rather than a flag** so that two requests arriving between deliveries collapse to the newer instead of the older winning.

the seam sets a level and never says for how long, while a vibrator takes a duration and stops by itself, so each request is a short pulse and a game holding rumble on sends more of them.

**android has one actuator and the seam names two motors, so the louder wins.** per-trigger vibration and the DualSense adaptive triggers are not forwarded at all: there is no actuator behind either, and an approximation would be indistinguishable from an ordinary rumble at the louder of the two levels. the lightbar is not forwarded for the same reason.

### the permission, and what its absence looks like

rumble needs `android.permission.VIBRATE`, a normal permission granted at install.

**its absence does not look like a missing permission.** `hasVibrator()` and `hasAmplitudeControl()` are both answered truthfully without it, so every capability check reports a healthy actuator and the `vibrate` call alone throws a `SecurityException` naming the permission. a rumble path can therefore look completely ready right up to the first buzz that does not happen.

that is also why the java side **returns whether the platform took the request** and the host counts only the trues. a void call reported success for anything that did not crash, so a refused request counted as delivered — and nothing earlier would have contradicted it.

## saying whether any of it happened

the failure this part is most likely to have is silence, and silence has three causes that look identical from outside: the payload never polled, the payload polled and no pad was connected, or a pad was connected and the game ignored it.

so three lines print once each, whether or not anything is being traced:

- the first read, with the wire version it agreed on
- the first read that finds a pad connected
- the first rumble the guest asks for

and the run summary counts reads, reads that found a pad, **rumbles asked for and rumbles delivered separately** — a gap between those two is what a broken delivery path looks like, and one number could not tell them apart. the summary prints whenever the bridge is enabled, including its zeroes, because a zero is the reading that matters.

| | |
| --- | --- |
| `--pad` | enables the bridge. **off by default**, in the shape `--vulkan` and `--audio` have: without it a poll is refused, the payload reports no pad, and the run is the one it was before this part existed |
| `--trace-pad` | every poll and every rumble. chatty — up to a thousand lines a second per polling thread, so it is for one question at a time |
| `--pad-selftest` | **one fabricated rumble at full strength when the guest first polls.** it exists because the two directions fail independently and an ordinary run exercises only one: a game that polls proves the read path continuously, while rumble is proven by nothing at all unless the title happens to vibrate. it announces itself in the log, so a buzz can never be mistaken for a game's own |

the app exposes all three as launch extras — `--ez tracepad`, `--ez padselftest` — and passes `--pad` on every launch.

## what the payload has to do

the launcher sets `SHARPEMU_HOST_INPUT=android` and the payload is expected to register a host input source that polls this bridge. that is **contract generation 3**; [`build-format.md`](build-format.md) owns the number.

the range is 2..3 rather than 3..3, so a generation-2 build still launches. it registers no input source, and its pad exports then report a controller that is permanently connected and permanently neutral — a game that ignores every button. that is admitted where a missing audio backend is refused, and the difference is what a person can tell: silent audio is indistinguishable from a scene with no music, while a controller that does nothing is obvious within seconds, and the launch log names the generation that ran.
