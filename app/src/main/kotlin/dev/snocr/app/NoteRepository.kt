package dev.snocr.app

import android.os.Environment
import java.io.File

class NoteEntry(val file: File, val relativePath: String) {
    val name: String get() = file.name
    val isPdf: Boolean get() = file.extension.equals("pdf", ignoreCase = true)
}

/** Finds notebooks on the device's shared storage. */
object NoteRepository {

    // Folders the Supernote system app uses on internal storage.
    private val SCAN_FOLDERS = listOf("Note", "Document", "EXPORT", "INBOX")
    private const val MAX_DEPTH = 6

    fun scan(): List<NoteEntry> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val results = mutableListOf<NoteEntry>()
        for (folder in SCAN_FOLDERS) {
            val dir = File(root, folder)
            if (dir.isDirectory) collect(dir, root, results, depth = 0)
        }
        return results.sortedByDescending { it.file.lastModified() }
    }

    private fun collect(dir: File, root: File, out: MutableList<NoteEntry>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory && !child.name.startsWith(".") ->
                    collect(child, root, out, depth + 1)
                child.isFile && (
                    child.extension.equals("note", ignoreCase = true) ||
                        child.extension.equals("pdf", ignoreCase = true)
                    ) ->
                    out.add(NoteEntry(child, child.relativeTo(root).path))
            }
        }
    }
}
