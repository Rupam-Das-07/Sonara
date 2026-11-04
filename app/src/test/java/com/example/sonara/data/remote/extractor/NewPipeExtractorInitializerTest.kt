package com.example.sonara.data.remote.extractor

import org.junit.Assert.assertTrue
import org.junit.Test

class NewPipeExtractorInitializerTest {
    @Test
    fun `initialization is idempotent for the same downloader`() {
        val downloader = NoOpDownloader()

        val first = NewPipeExtractorInitializer.initialize(downloader)
        val second = NewPipeExtractorInitializer.initialize(downloader)

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
    }
}
