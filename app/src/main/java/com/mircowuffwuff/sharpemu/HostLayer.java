package com.mircowuffwuff.sharpemu;

import android.view.Surface;

/**
 * The host layer, as a library.
 *
 * <p>Everything the app can ask for is the argument vector the shell binary already took, so a run
 * in here and a run from {@code adb shell} take the same flags and stay comparable. The one thing
 * that is not an argument is the surface, because it is a live object rather than a string.
 */
public final class HostLayer {

    static {
        System.loadLibrary("sharpemu-host-layer");
    }

    private HostLayer() {
    }

    /**
     * Hands the surface down, or takes it away when {@code surface} is null.
     *
     * <p>The native side reads the size from it and pins the buffer geometry to match, so this is
     * also what decides the extent the guest is told the display has.
     */
    public static native void nativeSetSurface(Surface surface);

    /**
     * The current state of the gamepad, pushed down whenever any control on it moves.
     *
     * <p>The guest polls for this rather than being handed it, because a host thread calling into the
     * guest would have to enter translated code — the one direction every seam here refuses. So the
     * native side keeps the latest and answers a guest that asks.
     *
     * <p><b>Scalars rather than a structure, deliberately.</b> One layout does have to cross this
     * boundary — the guest's own poll — and it is versioned and size-checked by the call that carries
     * it. A second layout here would be a second thing to keep in step for nothing.
     *
     * <p>Cheap enough for the input dispatch: it takes one uncontended lock and copies twelve bytes,
     * and it neither allocates nor blocks. Sticks are 0..255 with 128 centred and Y growing downward;
     * triggers are 0..255. See {@code PadState}, which is the only caller.
     */
    public static native void nativeSetPadState(
            int buttons, int leftX, int leftY, int rightX, int rightY, int leftTrigger,
            int rightTrigger, boolean connected);

    /**
     * Whether the chosen GPU driver is the one a run would render through.
     *
     * <p><b>The app cannot answer this and nothing on this side can.</b> adrenotools returns the
     * platform loader opened in an isolated namespace with a hook in front of the loader's own
     * {@code dlopen}, and a hook that cannot load the driver falls back to the system one and hands
     * back a handle that is good in every way. Every check available up here has already passed by
     * then.
     *
     * <p><b>This opens the driver rather than testing a copy of it</b>, and it is the same one-time
     * open the guest's first Vulkan call would have done — so asking here moves when the load
     * happens, not how often, and what is checked is the load the run will use. That is what lets a
     * launch be refused instead of ended several seconds in.
     *
     * <p>Called before {@link #nativeRun}, off the UI thread, and only when a driver was chosen.
     * True for anything short of a definite failure: the expensive mistake is refusing a driver that
     * works.
     *
     * @param driver absolute path to the driver's {@code .so}, on internal storage.
     * @param hooks the app's {@code nativeLibraryDir}, and nothing else.
     */
    public static native boolean nativeDriverLoads(String driver, String hooks);

    /**
     * The names of the boot checkpoints, in the order a boot reaches them. Asked for once.
     *
     * <p><b>Ids rather than indices</b>, so the two sides move independently: an id maps to whatever
     * this side draws for that phase, one it has never heard of changes nothing on screen, and one
     * that disappears takes its own text with it.
     *
     * <p>The last entry is the first presented frame rather than anything the emulator prints, so a
     * run is over when {@link #nativeBootCheckpointsReached()} reaches this array's length.
     */
    public static native String[] nativeBootCheckpointIds();

    /**
     * How many checkpoints this run has passed, from 0 to the table's length.
     *
     * <p><b>The one call here meant to be made repeatedly.</b> It is a single relaxed load behind the
     * JNI boundary, which is what lets this be asked once per drawn frame instead of the host layer
     * calling up — and it must not call up: the thread that would make that call is the one draining
     * the guest's stdout, and anything that blocks it blocks the guest in {@code write}.
     *
     * <p>The count can jump by more than one between two asks, when several checkpoints are passed
     * inside one frame or when a checkpoint the emulator no longer prints is passed over.
     *
     * <p>Only meaningful when the run was started with {@code --boot-progress}; without it this
     * stays at 0.
     */
    public static native int nativeBootCheckpointsReached();

    /**
     * When each checkpoint was passed, in milliseconds since the host layer started, and -1 for one
     * that was never passed. For recording a finished boot in order to predict the next one.
     *
     * <p><b>-1 is a real answer rather than a zero.</b> A checkpoint no line matched and one reached
     * in the same instant as its neighbour would otherwise look alike, and recording the second is
     * recording a table that has stopped matching.
     */
    public static native long[] nativeBootCheckpointTimes();

    /**
     * The sequence one past the newest line this process has printed.
     *
     * <p><b>Everything printed is one stream and this is a window onto it</b> — the emulator's own
     * logger, its raw console writes and the host layer's, all of which reach the log pump through
     * the pipe it puts under stdout and stderr, plus whatever {@link AppLog} has added of the app's.
     * A line is addressed by its sequence rather than by a position, so a reader that was away can
     * ask for what arrived after the last line it saw and can tell when the answer starts later than
     * that.
     *
     * <p><b>The repeatedly-made call of this group</b>: a lock and a load. Polled while the log is on
     * screen and not asked for at all otherwise.
     */
    public static native long nativeLogNext();

    /**
     * The sequence of the oldest line still held. Everything below it has been dropped, which the
     * ring does by line count and by total bytes, whichever it reaches first.
     */
    public static native long nativeLogOldest();

    /**
     * The lines from {@code from} up to but not including {@code to}, clamped to what is still held.
     *
     * <p><b>The copy out of the ring is the one thing here that can make the log pump wait</b>, and
     * the pump is what drains the guest's stdout — a guest blocked writing into a full pipe is a
     * guest stopped. So the range is the caller's to choose: a poll asks for the few lines that
     * arrived since it last looked, and only a viewer being opened asks for thousands.
     */
    public static native String[] nativeLogRange(long from, long to);

    /**
     * Keeps one line of the app's own where it arrived among the emulator's.
     *
     * <p><b>Java logging never touches that pipe</b> — it is the platform's own channel and does not
     * pass through stdout — so without this the app's lines are the one part of a run missing from
     * the window, including the line naming which build is running. It does not write to logcat:
     * {@link AppLog} has already done that, and this is the second of the two places a line goes.
     */
    public static native void nativeLogLine(String line);

    /**
     * Runs a guest to completion. Blocks for the whole run, so never call it on the UI thread.
     *
     * <p>A guest that calls {@code exit_group} does not return from here at all — it ends the
     * process, which is what the syscall means and the only safe answer once the other guest
     * threads are inside translated code. So this is called in a process given to one run and
     * ended with it; see {@link MainActivity}, which the manifest puts in {@code :guest} for
     * exactly that.
     */
    public static native int nativeRun(String[] args);
}
