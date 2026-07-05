// Kotlin port of supernotelib's ImageConverter (grayscale palette path)
// (https://github.com/jya-dev/supernote-tool, Apache License 2.0).
package dev.snocr.notekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** RGBA bitmap decoded from an embedded PNG (custom page templates). */
class RgbaBitmap(val pixels: IntArray, val width: Int, val height: Int) {
    init {
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }
}

/** Platform hook for decoding embedded PNG backgrounds (custom templates). */
fun interface PngBitmapDecoder {
    /** Decodes PNG bytes into RGBA pixels, packed as 0xAARRGGBB ints. */
    fun decode(data: ByteArray): RgbaBitmap
}

/** A rendered page: opaque ARGB pixels (0xFFrrggbb), row-major. */
class RenderedPage(val argb: IntArray, val width: Int, val height: Int)

/**
 * Renders a parsed notebook page to ARGB pixels by decoding and flattening
 * its layers, mirroring supernotelib's ImageConverter with the default
 * grayscale palette.
 */
class PageRenderer(private val pngDecoder: PngBitmapDecoder? = null) {

    fun render(notebook: Notebook, pageNumber: Int): RenderedPage {
        val page = notebook.page(pageNumber)
        if (!page.isLayerSupported) {
            throw UnsupportedNoteFormatException(
                "non-layered pages (original Supernote series) are not supported"
            )
        }
        return renderLayeredPage(notebook, page)
    }

    private fun renderLayeredPage(notebook: Notebook, page: Page): RenderedPage {
        val horizontal = page.isHorizontal
        val pageWidth = if (horizontal) notebook.height else notebook.width
        val pageHeight = if (horizontal) notebook.width else notebook.height

        // Decode each layer. RLE layers become grayscale; custom PNG
        // backgrounds become RGBA.
        val grayLayers = HashMap<String, GrayBitmap>()
        val rgbaLayers = HashMap<String, RgbaBitmap>()
        for (layer in page.layers) {
            val name = layer.name ?: continue
            val binary = layer.content ?: continue
            val style = page.style
            val isBg = name == "BGLAYER"
            val customBg = isBg && style != null && style.startsWith("user_")
            if (customBg) {
                val decoder = pngDecoder ?: throw NoteDecoderException(
                    "page uses a custom PNG template but no PNG decoder was provided"
                )
                val bitmap = decoder.decode(binary)
                if (bitmap.width != notebook.width || bitmap.height != notebook.height) {
                    throw NoteDecoderException(
                        "invalid template size = (${bitmap.width}, ${bitmap.height}), " +
                            "expected = (${notebook.width}, ${notebook.height})"
                    )
                }
                rgbaLayers[name] = bitmap
                continue
            }
            when (val protocol = layer.protocol) {
                "RATTA_RLE" -> {
                    val allBlank = isBg && style == "style_white" &&
                        binary.size == SPECIAL_WHITE_STYLE_BLOCK_SIZE
                    val decoder = RattaRleDecoder(notebook.supportsHighresGrayscale)
                    grayLayers[name] = decoder.decode(
                        binary, notebook.width, notebook.height,
                        allBlank = allBlank, horizontal = horizontal,
                    )
                }
                else -> throw UnsupportedNoteFormatException("unknown decode protocol: $protocol")
            }
        }

        // Flatten visible layers bottom-up (LAYERSEQ lists top to bottom).
        val out = IntArray(pageWidth * pageHeight) { WHITE_RGB }
        val visibility = layerVisibility(page)
        for (name in page.layerOrder.reversed()) {
            if (visibility[name] != true) continue
            val gray = grayLayers[name]
            val rgba = rgbaLayers[name]
            when {
                gray != null -> compositeGray(out, gray, pageWidth, pageHeight)
                rgba != null -> compositeCustomBackground(out, rgba, pageWidth, pageHeight)
            }
        }
        return RenderedPage(out, pageWidth, pageHeight)
    }

    /** Composites a grayscale layer; 0xff pixels are transparent. */
    private fun compositeGray(out: IntArray, layer: GrayBitmap, width: Int, height: Int) {
        if (layer.width != width || layer.height != height) {
            throw NoteDecoderException(
                "layer size (${layer.width}, ${layer.height}) does not match page ($width, $height)"
            )
        }
        for (i in out.indices) {
            val v = layer.pixels[i].toInt() and 0xff
            if (v != RattaRleDecoder.GRAY_TRANSPARENT) {
                out[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
    }

    /**
     * Composites a custom PNG template, replicating supernotelib exactly:
     * the template is alpha-blended over a 0xfe-white canvas (PIL's
     * `_whiten_transparent`, with PIL's rounding blend), then pixels whose
     * PIL luma is 0xff are treated as transparent by the flatten mask.
     */
    private fun compositeCustomBackground(out: IntArray, bg: RgbaBitmap, width: Int, height: Int) {
        if (bg.width != width || bg.height != height) {
            throw NoteDecoderException(
                "template size (${bg.width}, ${bg.height}) does not match page ($width, $height)"
            )
        }
        for (i in out.indices) {
            val p = bg.pixels[i]
            val a = (p ushr 24) and 0xff
            // PIL paste blend over the 0xfe canvas.
            val r = pilBlend((p ushr 16) and 0xff, a)
            val g = pilBlend((p ushr 8) and 0xff, a)
            val b = pilBlend(p and 0xff, a)
            // PIL convert('L'): rounded ITU-R 601-2 luma.
            val luma = (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16
            if (luma != RattaRleDecoder.GRAY_TRANSPARENT) {
                out[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * PIL's mask blend of a foreground channel over the 0xfe white canvas:
     * round((bg*(255-a) + fg*a) / 255), with PIL's MULDIV255 applied once to
     * the combined sum.
     */
    private fun pilBlend(fg: Int, a: Int): Int {
        val t = 0xfe * (255 - a) + fg * a + 128
        return ((t shr 8) + t) shr 8
    }

    /**
     * Layer visibility from the page's LAYERINFO JSON. Falls back to
     * mark-file semantics (only MARK layers visible) when absent.
     */
    private fun layerVisibility(page: Page): Map<String, Boolean> {
        val info = page.layerInfo ?: return page.layers.associate {
            (it.name ?: "") to (it.type == "MARK")
        }
        // LAYERINFO is a JSON array, sometimes base64-encoded first.
        val array = parseJsonArray(info)
            ?: parseJsonArray(decodeBase64OrNull(info))
            ?: throw NoteDecoderException("unparseable LAYERINFO")
        // Nullable values mirror Python: an entry with no isVisible stores
        // None, and only MAINLAYER's None is later coerced to visible.
        val visibility = HashMap<String, Boolean?>()
        for (element in array) {
            val obj = element.jsonObject
            val isBg = truthy(obj["isBackgroundLayer"]) ?: false
            val layerId = obj["layerId"]?.jsonPrimitive?.intOrNull
            val isVisible = truthy(obj["isVisible"])
            when {
                isBg -> visibility["BGLAYER"] = isVisible
                layerId == 0 -> visibility["MAINLAYER"] = isVisible
                layerId != null -> visibility["LAYER$layerId"] = isVisible
            }
        }
        // Old files may omit MAINLAYER info; default it to visible.
        if (visibility["MAINLAYER"] == null) visibility["MAINLAYER"] = true
        return visibility.mapValues { it.value == true }
    }

    /** Python truthiness of a JSON scalar: false/0/""/null are falsy. */
    private fun truthy(element: kotlinx.serialization.json.JsonElement?): Boolean? {
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (primitive is kotlinx.serialization.json.JsonNull) return null
        primitive.booleanOrNull?.let { return it }
        primitive.content.toDoubleOrNull()?.let { return it != 0.0 }
        return primitive.content.isNotEmpty()
    }

    private fun parseJsonArray(text: String?): JsonArray? {
        if (text == null) return null
        return try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase64OrNull(text: String): String? = try {
        // MIME decoder skips whitespace/newlines, like Python's b64decode.
        String(Base64.getMimeDecoder().decode(text), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        // supernotelib: a style_white background block of exactly this size is
        // the special "all blank" bitmap using 0x400-pixel runs.
        const val SPECIAL_WHITE_STYLE_BLOCK_SIZE = 0x140e

        private const val WHITE_RGB = 0xFFFFFFFF.toInt()
    }
}
