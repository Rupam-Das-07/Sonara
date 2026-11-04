package com.example.sonara.domain.model.video

/**
 * The only successful outcome of the video policy gate.
 */
enum class VideoEmbeddingEligibility {
    ALLOWED,
    DENIED
}

enum class VideoEmbeddingDenialReason {
    INVALID_VIDEO_ID,
    MADE_FOR_KIDS_REQUIRES_POLICY_APPROVAL,
    UNKNOWN_MADE_FOR_KIDS_STATUS,
    NOT_EMBEDDABLE,
    UNKNOWN_EMBEDDABILITY_STATUS,
    VERIFICATION_FAILED
}

/**
 * Pure result of evaluating whether a video may be embedded.
 */
data class VideoEmbeddingEligibilityResult(
    val eligibility: VideoEmbeddingEligibility,
    val denialReason: VideoEmbeddingDenialReason? = null
) {
    val isAllowed: Boolean
        get() = eligibility == VideoEmbeddingEligibility.ALLOWED

    val allowed: Boolean
        get() = isAllowed
}

/**
 * Fail-closed video embedding policy.
 *
 * Interim conservative policy: only explicitly NOT_MADE_FOR_KIDS videos may be embedded.
 * Made-for-Kids handling requires product/policy approval before it can be enabled safely.
 * Embeddability must also be explicitly EMBEDDABLE. UNKNOWN values always deny embedding.
 */
fun evaluateVideoEmbeddingEligibility(status: VideoStatus): VideoEmbeddingEligibilityResult =
    when {
        !isValidYouTubeVideoId(status.videoId) -> VideoEmbeddingEligibilityResult(
            VideoEmbeddingEligibility.DENIED,
            VideoEmbeddingDenialReason.INVALID_VIDEO_ID
        )
        status.madeForKidsStatus == MadeForKidsStatus.MADE_FOR_KIDS -> VideoEmbeddingEligibilityResult(
            VideoEmbeddingEligibility.DENIED,
            VideoEmbeddingDenialReason.MADE_FOR_KIDS_REQUIRES_POLICY_APPROVAL
        )
        status.madeForKidsStatus == MadeForKidsStatus.UNKNOWN -> VideoEmbeddingEligibilityResult(
            VideoEmbeddingEligibility.DENIED,
            VideoEmbeddingDenialReason.UNKNOWN_MADE_FOR_KIDS_STATUS
        )
        status.embeddabilityStatus == EmbeddabilityStatus.NOT_EMBEDDABLE -> VideoEmbeddingEligibilityResult(
            VideoEmbeddingEligibility.DENIED,
            VideoEmbeddingDenialReason.NOT_EMBEDDABLE
        )
        status.embeddabilityStatus == EmbeddabilityStatus.UNKNOWN -> VideoEmbeddingEligibilityResult(
            VideoEmbeddingEligibility.DENIED,
            VideoEmbeddingDenialReason.UNKNOWN_EMBEDDABILITY_STATUS
        )
        else -> VideoEmbeddingEligibilityResult(VideoEmbeddingEligibility.ALLOWED)
    }

/**
 * Errors are denied without attempting to inspect or infer missing metadata.
 */
fun evaluateVideoEmbeddingEligibility(statusResult: Result<VideoStatus>): VideoEmbeddingEligibilityResult =
    statusResult.fold(
        onSuccess = { status -> evaluateVideoEmbeddingEligibility(status) },
        onFailure = {
            VideoEmbeddingEligibilityResult(
                VideoEmbeddingEligibility.DENIED,
                VideoEmbeddingDenialReason.VERIFICATION_FAILED
            )
        }
    )
