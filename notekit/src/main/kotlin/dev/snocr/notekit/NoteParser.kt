// Kotlin port of supernotelib's parser for X-series/X2-series .note files
// (https://github.com/jya-dev/supernote-tool, Apache License 2.0).
package dev.snocr.notekit

/**
 * Parses Supernote X-series / X2-series (Nomad, Manta) `.note` files.
 *
 * The whole file is held in memory; typical notebooks are a few megabytes.
 * The parser is deliberately "loose": any `SN_FILE_VER_\d{8}` signature is
 * accepted so files from newer firmware keep working.
 */
object NoteParser {
    private val SIGNATURE_REGEX = Regex("SN_FILE_VER_\\d{8}")
    private const val SIGNATURE_OFFSET = 4
    private const val SIGNATURE_LENGTH = 20 // "SN_FILE_VER_YYYYNNNN"
    private const val ADDRESS_SIZE = 4
    // [^\n] in the value mirrors Python's '.' (which excludes only \n,
    // whereas Java's '.' also excludes \r and Unicode line separators).
    private val PARAM_REGEX = Regex("<([^:<>]+):([^\\n]*?)>")
    private val LAYER_KEYS = listOf("MAINLAYER", "LAYER1", "LAYER2", "LAYER3", "BGLAYER")

    fun parse(data: ByteArray): Notebook {
        if (data.size < SIGNATURE_OFFSET + SIGNATURE_LENGTH + ADDRESS_SIZE) {
            throw UnsupportedNoteFormatException("file too short to be a .note file")
        }
        val fileType = String(data, 0, 4, Charsets.US_ASCII)
        val signature = String(data, SIGNATURE_OFFSET, SIGNATURE_LENGTH, Charsets.US_ASCII)
        if (!SIGNATURE_REGEX.matches(signature)) {
            throw UnsupportedNoteFormatException(
                "unsupported signature (only X-series/X2-series .note files are supported)"
            )
        }

        val footerAddress = readUInt32(data, data.size - ADDRESS_SIZE)
        val footer = parseMetadataBlock(data, footerAddress)

        val headerAddress = footer["FILE_FEATURE"]?.toLongOrNull()
            ?: throw UnsupportedNoteFormatException("missing FILE_FEATURE in footer")
        val header = parseMetadataBlock(data, headerAddress)

        // Footer keys starting with "PAGE" are page block addresses, in file order.
        val pages = footer.keys.filter { it.startsWith("PAGE") }
            .flatMap { key -> footer.getAll(key) }
            .map { address -> parsePage(data, address.toLong()) }

        if (pages.isEmpty()) {
            throw UnsupportedNoteFormatException("no pages found in file")
        }

        return Notebook(fileType, signature, header, footer, pages)
    }

    private fun parsePage(data: ByteArray, address: Long): Page {
        val pageInfo = parseMetadataBlock(data, address)
        // Iterate page keys in file order, keeping only layer keys - this
        // mirrors supernotelib, where layer index follows key order in the block.
        val layers = pageInfo.keys.filter { it in LAYER_KEYS }.map { key ->
            val layerAddress = pageInfo[key]!!.toLong()
            val layerInfo = parseMetadataBlock(data, layerAddress)
            Layer(layerInfo)
        }
        val page = Page(pageInfo, layers)
        // Load bitmap content for each layer.
        for (layer in layers) {
            val bitmapAddress = layer.metadata["LAYERBITMAP"]?.toLongOrNull() ?: 0L
            layer.content = contentAt(data, bitmapAddress)
        }
        if (layers.isEmpty()) {
            val dataAddress = pageInfo["DATA"]?.toLongOrNull() ?: 0L
            page.content = contentAt(data, dataAddress)
        }
        applyDuplicateMainLayerWorkaround(page)
        return page
    }

    /**
     * Some files contain two layers named MAINLAYER; the second one is really
     * the background layer (see supernotelib utils.WorkaroundPageWrapper).
     */
    private fun applyDuplicateMainLayerWorkaround(page: Page) {
        var mainVisited = false
        for (layer in page.layers) {
            val name = layer.name ?: continue
            if (mainVisited && name == "MAINLAYER") {
                layer.name = "BGLAYER"
            } else if (name == "MAINLAYER") {
                mainVisited = true
            }
        }
    }

    /** Reads a length-prefixed content block; returns null for address 0. */
    private fun contentAt(data: ByteArray, address: Long): ByteArray? {
        if (address == 0L) return null
        val offset = checkedOffset(data, address)
        val length = checkedBlockLength(data, offset, address)
        return data.copyOfRange(offset + 4, offset + 4 + length)
    }

    private fun parseMetadataBlock(data: ByteArray, address: Long): MetadataBlock {
        val block = MetadataBlock()
        if (address == 0L) return block
        val offset = checkedOffset(data, address)
        val length = checkedBlockLength(data, offset, address)
        val text = String(data, offset + 4, length, Charsets.UTF_8)
        for (match in PARAM_REGEX.findAll(text)) {
            block.put(match.groupValues[1], match.groupValues[2])
        }
        return block
    }

    /** Validates the block's 32-bit length field in Long math (no overflow). */
    private fun checkedBlockLength(data: ByteArray, offset: Int, address: Long): Int {
        val length = readUInt32(data, offset)
        if (offset + 4 + length > data.size) {
            throw UnsupportedNoteFormatException("block at $address exceeds file size")
        }
        return length.toInt()
    }

    private fun checkedOffset(data: ByteArray, address: Long): Int {
        if (address < 0 || address + 4 > data.size) {
            throw UnsupportedNoteFormatException("block address out of range: $address")
        }
        return address.toInt()
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
    }
}
