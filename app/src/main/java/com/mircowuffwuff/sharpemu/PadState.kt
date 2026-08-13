package com.mircowuffwuff.sharpemu

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One gamepad, as the emulator's input seam wants it, assembled from android's key and motion events.
 *
 * The button numbering is the seam's own and not the guest's: the emulator translates these to
 * `SCE_PAD_BUTTON` bits on its side of the boundary, so no PlayStation ABI value appears here. Sticks
 * are 0..255 with 128 centred and **Y growing downward**, which matches both android's axis sign and
 * the seam's convention, so nothing is flipped anywhere.
 */
object PadButton {
    const val UP = 1 shl 0
    const val DOWN = 1 shl 1
    const val LEFT = 1 shl 2
    const val RIGHT = 1 shl 3
    const val CROSS = 1 shl 4
    const val CIRCLE = 1 shl 5
    const val SQUARE = 1 shl 6
    const val TRIANGLE = 1 shl 7
    const val L1 = 1 shl 8
    const val R1 = 1 shl 9
    const val L2 = 1 shl 10
    const val R2 = 1 shl 11
    const val L3 = 1 shl 12
    const val R3 = 1 shl 13
    const val OPTIONS = 1 shl 14
    const val TOUCHPAD = 1 shl 15
    const val CREATE = 1 shl 16
}

/**
 * The live state of the pad, and the only thing that talks to the host layer about input.
 *
 * **Held here rather than rebuilt per event**, because android delivers one event per changed control:
 * a key event says a button went down and says nothing about the sticks, so a snapshot assembled from
 * one event alone would report every other control as released. So each event edits this and the whole
 * of it is pushed down afterwards.
 *
 * Not thread-safe and deliberately not synchronised: every caller is the input dispatch on the UI
 * thread. The crossing into other threads happens on the native side, which takes a lock for it.
 */
object PadState {

    /** Centre for a stick axis. The seam's convention, and android's 0.0 maps onto it. */
    private const val CENTRE = 128

    private var buttons = 0
    private var leftX = CENTRE
    private var leftY = CENTRE
    private var rightX = CENTRE
    private var rightY = CENTRE
    private var leftTrigger = 0
    private var rightTrigger = 0

    /**
     * Which device ids are gamepads we are tracking, in the order they arrived.
     *
     * **One pad reaches the guest and that is a real ceiling rather than a shortcut**: the emulator's
     * pad exports read at most two states and take the type, motion and touch of the first, so ports
     * are not addressable from here yet. What this set is for is knowing whether *any* pad is present,
     * so that unplugging one of two does not report the pad as gone.
     */
    private val devices = LinkedHashSet<Int>()

    /** True once anything has been seen, which is what the guest is told as `connected`. */
    val connected: Boolean get() = devices.isNotEmpty()

    /**
     * Whether this event came from something with a stick or a gamepad button on it.
     *
     * The source is a bit mask and a device is commonly several things at once — a gamepad that also
     * reports itself as a keyboard is the normal case, which is why this tests for the bits rather
     * than comparing equality.
     */
    @JvmStatic
    fun isGamepad(device: InputDevice?): Boolean {
        if (device == null) {
            return false
        }
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * The button mapping, or 0 for a key that is not one of ours.
     *
     * **Positional rather than by letter.** Android names the face buttons A, B, X and Y after the
     * layout most controllers are printed with, and the mapping is by where the button physically is:
     * A is the bottom button and becomes Cross, B is the right one and becomes Circle, X is the left
     * one and becomes Square, Y is the top one and becomes Triangle. That is what makes a controller
     * with PlayStation glyphs on it behave the way its glyphs say, and it is what every other android
     * emulator of a PlayStation does.
     *
     * `KEYCODE_BACK` is deliberately absent. A gamepad that reports its own back button would
     * otherwise open the in-game panel, and on this device the built-in controls do exactly that.
     */
    private fun buttonFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> PadButton.CROSS
        KeyEvent.KEYCODE_BUTTON_B -> PadButton.CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> PadButton.SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> PadButton.TRIANGLE
        KeyEvent.KEYCODE_DPAD_UP -> PadButton.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.RIGHT
        KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
        // the triggers arrive as keys on a pad with digital ones and as axes on a pad with analogue
        // ones. both are handled: this sets the button bit, and the axis path below sets the depth.
        KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
        KeyEvent.KEYCODE_BUTTON_START -> PadButton.OPTIONS
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.CREATE
        else -> 0
    }

    /**
     * Takes a key event. Returns true when it was the pad's, in which case it goes no further.
     *
     * **Consuming it is what stops a d-pad walking the app's focus.** An unconsumed `DPAD_DOWN`
     * reaching the view hierarchy moves focus to whatever is focusable, and the in-game panel has a
     * button on it.
     */
    @JvmStatic
    fun onKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.device)) {
            return false
        }
        val bit = buttonFor(event.keyCode)
        if (bit == 0) {
            return false
        }
        devices.add(event.deviceId)

        // a repeat is android's auto-repeat on a key that is already down, and it says nothing new.
        // acting on it would be a press per repeat interval for a finger that never moved.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            return true
        }

        buttons = when (event.action) {
            KeyEvent.ACTION_DOWN -> buttons or bit
            KeyEvent.ACTION_UP -> buttons and bit.inv()
            else -> return false
        }
        // a digital trigger has no depth to report, so the button bit is given one: fully down or not
        // at all. a pad with analogue triggers sends the axis instead and the axis wins, since it
        // arrives as its own event and overwrites this.
        when (bit) {
            PadButton.L2 -> leftTrigger = if (event.action == KeyEvent.ACTION_DOWN) 255 else 0
            PadButton.R2 -> rightTrigger = if (event.action == KeyEvent.ACTION_DOWN) 255 else 0
        }
        push()
        return true
    }

    /**
     * Takes a joystick motion event. Returns true when it was the pad's.
     *
     * One event carries every axis the device has, so all of them are read rather than looking for
     * which changed — android does not say, and reading them all is what makes the snapshot whole.
     */
    @JvmStatic
    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.device) || event.action != MotionEvent.ACTION_MOVE) {
            return false
        }
        devices.add(event.deviceId)

        leftX = axisToByte(event, MotionEvent.AXIS_X)
        leftY = axisToByte(event, MotionEvent.AXIS_Y)
        // Z and RZ are where the right stick is on essentially every android gamepad, the HID usage
        // tables having no second stick of their own.
        rightX = axisToByte(event, MotionEvent.AXIS_Z)
        rightY = axisToByte(event, MotionEvent.AXIS_RZ)

        // two spellings for the same pair of controls, and a device uses one or the other. LTRIGGER
        // and RTRIGGER are the gamepad names; BRAKE and GAS are what a device described as a wheel
        // reports, and several handheld pads describe themselves that way. the larger of the two is
        // taken so that a device sending only one is unaffected by the other reading zero.
        leftTrigger = maxOf(
            triggerToByte(event, MotionEvent.AXIS_LTRIGGER),
            triggerToByte(event, MotionEvent.AXIS_BRAKE))
        rightTrigger = maxOf(
            triggerToByte(event, MotionEvent.AXIS_RTRIGGER),
            triggerToByte(event, MotionEvent.AXIS_GAS))

        // a d-pad that reports as a hat rather than as four keys. the bits are rewritten from the hat
        // whenever the device has one, so a hat returning to centre releases them.
        if (hasAxis(event.device, MotionEvent.AXIS_HAT_X) ||
            hasAxis(event.device, MotionEvent.AXIS_HAT_Y)) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            buttons = buttons and
                    (PadButton.LEFT or PadButton.RIGHT or PadButton.UP or PadButton.DOWN).inv()
            if (hatX < -0.5f) buttons = buttons or PadButton.LEFT
            if (hatX > 0.5f) buttons = buttons or PadButton.RIGHT
            if (hatY < -0.5f) buttons = buttons or PadButton.UP
            if (hatY > 0.5f) buttons = buttons or PadButton.DOWN
        }

        // the analogue triggers also press the digital bits, because a game reads one or the other and
        // a pad that only ever sent axes would never press L2 as far as the guest could tell.
        buttons = if (leftTrigger > 32) buttons or PadButton.L2 else buttons and PadButton.L2.inv()
        buttons = if (rightTrigger > 32) buttons or PadButton.R2 else buttons and PadButton.R2.inv()

        push()
        return true
    }

    /**
     * A device arriving or leaving. Neither is an event on its own, so both come from the activity.
     *
     * A pad going away pushes a released state rather than only clearing the flag: a stick held over
     * when the cable came out would otherwise be the last thing the guest was told, and it would hold
     * that position forever.
     */
    @JvmStatic
    fun onDeviceChanged() {
        val present = LinkedHashSet<Int>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id)
            if (isGamepad(device)) {
                present.add(id)
            }
        }
        if (present == devices) {
            return
        }
        devices.clear()
        devices.addAll(present)
        if (devices.isEmpty()) {
            release()
        }
        push()
    }

    /** Everything up and centred. What a pad that has gone away reports. */
    @JvmStatic
    fun release() {
        buttons = 0
        leftX = CENTRE
        leftY = CENTRE
        rightX = CENTRE
        rightY = CENTRE
        leftTrigger = 0
        rightTrigger = 0
    }

    /** Releases everything and tells the host, for a run being left rather than a pad being unplugged. */
    @JvmStatic
    fun clear() {
        release()
        push()
    }

    private fun hasAxis(device: InputDevice?, axis: Int): Boolean =
        device?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null

    /**
     * A stick axis, -1.0..1.0 from android, onto 0..255 with 128 centred.
     *
     * The device's own flat and fuzz are not applied. Android reports them per axis and the emulator
     * applies a deadzone of its own when it merges the stick, so subtracting one here would be a
     * deadzone inside a deadzone — and the second one would be invisible to anybody tuning the first.
     */
    private fun axisToByte(event: MotionEvent, axis: Int): Int {
        if (!hasAxis(event.device, axis)) {
            return CENTRE
        }
        val value = event.getAxisValue(axis).coerceIn(-1f, 1f)
        if (abs(value) < 0.001f) {
            return CENTRE
        }
        // 128 + v*127 rather than 127.5, so that centre is exactly 128 and both ends are reachable:
        // -1.0 gives 1 and 1.0 gives 255. losing 0 costs nothing and an off-centre centre costs a
        // permanent drift.
        return (CENTRE + value * 127f).roundToInt().coerceIn(0, 255)
    }

    /** A trigger axis, 0.0..1.0 from android, onto 0..255. */
    private fun triggerToByte(event: MotionEvent, axis: Int): Int {
        if (!hasAxis(event.device, axis)) {
            return 0
        }
        val value = event.getAxisValue(axis).coerceIn(0f, 1f)
        return (value * 255f).roundToInt().coerceIn(0, 255)
    }

    /**
     * Hands the whole state down to the host layer.
     *
     * **Every event, with no coalescing.** The native side takes one uncontended lock and copies
     * twelve bytes, and the guest reads whatever is latest — so a push that arrives between two polls
     * costs nothing and a push that is skipped is a control the guest never learns about.
     */
    private fun push() {
        HostLayer.nativeSetPadState(
            buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, connected)
    }
}
