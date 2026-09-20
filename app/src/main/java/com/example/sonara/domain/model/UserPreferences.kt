package com.example.sonara.domain.model

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

/**
 * Domain model representing user-configurable application preferences.
 *
 * NEW FIELDS (Settings + Search Phase 1):
 *
 * [appLanguage]              — UI display language. Independent from lyric/romanization language.
 *                              Defaults to system locale (SYSTEM_DEFAULT).
 *
 * [equalizerEnabled]         — Whether the Android audio-effects Equalizer is active.
 *                              Bound to the ExoPlayer audio session in SonaraPlaybackService.
 *
 * [equalizerBandGains]       — Per-band gain levels in millibels (mB). 5 entries indexed 0..4.
 *                              Safe default is 0 mB on each band (flat response).
 *
 * [stopMusicOnTaskClear]     — When ON: SonaraPlaybackService.onTaskRemoved() unconditionally
 *                              stops playback. When OFF: current behaviour (playback continues
 *                              while playing, service stops only when idle).
 *
 * [downloadFileFormat]       — Preferred container for offline downloads.
 *                              Only WEBM is currently deliverable by the backend.
 *
 * [downloadLocationMode]     — Where download files are stored.
 *                              USER_SELECTED requires SAF frontend integration (Phase 2).
 *
 * LOUDNESS NORMALIZER — NOT IMPLEMENTED:
 *   Genuine loudness normalization (LUFS/EBU R128/ReplayGain) requires loudness
 *   metadata embedded in or alongside the audio stream. The Sonara backend does not
 *   currently expose ReplayGain tags, and the YouTube audio streams delivered via
 *   yt-dlp do not carry LUFS metadata accessible to Android ExoPlayer.
 *   Using ExoPlayer.setVolume() as a substitute would be scalar attenuation, not
 *   loudness normalization — it does NOT equalize perceived loudness across tracks.
 *   This preference is intentionally omitted until the backend/provider supplies
 *   the required loudness metadata or a proper DSP AudioProcessor pipeline is added.
 *   Status: BACKEND INFRASTRUCTURE READY / FUNCTIONAL NORMALIZATION PENDING.
 */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showRomanized: Boolean = true,
    val restorePlaybackSession: Boolean = true,
    val defaultSearchMode: SearchMode = SearchMode.SONGS,
    val reduceMotion: Boolean = false,
    val streamingQuality: AudioQuality = AudioQuality.AUTO,
    val downloadQuality: AudioQuality = AudioQuality.VERY_HIGH,
    val downloadOverWifiOnly: Boolean = false,

    // ─── Phase 1 additions ────────────────────────────────────────────────────
    val appLanguage: AppLanguage = AppLanguage.SYSTEM_DEFAULT,
    val equalizerEnabled: Boolean = false,
    val equalizerBandGains: List<Int> = listOf(0, 0, 0, 0, 0), // millibels, 5 bands (0..4)
    val stopMusicOnTaskClear: Boolean = false,
    val downloadFileFormat: DownloadFileFormat = DownloadFileFormat.AUTO,
    val downloadLocationMode: DownloadLocationMode = DownloadLocationMode.APP_PRIVATE
)
