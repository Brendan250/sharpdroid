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
     * Runs a guest to completion. Blocks for the whole run, so never call it on the UI thread.
     *
     * <p>A guest that calls {@code exit_group} does not return from here at all — it takes the
     * process with it, which is what the host layer has always done and is the first thing a real
     * frontend will have to solve.
     */
    public static native int nativeRun(String[] args);
}
