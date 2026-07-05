package dev.snocr.notekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageRangesTest {
    @Test
    fun `all and blank select everything`() {
        assertEquals(listOf(0, 1, 2), PageRanges.parse("all", 3))
        assertEquals(listOf(0, 1, 2), PageRanges.parse("  ", 3))
        assertEquals(listOf(0, 1, 2), PageRanges.parse("ALL", 3))
    }

    @Test
    fun `ranges and singles combine without duplicates`() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3,5", 6))
        assertEquals(listOf(1, 2, 3), PageRanges.parse("2,3,2-4", 6))
        assertEquals(listOf(6), PageRanges.parse("7", 7))
    }

    @Test
    fun `rejects bad input`() {
        assertFailsWith<IllegalArgumentException> { PageRanges.parse("0", 3) }
        assertFailsWith<IllegalArgumentException> { PageRanges.parse("4", 3) }
        assertFailsWith<IllegalArgumentException> { PageRanges.parse("3-1", 3) }
        assertFailsWith<IllegalArgumentException> { PageRanges.parse("x", 3) }
        assertFailsWith<IllegalArgumentException> { PageRanges.parse(",", 3) }
    }
}
