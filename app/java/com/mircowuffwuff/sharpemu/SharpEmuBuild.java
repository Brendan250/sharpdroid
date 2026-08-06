package com.mircowuffwuff.sharpemu;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A SharpEmu build: a directory holding a payload, its {@code plugins/}, and a {@code meta.json}
 * that gives it an identity.
 *
 * <p><b>A build is a directory, not a file.</b> SharpEmu resolves {@code plugins/} relative to its
 * own executable — {@code AppContext.BaseDirectory/plugins} for managed plugins and
 * {@code ffmpeg.RootPath} for the Bink decoder — so a payload staged on its own is a payload with
 * no audio and no video. The host layer needs no part of this: {@code GuestProcFS::SetExe} is
 * {@code realpath} of the payload path it is handed, so {@code AppContext.BaseDirectory} follows
 * the build directory by itself.
 *
 * <p>Everything here is the launcher's job on purpose. The host layer keeps taking a payload path
 * and stays a thing that runs an ELF; name resolution, {@code meta.json} and the contract check
 * live where the frontend's own build list will need them anyway, because two implementations of
 * one contract is one too many.
 */
final class SharpEmuBuild {

    private static final String TAG = "sharpemu";

    /**
     * The launcher-to-payload interface generation this app speaks: which environment variables the
     * payload is expected to understand and which host window it must implement.
     *
     * <p>A <b>range</b> rather than a single number, so bumping it does not silently invalidate
     * every build a user has already imported. Outside the range the launch is refused and both
     * numbers are named — because the failure this replaces is a pristine upstream build ignoring
     * {@code SHARPEMU_HOST_WINDOW=android}, constructing an {@code SdlHostWindow} and dying six
     * seconds later on "No available video device", which names the wrong component entirely.
     *
     * <p>It is a courtesy and not a guarantee: a build that declares 1 and lies still dies inside
     * SDL. The real guarantee is knowing where a build came from, which is what the launch log is
     * for.
     */
    /**
     * <p><b>2 means the payload understands the audio flag.</b> The bump is the first real
     * exercise of this mechanism, and it is
     * also the first time the range does not include every generation before it. The reason is
     * what a contract-1 build now does: it does not know {@code SHARPEMU_HOST_AUDIO}, so it asks
     * SDL for a device, SDL names four backends Android does not have, and the port degrades to
     * {@code backend=silent}. The game renders perfectly and makes no sound, and nothing anywhere
     * reports an error — which is precisely the class of failure this check exists to turn into a
     * refusal. "The emulator has no audio" is exactly the report a mismatched build generates.
     *
     * <p>The alternative, {@code 1..2}, was considered and rejected: it would keep old builds
     * launching at the cost of making the one failure they have invisible. Audio is a
     * {@code parity}-tier change — it is part of the output being correct rather than fast — so a
     * build without it is not a build this app should quietly run.
     */
    static final int CONTRACT_MIN = 2;
    static final int CONTRACT_MAX = 2;

    /** Where the build will be run from — always internal storage by the time this is returned. */
    final File dir;
    final String folder;
    final String id;
    final String name;
    final String sharpemuVersion;
    final int buildVersion;
    final int hostContract;
    final String payload;
    final String notes;
    /** Guest environment this build wants defaulted on. The lowest-precedence source there is. */
    final Map<String, String> env;

    private SharpEmuBuild(File dir, String folder, JSONObject json) {
        this.dir = dir;
        this.folder = folder;
        this.id = json.optString("id", folder);
        this.name = json.optString("name", this.id);
        this.sharpemuVersion = json.optString("sharpemuVersion", "0");
        this.buildVersion = json.optInt("buildVersion", 0);
        this.hostContract = json.optInt("hostContract", 0);
        this.payload = json.optString("payload", "SharpEmu");
        this.notes = json.optString("notes", "");
        this.env = new LinkedHashMap<>();
        JSONObject e = json.optJSONObject("env");
        if (e != null) {
            for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                String key = it.next();
                this.env.put(key, e.optString(key));
            }
        }
    }

    File payloadFile() {
        return new File(dir, payload);
    }

    /** id, version, build number and contract — what the launch log has to say. */
    String identity() {
        return name + " (" + id + " " + sharpemuVersion + " b" + buildVersion
                + ", contract " + hostContract + ")";
    }

    /**
     * The most recently staged build, or the most recently installed one if nothing is staged.
     * Returns null and says why if there is nothing at all.
     *
     * <p><b>This is what "no {@code --es sharpemu}" means</b>, and it is the same rule the scripts
     * follow when you omit the flag: whatever the device already has. In a deploy loop "the one I
     * last put there" is what is meant, and the alternative — the highest {@code buildVersion} — is
     * exactly the footgun that selecting by id was removed for, since a freshly staged {@code b1}
     * loses to a {@code b3} that is still lying around.
     *
     * <p>Staged wins over installed wholesale rather than by date. {@code adb} writes the staged
     * directory, so it is the one that moves; an installed copy is a leftover of the id-resolution
     * era, whose copy timestamp says when it was copied and not when its bytes were chosen.
     *
     * <p>It logs the directory it picked and how many it picked from. A run attributed to the wrong
     * artefact is this project's oldest failure, and the cure has always been saying which.
     */
    static SharpEmuBuild mostRecent(File staged, File internal) {
        SharpEmuBuild best = mostRecentIn(staged);
        String where = "staged";
        if (best == null) {
            best = mostRecentIn(internal);
            where = "installed";
        }
        if (best == null) {
            Log.e(TAG, "[app] no build in " + staged + " or " + internal
                    + " — stage one with scripts/stage-build.ps1, or name one with --es sharpemu <path>");
            return null;
        }
        Log.i(TAG, "[app] no build named, so the most recently " + where + " one: " + best.dir);
        if (!accept(best)) {
            return null;
        }
        return best;
    }

    private static SharpEmuBuild mostRecentIn(File root) {
        File[] entries = root.listFiles();
        if (entries == null) {
            return null;
        }
        SharpEmuBuild best = null;
        long bestAt = Long.MIN_VALUE;
        for (File entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }
            SharpEmuBuild build = read(entry);
            if (build != null && entry.lastModified() > bestAt) {
                bestAt = entry.lastModified();
                best = build;
            }
        }
        return best;
    }

    /**
     * Finds the newest build with this id, installs it onto internal storage if it is only staged,
     * and returns it. Returns null and says why on every failure path — <b>never a fallback</b>.
     *
     * <p><b>Deliberately uncalled since 2026-08-05, and kept on purpose.</b> Nothing reaches this
     * from a launch intent any more: {@code --es sharpemu} takes a path and {@link MainActivity}
     * hands it to {@link #resolvePath}. The frontend is what will call this — it resolves a build a
     * user chose by identity, from its own list, without firing an intent at itself, and the install
     * step below is the one it needs. Deleting it would mean writing it again.
     *
     * <p>Its own footgun is why the intent stopped using it: it answers with the <i>highest</i>
     * {@code buildVersion} of that id, so a freshly staged {@code b1} is silently ignored while a
     * {@code b3} exists. That is fine for a user picking from a list of one build per id, and it was
     * not fine for a deploy loop.
     *
     * @param internal {@code getFilesDir()/builds} — where builds are run from, and where nothing
     *                 is ever deleted automatically.
     * @param staged   {@code <external files>/builds} — where adb can write. In the PoC this is the
     *                 stand-in for "bundled in the APK", and the step that follows it is the one the
     *                 real frontend performs.
     */
    static SharpEmuBuild resolve(String id, File internal, File staged) {
        // An absolute path names one build directory and runs it where it lies — no id resolution,
        // no copy onto internal storage. That is what a deploy loop wants: the internal install
        // exists for durability, not because a payload has to be there, and a build
        // running off external FUSE storage at 874–902 ms against 879–907 from internal.
        //
        // It also sidesteps the trap that id resolution has by design: `--es sharpemu parity`
        // answers with the *newest* parity, so a work-in-progress staged as b1 is silently ignored
        // when a b3 is present. A path cannot be ambiguous about which directory it meant.
        if (id.startsWith("/")) {
            return resolvePath(new File(id));
        }

        List<SharpEmuBuild> installed = scan(internal, id);
        List<SharpEmuBuild> available = scan(staged, id);

        SharpEmuBuild best = newest(installed);
        SharpEmuBuild bestStaged = newest(available);
        // a newer staged build wins over an older installed one, which is what makes re-staging the
        // same id with a bumped buildVersion the way to iterate.
        if (best == null || (bestStaged != null && compare(bestStaged, best) > 0)) {
            best = bestStaged;
        }

        if (best == null) {
            Log.e(TAG, "[app] no build called '" + id + "'. looked in " + internal + " and " + staged
                    + " — package one with scripts/package-build.ps1");
            return null;
        }

        if (!accept(best)) {
            return null;
        }

        // extract on first selection, not on install or update: the copy is 76 MB, and a build that
        // is never chosen never costs anything. once chosen it stays, so "the new build broke my
        // game and the old one is gone" cannot happen.
        if (!best.dir.getAbsolutePath().startsWith(internal.getAbsolutePath())) {
            File target = new File(internal, best.folder);
            if (!install(best.dir, target)) {
                return null;
            }
            best = read(target);
            if (best == null) {
                Log.e(TAG, "[app] installed " + id + " and could not read it back from " + target);
                return null;
            }
            if (!accept(best)) {
                return null;
            }
        }
        return best;
    }

    /**
     * Runs a build directory where it lies. Same contract and payload checks as {@link #resolve},
     * and deliberately no install step.
     *
     * <p><b>This is what {@code --es sharpemu} reaches now.</b> The internal install exists for
     * durability rather than because a payload has to be there — a build running off
     * external FUSE storage at 874–902 ms against 879–907 from internal — so a directory named
     * outright is simply run.
     */
    static SharpEmuBuild resolvePath(File dir) {
        if (!dir.isDirectory()) {
            Log.e(TAG, "[app] no build directory at " + dir
                    + " — stage one with scripts/stage-build.ps1");
            return null;
        }
        SharpEmuBuild build = read(dir);
        if (build == null) {
            Log.e(TAG, "[app] " + dir + " has no readable meta.json, so it has no identity"
                    + " — package it with scripts/package-build.ps1");
            return null;
        }
        if (!accept(build)) {
            return null;
        }
        Log.i(TAG, "[app] running in place, not installed: " + dir);
        return build;
    }

    /** The two things that make a build runnable: a contract this app speaks, and its payload. */
    private static boolean accept(SharpEmuBuild build) {
        if (build.hostContract < CONTRACT_MIN || build.hostContract > CONTRACT_MAX) {
            Log.e(TAG, "[app] " + build.identity() + " declares host contract " + build.hostContract
                    + " and this app speaks " + CONTRACT_MIN + ".." + CONTRACT_MAX
                    + " — refusing to launch it");
            return false;
        }
        if (!build.payloadFile().isFile()) {
            Log.e(TAG, "[app] " + build.identity() + " names payload '" + build.payload
                    + "' and it is not in " + build.dir);
            return false;
        }
        return true;
    }

    private static List<SharpEmuBuild> scan(File root, String id) {
        List<SharpEmuBuild> found = new ArrayList<>();
        File[] entries = root.listFiles();
        if (entries == null) {
            return found;
        }
        for (File entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }
            SharpEmuBuild build = read(entry);
            if (build != null && build.id.equals(id)) {
                found.add(build);
            }
        }
        return found;
    }

    private static SharpEmuBuild read(File dir) {
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) {
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
            return new SharpEmuBuild(dir, dir.getName(), new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e(TAG, "[app] could not read " + meta, e);
            return null;
        }
    }

    private static SharpEmuBuild newest(List<SharpEmuBuild> builds) {
        SharpEmuBuild best = null;
        for (SharpEmuBuild build : builds) {
            if (best == null || compare(build, best) > 0) {
                best = build;
            }
        }
        return best;
    }

    private static int compare(SharpEmuBuild a, SharpEmuBuild b) {
        int v = compareVersions(a.sharpemuVersion, b.sharpemuVersion);
        if (v != 0) {
            return v;
        }
        return Integer.compare(a.buildVersion, b.buildVersion);
    }

    /**
     * Orders two {@code sharpemuVersion} strings.
     *
     * <p><b>Not lexicographically</b>, which would put {@code 0.0.10} below {@code 0.0.9}, and
     * SharpEmu's own versions already carry suffixes like {@code -hotfix-2}. The leading dotted
     * numbers are compared numerically, and a suffix orders <i>below</i> a bare version of the same
     * numbers — {@code 0.0.3-hotfix-2} is a 0.0.3, not something after it.
     *
     * <p>Third-party builds are why this is a real comparator rather than an assumption about our
     * own version strings.
     */
    static int compareVersions(String a, String b) {
        String coreA = core(a);
        String coreB = core(b);
        String[] partsA = coreA.isEmpty() ? new String[0] : coreA.split("\\.");
        String[] partsB = coreB.isEmpty() ? new String[0] : coreB.split("\\.");
        for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
            long na = i < partsA.length ? parse(partsA[i]) : 0;
            long nb = i < partsB.length ? parse(partsB[i]) : 0;
            if (na != nb) {
                return na < nb ? -1 : 1;
            }
        }

        String suffixA = a.substring(coreA.length());
        String suffixB = b.substring(coreB.length());
        if (suffixA.isEmpty() != suffixB.isEmpty()) {
            return suffixA.isEmpty() ? 1 : -1;
        }
        // two suffixes of the same shape: their own numbers first, so hotfix-10 beats hotfix-2.
        String[] numsA = suffixA.split("\\D+");
        String[] numsB = suffixB.split("\\D+");
        for (int i = 0; i < Math.max(numsA.length, numsB.length); i++) {
            long na = i < numsA.length ? parse(numsA[i]) : 0;
            long nb = i < numsB.length ? parse(numsB[i]) : 0;
            if (na != nb) {
                return na < nb ? -1 : 1;
            }
        }
        return suffixA.compareTo(suffixB);
    }

    /** The leading {@code 1.2.3} of a version string, without whatever follows it. */
    private static String core(String v) {
        int i = 0;
        int lastDigit = -1;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') {
                lastDigit = i;
            } else if (c != '.') {
                break;
            }
            i++;
        }
        return lastDigit < 0 ? "" : v.substring(0, lastDigit + 1);
    }

    private static long parse(String s) {
        try {
            return s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Copies a staged build onto internal storage.
     *
     * <p><b>Internal storage is a durability decision here, not a technical requirement, and the
     * distinction matters.</b> The GPU driver must be internal because the linker refuses to
     * {@code dlopen} a library from a volume other apps can write. The payload has no such
     * constraint — it is read into anonymous memory, never {@code dlopen}'d, never executed as a
     * file. So nobody should later "optimise" this copy away on the grounds that the driver's
     * reason does not apply.
     */
    private static boolean install(File source, File target) {
        long started = System.currentTimeMillis();
        // a half-finished copy that looks complete is worse than no copy: install beside, then
        // swap, so an interrupted install leaves nothing that resolve() would find.
        File partial = new File(target.getParentFile(), target.getName() + ".partial");
        deleteTree(partial);
        deleteTree(target);
        if (!partial.mkdirs()) {
            Log.e(TAG, "[app] could not create " + partial);
            return false;
        }
        try {
            long bytes = copyTree(source, partial);
            if (!partial.renameTo(target)) {
                Log.e(TAG, "[app] could not move " + partial + " to " + target);
                deleteTree(partial);
                return false;
            }
            Log.i(TAG, "[app] installed " + source.getName() + " (" + bytes + " bytes) to " + target
                    + " in " + (System.currentTimeMillis() - started) + " ms");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[app] could not install " + source, e);
            deleteTree(partial);
            return false;
        }
    }

    private static long copyTree(File source, File target) throws Exception {
        long bytes = 0;
        File[] entries = source.listFiles();
        if (entries == null) {
            return 0;
        }
        for (File entry : entries) {
            File to = new File(target, entry.getName());
            if (entry.isDirectory()) {
                if (!to.isDirectory() && !to.mkdirs()) {
                    throw new Exception("could not create " + to);
                }
                bytes += copyTree(entry, to);
            } else {
                try (InputStream in = new FileInputStream(entry);
                     OutputStream out = new FileOutputStream(to)) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        bytes += n;
                    }
                }
            }
        }
        return bytes;
    }

    private static void deleteTree(File file) {
        File[] entries = file.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                deleteTree(entry);
            }
        }
        file.delete();
    }
}
