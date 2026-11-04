package com.example.sonara.data.remote.extractor

import android.content.ContextWrapper
import com.example.sonara.AppContainer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppContainerDownloaderLifecycleTest {
    private class FakeContext : ContextWrapper(null)

    @Test
    fun `app container shares exact same newPipeDownloader instance across all extractors`() {
        val container = AppContainer(FakeContext())

        val sharedDownloader = container.newPipeDownloader
        assertNotNull(sharedDownloader)

        val searchPort = container.soundcloudSearchPort
        val searchDownloaderField = SoundcloudSearchExtractorAdapter::class.java
            .getDeclaredField("downloader")
            .apply { isAccessible = true }
        val searchDownloader = searchDownloaderField.get(searchPort)
        assertSame(
            "soundcloudSearchPort must use the shared newPipeDownloader instance",
            sharedDownloader,
            searchDownloader
        )

        val trackExtractor = container.soundcloudExtractor
        val trackDownloaderField = SoundcloudTrackExtractor::class.java
            .getDeclaredField("downloader")
            .apply { isAccessible = true }
        val trackDownloader = trackDownloaderField.get(trackExtractor)
        assertSame(
            "soundcloudExtractor must use the shared newPipeDownloader instance",
            sharedDownloader,
            trackDownloader
        )

        val videoExtractor = container.videoExtractor
        val videoDownloaderField = NewPipeVideoExtractor::class.java
            .getDeclaredField("downloader")
            .apply { isAccessible = true }
        val videoDownloader = videoDownloaderField.get(videoExtractor)
        assertSame(
            "videoExtractor must use the shared newPipeDownloader instance",
            sharedDownloader,
            videoDownloader
        )
    }
}
