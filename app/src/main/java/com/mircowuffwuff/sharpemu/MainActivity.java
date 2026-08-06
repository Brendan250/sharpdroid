package com.mircowuffwuff.sharpemu;

import android.app.Activity;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One activity, one SurfaceView, one guest.
 *
 * <p>The payload lives in the app's own external files directory rather than in
 * {@code /data/local/tmp}, which is {@code shell_data_file} and which SELinux denies an app. That
 * costs nothing: the host layer maps guest images into anonymous memory and reads into them rather
 * than mapping them from a file, so a {@code noexec} volume is not a problem and never was.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "sharpemu";
    /**
     * The game directory under {@code <external files>/games/}, without the {@code eboot.bin}.
     *
     * <p>Overridden per launch by {@code --es game <name>}, for the same reason the driver is an
     * extra: comparing titles should not cost an APK rebuild.
     */
    private static final String GAME = "Dreaming Sarah [PPSA02929]";

    private String gameName;

    /**
     * {@code --es safgame <directory name>}, a game inside the granted tree instead of a staged one.
     *
     * <p>Null means the staged path, which is the mode every script uses and every number was
     * measured on. The two are deliberately reachable side by side, on the same build, so the cost of
     * the file layer stays something that can be measured rather than argued about.
     */
    private String safGameName;

    /**
     * Which staged GPU driver to inject, or null for the stock Adreno one.
     *
     * <p>A name under {@code <external files>/gpu-drivers/}, put there by
     * {@code scripts/stage-driver.ps1}. This is the one knob in this class that is a real choice
     * rather than a constant, and in the frontend it becomes a Video setting — the package format
     * is already the one a user would import.
     *
     * <p><b>Null on purpose.</b> Turnip injection works, and the first one tried was 5.4x slower
     * than the stock driver on this device — 10.5 fps against 57.2 — for a reason that turned out to
     * be ours rather than the driver's, and is fixed. So the default is still the stock driver,
     * which is the configuration every measurement was taken at.
     *
     * <p>Overridden per launch by {@code --es driver <name>}, so comparing drivers is a loop over
     * {@code am start} rather than a rebuild each time. {@code stock} means the same as null.
     */
    private static final String DRIVER = null;

    // Which SharpEmu build to run is not a constant here, and stopped being one on 2026-08-05. It is
    // `--es sharpemu <absolute path to a build directory>`, or, with no extra, the most recently
    // staged build — see SharpEmuBuild.mostRecent. In the frontend it becomes the **Use recommended
    // build** toggle, which is where a hardcoded recommendation belongs: a user who turns it off
    // stores a concrete identity rather than a pointer, so their choice survives every update.

    /** Resolved once in {@link #onCreate}, because the intent is not readable from a worker. */
    private String driverName;
    private String[] driverEnv = {};
    private boolean profile;
    private boolean turbo;
    private boolean audioWatchdog;
    /** {@code --ez tracefiles}, counting the guest's file access under the game directory. */
    private boolean traceFiles;
    /** The host layer's SMC tracking mode. mtrack is the default every measurement was taken on. */
    private String smcMode = "mtrack";
    private String[] guestEnv = {};
    /** {@code --es sharpemu}, an absolute path to a build directory, or null for the latest staged. */
    private String buildPath;
    /** The selected build's own environment defaults. The lowest-precedence source there is. */
    private Map<String, String> buildEnv = new LinkedHashMap<>();

    private boolean started;
    private int surfaceWidth;
    private int surfaceHeight;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // driver selection off the launch intent, so a comparison across driver builds is a loop
        // over `am start --es driver <name>` instead of an APK rebuild per candidate. "stock" and
        // absence both mean the platform's own driver.
        driverName = getIntent().getStringExtra("driver");
        if (driverName == null) {
            driverName = DRIVER;
        }
        if ("stock".equals(driverName) || "".equals(driverName)) {
            driverName = null;
        }
        // and mesa's own knobs, comma-separated: --es driverenv TU_DEBUG=sysmem,TU_DEBUG=noubwc
        String env = getIntent().getStringExtra("driverenv");
        if (env != null && !env.isEmpty()) {
            driverEnv = env.split(",");
        }
        // --ez profile true, for the same reason the driver is an extra: finding a stall should not
        // cost an APK rebuild.
        profile = getIntent().getBooleanExtra("profile", false);
        // --ez turbo true pins the GPU clocks through KGSL. off by default: a thermal and battery
        // trade rather than a free win, and every number recorded before it was taken without it.
        turbo = getIntent().getBooleanExtra("turbo", false);
        // --ez audiowatchdog true reports the stream's state once a second whether or not the guest
        // is submitting. the periodic report on the write path cannot see the guest stopping.
        audioWatchdog = getIntent().getBooleanExtra("audiowatchdog", false);
        // --ez tracefiles true counts what the guest asks of the game directory: opens, stats,
        // directory listings, and what it then does with the descriptors. it costs a predictable
        // branch per file syscall when off, and it is what makes two ways of reaching the same game
        // comparable rather than a matter of opinion.
        traceFiles = getIntent().getBooleanExtra("tracefiles", false);
        // --es smc full, because chasing the audio stall needs the two SMC modes compared on the
        // same build. this
        // is a *launch* extra and still not a build one: the comment below about a payload that can
        // ask for --smc none stands, and nothing here lets it. mtrack stays the default, so a run
        // that does not say otherwise is the configuration every published number was taken on.
        String smc = getIntent().getStringExtra("smc");
        if (smc != null && ("none".equals(smc) || "mtrack".equals(smc) || "full".equals(smc))) {
            smcMode = smc;
        }
        // extra guest environment, comma-separated: --es guestenv DOTNET_EnableAVX=0
        // these reach SharpEmu itself, unlike driverenv which reaches the GPU driver.
        String genv = getIntent().getStringExtra("guestenv");
        if (genv != null && !genv.isEmpty()) {
            guestEnv = genv.split(",");
        }
        gameName = getIntent().getStringExtra("game");
        if (gameName == null || gameName.isEmpty()) {
            gameName = GAME;
        }
        // --es safgame <directory name>, naming a game inside the tree the user granted rather than
        // one staged into the app's own directory. **absent is the whole point**: without it nothing
        // here changes, the game is a path, no interception is registered, and the run is exactly the
        // one every measurement so far was taken on. That is what keeps a frame rate measured through
        // the scripts free of any alibi.
        safGameName = getIntent().getStringExtra("safgame");
        // --es sharpemu <absolute path>, in the shape --es driver and --es game already have:
        // comparing two builds should be a loop over `am start`, not an APK rebuild per candidate.
        // **A path, never an id** — see resolvePayload for why the id form was removed. Null here
        // means none was given, which is a real answer rather than a missing one.
        buildPath = getIntent().getStringExtra("sharpemu");
        // a game boot is minutes of work with no touch input, and the screen going off takes the
        // surface with it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
        goFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullscreen();
        }
    }

    /**
     * The system bars are not decoration here. They shrink the surface, and the surface is what
     * decides the extent the guest renders at — so a visible navigation bar would mean the guest
     * presenting 1920x1005 into a panel that is 1920x1080.
     */
    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // deliberately nothing. surfaceChanged always follows and is the first point with a size.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "[app] surface " + width + "x" + height + " format " + format);
        surfaceWidth = width;
        surfaceHeight = height;
        HostLayer.nativeSetSurface(holder.getSurface());

        if (!started) {
            started = true;
            new Thread(this::runGuest, "sharpemu-host-layer").start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // the guest keeps running and its presents become no-ops. it has no idea, which is the
        // point of the host layer owning the swapchain.
        HostLayer.nativeSetSurface(null);
    }

    /**
     * Installs a staged adrenotools driver package onto internal storage and returns the .so, or
     * null if there is nothing staged — in which case the run is the stock Adreno driver and the
     * flags below are simply not passed.
     *
     * <p>The copy is not ceremony. adrenotools stats the driver and then {@code dlopen}s it, and
     * the linker refuses a library from anywhere another app could have written it, which is what
     * {@code /storage/emulated/0} is. So the package is staged where adb can reach it and installed
     * where the linker will accept it. It is also FUSE-backed over there and this is 15 MB, which
     * is the second reason not to load it in place.
     */
    private String installDriver(File externalRoot) {
        if (driverName == null) {
            return null;
        }
        File staged = new File(AppStorage.stagedDrivers(externalRoot), driverName);
        File meta = new File(staged, "meta.json");
        if (!meta.isFile()) {
            Log.i(TAG, "[app] no driver staged at " + staged + " — using the stock adreno driver");
            return null;
        }

        try {
            byte[] raw = new byte[(int) meta.length()];
            try (InputStream in = new FileInputStream(meta)) {
                int read = 0;
                while (read < raw.length) {
                    int n = in.read(raw, read, raw.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            // the package names its own library, so nothing here knows or cares that turnip's is
            // called libvulkan_freedreno.so.
            String libName = json.getString("libraryName");
            File source = new File(staged, libName);
            if (!source.isFile()) {
                Log.e(TAG, "[app] meta.json names " + libName + " and it is not in " + staged);
                return null;
            }

            // per driver, so switching between two packages cannot leave the previous one's
            // library sitting in the directory being pointed at.
            File installDir = AppStorage.installedDriver(getFilesDir(), driverName);
            if (!installDir.isDirectory() && !installDir.mkdirs()) {
                Log.e(TAG, "[app] could not create " + installDir);
                return null;
            }
            File installed = new File(installDir, libName);

            // length is enough to notice a re-staged driver and cheap enough to check every
            // launch. a 15 MB copy off a FUSE volume is not something to do per run for nothing.
            if (installed.length() != source.length()) {
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = new FileOutputStream(installed)) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                Log.i(TAG, "[app] installed " + libName + " (" + installed.length() + " bytes) to " + installDir);
            }

            Log.i(TAG, "[app] driver: " + json.optString("name", driverName) + " — "
                    + json.optString("driverVersion", "unknown version"));
            return installed.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "[app] could not install the driver", e);
            return null;
        }
    }

    /**
     * Resolves {@link #buildPath} to a payload, or null if it does not resolve.
     *
     * <p><b>A build is named by path, and never by id.</b> Until 2026-08-05 this took a
     * {@code meta.json} {@code id} and answered with the highest installed {@code buildVersion} of
     * it — so a freshly staged {@code b1} was silently ignored while a {@code b3} existed, and
     * testing a new build meant remembering to bump its version to make it win. That is the
     * {@code mrpurple-t29} trap with a different mechanism: a plausible number attributed to the
     * wrong artefact, with nothing erroring. A path cannot be ambiguous about which directory it
     * meant.
     *
     * <p><b>An id here is refused outright rather than resolved.</b> Keeping both forms is what let
     * the ambiguous one survive, and this is pre-release, so nothing outside the tooling ever sent
     * one. The contract number does not move for it: {@code hostContract} gates the <i>payload</i>,
     * and a build packaged before this change is byte-for-byte compatible with the app after it.
     * Bumping it would refuse working builds by name, which is a false negative in the mechanism
     * built to prevent false negatives.
     *
     * <p><b>Nothing at all means the most recently staged build</b>, which is what the scripts mean
     * by omitting the flag. Naming nothing is a real answer; it is naming something ambiguous that
     * was the problem.
     */
    private File resolvePayload(File root) {
        File staged = AppStorage.stagedBuilds(root);
        SharpEmuBuild build;
        if (buildPath == null || buildPath.isEmpty()) {
            build = SharpEmuBuild.mostRecent(staged, AppStorage.installedBuilds(getFilesDir()));
        } else if (!buildPath.startsWith("/")) {
            Log.e(TAG, "[app] --es sharpemu wants an absolute path to a build directory, and '"
                    + buildPath + "' is a name. selecting a build by id was removed on 2026-08-05,"
                    + " because it answered with the highest installed buildVersion and so could run"
                    + " a different build to the one that was just staged. stage one with"
                    + " scripts/stage-build.ps1 and pass the path it prints, or pass nothing for the"
                    + " most recently staged build");
            return null;
        } else {
            build = SharpEmuBuild.resolvePath(new File(buildPath));
        }
        if (build == null) {
            return null;
        }
        // the launch log names the build, and that is not decoration: a third-party build
        // misbehaving arrives as "your emulator is broken", so a run has to be traceable to the
        // artefact that produced it without asking the person who ran it.
        Log.i(TAG, "[app] build: " + build.identity() + " at " + build.dir);
        if (!build.notes.isEmpty()) {
            Log.i(TAG, "[app]   " + build.notes);
        }
        buildEnv = build.env;
        return build.payloadFile();
    }

    /**
     * Points the guest file layer at a game inside a tree the user has already granted us.
     *
     * <p><b>The grant is the first persisted read permission we hold, and that is a placeholder.</b>
     * There is no picker yet, so this reuses whatever was granted by hand — which is enough to prove
     * the file layer against a real dump, and is the one piece here that a directory picker replaces
     * wholesale rather than extends.
     */
    private boolean mountSafGame() {
        Uri tree = null;
        for (UriPermission held : getContentResolver().getPersistedUriPermissions()) {
            if (held.isReadPermission()) {
                tree = held.getUri();
                break;
            }
        }
        if (tree == null) {
            Log.e(TAG, "[app] --es safgame needs a granted directory and this app holds none."
                    + " grant one through the picker first");
            return false;
        }
        Log.i(TAG, "[app] using the tree granted earlier: " + tree);
        return GuestFiles.mount(this, tree, safGameName);
    }

    private void runGuest() {
        File root = getExternalFilesDir(null);
        if (root == null) {
            Log.e(TAG, "[app] no external files directory");
            return;
        }

        File payload = resolvePayload(root);
        if (payload == null) {
            return;
        }

        // the game, one of two ways. a staged directory is a real path and the host layer opens it
        // with an ordinary openat; a granted one is not a path at all, so the host layer is told to
        // mount the provider and the guest is handed an invented path under it. everything after
        // this point is the same argument vector either way.
        String guestGame;
        File staged = new File(AppStorage.games(root), gameName + "/eboot.bin");
        if (safGameName != null && !safGameName.isEmpty()) {
            if (!mountSafGame()) {
                return;
            }
            guestGame = GuestFiles.MOUNT + "/eboot.bin";
        } else {
            guestGame = staged.getAbsolutePath();
            if (!staged.exists()) {
                Log.e(TAG, "[app] missing: " + staged.getAbsolutePath()
                        + " — stage it with scripts/stage-game.ps1");
                return;
            }
        }
        for (File needed : new File[] {payload, AppStorage.guestLibs(root)}) {
            if (!needed.exists()) {
                Log.e(TAG, "[app] missing: " + needed.getAbsolutePath()
                        + " — stage it with scripts/stage-game.ps1 or scripts/stage-guest-libs.ps1");
                return;
            }
        }

        List<String> args = new ArrayList<>();
        args.add("--timestamps");
        args.add("--vulkan");
        // the audio thunk, in the shape --vulkan has. nothing at all is needed from this side
        // besides the flag: AAudio is a pure NDK C API, so there is no JNI, no looper and no
        // permission — RECORD_AUDIO gates input and this only ever plays.
        args.add("--audio");
        if (audioWatchdog) {
            args.add("--audio-watchdog");
        }
        // the custom driver, if one is staged. both flags or neither: with neither, the host layer
        // opens the platform loader exactly as every measurement up to here did, so the stock
        // baseline stays reproducible from the same build.
        String driver = installDriver(root);
        if (driver != null) {
            args.add("--vulkan-driver");
            args.add(driver);
            // and the hooks, which adrenotools loads by soname from this directory and nowhere
            // else. it must be nativeLibraryDir itself — a directory that merely contains copies
            // of them is not the same thing, and getting it wrong fails by quietly falling back
            // to the stock driver rather than by erroring.
            args.add("--vulkan-hooks");
            args.add(getApplicationInfo().nativeLibraryDir);
            for (String assignment : driverEnv) {
                args.add("--vulkan-driver-env");
                args.add(assignment);
            }
        }
        if (profile) {
            args.add("--vulkan-profile");
        }
        if (turbo) {
            args.add("--vulkan-turbo");
        }
        args.add("--smc");
        args.add(smcMode);

        // guest environment, in precedence order: **build defaults < app settings < intent
        // extras**, last wins. it is a map rather than a list of --env flags so a variable a build
        // defaults on and a launch overrides reaches the guest once, with the override's value —
        // two --env flags naming the same variable would be a coin toss over which the guest reads.
        //
        // the missing tier is explicit --env on the shell binary's command line, which is above all
        // three and is not reachable from here. a build may set *only* this: --smc, --asyncsig and
        // the --vulkan-* family are properties of the host layer's correctness, and a payload able
        // to ask for --smc none is a payload able to break the thing running it.
        Map<String, String> env = new LinkedHashMap<>(buildEnv);
        // both of these are load-bearing and both belong in the Environment tab of the real
        // frontend rather than here. without the first, the SMC tracker cannot see CoreCLR's JIT
        // writes and a boot costs 65x; without the second the fork constructs an SDL window and
        // dies on "No available video device".
        env.put("DOTNET_EnableWriteXorExecute", "0");
        env.put("SHARPEMU_HOST_WINDOW", "android");
        // and the third: without it the fork asks SDL for an audio device, SDL names four backends
        // android does not have, and the port degrades to backend=silent with nothing erroring.
        // that is the failure hostContract 2 exists to refuse, so this and the contract move
        // together.
        env.put("SHARPEMU_HOST_AUDIO", "android");
        // and this is the one that stops the extent being a coincidence: the host has the window,
        // the guest does not, so the size travels from here rather than being agreed by two
        // separately hand-set defaults.
        env.put("SHARPEMU_HOST_WINDOW_SIZE", surfaceWidth + "x" + surfaceHeight);
        for (String assignment : guestEnv) {
            int eq = assignment.indexOf('=');
            if (eq < 1) {
                Log.e(TAG, "[app] --es guestenv wants NAME=VALUE, ignoring '" + assignment + "'");
                continue;
            }
            env.put(assignment.substring(0, eq), assignment.substring(eq + 1));
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            args.add("--env");
            args.add(e.getKey() + "=" + e.getValue());
        }

        args.add("--libs");
        args.add(AppStorage.guestLibs(root).getAbsolutePath());
        // internal storage, not the external one the payload sits on: .NET reaches for TMPDIR far
        // more than for its own bundle, and the external volume is FUSE-backed on Android 11+, so
        // every file operation there is a userspace round trip.
        args.add("--tmp");
        args.add(getCacheDir().getAbsolutePath());
        // the mount, and only when a granted game asked for one. the flag being absent is what keeps
        // an ordinary run on exactly the code path it has always been on.
        if (safGameName != null && !safGameName.isEmpty()) {
            args.add("--saf-mount");
            args.add(GuestFiles.MOUNT);
        }
        if (traceFiles) {
            // the game's own directory rather than the one above it, so a second staged game cannot
            // land in the counts, and so the numbers stay comparable between two runs of different
            // titles -- what the guest asks of *a* game is the measurement, not what it asks of the
            // directory games happen to share. under a mount that is the mount itself, which is the
            // same directory named the other way, so the two ways of reaching one game produce two
            // counts that can be put side by side.
            args.add("--trace-files");
            args.add(new File(guestGame).getParent());
        }
        args.add(payload.getAbsolutePath());
        args.add(guestGame);

        // named the way the run reaches it, because the two are different enough that a log which
        // said only "game: X" would not tell you which of the two arms produced the numbers under it.
        Log.i(TAG, "[app] game: " + (safGameName != null && !safGameName.isEmpty()
                ? safGameName + " (through a grant)" : gameName + " (staged)"));
        Log.i(TAG, "[app] starting: " + String.join(" ", args));
        int status = HostLayer.nativeRun(args.toArray(new String[0]));
        Log.i(TAG, "[app] host layer returned " + status);
        // the lookups that came back empty, counted rather than each one reported. it prints only
        // when the guest returns rather than calling exit_group, which is the same limitation the
        // line above it has always had.
        if (safGameName != null && !safGameName.isEmpty()) {
            Log.i(TAG, "[app] " + GuestFiles.missCount() + " lookups came back empty");
        }
    }
}
