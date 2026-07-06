// Kotlin port of supernotelib's RATTA_RLE decoders
// (https://github.com/jya-dev/supernote-tool, Apache License 2.0).
package dev.snocr.notekit

/**
 * Decodes the RATTA_RLE bitmap protocol into an 8-bit grayscale byte array.
 *
 * Grayscale codes follow supernotelib's default palette: 0x00 black,
 * 0x9d dark gray, 0xc9 gray, 0xfe white, 0xff transparent.
 *
 * @param highresGrayscale true for X2-series files (firmware >= Chauvet 3.14.27),
 *   which renumbered the gray color codes and pass unknown codes through as
 *   literal gray values.
 */
class RattaRleDecoder(private val highresGrayscale: Boolean) {

    private val colormap: Map<Int, Int> = if (highresGrayscale) {
        mapOf(
            0x61 to GRAY_BLACK,
            0x62 to GRAY_TRANSPARENT,
            0x9D to GRAY_DARK_GRAY,
            0xC9 to GRAY_GRAY,
            0x65 to GRAY_WHITE,
            0x66 to GRAY_BLACK,        // marker black
            0x9E to GRAY_DARK_GRAY,    // marker dark gray
            0xCA to GRAY_GRAY,         // marker gray
            0x63 to GRAY_DARK_GRAY_COMPAT,
            0x64 to GRAY_GRAY_COMPAT,
        )
    } else {
        mapOf(
            0x61 to GRAY_BLACK,
            0x62 to GRAY_TRANSPARENT,
            0x63 to GRAY_DARK_GRAY,
            0x64 to GRAY_GRAY,
            0x65 to GRAY_WHITE,
            0x66 to GRAY_BLACK,        // marker black
            0x67 to GRAY_DARK_GRAY,    // marker dark gray
            0x68 to GRAY_GRAY,         // marker gray
        )
    }

    /**
     * @param data compressed bitmap bytes
     * @param pageWidth page width in pixels
     * @param pageHeight page height in pixels
     * @param allBlank hint for the special all-white background block
     * @param horizontal true when the page orientation is horizontal
     *   (width and height are swapped)
     * @return grayscale bytes of size width*height (row-major)
     */
    fun decode(
        data: ByteArray,
        pageWidth: Int,
        pageHeight: Int,
        allBlank: Boolean = false,
        horizontal: Boolean = false,
    ): GrayBitmap {
        val width = if (horizontal) pageHeight else pageWidth
        val height = if (horizontal) pageWidth else pageHeight
        val expectedLength = width * height
        val out = ByteArray(expectedLength)
        var pos = 0

        fun push(colorCode: Int, length: Int) {
            if (length <= 0) return
            if (pos + length > expectedLength) {
                throw NoteDecoderException(
                    "uncompressed bitmap length exceeds ${expectedLength}: ${pos + length}"
                )
            }
            val value = mappedColor(colorCode)
            out.fill(value, pos, pos + length)
            pos += length
        }

        var i = 0
        var holderColor = -1
        var holderLength = -1
        while (i + 1 < data.size) {
            var colorCode = data[i].toInt() and 0xff
            var length = data[i + 1].toInt() and 0xff
            i += 2
            var dataPushed = false

            if (holderColor >= 0) {
                val prevColor = holderColor
                val prevLength = holderLength
                holderColor = -1
                holderLength = -1
                if (colorCode == prevColor) {
                    length = 1 + length + (((prevLength and 0x7f) + 1) shl 7)
                    push(colorCode, length)
                    dataPushed = true
                } else {
                    push(prevColor, ((prevLength and 0x7f) + 1) shl 7)
                }
            }

            if (!dataPushed) {
                if (length == SPECIAL_LENGTH_MARKER) {
                    push(colorCode, if (allBlank) SPECIAL_LENGTH_FOR_BLANK else SPECIAL_LENGTH)
                } else if (length and 0x80 != 0) {
                    holderColor = colorCode
                    holderLength = length
                } else {
                    push(colorCode, length + 1)
                }
            }
        }

        if (holderColor >= 0) {
            push(holderColor, adjustTailLength(holderLength, pos, expectedLength))
        }

        if (pos != expectedLength) {
            throw NoteDecoderException("uncompressed bitmap length = $pos, expected = $expectedLength")
        }
        return GrayBitmap(out, width, height)
    }

    private fun mappedColor(colorCode: Int): Byte {
        val mapped = colormap[colorCode]
        if (mapped != null) return mapped.toByte()
        if (highresGrayscale) {
            // X2 decoder passes unknown codes through as literal gray values.
            return colorCode.toByte()
        }
        throw NoteDecoderException("unknown color code: 0x${colorCode.toString(16)}")
    }

    private fun adjustTailLength(tailLength: Int, currentLength: Int, totalLength: Int): Int {
        val gap = totalLength - currentLength
        for (i in 7 downTo 0) {
            val length = ((tailLength and 0x7f) + 1) shl i
            if (length <= gap) return length
        }
        return 0
    }

    companion object {
        const val SPECIAL_LENGTH_MARKER = 0xff
        const val SPECIAL_LENGTH = 0x4000
        const val SPECIAL_LENGTH_FOR_BLANK = 0x400

        // Default grayscale palette (supernotelib color.py)
        const val GRAY_BLACK = 0x00
        const val GRAY_DARK_GRAY = 0x9d
        const val GRAY_GRAY = 0xc9
        const val GRAY_WHITE = 0xfe
        const val GRAY_TRANSPARENT = 0xff
        const val GRAY_DARK_GRAY_COMPAT = 0x30
        const val GRAY_GRAY_COMPAT = 0x50
    }
}

/** An 8-bit grayscale bitmap; value 0xff marks transparent pixels. */
class GrayBitmap(val pixels: ByteArray, val width: Int, val height: Int)
