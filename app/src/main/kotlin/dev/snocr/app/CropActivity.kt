package dev.snocr.app

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * A focused, full-screen crop screen for a single page: drag a box around the
 * part of the page a question is about. Returns the region normalized to the
 * page so the Ask screen can crop a higher-resolution render of it.
 *
 * Not exported: it is only ever launched by [ReaderActivity] with a path that
 * the reader already validated.
 */
class CropActivity : AppCompatActivity() {

    private lateinit var regionView: RegionSelectView
    private lateinit var statusView: TextView
    private lateinit var useButton: Button

    private var source: PageSource? = null
    private var pageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        regionView = findViewById(R.id.region_view)
        statusView = findViewById(R.id.crop_status)
        useButton = findViewById(R.id.crop_use_button)
        val wholeButton = findViewById<Button>(R.id.crop_whole_button)
        val clearButton = findViewById<Button>(R.id.crop_clear_button)
        val cancelButton = findViewById<Button>(R.id.crop_cancel_button)

        val file = File(intent.getStringExtra(EXTRA_FILE_PATH)!!)
        pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0)
        title = getString(R.string.page_n, pageIndex + 1)

        val initial = intent.getFloatArrayExtra(EXTRA_REGION)?.let {
            if (it.size == 4) RectF(it[0], it[1], it[2], it[3]) else null
        }

        useButton.isEnabled = false
        regionView.onRegionChanged = { r -> useButton.isEnabled = r != null }
        clearButton.setOnClickListener {
            regionView.setNormalizedRect(null)
            useButton.isEnabled = false
        }
        cancelButton.setOnClickListener { finish() }
        wholeButton.setOnClickListener { done(null) }
        useButton.setOnClickListener { done(regionView.normalizedRect()) }

        statusView.text = getString(R.string.opening_notebook)
        thread {
            val opened = runCatching {
                val s = PageSource.open(file)
                s to s.renderPage(pageIndex, CROP_LONG_EDGE)
            }
            runOnUiThread {
                opened.onSuccess { (s, bmp) ->
                    source = s
                    statusView.visibility = View.GONE
                    regionView.setBitmap(bmp)
                    if (initial != null) {
                        regionView.post {
                            regionView.setNormalizedRect(initial)
                            useButton.isEnabled = true
                        }
                    }
                }.onFailure {
                    statusView.text = getString(R.string.parse_failed, it.message)
                }
            }
        }
    }

    private fun done(region: RectF?) {
        val data = Intent().putExtra(EXTRA_PAGE_INDEX, pageIndex)
        if (region != null) {
            data.putExtra(
                EXTRA_REGION,
                floatArrayOf(region.left, region.top, region.right, region.bottom)
            )
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        source?.close()
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_PAGE_INDEX = "page_index"
        const val EXTRA_REGION = "region"
        private const val CROP_LONG_EDGE = 1600
    }
}
