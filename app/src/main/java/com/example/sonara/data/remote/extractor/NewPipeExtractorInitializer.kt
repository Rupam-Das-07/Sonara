package com.example.sonara.data.remote.extractor

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/** Process-wide, idempotent NewPipe configuration guard. */
object NewPipeExtractorInitializer {
    private val lock = Any()
    private var configuredDownloader: Downloader? = null

    fun initialize(downloader: Downloader): Result<Unit> = synchronized(lock) {
        val configured = configuredDownloader
        when {
            configured === downloader -> Result.success(Unit)
            configured != null -> Result.failure(
                IllegalStateException("NewPipe Extractor is already configured with another downloader")
            )
            else -> {
                NewPipe.init(downloader)
                configuredDownloader = downloader
                Result.success(Unit)
            }
        }
    }
}
