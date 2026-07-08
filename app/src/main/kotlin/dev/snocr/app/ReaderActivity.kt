package dev.snocr.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/**
 * Opens a notebook (or PDF) and shows its pages in a vertical scroll, the way
 * the Supernote app presents a document. Tap pages to select which ones the
 * question should be about; long-press a page to pick just an area of it. Then
 * continue to the Ask screen.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var statusView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var askButton: Button
    private lateinit var cropLauncher: ActivityResultLauncher<Intent>

    private var pageCount = 0
    private val selected = linkedSetOf<Int>()
    private val regions = HashMap<Int, RectF>()

    // One page render at a time (PdfRenderer requires it); cache rendered pages.
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val pageCache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private var source: PageSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        file = File(intent.getStringExtra(EXTRA_FILE_PATH)!!)
        title = file.name

        statusView = findViewById(R.id.reader_status)
        recycler = findViewById(R.id.page_list)
        askButton = findViewById(R.id.reader_ask_button)
        recycler.layoutManager = LinearLayoutManager(this)

        cropLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val idx = data.getIntExtra(CropActivity.EXTRA_PAGE_INDEX, -1)
            if (idx < 0) return@registerForActivityResult
            val region = data.getFloatArrayExtra(CropActivity.EXTRA_REGION)?.let {
                if (it.size == 4) RectF(it[0], it[1], it[2], it[3]) else null
            }
            if (region != null) regions[idx] = region else regions.remove(idx)
            selected.add(idx)
            recycler.adapter?.notifyItemChanged(idx)
            updateAskButton()
        }

        askButton.setOnClickListener { openAsk() }
        updateAskButton()

        statusView.text = getString(R.string.opening_notebook)
        renderExecutor.execute {
            val opened = runCatching { PageSource.open(file) }
            runOnUiThread {
                opened.onSuccess {
                    source = it
                    pageCount = it.pageCount
                    statusView.visibility = View.GONE
                    recycler.adapter = PageAdapter()
                }.onFailure {
                    statusView.text = getString(R.string.parse_failed, it.message)
                    askButton.isEnabled = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderExecutor.shutdownNow()
        source?.close()
    }

    private fun updateAskButton() {
        askButton.text = if (selected.isEmpty()) {
            getString(R.string.ask_about_all)
        } else {
            resources.getQuantityString(R.plurals.ask_about_pages, selected.size, selected.size)
        }
    }

    private fun openAsk() {
        val indices = if (selected.isEmpty()) IntArray(pageCount) { it } else selected.sorted().toIntArray()
        val regionArr = FloatArray(indices.size * 4) { -1f }
        indices.forEachIndexed { i, idx ->
            regions[idx]?.let { r ->
                regionArr[i * 4] = r.left
                regionArr[i * 4 + 1] = r.top
                regionArr[i * 4 + 2] = r.right
                regionArr[i * 4 + 3] = r.bottom
            }
        }
        startActivity(
            Intent(this, AskActivity::class.java)
                .putExtra(AskActivity.EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(AskActivity.EXTRA_PAGE_INDICES, indices)
                .putExtra(AskActivity.EXTRA_PAGE_REGIONS, regionArr)
        )
    }

    private fun launchCrop(pageIndex: Int) {
        val intent = Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_FILE_PATH, file.absolutePath)
            .putExtra(CropActivity.EXTRA_PAGE_INDEX, pageIndex)
        regions[pageIndex]?.let {
            intent.putExtra(
                CropActivity.EXTRA_REGION,
                floatArrayOf(it.left, it.top, it.right, it.bottom)
            )
        }
        cropLauncher.launch(intent)
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_page, parent, false)
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(position)
        }
    }

    private inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.page_image)
        private val label: TextView = view.findViewById(R.id.page_label)
        private val check: View = view.findViewById(R.id.page_check)
        private var boundPage = -1

        init {
            view.setOnClickListener {
                if (boundPage < 0) return@setOnClickListener
                if (!selected.add(boundPage)) {
                    selected.remove(boundPage)
                    regions.remove(boundPage)
                }
                updateSelection()
                updateAskButton()
            }
            view.setOnLongClickListener {
                if (boundPage < 0 || source == null) return@setOnLongClickListener false
                launchCrop(boundPage)
                true
            }
        }

        fun bind(position: Int) {
            boundPage = position
            updateSelection()
            val cached = pageCache.get(position)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.setImageBitmap(null)
                image.setBackgroundColor(Color.parseColor("#EEEEEE"))
                renderExecutor.execute {
                    val src = source ?: return@execute
                    val bitmap = runCatching { src.renderPage(position, READER_LONG_EDGE) }.getOrNull()
                    if (bitmap != null) {
                        pageCache.put(position, bitmap)
                        image.post {
                            if (boundPage == position) {
                                image.setBackgroundColor(Color.WHITE)
                                image.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }

        private fun updateSelection() {
            label.text = if (regions.containsKey(boundPage)) {
                getString(R.string.page_area, boundPage + 1)
            } else {
                getString(R.string.page_n, boundPage + 1)
            }
            check.visibility = if (selected.contains(boundPage)) View.VISIBLE else View.GONE
            itemView.isSelected = selected.contains(boundPage)
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        private const val READER_LONG_EDGE = 1400
    }
}
