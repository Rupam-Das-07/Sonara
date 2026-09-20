package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LineSyncCalculatorTest {

    private val sampleLines = listOf(
        LyricLine(1000L, "Line 1"),
        LyricLine(5000L, "Line 2"),
        LyricLine(10000L, "Line 3"),
        LyricLine(20000L, "Line 4")
    )

    @Test
    fun `returns -1 when lines are empty or before first line`() {
        assertEquals(-1, LineSyncCalculator.findActiveLineIndex(500L, emptyList()))
        assertEquals(-1, LineSyncCalculator.findActiveLineIndex(500L, sampleLines))
    }

    @Test
    fun `returns exact index within timestamp ranges`() {
        assertEquals(0, LineSyncCalculator.findActiveLineIndex(1000L, sampleLines))
        assertEquals(0, LineSyncCalculator.findActiveLineIndex(4999L, sampleLines))
        assertEquals(1, LineSyncCalculator.findActiveLineIndex(5000L, sampleLines))
        assertEquals(1, LineSyncCalculator.findActiveLineIndex(9999L, sampleLines))
        assertEquals(2, LineSyncCalculator.findActiveLineIndex(10000L, sampleLines))
        assertEquals(3, LineSyncCalculator.findActiveLineIndex(20000L, sampleLines))
        assertEquals(3, LineSyncCalculator.findActiveLineIndex(50000L, sampleLines))
    }

    @Test
    fun `fast path pointer progression matches binary search fallback`() {
        var lastPointer = 0
        val positions = listOf(1500L, 2000L, 5100L, 5500L, 10200L, 25000L)

        for (pos in positions) {
            val fastIndex = LineSyncCalculator.findActiveLineIndex(pos, sampleLines, lastPointer)
            val searchIndex = LineSyncCalculator.findActiveLineIndex(pos, sampleLines, 0)
            assertEquals(searchIndex, fastIndex)
            lastPointer = fastIndex
        }
    }

    @Test
    fun `lead time compensation activates upcoming line smoothly before vocal onset`() {
        // Line 2 starts at 5000ms. With 150ms lead time, at 4850ms it should activate Line 2
        assertEquals(0, LineSyncCalculator.findActiveLineIndex(4840L, sampleLines, 0, leadTimeMs = 150L))
        assertEquals(1, LineSyncCalculator.findActiveLineIndex(4850L, sampleLines, 0, leadTimeMs = 150L))
        assertEquals(1, LineSyncCalculator.findActiveLineIndex(4999L, sampleLines, 0, leadTimeMs = 150L))
        assertEquals(1, LineSyncCalculator.findActiveLineIndex(5000L, sampleLines, 0, leadTimeMs = 150L))
    }
}
