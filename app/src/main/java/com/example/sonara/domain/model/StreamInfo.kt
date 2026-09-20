package com.example.sonara.domain.model

/**
 * Ephemeral stream information resolved on-demand via StreamResolverPort.
 * NOTE: Stream URLs are strictly ephemeral and MUST NOT be persisted.
 */
data class StreamInfo(
    val trackId: String,
    val streamUrl: String,
    val expiresAt: Long = 0L,
    val format: String = "audio/mpeg",
    val codec: String = "opus",
    val bitrateKbps: Int = 160,
    val qualityTier: String = "STANDARD",
    val provider: String = "youtube"
)
