package com.mircowuffwuff.sharpemu

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * A game the app can launch: a directory holding an `eboot.bin`, and whatever identity the dump
 * carries beside it.
 *
 * **The directory name stays the identity the rest of the app works in**, and [name] and [titleId]
 * are decoration on top of it. [folder] is what the host layer is handed, what every staging script
 * writes and what every log line says, so a row whose display name is missing or wrong is still a
 * row that can be launched and found on disk.
 *
 * **Where the files are is [source]'s to know and nobody else's.** A staged directory and a game
 * inside a granted tree produce the same row and the same launch, and differ only in what the intent
 * carries — see [GameSource] and [GameListActivity.launch].
 *
 * @see GameLibrary for where these come from and which volumes are searched.
 */
data class Game(
    val source: GameSource,
    /** What to call it on screen. The dump's own title name, or [folder] when there is none. */
    val name: String,
    /** e.g. `PPSA02929`. Null when neither the dump nor the directory name offers one. */
    val titleId: String?,
) {

    /** The directory name, e.g. `Dreaming Sarah [PPSA02929]`. What [MainActivity] takes as `game`. */
    val folder: String get() = source.folder

    /** The dump's artwork for coil, or null. A real PNG, not the `.dds` beside it. */
    val icon: Any? get() = source.icon

    companion object {

        const val EBOOT = "eboot.bin"

        const val PARAM = "sce_sys/param.json"

        /** Where the emulator looks second. See [GameSource.openParam]. */
        const val PARAM_BESIDE_EBOOT = "param.json"

        const val ICON = "sce_sys/icon0.png"

        /**
         * A cap on `param.json`, beyond which it is not read at all.
         *
         * Both dumps checked are a few kilobytes. This is not a real format limit — it is a refusal
         * to pull an arbitrarily large file into memory on the strength of its path, since the
         * directory it comes from is one anybody can write with `adb push` or hand us with a grant.
         *
         * **It is enforced on the stream rather than on a reported length**, which is what lets one
         * reader serve both sources: a staged file could be measured with `length()` first, and a
         * document could not without a second provider round trip to learn what reading it says
         * anyway.
         */
        private const val PARAM_MAX_BYTES = 1 * 1024 * 1024

        private const val TAG = "sharpemu"

        /**
         * One directory's identity, whichever kind of directory it is.
         *
         * **Every part of this degrades to the directory name rather than failing.** A dump with no
         * `sce_sys/`, a truncated `param.json`, a `param.json` that is not JSON at all — each of
         * those is a game that boots perfectly well, so none of them may cost a row.
         */
        fun read(source: GameSource): Game {
            val param = readParam(source)
            return Game(
                source = source,
                name = param?.let(::titleName) ?: source.folder,
                titleId = param?.optString("titleId")?.takeIf { it.isNotBlank() }
                    ?: titleIdFromFolder(source.folder),
            )
        }

        /**
         * **The title id the emulator will resolve for this dump, sanitized the way it sanitizes
         * one.** Never null: a dump that offers none resolves to `UNKNOWN`, which is the emulator's
         * own answer rather than a placeholder of ours.
         *
         * This exists so the launcher can name a per-title directory the emulator would otherwise
         * have named itself — the pipeline cache, whose environment variable takes the blob's path
         * rather than a root to hang a layout under. **So the rule has to be the emulator's rule and
         * not a plausible imitation**, and every part of it is matched deliberately: the field is
         * `titleId` and it counts only when it is a JSON *string*, `param.json` is looked for under
         * `sce_sys/` and then beside the eboot, and each character survives only if it is an ASCII
         * letter, digit, `-` or `_`, uppercased, with everything else becoming `_`.
         *
         * **A disagreement here is silent.** It would not break a run: the cache is validated by the
         * driver and rebuilt when it is rejected. It would file one game's pipelines under a name
         * nothing else uses, so a launch would quietly recompile what it had already compiled, and
         * the directory would sit beside a save data directory named the other way. [titleId] is the
         * *list's* answer to the same question and is deliberately not this one — it falls back to
         * the `[PPSA…]` in a directory name, which is a staging convention of ours that the emulator
         * knows nothing about.
         */
        @JvmStatic
        fun emulatorTitleId(param: InputStream?, folder: String): String {
            val raw = try {
                param?.use { stream ->
                    readCapped(stream, folder)?.let { JSONObject(it) }?.opt("titleId") as? String
                }
            } catch (e: Exception) {
                Log.w(TAG, "[app] could not read " + PARAM + " of " + folder + ": " + e)
                null
            }
            return sanitizeTitleId(raw)
        }

        private fun sanitizeTitleId(raw: String?): String {
            val trimmed = raw?.trim()
            if (trimmed.isNullOrEmpty()) {
                return "UNKNOWN"
            }
            val out = StringBuilder(trimmed.length)
            for (character in trimmed) {
                val kept = character in 'A'..'Z' || character in 'a'..'z' ||
                    character in '0'..'9' || character == '-' || character == '_'
                out.append(if (kept) character.uppercaseChar() else '_')
            }
            return out.toString()
        }

        private fun readParam(source: GameSource): JSONObject? =
            try {
                source.openParam()?.use { JSONObject(readCapped(it, source.folder) ?: return null) }
            } catch (e: Exception) {
                // and the row falls back to the directory name. logged rather than swallowed,
                // because a dump the emulator boots and the list cannot name is worth seeing once.
                Log.w(TAG, "[app] could not read " + PARAM + " of " + source.folder + ": " + e)
                null
            }

        /** The whole stream as text, or null if it turned out to be larger than [PARAM_MAX_BYTES]. */
        private fun readCapped(stream: InputStream, folder: String): String? {
            // one byte past the cap, so "exactly at the cap" and "over it" are distinguishable
            // without asking anything how long it is.
            val raw = stream.readAtMost(PARAM_MAX_BYTES + 1)
            if (raw.size > PARAM_MAX_BYTES) {
                Log.w(TAG, "[app] ignoring oversized " + PARAM + " of " + folder)
                return null
            }
            return String(raw, Charsets.UTF_8)
        }

        /** At most [limit] bytes, which the standard `readBytes` has no form of. */
        private fun InputStream.readAtMost(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * The display name, in the language the device is set to if the dump has it.
         *
         * `localizedParameters` holds one object per language tag — `en-US`, `ja-JP` — alongside a
         * plain `defaultLanguage` string, which is why the entries are filtered to objects before
         * anything looks at them.
         *
         * The order is: the exact tag, then any entry in the same language whatever its region, then
         * the dump's own default, then simply the first name present. The last step is what stops a
         * dump localised into languages this device is not set to from showing a directory name.
         */
        private fun titleName(param: JSONObject): String? {
            val localized = param.optJSONObject("localizedParameters") ?: return null
            val entries = localized.keys().asSequence()
                .mapNotNull { key -> localized.optJSONObject(key)?.let { key to it } }
                .toList()
            if (entries.isEmpty()) return null

            val locale = Locale.getDefault()
            val wanted = buildList {
                add(locale.toLanguageTag())
                if (locale.language.isNotEmpty()) add(locale.language)
                localized.optString("defaultLanguage").takeIf { it.isNotBlank() }?.let { add(it) }
            }

            for (tag in wanted) {
                val exact = entries.firstOrNull { it.first.equals(tag, ignoreCase = true) }
                exact?.second?.localTitleName()?.let { return it }

                val language = tag.substringBefore('-')
                val loose = entries.firstOrNull {
                    it.first.substringBefore('-').equals(language, ignoreCase = true)
                }
                loose?.second?.localTitleName()?.let { return it }
            }

            return entries.firstNotNullOfOrNull { it.second.localTitleName() }
        }

        private fun JSONObject.localTitleName(): String? =
            optString("titleName").takeIf { it.isNotBlank() }

        /**
         * The title id out of a directory named `Dreaming Sarah [PPSA02929]`.
         *
         * The staging convention rather than the format: this is only reached when the dump did not
         * say, and a directory somebody named that way is telling us something the file did not.
         */
        private fun titleIdFromFolder(folder: String): String? =
            Regex("""\[([A-Za-z0-9-]+)]\s*$""").find(folder)?.groupValues?.get(1)
    }
}
