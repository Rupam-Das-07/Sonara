package com.example.sonara.domain.model.soundcloud

import androidx.media3.common.MimeTypes

/**
 * Ephemeral audio stream descriptor extracted for a SoundCloud song.
 *
 * Direct stream URLs are temporary (signed CloudFront URLs) and MUST NOT be persisted to
 * Room database, DataStore, or persistent queue models.
 */
data class SoundcloudAudioStream(
    val id: String,
    val streamUrl: String,
    val deliveryMethod: SoundcloudDeliveryMethod,
    val mediaFormat: String,
    val mimeType: String,
    val bitrateKbps: Int
) {
    companion object {
        const val FORMAT_MP3 = "MP3"
        const val FORMAT_AAC = "M4A"
        const val FORMAT_OPUS = "OPUS"

        fun resolveMimeType(deliveryMethod: SoundcloudDeliveryMethod, mediaFormat: String): String =
            when (deliveryMethod) {
                SoundcloudDeliveryMethod.HLS -> MimeTypes.APPLICATION_M3U8
                SoundcloudDeliveryMethod.PROGRESSIVE_HTTP -> when (mediaFormat.uppercase()) {
                    FORMAT_MP3 -> MimeTypes.AUDIO_MPEG
                    FORMAT_AAC -> MimeTypes.AUDIO_AAC
                    FORMAT_OPUS -> MimeTypes.AUDIO_OPUS
                    else -> "audio/*"
                }
            }
    }
}
