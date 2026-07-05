package dev.snocr.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.snocr.claudekit.Attachment
import dev.snocr.claudekit.ClaudeApiException
import dev.snocr.claudekit.ClaudeConversation
import dev.snocr.claudekit.Prompts
import dev.snocr.notekit.NoteParser
import dev.snocr.notekit.Notebook
import dev.snocr.notekit.PageRanges
import dev.snocr.notekit.PageRenderer
import dev.snocr.notekit.PngBitmapDecoder
import dev.snocr.notekit.RgbaBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The question screen: pick pages, type a prompt, stream Claude's answer.
 * Works on a .note file (rendered locally by notekit), a PDF, or content
 * shared from other apps.
 */
class AskActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var pagesInput: EditText
    private lateinit var promptInput: EditText
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
    private var notebook: Notebook? = null
    private var sharedUris: List<Uri> = emptyList()

    private var conversation: ClaudeConversation? = null
    @Volatile private var working = false
    @Volatile private var stopRequested = false

    private class StoppedException : Exception()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ask)
        prefs = Prefs(this)

        titleView = findViewById(R.id.ask_title)
        pagesInput = findViewById(R.id.pages_input)
        promptInput = findViewById(R.id.prompt_input)
        askButton = findViewById(R.id.ask_button)
        transcribeButton = findViewById(R.id.transcribe_button)
        stopButton = findViewById(R.id.stop_button)
        copyButton = findViewById(R.id.copy_button)
        shareButton = findViewById(R.id.share_button)
        resetButton = findViewById(R.id.reset_button)
        statusView = findViewById(R.id.status_view)
        answerView = findViewById(R.id.answer_view)
        answerScroll = findViewById(R.id.answer_scroll)

        askButton.setOnClickListener { startTurn(promptInput.text.toString().trim()) }
        transcribeButton.setOnClickListener { startTurn(Prompts.TRANSCRIBE, isTranscribe = true) }
        stopButton.setOnClickListener {
            stopRequested = true
            conversation?.cancel()
        }
        copyButton.setOnClickListener { copyAnswer() }
        shareButton.setOnClickListener { shareAnswer() }
        resetButton.setOnClickListener { resetConversation() }

        loadSource(intent)
    }

    private fun loadSource(intent: Intent) {
        when {
            intent.hasExtra(EXTRA_FILE_PATH) -> {
                val file = File(intent.getStringExtra(EXTRA_FILE_PATH)!!)
                if (!isAllowedNotePath(file)) {
                    setStatus(getString(R.string.nothing_shared))
                    askButton.isEnabled = false
                    transcribeButton.isEnabled = false
                    return
                }
                noteFile = file
                titleView.text = file.name
                if (file.extension.equals("note", ignoreCase = true)) {
                    thread {
                        val parsed = runCatching { NoteParser.parse(file.readBytes()) }
                        runOnUiThread {
                            parsed.onSuccess {
                                notebook = it
                                pagesInput.hint = getString(R.string.pages_hint_n, it.totalPages)
                            }.onFailure {
                                setStatus(getString(R.string.parse_failed, it.message))
                                askButton.isEnabled = false
                                transcribeButton.isEnabled = false
                            }
                        }
                    }
                } else {
                    pagesInput.visibility = View.GONE
                    findViewById<View>(R.id.pages_label).visibility = View.GONE
                }
            }
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                sharedUris = extractSharedUris(intent)
                titleView.text = resources.getQuantityString(
                    R.plurals.shared_items, sharedUris.size, sharedUris.size
                )
                pagesInput.visibility = View.GONE
                findViewById<View>(R.id.pages_label).visibility = View.GONE
                if (sharedUris.isEmpty()) {
                    setStatus(getString(R.string.nothing_shared))
                    askButton.isEnabled = false
                    transcribeButton.isEnabled = false
                }
            }
            else -> {
                setStatus(getString(R.string.nothing_shared))
                askButton.isEnabled = false
                transcribeButton.isEnabled = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        else -> emptyList()
    }

    private fun startTurn(question: String, isTranscribe: Boolean = false) {
        if (working) return
        if (question.isEmpty()) {
            setStatus(getString(R.string.enter_question))
            return
        }
        if (prefs.apiKey.isEmpty()) {
            setStatus(getString(R.string.api_key_missing))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        working = true
        stopRequested = false
        setControlsEnabled(false)
        if (conversation == null) answerView.text = ""
        appendAnswerHeader(if (isTranscribe) getString(R.string.transcription) else question)
        val pagesSpec = pagesInput.text.toString()

        thread {
            try {
                val active = conversation ?: ClaudeConversation(prefs.claudeConfig())
                conversation = active
                val attachments =
                    if (active.turnCount == 0) prepareAttachments(pagesSpec) else emptyList()
                if (stopRequested) throw StoppedException()
                setStatus(getString(R.string.waiting_for_claude, prefs.model.displayName))
                var firstDelta = true
                val result = active.ask(question, attachments) { delta ->
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
                        "refusal" -> setStatus(
                            getString(
                                R.string.refused,
                                result.refusalExplanation ?: getString(R.string.refused_generic)
                            )
                        )
                        "max_tokens" -> setStatus(getString(R.string.truncated))
                        else -> setStatus(getString(R.string.done_hint))
                    }
                    promptInput.setText("")
                    promptInput.hint = getString(R.string.follow_up_hint)
                }
            } catch (e: StoppedException) {
                runOnUiThread { setStatus(getString(R.string.stopped)) }
            } catch (e: ClaudeApiException) {
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

    /** Renders the selected source into Claude attachments. Worker thread. */
    private fun prepareAttachments(pagesSpec: String): List<Attachment> {
        val file = noteFile
        return when {
            file != null && file.extension.equals("note", ignoreCase = true) -> {
                val parsed = notebook ?: NoteParser.parse(file.readBytes()).also { notebook = it }
                val pages = PageRanges.parse(pagesSpec, parsed.totalPages)
                if (pages.size > prefs.maxPages) {
                    throw IllegalArgumentException(
                        getString(R.string.too_many_pages, pages.size, prefs.maxPages)
                    )
                }
                val renderer = PageRenderer(androidPngDecoder)
                pages.mapIndexed { index, pageNumber ->
                    if (stopRequested) throw StoppedException()
                    setStatus(getString(R.string.rendering_page, index + 1, pages.size))
                    val rendered = renderer.render(parsed, pageNumber)
                    val bitmap = Bitmap.createBitmap(
                        rendered.argb, rendered.width, rendered.height, Bitmap.Config.ARGB_8888
                    )
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bitmap.recycle()
                    Attachment.png(out.toByteArray())
                }
            }
            file != null -> {
                if (file.length() > MAX_PDF_BYTES) {
                    throw IllegalArgumentException(
                        getString(
                            R.string.pdf_too_large,
                            android.text.format.Formatter.formatShortFileSize(this, file.length())
                        )
                    )
                }
                listOf(Attachment.pdf(file.readBytes()))
            }
            else -> sharedUris.map { uri -> attachmentFromUri(uri) }
        }
    }

    private fun attachmentFromUri(uri: Uri): Attachment {
        val type = contentResolver.getType(uri) ?: ""
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException(getString(R.string.cannot_read_shared))
        return if (type == "application/pdf") {
            if (bytes.size > MAX_PDF_BYTES) {
                throw IllegalArgumentException(
                    getString(
                        R.string.pdf_too_large,
                        android.text.format.Formatter.formatShortFileSize(this, bytes.size.toLong())
                    )
                )
            }
            Attachment.pdf(bytes)
        } else {
            // Normalize shared images: downscale to the model's useful
            // resolution and re-encode as JPEG (photos as PNG easily exceed
            // the API's per-image size limit).
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException(getString(R.string.cannot_read_shared))
            val longEdge = maxOf(bitmap.width, bitmap.height)
            if (longEdge > MAX_IMAGE_EDGE) {
                val scale = MAX_IMAGE_EDGE.toFloat() / longEdge
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                bitmap.recycle()
                bitmap = scaled
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            Attachment.jpeg(out.toByteArray())
        }
    }

    /** Only files inside the device's shared-storage notebook folders. */
    private fun isAllowedNotePath(file: File): Boolean {
        val root = android.os.Environment.getExternalStorageDirectory() ?: return false
        val canonical = try {
            file.canonicalFile
        } catch (e: IOException) {
            return false
        }
        val extensionOk = canonical.extension.equals("note", ignoreCase = true) ||
            canonical.extension.equals("pdf", ignoreCase = true)
        return extensionOk && canonical.path.startsWith(root.canonicalPath + File.separator)
    }

    private val androidPngDecoder = PngBitmapDecoder { data ->
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IOException("cannot decode template PNG")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = RgbaBitmap(pixels, bitmap.width, bitmap.height)
        bitmap.recycle()
        result
    }

    private fun appendAnswerHeader(question: String) {
        val header = if (answerView.text.isEmpty()) "Q: $question\n\n" else "\n\nQ: $question\n\n"
        answerView.append(header)
    }

    private fun resetConversation() {
        conversation?.cancel()
        conversation = null
        answerView.text = ""
        promptInput.setText("")
        promptInput.hint = getString(R.string.prompt_hint)
        setStatus(getString(R.string.new_conversation))
    }

    private fun copyAnswer() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("answer", answerView.text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAnswer() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, answerView.text.toString()),
                getString(R.string.share_answer)
            )
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        askButton.isEnabled = enabled
        transcribeButton.isEnabled = enabled
        resetButton.isEnabled = enabled
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

        // The Messages API caps requests at 32MB; leave room for base64.
        private const val MAX_PDF_BYTES = 20L * 1024 * 1024

        // Claude's maximum useful image resolution (long edge).
        private const val MAX_IMAGE_EDGE = 2576
    }
}
