package dev.snocr.notekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixel-exact comparison against supernotelib (the Python reference
 * implementation). Golden data is produced by tools/make_golden.py.
 */
class GoldenTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) {
            "missing golden resource $name - run tools/make_golden.py"
        }.readBytes()

    private val manifest: JsonObject =
        Json.parseToJsonElement(String(resource("manifest.json"))).jsonObject

    /** PNG decoder backed by ImageIO for JVM tests (the app uses BitmapFactory). */
    private val pngDecoder = PngBitmapDecoder { data ->
        val img = ImageIO.read(ByteArrayInputStream(data))
        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        RgbaBitmap(pixels, img.width, img.height)
    }

    @TestFactory
    fun `rendered pages match supernotelib output`(): List<DynamicTest> =
        manifest["notes"]!!.jsonArray.flatMap { noteEntry ->
            val obj = noteEntry.jsonObject
            val noteFile = obj["file"]!!.jsonPrimitive.content
            val pages = obj["pages"]!!.jsonArray.map { it.jsonPrimitive.content }
            val notebook = NoteParser.parse(resource(noteFile))
            val renderer = PageRenderer(pngDecoder)
            pages.mapIndexed { pageIndex, pngName ->
                DynamicTest.dynamicTest("$noteFile page $pageIndex") {
                    val rendered = renderer.render(notebook, pageIndex)
                    val expected = ImageIO.read(ByteArrayInputStream(resource(pngName)))
                    assertEquals(expected.width, rendered.width, "width")
                    assertEquals(expected.height, rendered.height, "height")
                    var mismatches = 0
                    var firstMismatch = ""
                    for (y in 0 until expected.height) {
                        for (x in 0 until expected.width) {
                            val want = expected.getRGB(x, y) or 0xFF000000.toInt()
                            val got = rendered.argb[y * rendered.width + x]
                            if (want != got) {
                                if (mismatches == 0) {
                                    firstMismatch = "first mismatch at ($x,$y): " +
                                        "want ${want.toUInt().toString(16)} got ${got.toUInt().toString(16)}"
                                }
                                mismatches++
                            }
                        }
                    }
                    assertEquals(0, mismatches, "pixel mismatches. $firstMismatch")
                }
            }
        }

    @TestFactory
    fun `rle decoder matches supernotelib byte for byte`(): List<DynamicTest> =
        manifest["rle"]!!.jsonArray.map { caseEntry ->
            val obj = caseEntry.jsonObject
            val inputName = obj["input"]!!.jsonPrimitive.content
            DynamicTest.dynamicTest(inputName) {
                val input = resource(inputName)
                val expected = resource(obj["expected"]!!.jsonPrimitive.content)
                val decoder = RattaRleDecoder(obj["highres"]!!.jsonPrimitive.boolean)
                val decoded = decoder.decode(
                    input,
                    obj["width"]!!.jsonPrimitive.int,
                    obj["height"]!!.jsonPrimitive.int,
                )
                assertTrue(
                    expected.contentEquals(decoded.pixels),
                    "$inputName: decoded bytes differ from supernotelib",
                )
            }
        }
}
