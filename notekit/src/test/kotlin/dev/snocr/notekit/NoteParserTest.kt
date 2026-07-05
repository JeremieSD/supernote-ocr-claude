package dev.snocr.notekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoteParserTest {

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")).readBytes()

    @Test
    fun `parses manta notebook metadata`() {
        val notebook = NoteParser.parse(golden("manta_basic.note"))
        assertEquals("note", notebook.fileType)
        assertEquals("SN_FILE_VER_20230015", notebook.signature)
        assertEquals(1920, notebook.width)
        assertEquals(2560, notebook.height)
        assertEquals(2, notebook.totalPages)
        assertTrue(notebook.supportsHighresGrayscale)
        val page = notebook.page(0)
        assertEquals("style_white", page.style)
        assertEquals(listOf("MAINLAYER", "BGLAYER"), page.layerOrder)
    }

    @Test
    fun `legacy a5x file uses classic canvas and decoder`() {
        val notebook = NoteParser.parse(golden("a5x_legacy.note"))
        assertEquals(1404, notebook.width)
        assertEquals(1872, notebook.height)
        assertEquals(false, notebook.supportsHighresGrayscale)
    }

    @Test
    fun `rejects non-note data`() {
        assertFailsWith<UnsupportedNoteFormatException> {
            NoteParser.parse("not a note file at all, definitely too weird".toByteArray())
        }
        assertFailsWith<UnsupportedNoteFormatException> { NoteParser.parse(ByteArray(4)) }
    }

    @Test
    fun `accepts future signatures`() {
        // Loose policy: any SN_FILE_VER_ + 8 digits should parse.
        val blob = golden("manta_basic.note").copyOf()
        val future = "SN_FILE_VER_20991234".toByteArray()
        System.arraycopy(future, 0, blob, 4, future.size)
        val notebook = NoteParser.parse(blob)
        assertEquals("SN_FILE_VER_20991234", notebook.signature)
        assertTrue(notebook.supportsHighresGrayscale)
    }
}
