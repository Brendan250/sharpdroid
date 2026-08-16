package com.mircowuffwuff.sharpemu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.mircowuffwuff.sharpemu.databinding.ActivityLicenceTextBinding
import java.util.concurrent.Executors

/**
 * One licence document, read as it ships.
 *
 * Reached from the Third-party licences list, and from the About screen's own licence card — the text
 * this app is under is the GPL, and the GPL ships beside the guest libraries already, so there is one
 * copy of it in the APK rather than two that can disagree.
 *
 * **Nothing is reformatted and nothing is parsed.** These are plain text written by the people whose
 * terms they state, laid out for a fixed-width column: indented clauses, aligned numbering, a title
 * centred with spaces. Rendering them as prose reflows all of it into something that reads as damaged,
 * and looking for markdown in them finds headings and emphasis that are not there.
 *
 * **The read does not trim.** Every one of these files opens with a title centred by leading spaces,
 * so stripping whitespace shifts the first line left and nothing else — which looks like a bug in the
 * document rather than in the reader.
 *
 * **Off the main thread, for the largest rather than the typical.** These run from 2 to 68 KB and the
 * big one is a copyright statement listing every file in a compiler, so the screen draws its frame
 * first and fills in.
 */
class LicenceTextActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicenceTextBinding
    private lateinit var drawnWith: String
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)
        binding = ActivityLicenceTextBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        val asset = intent.getStringExtra(EXTRA_ASSET)
        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (asset == null) {
            // nothing but a hand-written intent reaches this. finishing beats an empty page that
            // looks like a licence with no text in it.
            finish()
            return
        }
        worker.execute {
            val text = read(asset)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.text.text = text ?: getString(R.string.licences_unreadable)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Theme.recreateIfStale(this, drawnWith)) return
        SystemBars.apply(this, binding.root)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** The asset whole, byte for byte. See the class note on why nothing is trimmed. */
    private fun read(asset: String): String? = try {
        assets.open(asset).use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        Log.e(TAG, "[app] could not read the asset $asset", e)
        null
    }

    companion object {
        private const val TAG = "sharpemu"

        private const val EXTRA_ASSET = "asset"
        private const val EXTRA_TITLE = "title"

        /**
         * Opens [asset], titled [title].
         *
         * **The title is passed rather than derived from the path**, because the two differ: a
         * copyright statement is named for its source package and lives in a file with a suffix on it,
         * and the list is where that is already worked out.
         */
        fun open(context: Context, asset: String, title: String) = context.startActivity(
            Intent(context, LicenceTextActivity::class.java)
                .putExtra(EXTRA_ASSET, asset)
                .putExtra(EXTRA_TITLE, title)
        )
    }
}
