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
