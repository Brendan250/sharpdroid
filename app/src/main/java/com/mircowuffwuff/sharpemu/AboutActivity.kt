package com.mircowuffwuff.sharpemu

import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.mircowuffwuff.sharpemu.databinding.ActivityAboutBinding

/**
 * The About screen: what this is, what it runs, what it was read against, and what it is under.
 *
 * Reached from **Settings → About**, whose card opens this directly — the shape User data already uses
 * for a section that is a screen rather than a list of rows.
 *
 * **A colophon rather than a list of credits.** Five facts is what the screen carries, and five facts
 * do not want five cards to live in: a label column and a value column states all of it at once, on a
 * landscape handheld, with nothing scrolled and nothing hidden behind a tap that only reveals text.
 * `part_about_facts.xml` is the table and is shared by both orientations; `part_about_body.xml` is the
 * composition and has a `-land` variant, because the one thing the shape of the screen changes is
 * whether the drawing sits above the text or beside it.
 *
 * **It credits without thanking.** Listing somebody under *Read against* is the credit; a paragraph
 * about how grateful we are is what happens when a card asks for prose there is no prose to give.
 *
 * **There is no adapter and there is no list.** Everything here ships in the APK and none of it changes
 * while the screen is up. The one thing read off the device is which SharpEmu build a launch would
 * run, and that is asked of [BuildLibrary] rather than worked out again — the same resolution the
 * build row and the launcher itself use, so this screen cannot name a build the launch would not.
 *
 * **No network.** Every link hands its URL to whatever browser the device has and the screen works
 * with none, which is the state some of these devices are in and the state all of them are in on a
 * plane.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var drawnWith: String

    /**
     * Set between the drawing being tapped and the browser being asked for.
     *
     * The drawing answers a tap by moving and *then* leaving, so there is a window in which a second
     * tap would queue a second departure. It is cleared in `onResume`, which is where coming back from
     * the browser lands.
     */
    private var leaving = false

    override fun onCreate(state: Bundle?) {
        // before setContentView, or the theme is resolved after the views are already inflated.
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.toolbar.setTitle(R.string.settings_about)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // the body is an include with a `-land` variant, so everything below binds through it. both
        // variants carry the same ids, which is what lets one set of wiring serve two compositions.
        val body = binding.body
        body.version.text = versionLine()
        body.version.setOnClickListener { copyVersion() }

        // **the drawing is the only way to the donation URL and nothing on the screen says so**,
        // which is the point rather than an oversight: the page names what this app is built on and
        // declines to ask anybody for anything. what is there for whoever presses it anyway is the
        // drawing moving first. the content description is what a screen reader is given instead.
        body.mirco.setOnClickListener { wiggleThenOpen(it, DONATE) }

        val facts = body.facts
        facts.build.text = buildLine()
        facts.sharpemu.setOnClickListener { open(SHARPEMU) }
        facts.eden.setOnClickListener { open(EDEN) }
        facts.gamenative.setOnClickListener { open(GAMENATIVE) }
        facts.source.setOnClickListener { open(REPOSITORY) }
        // **the GPL text this app ships beside its guest libraries, rather than a second copy of it in
        // `res/`.** it is the same document either way, and one copy cannot disagree with the other.
        facts.readLicence.setOnClickListener {
            LicenceTextActivity.open(this, LicencesActivity.textAsset(GPL), GPL)
        }
        facts.thirdParty.setOnClickListener {
            startActivity(Intent(this, LicencesActivity::class.java))
        }

        // the entrance, once, on the way in. deliberately not repeated by onResume: a screen that
        // replays its animation every time something closes over it reads as a screen reloading.
        body.mirco.alpha = 0f
        body.mirco.translationY = 20f
        body.mirco.animate().alpha(1f).translationY(0f).setDuration(380).setStartDelay(40).start()
    }

    override fun onResume() {
        super.onResume()
        leaving = false
        if (Theme.recreateIfStale(this, drawnWith)) return
        // the fullscreen toggle lives on another section and this screen is reachable after it.
        SystemBars.apply(this, binding.root)
    }

    // ---------------------------------------------------------------------------------------------
    // what the lines say

    /**
     * The app version, and the commit this APK was built from when it knows one.
     *
     * **An APK built outside the repository does not know one**, and that is a normal state rather
     * than a fault — `app_commit` is empty there, and the line is the version alone. A placeholder
     * would be worse than saying less: an unresolvable string in a bug report is one somebody then
     * tries to resolve.
     */
    private fun versionLine(): String {
        // the flags overload is deprecated above API 32 and the replacement needs a version guard for
        // a value that has never differed between the two. one call, and this is the whole of it.
        @Suppress("DEPRECATION")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        val commit = getString(R.string.app_commit)
        return if (commit.isEmpty()) version else getString(R.string.about_version, version, commit)
    }

    /**
     * The SharpEmu build a launch would run, as a version and a commit.
     *
     * **Asked of [BuildLibrary] rather than resolved again**, which is what stops this screen naming a
     * build the launcher would not: the store, the bundled build and the most recently staged one are
     * three answers in a defined order, and there is one implementation of that order.
     *
     * **A device with no build at all is a normal state**, since a development APK ships none, and so
     * is a build that records no commit, which is one packaged from a published archive.
     */
    private fun buildLine(): String {
        val internalRoot = AppStorage.installedBuilds(filesDir)
        // a device with no external storage has nothing staged on it, so the internal root answers for
        // both and finds nothing in the second. this screen must not be the one place that throws.
        val staged = AppStorage.stagedBuilds(getExternalFilesDir(null) ?: filesDir)
        val listing = BuildLibrary.of(this, internalRoot, staged, Settings.of(this).build)
        val chosen = (listOfNotNull(listing.bundled) + listing.entries).firstOrNull { it.selected }
            ?.build
            ?: return getString(R.string.about_build_none)
        val commit = chosen.shortCommit()
        return if (commit.isEmpty()) {
            getString(R.string.about_build_no_commit, chosen.sharpemuVersion)
        } else {
            getString(R.string.about_version, chosen.sharpemuVersion, commit)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // what a tap does

    private fun copyVersion() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.settings_about), binding.body.version.text)
        )
        // **android says so itself from API 33**, with a preview of what was copied, and a toast on
        // top of it is the same news twice.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.about_version_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Rocks [view], then opens [url].
     *
     * **The movement is the whole affordance.** The drawing carries no ripple, so nothing about it
     * says it is pressable until it is pressed — and then what answers is not a highlight but the
     * thing itself moving. Leaving at the end of that rather than at the start is what makes the two
     * one gesture: a browser that opened on the down-press would take the screen away before anybody
     * saw the drawing react.
     *
     * The pivot is the bottom edge, so it rocks where it is sitting rather than spinning about its
     * middle, and [leaving] is what stops a second tap queueing a second departure.
     */
    private fun wiggleThenOpen(view: View, url: String) {
        if (leaving) return
        leaving = true
        view.pivotX = view.width / 2f
        view.pivotY = view.height.toFloat()
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -6f, 5f, -3f, 2f, 0f).apply {
            duration = WIGGLE_MS
            doOnEnd {
                // the screen can be left while this runs -- by the back gesture, or by the theme
                // being changed behind it -- and a browser opening out of an activity on its way out
                // is a browser nobody asked for.
                if (!isFinishing && !isDestroyed) open(url)
            }
            start()
        }
    }

    /**
     * Hands [url] to whatever can open it.
     *
     * **A device with nothing that can is a real state here** — a handheld set up to run games and
     * nothing else has no browser — so every link on this screen fails the same way and none of them
     * takes the app with it.
     */
    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (nothingCanOpenIt: ActivityNotFoundException) {
            leaving = false
            Toast.makeText(this, R.string.about_link_failed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val SHARPEMU = "https://github.com/sharpemu/sharpemu"
        const val EDEN = "https://eden-emu.dev/"
        const val GAMENATIVE = "https://github.com/utkarshdalal/GameNative"
        const val REPOSITORY = "https://github.com/sharpemu-android/sharpemu-android"
        const val DONATE = "https://donate.mircowuffwuff.com/"

        /** The licence text this app is itself under, which ships beside the guest libraries. */
        const val GPL = "GPL-2"

        /** Long enough to read as a reaction, short enough that nobody waits on it to leave. */
        const val WIGGLE_MS = 620L
    }
}
