// Kotlin port of the Supernote .note file model, based on supernotelib
// (https://github.com/jya-dev/supernote-tool, Apache License 2.0).
package dev.snocr.notekit

/** A metadata block: `<KEY:VALUE>` pairs. Duplicate keys accumulate into lists. */
class MetadataBlock {
    private val params = LinkedHashMap<String, MutableList<String>>()

    fun put(key: String, value: String) {
        params.getOrPut(key) { mutableListOf() }.add(value)
    }

    /** First value for the key, or null. */
    operator fun get(key: String): String? = params[key]?.firstOrNull()

    fun getAll(key: String): List<String> = params[key] ?: emptyList()

    /** Keys in file order. */
    val keys: List<String> get() = params.keys.toList()

    fun containsKey(key: String): Boolean = params.containsKey(key)
}

class Layer(val metadata: MetadataBlock) {
    var content: ByteArray? = null

    // LAYERNAME is mutable because of the duplicated-MAINLAYER workaround
    // (see supernotelib utils.WorkaroundPageWrapper).
    var name: String? = metadata["LAYERNAME"]
    val protocol: String? get() = metadata["LAYERPROTOCOL"]
    val type: String? get() = metadata["LAYERTYPE"]
}

class Page(val metadata: MetadataBlock, val layers: List<Layer>) {
    var content: ByteArray? = null

    val isLayerSupported: Boolean get() = layers.isNotEmpty()

    val protocol: String?
        get() = if (isLayerSupported) layers[0].metadata["LAYERPROTOCOL"] else metadata["PROTOCOL"]

    val style: String? get() = metadata["PAGESTYLE"]

    /** Raw LAYERINFO with '#' restored to ':'; null when absent or "none". */
    val layerInfo: String?
        get() {
            val info = metadata["LAYERINFO"]
            if (info == null || info == "none") return null
            return info.replace('#', ':')
        }

    val layerOrder: List<String>
        get() = metadata["LAYERSEQ"]?.split(",") ?: emptyList()

    val orientation: String get() = metadata["ORIENTATION"] ?: ORIENTATION_VERTICAL

    val isHorizontal: Boolean get() = orientation == ORIENTATION_HORIZONTAL

    companion object {
        const val ORIENTATION_VERTICAL = "1000"
        const val ORIENTATION_HORIZONTAL = "1090"
    }
}

class Notebook(
    val fileType: String,
    val signature: String,
    val header: MetadataBlock,
    val footer: MetadataBlock,
    val pages: List<Page>,
) {
    val totalPages: Int get() = pages.size

    // Devices reporting equipment N5 (Manta / A5 X2) use a 1920x2560 canvas;
    // everything else uses the classic 1404x1872.
    val width: Int = if (header["APPLY_EQUIPMENT"] == "N5") A5X2_PAGE_WIDTH else PAGE_WIDTH
    val height: Int = if (header["APPLY_EQUIPMENT"] == "N5") A5X2_PAGE_HEIGHT else PAGE_HEIGHT

    fun page(number: Int): Page {
        require(number in pages.indices) { "page number out of range: $number" }
        return pages[number]
    }

    /** Firmware Chauvet 3.14.27+ files use the X2 high-res grayscale color codes. */
    val supportsHighresGrayscale: Boolean
        get() = (signature.takeLast(8).toIntOrNull() ?: 0) >= 20230015

    companion object {
        const val PAGE_WIDTH = 1404
        const val PAGE_HEIGHT = 1872
        const val A5X2_PAGE_WIDTH = 1920
        const val A5X2_PAGE_HEIGHT = 2560
    }
}

class UnsupportedNoteFormatException(message: String) : Exception(message)
class NoteDecoderException(message: String) : Exception(message)
