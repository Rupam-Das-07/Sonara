package com.example.sonara.domain.ports

import com.example.sonara.domain.model.soundcloud.SoundcloudTrackExtraction

/**
 * Domain port for extracting song metadata and ephemeral audio streams from SoundCloud.
 */
interface SoundcloudTrackExtractionPort {
    suspend fun extract(input: String): Result<SoundcloudTrackExtraction>
}
