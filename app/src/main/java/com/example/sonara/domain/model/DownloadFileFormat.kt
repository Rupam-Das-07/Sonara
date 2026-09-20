package com.example.sonara.domain.model

/**
 * Represents the user-selected download file format preference.
 *
 * IMPORTANT — FORMAT AVAILABILITY:
 *
 * The Sonara backend routes all audio through the yt-dlp `bestaudio` selection,
 * which returns an `audio/webm` (Opus-in-WebM) stream. No server-side transcoding
 * pipeline exists to convert to M4A (AAC-in-M4A) or MP4 (video container).
 *
 * Renaming a WebM binary to .m4a / .mp4 does NOT produce a valid container —
 * the resulting file would be unplayable by Android MediaPlayer / ExoPlayer.
 *
 * Availability policy at download time (DownloadEngine):
 *   WEBM  → download proceeds normally to .webm
 *   M4A   → [isCurrentlyAvailable = false] → abort with FormatUnavailableException
 *   MP4   → [isCurrentlyAvailable = false] → abort with FormatUnavailableException
 *
 * Existing downloaded files are NEVER renamed or re-encoded when the preference
 * changes. Already-downloaded tracks remain valid at their original paths.
 *
 * Future work: When the backend exposes a transcoding endpoint (e.g. yt-dlp
 * `--remux-video mp4` or a server-side ffmpeg pipeline), set
 * [isCurrentlyAvailable] = true for the corresponding format and update
 * DownloadEngine to use the correct endpoint + extension.
 */
enum class DownloadFileFormat(
    /** Human-readable display label. */
    val displayLabel: String,
    /** File extension used for the downloaded file. */
    val fileExtension: String,
    /**
     * MIME type associated with this format.
     * Used when creating MediaStore entries (future) and for diagnostics.
     */
    val mimeType: String,
    /**
     * Whether this format can currently be obtained natively from Sonara providers.
     *
     * TRUE for formats that Sonara can legitimately resolve natively:
     * - AUTO: Preserves native resolved stream container (WebM for YouTube, M4A for JioSaavn)
     * - WEBM: Native Opus stream in WebM container (YouTube)
     * - M4A: Native AAC-LC stream in MP4/M4A container (JioSaavn)
     * - MP4: Native AAC-LC stream in audio-only MP4 container (JioSaavn)
     */
    val isCurrentlyAvailable: Boolean
) {
    AUTO(
        displayLabel = "Original / Auto",
        fileExtension = "",
        mimeType = "*/*",
        isCurrentlyAvailable = true
    ),
    WEBM(
        displayLabel = "WebM (Opus)",
        fileExtension = "webm",
        mimeType = "audio/webm",
        isCurrentlyAvailable = true
    ),
    M4A(
        displayLabel = "M4A (AAC)",
        fileExtension = "m4a",
        mimeType = "audio/mp4",
        isCurrentlyAvailable = true
    ),
    MP4(
        displayLabel = "MP4 Audio (.mp4)",
        fileExtension = "mp4",
        mimeType = "audio/mp4",
        isCurrentlyAvailable = true
    );

    companion object {
        /** Safe deserialisation — returns AUTO on unknown stored value. */
        fun fromName(name: String): DownloadFileFormat =
            entries.firstOrNull { it.name == name } ?: AUTO

        /** Returns only formats that the providers can currently produce natively. */
        fun availableFormats(): List<DownloadFileFormat> =
            entries.filter { it.isCurrentlyAvailable }
    }
}
