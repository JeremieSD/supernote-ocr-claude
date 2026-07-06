package dev.snocr.notekit

/** Parses user-facing page selections like "all", "3", "1-4,7". */
object PageRanges {
    /**
     * @param spec 1-based page selection; blank or "all" selects everything.
     * @param totalPages number of pages in the notebook
     * @return 0-based page indices, ordered, without duplicates
     * @throws IllegalArgumentException for malformed or out-of-range input
     */
    fun parse(spec: String, totalPages: Int): List<Int> {
        val trimmed = spec.trim().lowercase()
        if (trimmed.isEmpty() || trimmed == "all") return (0 until totalPages).toList()
        val selected = linkedSetOf<Int>()
        for (part in trimmed.split(",")) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val bounds = piece.split("-", limit = 2).map {
                it.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("invalid page selection: \"$piece\"")
            }
            val (from, to) = if (bounds.size == 2) bounds[0] to bounds[1] else bounds[0] to bounds[0]
            if (from < 1 || to > totalPages || from > to) {
                throw IllegalArgumentException(
                    "page range $piece is outside 1-$totalPages"
                )
            }
            (from..to).forEach { selected.add(it - 1) }
        }
        if (selected.isEmpty()) throw IllegalArgumentException("no pages selected")
        return selected.toList()
    }
}
