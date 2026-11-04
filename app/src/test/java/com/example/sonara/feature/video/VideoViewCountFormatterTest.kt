package com.example.sonara.feature.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoViewCountFormatterTest {

    @Test
    fun `format returns null for null or negative view counts`() {
        assertNull(VideoViewCountFormatter.format(null))
        assertNull(VideoViewCountFormatter.format(-1L))
        assertNull(VideoViewCountFormatter.format(-100L))
        assertNull(VideoViewCountFormatter.format(Long.MIN_VALUE))
    }

    @Test
    fun `format handles singular and zero view counts`() {
        assertEquals("0 views", VideoViewCountFormatter.format(0L))
        assertEquals("1 view", VideoViewCountFormatter.format(1L))
    }

    @Test
    fun `format formats sub thousand views without suffix`() {
        assertEquals("2 views", VideoViewCountFormatter.format(2L))
        assertEquals("950 views", VideoViewCountFormatter.format(950L))
        assertEquals("999 views", VideoViewCountFormatter.format(999L))
    }

    @Test
    fun `format formats thousands with K suffix`() {
        assertEquals("1K views", VideoViewCountFormatter.format(1_000L))
        assertEquals("1.2K views", VideoViewCountFormatter.format(1_200L))
        assertEquals("10.5K views", VideoViewCountFormatter.format(10_500L))
        assertEquals("100K views", VideoViewCountFormatter.format(100_000L))
        assertEquals("999.9K views", VideoViewCountFormatter.format(999_900L))
        assertEquals("999.9K views", VideoViewCountFormatter.format(999_949L))
    }

    @Test
    fun `format promotes to M suffix at rounding boundary`() {
        // Values at or above 999_950 round up to 1.0M
        assertEquals("1M views", VideoViewCountFormatter.format(999_950L))
        assertEquals("1M views", VideoViewCountFormatter.format(999_999L))
        assertEquals("1M views", VideoViewCountFormatter.format(1_000_000L))
        assertEquals("1M views", VideoViewCountFormatter.format(1_049_999L))
        assertEquals("1.1M views", VideoViewCountFormatter.format(1_050_000L))
    }

    @Test
    fun `format formats millions with M suffix`() {
        assertEquals("1M views", VideoViewCountFormatter.format(1_000_000L))
        assertEquals("1.2M views", VideoViewCountFormatter.format(1_200_000L))
        assertEquals("1.2M views", VideoViewCountFormatter.format(1_234_567L))
        assertEquals("50M views", VideoViewCountFormatter.format(50_000_000L))
        assertEquals("999.9M views", VideoViewCountFormatter.format(999_949_999L))
    }

    @Test
    fun `format promotes to B suffix at rounding boundary`() {
        // Values at or above 999_950_000 round up to 1.0B
        assertEquals("1B views", VideoViewCountFormatter.format(999_950_000L))
        assertEquals("1B views", VideoViewCountFormatter.format(999_999_999L))
        assertEquals("1B views", VideoViewCountFormatter.format(1_000_000_000L))
        assertEquals("1B views", VideoViewCountFormatter.format(1_049_999_999L))
        assertEquals("1.1B views", VideoViewCountFormatter.format(1_050_000_000L))
    }

    @Test
    fun `format formats billions with B suffix`() {
        assertEquals("1B views", VideoViewCountFormatter.format(1_000_000_000L))
        assertEquals("1.5B views", VideoViewCountFormatter.format(1_500_000_000L))
        assertEquals("2.8B views", VideoViewCountFormatter.format(2_800_000_000L))
    }
}
