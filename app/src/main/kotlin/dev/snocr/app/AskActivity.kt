package dev.snocr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.snocr.claudekit.AiApiException
import dev.snocr.claudekit.AiConversation
import dev.snocr.claudekit.Attachment
import dev.snocr.claudekit.Prompts
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The question screen. Write your question by hand (default) or type it, and
 * stream the answer. Context is a set of notebook pages (rendered locally to
 * images) or content shared from another app.
 */
class AskActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var drawingView: DrawingView
    private lateinit var promptInput: EditText
    private lateinit var toggleInputButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button
    private lateinit var askButton: Button
    private lateinit var transcribeButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button
    private lateinit var resetButton: Button
    private lateinit var statusView: TextView
    private lateinit var answerView: TextView
    private lateinit var answerScroll: ScrollView

    private var noteFile: File? = null
    private var pageIndices: IntArray? = null
    private var sharedUris: List<Uri> = emptyList()

    private var conversation: AiConversation? = null
    private var typingMode = false
    @Volatile private var working = false
    @Volatile private var stopRequested = false

    private class StoppedException : Exception()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ask)
        prefs = Prefs(this)

        titleView = findViewById(R.id.ask_title)
        drawingView = findViewById(R.id.drawing_view)
        promptInput = findViewById(R.id.prompt_input)
        toggleInputButton = findViewById(R.id.toggle_input_button)
        undoButton = findViewById(R.id.undo_button)
        clearButton = findViewById(R.id.clear_button)
        askButton = findViewById(R.id.ask_button)
        transcribeButton = findViewById(R.id.transcribe_button)
        stopButton = findViewById(R.id.stop_button)
        copyButton = findViewById(R.id.copy_button)
        shareButton = findViewById(R.id.share_button)
        resetButton = findViewById(R.id.reset_button)
        statusView = findViewById(R.id.status_view)
        answerView = findViewById(R.id.answer_view)
        answerScroll = findViewById(R.id.answer_scroll)

        toggleInputButton.setOnClickListener { setTypingMode(!typingMode) }
        undoButton.setOnClickListener { drawingView.undo() }
        clearButton.setOnClickListener { drawingView.clear() }
        askButton.setOnClickListener { onAsk() }
        transcribeButton.setOnClickListener { startTurn(Prompts.TRANSCRIBE, null, isTranscribe = true) }
        stopButton.setOnClickListener {
            stopRequested = true
            conversation?.cancel()
        }
        copyButton.setOnClickListener { copyAnswer() }
        shareButton.setOnClickListener { shareAnswer() }
        resetButton.setOnClickListener { resetConversation() }

        setTypingMode(false)
        loadSource(intent)
    }

    private fun loadSource(intent: Intent) {
        when {
            intent.hasExtra(EXTRA_FILE_PATH) -> {
                val file = File(intent.getStringExtra(EXTRA_FILE_PATH)!!)
                if (!isAllowedPath(file)) {
                    disableWith(getString(R.string.nothing_shared))
                    return
                }
                noteFile = file
                pageIndices = intent.getIntArrayExtra(EXTRA_PAGE_INDICES)
                val pageNote = pageIndices?.let {
                    " · " + resources.getQuantityString(R.plurals.n_pages, it.size, it.size)
                } ?: ""
                titleView.text = file.name + pageNote
            }
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                sharedUris = extractSharedUris(intent)
                titleView.text = resources.getQuantityString(
                    R.plurals.shared_items, sharedUris.size, sharedUris.size
                )
                if (sharedUris.isEmpty()) disableWith(getString(R.string.nothing_shared))
            }
            else -> disableWith(getString(R.string.nothing_shared))
        }
    }

    private fun disableWith(message: String) {
        setStatus(message)
        askButton.isEnabled = false
        transcribeButton.isEnabled = false
    }

    @Suppress("DEPRECATION")
    private fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        else -> emptyList()
    }

    private fun setTypingMode(typing: Boolean) {
        typingMode = typing
        promptInput.visibility = if (typing) View.VISIBLE else View.GONE
        drawingView.visibility = if (typing) View.GONE else View.VISIBLE
        undoButton.visibility = if (typing) View.GONE else View.VISIBLE
        clearButton.visibility = if (typing) View.GONE else View.VISIBLE
        toggleInputButton.text =
            getString(if (typing) R.string.write_instead else R.string.type_instead)
        if (typing) {
            promptInput.requestFocus()
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(promptInput.windowToken, 0)
        }
    }

    private fun onAsk() {
        if (typingMode) {
            val text = promptInput.text.toString().trim()
            if (text.isEmpty()) {
                setStatus(getString(R.string.enter_question))
                return
            }
            startTurn(text, null)
        } else {
            val bitmap = drawingView.exportBitmap()
            if (bitmap == null) {
                setStatus(getString(R.string.write_a_question))
                return
            }
            val png = bitmap.toPng()
            bitmap.recycle()
            startTurn(Prompts.HANDWRITTEN_QUESTION, png)
        }
    }

    private fun startTurn(questionText: String, questionImage: ByteArray?, isTranscribe: Boolean = false) {
        if (working) return
        if (prefs.activeKey.isEmpty()) {
            setStatus(getString(R.string.api_key_missing))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        working = true
        stopRequested = false
        setControlsEnabled(false)
        if (conversation == null) answerView.text = ""
        appendAnswerHeader(if (isTranscribe) getString(R.string.transcription) else getString(R.string.your_question))

        thread {
            try {
                val active = conversation ?: AiConversation.create(prefs.aiConfig())
                conversation = active
                val context =
                    if (active.turnCount == 0) prepareContext() else emptyList()
                if (stopRequested) throw StoppedException()
                setStatus(getString(R.string.waiting_for_model, prefs.activeModelId))
                var firstDelta = true
                val result = active.ask(questionText, questionImage, context) { delta ->
                    runOnUiThread {
                        if (firstDelta) {
                            firstDelta = false
                            setStatus(getString(R.string.answering))
                        }
                        answerView.append(delta)
                        answerScroll.post { answerScroll.scrollTo(0, answerView.bottom) }
                    }
                }
                runOnUiThread {
                    when (result.stopReason) {
                        "refusal" -> setStatus(getString(
                            R.string.refused,
                            result.refusalExplanation ?: getString(R.string.refused_generic)
                        ))
                        "max_tokens" -> setStatus(getString(R.string.truncated))
                        else -> setStatus(getString(R.string.done_hint))
                    }
                    promptInput.setText("")
                    drawingView.clear()
                }
            } catch (e: StoppedException) {
                runOnUiThread { setStatus(getString(R.string.stopped)) }
            } catch (e: AiApiException) {
                runOnUiThread { setStatus(getString(R.string.api_error, e.message)) }
            } catch (e: IOException) {
                runOnUiThread {
                    setStatus(
                        if (stopRequested) getString(R.string.stopped)
                        else getString(R.string.network_error, e.message)
                    )
                }
            } catch (e: IllegalArgumentException) {
                runOnUiThread { setStatus(e.message ?: getString(R.string.generic_error)) }
            } catch (e: OutOfMemoryError) {
                conversation = null
                runOnUiThread { setStatus(getString(R.string.out_of_memory)) }
            } catch (e: Exception) {
                runOnUiThread { setStatus(getString(R.string.generic_error) + ": " + e.message) }
            } finally {
                working = false
                runOnUiThread { setControlsEnabled(true) }
            }
        }
    }

    /** Renders the selected pages / shared content to image attachments. Worker thread. */
    private fun prepareContext(): List<Attachment> {
        val file = noteFile
        return when {
            file != null -> renderNotebookPages(file)
            else -> sharedUris.flatMap { attachmentsFromUri(it) }
        }
    }

    private fun renderNotebookPages(file: File): List<Attachment> {
        val isNote = file.extension.equals("note", ignoreCase = true)
        PageSource.open(file).use { source ->
            val indices = pageIndices ?: IntArray(source.pageCount) { it }
            if (indices.size > prefs.maxPages) {
                throw IllegalArgumentException(
                    getString(R.string.too_many_pages, indices.size, prefs.maxPages)
                )
            }
            return indices.mapIndexed { i, pageIndex ->
                if (stopRequested) throw StoppedException()
                setStatus(getString(R.string.rendering_page, i + 1, indices.size))
                val bitmap = source.renderPage(pageIndex, CONTEXT_LONG_EDGE)
                val attachment = if (isNote) Attachment.png(bitmap.toPng())
                else Attachment.jpeg(bitmap.toJpeg())
                bitmap.recycle()
                attachment
            }
        }
    }

    private fun attachmentsFromUri(uri: Uri): List<Attachment> {
        val type = contentResolver.getType(uri) ?: ""
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException(getString(R.string.cannot_read_shared))
        if (type == "application/pdf") {
            val tmp = File(cacheDir, "shared_${System.nanoTime()}.pdf")
            try {
                tmp.writeBytes(bytes)
                PageSource.open(tmp).use { source ->
                    val count = source.pageCount.coerceAtMost(prefs.maxPages)
                    return (0 until count).map { i ->
                        if (stopRequested) throw StoppedException()
                        setStatus(getString(R.string.rendering_page, i + 1, count))
                        val bitmap = source.renderPage(i, CONTEXT_LONG_EDGE)
                        val att = Attachment.jpeg(bitmap.toJpeg())
                        bitmap.recycle()
                        att
                    }
                }
            } finally {
                tmp.delete()
            }
        }
        // A shared image: downscale and re-encode.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException(getString(R.string.cannot_read_shared))
        val scaled = bitmap.scaledToLongEdge(CONTEXT_LONG_EDGE)
        val att = Attachment.jpeg(scaled.toJpeg())
        scaled.recycle()
        return listOf(att)
    }

    /** Only files inside shared storage may be opened via the exported intent. */
    private fun isAllowedPath(file: File): Boolean {
        val root = android.os.Environment.getExternalStorageDirectory() ?: return false
        val canonical = try {
            file.canonicalFile
        } catch (e: IOException) {
            return false
        }
        val extOk = canonical.extension.equals("note", ignoreCase = true) ||
            canonical.extension.equals("pdf", ignoreCase = true)
        return extOk && canonical.path.startsWith(root.canonicalPath + File.separator)
    }

    private fun appendAnswerHeader(label: String) {
        val header = if (answerView.text.isEmpty()) "$label\n\n" else "\n\n— $label —\n\n"
        answerView.append(header)
    }

    private fun resetConversation() {
        conversation?.cancel()
        conversation = null
        answerView.text = ""
        promptInput.setText("")
        drawingView.clear()
        setStatus(getString(R.string.new_conversation))
    }

    private fun copyAnswer() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("answer", answerView.text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAnswer() {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, answerView.text.toString()),
            getString(R.string.share_answer)
        ))
    }

    private fun setControlsEnabled(enabled: Boolean) {
        askButton.isEnabled = enabled
        transcribeButton.isEnabled = enabled
        resetButton.isEnabled = enabled
        toggleInputButton.isEnabled = enabled
        stopButton.isEnabled = !enabled
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusView.text = message }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.cancel()
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_PAGE_INDICES = "page_indices"

        // Context pages are downsampled to this long edge to control token cost
        // while staying legible for OCR.
        private const val CONTEXT_LONG_EDGE = 1600
    }
}
