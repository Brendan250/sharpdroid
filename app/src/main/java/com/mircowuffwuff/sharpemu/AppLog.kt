package com.mircowuffwuff.sharpemu

import android.util.Log

/**
 * What the app says about itself, said in both of the places it has to be said.
 *
 * **The app's own lines are the one part of a run that logcat has and the log window would not.**
 * Everything the emulator and the host layer print goes to stdout, where the log pump picks it up and
 * keeps it; java logging is the platform's own channel and never passes through those descriptors. So
 * a window fed only from the pump is missing the line naming which build is running, which driver was
 * chosen and whether the guest's libraries came out of the APK — the configuration half of any report
 * about a run.
 *
 * **Each call is one line in two places, not two lines.** `logcat` gets exactly what it always got —
 * the same tag, the same level, the same text — and the ring gets the same text where it arrived among
 * the emulator's, so a person reading the window sees the app speak in the order it spoke.
 *
 * **It mirrors only where there is something to mirror into.** The ring lives in the host layer, which
 * is loaded by the process that runs a guest and by no other, so [attach] is called by that process
 * and everywhere else this is `android.util.Log` with a wrapper around it. Mirroring unconditionally
 * would pull a thirty-megabyte library into the game list for the sake of a line nobody can read
 * there.
 *
 * The signatures are the platform's on purpose: a call site says what it said before, so nothing about
 * a message depends on which of the two loggers it went through.
 */
object AppLog {

    /**
     * Whether a line also goes to the host layer's ring.
     *
     * Volatile rather than synchronised: it is written once, before anything reads it, and read from
     * every thread that logs. A read that saw the old value would cost one line out of the window.
     */
    @Volatile
    private var mirroring = false

    /**
     * Called by the process that runs a guest, before it logs anything.
     *
     * **It does not load the host layer and does not need to.** The first mirrored line is what
     * touches [HostLayer], and that process loads it for the run regardless — so the only thing this
     * moves is which call is the first to load it, and earlier is better: the library's own log pump
     * starts at load, and anything printed before it starts goes to a stdout that is `/dev/null` in an
     * app.
     */
    @JvmStatic
    fun attach() {
        mirroring = true
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        mirror(message)
    }

    @JvmStatic
    fun i(tag: String, message: String, error: Throwable) {
        Log.i(tag, message, error)
        mirror(message + ": " + error)
    }

    @JvmStatic
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        mirror(message)
    }

    @JvmStatic
    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
        mirror(message + ": " + error)
    }

    @JvmStatic
    fun e(tag: String, message: String) {
        Log.e(tag, message)
        mirror(message)
    }

    @JvmStatic
    fun e(tag: String, message: String, error: Throwable) {
        Log.e(tag, message, error)
        mirror(message + ": " + error)
    }

    /**
     * **A throwable is flattened to its one line rather than dropped or printed whole.** logcat gets
     * the stack trace, which is where somebody chasing a crash reads it; the window is being read on a
     * phone over a running game, where forty frames of java would bury the lines around it. What
     * carries is the exception's type and message, which is what makes the line beside it make sense.
     */
    private fun mirror(message: String) {
        if (mirroring) {
            HostLayer.nativeLogLine(message)
        }
    }
}
