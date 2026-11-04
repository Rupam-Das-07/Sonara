package com.example.sonara.domain.model.video

/**
 * Normalized made-for-kids status returned by the Sonara backend.
 *
 * UNKNOWN is intentionally retained as a first-class value. Callers must not
 * infer a safe value when the upstream status is absent or malformed.
 */
enum class MadeForKidsStatus {
    MADE_FOR_KIDS,
    NOT_MADE_FOR_KIDS,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): MadeForKidsStatus = when (value?.trim()) {
            "MADE_FOR_KIDS" -> MADE_FOR_KIDS
            "NOT_MADE_FOR_KIDS" -> NOT_MADE_FOR_KIDS
            else -> UNKNOWN
        }
    }
}

/**
 * Normalized embeddability status returned by the Sonara backend.
 *
 * UNKNOWN is fail-closed for video embedding decisions.
 */
enum class EmbeddabilityStatus {
    EMBEDDABLE,
    NOT_EMBEDDABLE,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): EmbeddabilityStatus = when (value?.trim()) {
            "EMBEDDABLE" -> EMBEDDABLE
            "NOT_EMBEDDABLE" -> NOT_EMBEDDABLE
            else -> UNKNOWN
        }
    }
}

/**
 * Backend status metadata for one YouTube video.
 *
 * This contains policy metadata only; it does not imply that playback or
 * extraction is available.
 */
data class VideoStatus(
    val videoId: String,
    val madeForKidsStatus: MadeForKidsStatus,
    val embeddabilityStatus: EmbeddabilityStatus
)

internal fun isValidYouTubeVideoId(videoId: String): Boolean =
    videoId.length == 11 && videoId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
