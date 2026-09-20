package com.example.sonara.domain.ports

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo

/**
 * Domain port for on-demand stream URL resolution.
 * Sits between Playback / RefreshingDataSource and remote data providers.
 */
interface StreamResolverPort {
    suspend fun resolveStream(
        trackId: String,
        quality: AudioQuality = AudioQuality.AUTO
    ): Result<StreamInfo>

    suspend fun resolveStream(
        trackId: String,
        quality: AudioQuality,
        title: String,
        artist: String,
        durationSeconds: Int
    ): Result<StreamInfo> = resolveStream(trackId, quality)

    fun clearCache()
}
