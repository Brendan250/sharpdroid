package com.mircowuffwuff.sharpdroid

import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.mircowuffwuff.sharpdroid.databinding.ActivityAboutBinding

/**
 * the About screen: what this is, what it runs, what it was read against, and what it is under.
 *
 * reached from **Settings → About**, whose card opens this directly -- the shape User data already uses
 * for a section that is a screen rather than a list of rows.
 *
 * **a colophon rather than a list of credits.** five facts is what the screen carries, and five facts
 * do not want five cards to live in: a label column and a value column states all of it at once, on a
 * landscape handheld, with nothing scrolled and nothing hidden behind a tap that only reveals text.
 * `part_about_facts.xml` is the table and is shared by both orientations; `part_about_body.xml` is the
 * composition and has a `-land` variant, because the one thing the shape of the screen changes is
 * whether the drawing sits above the text or beside it.
 *
 * **it credits without thanking.** listing somebody under *Read against* is the credit; a paragraph
 * about how grateful we are is what happens when a card asks for prose there is no prose to give.
 *
 * **there is no adapter and there is no list.** everything here ships in the APK and none of it changes
 * while the screen is up. the one thing read off the device is which SharpEmu build a launch would
 * run, and that is asked of [BuildLibrary] rather than worked out again -- the same resolution the
 * build row and the launcher itself use, so this screen cannot name a build the launch would not.
 *
 * **no network.** every link hands its URL to whatever browser the device has and the screen works
 * with none, which is the state some of these devices are in and the state all of them are in on a
 * plane.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var drawnWith: String

    /**
     * set between the drawing being tapped and the browser being asked for.
     *
     * the drawing answers a tap by moving and *then* leaving, so there is a window in which a second
     * tap would queue a second departure. it is cleared in `onResume`, which is where coming back from
     * the browser lands -- and is also what tells that method the face is still the pulled one and
     * wants putting back.
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

        binding.toolbar.setTitle(R.string.about)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // the body is an include with a `-land` variant, so everything below binds through it. both
        // variants carry the same ids, which is what lets one set of wiring serve two compositions.
        val body = binding.body
        body.version.text = versionLine()
        body.version.setOnClickListener { copyVersion() }

        // **the drawing is the only way to the donation URL, and what says so is lettering laid over
        // it rather than a sentence.** the page names what this app is built on and asks for nothing
        // in words; the invitation is drawn, and what answers a press is the drawing rocking and
        // pulling a face. the content description is what a screen reader is given instead, and it
        // names the same person in either expression.
        body.mirco.setOnClickListener { wiggleThenOpen(body.mirco, DONATE) }

        val facts = body.facts
        facts.versions.text = versionsLine()
        facts.sharpemu.setOnClickListener { open(SHARPEMU) }
        facts.fexcore.setOnClickListener { open(FEXCORE) }
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
        //
        // **it rises to where the layout put the drawing rather than to zero.** the layout lifts it
        // off the block by translating it, so an entrance that ended at zero would animate that lift
        // away on every visit -- silently, since the drawing would simply settle a few dp lower than
        // it was drawn. the resting value is read here rather than named, so moving it is still a
        // one-attribute change in the layout.
        //
        // **only the drawing enters. the lettering laid over it is on the page from the first
        // frame**, at the place and the strength the layout gives it, so what the entrance shows is
        // the character settling in under an invitation that is already there rather than a block of
        // picture sliding up as one.
        val resting = body.mirco.translationY
        body.mirco.alpha = 0f
        body.mirco.translationY = resting + ENTRANCE_RISE
        body.mirco.animate().alpha(1f).translationY(resting)
            .setDuration(380).setStartDelay(40).start()
    }

    override fun onResume() {
        super.onResume()
        // **coming back is where the face goes back**, because the departure carries it: the last
        // thing seen of this screen is the drawing still pulling it, and the first thing seen of it
        // again is the drawing composed. putting it back on the way out instead would spend the
        // whole expression on the frame or two before a browser covers the screen.
        if (leaving) binding.body.mirco.setImageResource(R.drawable.mirco)
        leaving = false
        if (Theme.recreateIfStale(this, drawnWith)) return
        // the fullscreen toggle lives on another section and this screen is reachable after it.
        SystemBars.apply(this, binding.root)
    }

    // ---------------------------------------------------------------------------------------------
    // what the lines say

    /**
     * the app version, and the commit this APK was built from when it knows one.
     *
     * **an APK built outside the repository does not know one**, and that is a normal state rather
     * than a fault -- `app_commit` is empty there, and the line is the version alone. a placeholder
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
        // **the version stands bare, unlike the SharpEmu build's version on the same screen.** a
        // release here is a counter and the tag that names it is what carries the prefix, so a v in
        // front would be a third spelling of one release. the separator is about_version, which is
        // also what parts the two emulators, so one screen divides values one way.
        return if (commit.isEmpty()) {
            version
        } else {
            getString(R.string.about_version, version, commit)
        }
    }

    /**
     * both emulators' versions, on one line, in the order the two names above them are in.
     *
     * **it is one string rather than a field each with a separator between them**, because the dot
     * dividing the two is meant to be the same mark as the dot inside the SharpEmu version -- and the
     * only way to be sure of that is for it to be the same character of the same string. it is also
     * `about_version`, the format the app's own version line uses, so the two lines cannot drift.
     *
     * **the dot goes with the value it introduces.** a build that could not describe the FEX submodule
     * leaves the line at SharpEmu's half alone: an empty field beside a full one reads as a value that
     * failed, and a separator with nothing after it as one still arriving.
     */
    private fun versionsLine(): String {
        val build = buildLine()
        val fex = getString(R.string.fex_version)
        return if (fex.isEmpty()) build else getString(R.string.about_version, build, fex)
    }

    /**
     * the SharpEmu build a launch would run, as a version and a commit.
     *
     * **asked of [BuildLibrary] rather than resolved again**, which is what stops this screen naming a
     * build the launcher would not: the store, the bundled build and the most recently staged one are
     * three answers in a defined order, and there is one implementation of that order.
     *
     * **a device with no build at all is a normal state**, since a development APK ships none, and so
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
            // the build manager's own format for this pair, so one build reads identically on both
            // screens rather than nearly identically.
            getString(R.string.version_commit, chosen.sharpemuVersion, commit)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // what a tap does

    private fun copyVersion() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.about), binding.body.version.text)
        )
        // **android says so itself from API 33**, with a preview of what was copied, and a toast on
        // top of it is the same news twice.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.about_version_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * rocks [drawing], pulls a face doing it, and opens [url] on the beat the rock lands.
     *
     * **the lettering asks and the movement answers.** the drawing carries no ripple, so what says it
     * is pressable is the invitation drawn over it, and what a press gets back is not a highlight but
     * the thing itself moving. leaving at the end of that rather than at the start is what makes the
     * two one gesture: a browser that opened on the down-press would take the screen away before
     * anybody saw the drawing react.
     *
     * **the lettering takes no part in this, which is why it is a sibling view rather than a layer
     * of this drawable.** a layer would be registered to the artwork for free and would need no
     * second view -- and it is drawn *inside* the view, so it would rotate with the rock and ride the
     * entrance too, and the lettering does neither. the character is what reacts to a press: a
     * caption swinging along reads as the whole picture wobbling. two views laid on the same box
     * cost a comment instead, and buy animations that move only what is meant to move.
     *
     * **the face is swapped outright rather than crossfaded**, and it is the same drawing with a
     * different expression on it -- same size, same outline, same everything but the eyes and the
     * mouth. a dissolve between two of those reads as an image loading; a cut reads as somebody
     * reacting, which is what is being drawn.
     *
     * **the face outlasts both the rock and the departure, and `onResume` is what puts it back.**
     * the rock is over in [WIGGLE_MS] and the expression is worth more than that -- but the way to
     * spend it is not to make anybody wait for it. the browser is asked for the moment the rock
     * settles, exactly as it would be with one face; what changes is only that the drawing is still
     * pulling the other one as the screen is taken away, and is composed again when it comes back.
     *
     * **nothing opening is the one case that has to put the face back itself**, since there is then
     * no browser to cover the screen and no return to be resumed from. [FACE_HELD_MS] is how long it
     * sits there first, so a failed link still reads as the drawing having answered the press.
     *
     * the pivot is the bottom edge, so it rocks where it is sitting rather than spinning about its
     * middle, and [leaving] is what stops a second tap queueing a second departure.
     */
    private fun wiggleThenOpen(drawing: ImageView, url: String) {
        if (leaving) return
        leaving = true
        drawing.setImageResource(R.drawable.mirco_tapped)
        drawing.pivotX = drawing.width / 2f
        drawing.pivotY = drawing.height.toFloat()
        ObjectAnimator.ofFloat(drawing, View.ROTATION, 0f, -6f, 5f, -3f, 2f, 0f).apply {
            duration = WIGGLE_MS
            // `doOnEnd` runs whether the rock finished or was cancelled under it, so there is no
            // way to end up rocked, faced and going nowhere.
            doOnEnd {
                // the screen can be left while this runs -- by the back gesture, or by the theme
                // being changed behind it -- and a browser opening out of an activity on its way out
                // is a browser nobody asked for. the face leaves with the screen either way.
                if (isFinishing || isDestroyed) return@doOnEnd
                // **the two drawings are the same size**, so putting the resting one back asks for
                // no layout pass and the page does not move under the swap in either direction.
                if (!open(url)) {
                    drawing.postDelayed({ drawing.setImageResource(R.drawable.mirco) }, FACE_HELD_MS)
                }
            }
            start()
        }
    }

    /**
     * hands [url] to whatever can open it, and says whether anything took it.
     *
     * **a device with nothing that can is a real state here** -- a handheld set up to run games and
     * nothing else has no browser -- so every link on this screen fails the same way and none of them
     * takes the app with it. the answer is what the drawing needs: a link that opened is a screen
     * about to be covered and later resumed, and a link that did not is a screen that stays where it
     * is, with whatever the press changed still on it.
     *
     * the five links that are text ignore it, which is right rather than an oversight -- the toast
     * is the whole of what a failure owes them.
     */
    private fun open(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (nothingCanOpenIt: ActivityNotFoundException) {
            leaving = false
            Toast.makeText(this, R.string.about_link_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    private companion object {
        const val SHARPEMU = "https://github.com/sharpemu/sharpemu"

        /** FEX's repository rather than its site, since the repository is what this pins. */
        const val FEXCORE = "https://github.com/FEX-Emu/FEX"
        const val EDEN = "https://eden-emu.dev/"
        const val GAMENATIVE = "https://github.com/utkarshdalal/GameNative"
        const val REPOSITORY = "https://github.com/mircowuffwuff/sharpdroid"
        const val DONATE = "https://support.mircowuffwuff.com/"

        /** the licence text this app is itself under, which ships beside the guest libraries. */
        const val GPL = "GPL-2"

        /** long enough to read as a reaction, short enough that nobody waits on it to leave. */
        const val WIGGLE_MS = 620L

        /**
         * how long the pulled face stays on when nothing opens, in milliseconds.
         *
         * **it is the failure path's number and no other.** a link that opens spends the face on the
         * departure and gets it back on the return, so this stands in for a browser that never
         * arrives -- measured from the end of the rock, which keeps it independent of [WIGGLE_MS].
         */
        const val FACE_HELD_MS = 1000L

        /** how far below its resting place the drawing starts, in pixels. */
        const val ENTRANCE_RISE = 20f
    }
}
