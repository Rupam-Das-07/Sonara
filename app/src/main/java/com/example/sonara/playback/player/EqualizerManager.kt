package com.example.sonara.playback.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Manages a hardware [android.media.audiofx.Equalizer] attached to an ExoPlayer audio session.
 *
 * DESIGN CONSTRAINTS:
 * - The Equalizer is a hardware audio effect and MUST be bound to an audio session ID.
 * - ExoPlayer exposes its session via [ExoPlayer.audioSessionId].
 * - The session ID is stable for the lifetime of a single ExoPlayer instance but
 *   changes when ExoPlayer is released and recreated. This class must be released and
 *   re-instantiated accordingly (handled by [SonaraPlaybackService]).
 * - Not all Android devices support equalizer effects (e.g. some emulators or
 *   devices with Dolby/SRS DSP pipelines lock the audio path). [isSupported] will
 *   be false on such devices and all operations become no-ops.
 *
 * DEVICE COMPATIBILITY:
 * - [Equalizer] construction with a valid session ID throws [RuntimeException] on some
 *   devices. This class catches such exceptions and sets [isSupported] = false.
 * - Accessing individual bands on unsupported hardware also throws; all band operations
 *   are guarded.
 *
 * LOUDNESS NORMALIZATION — NOT implemented:
 * - This class provides genuine 5-band parametric EQ, not loudness normalization.
 * - Loudness normalization (LUFS/EBU R128) requires per-track loudness metadata that
 *   the current Sonara backend/yt-dlp pipeline does not supply.
 * - Do NOT use this class for fake volume normalization via setVolume().
 *
 * STANDARD BAND FREQUENCIES (approximate, device-dependent):
 *   Band 0: ~60 Hz  (sub-bass)
 *   Band 1: ~230 Hz (bass)
 *   Band 2: ~910 Hz (midrange)
 *   Band 3: ~3600 Hz (presence)
 *   Band 4: ~14000 Hz (air/treble)
 */
class EqualizerManager(
    audioSessionId: Int
) {
    companion object {
        private const val TAG = "EqualizerManager"
        const val BAND_COUNT = 5

        /**
         * Lightweight static capability check using AudioEffect.queryEffects()
         * without instantiating an Equalizer or touching audio sessions.
         */
        fun isHardwareSupported(): Boolean {
            return try {
                val effects = AudioEffect.queryEffects() ?: return false
                effects.any { it.type == AudioEffect.EFFECT_TYPE_EQUALIZER }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private var equalizer: Equalizer? = null

    /**
     * True if a hardware equalizer was successfully attached to [audioSessionId].
     * False if the device does not support it or construction failed.
     * All methods are no-ops when this is false.
     */
    val isSupported: Boolean

    /**
     * Per-band [millibels] range supported by this device.
     * Null when [isSupported] is false.
     * Typical values: -1500 mB to +1500 mB (i.e. ±15 dB in 100 mB increments).
     */
    val bandLevelRange: Pair<Short, Short>?

    /**
     * Approximate centre frequencies (in millihertz) for each band, indexed 0..4.
     * Null when [isSupported] is false.
     */
    val bandCentreFrequenciesMilliHz: List<Int>?

    init {
        var supported = false
        var levelRange: Pair<Short, Short>? = null
        var centreFreqs: List<Int>? = null

        try {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            supported = true

            val range = eq.bandLevelRange
            levelRange = Pair(range[0], range[1])

            centreFreqs = (0 until eq.numberOfBands.toInt()).map { band ->
                eq.getCenterFreq(band.toShort()).toInt()
            }

            Log.i(TAG, "Equalizer attached to session $audioSessionId. " +
                    "Bands: ${eq.numberOfBands}, Range: ${range[0]}..${range[1]} mB")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Equalizer not supported on this device (session=$audioSessionId): ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Equalizer UnsupportedOperationException (session=$audioSessionId): ${e.message}")
        }

        isSupported = supported
        bandLevelRange = levelRange
        bandCentreFrequenciesMilliHz = centreFreqs
    }

    /**
     * Enable or disable the equalizer.
     * No-op if [isSupported] is false.
     */
    fun setEnabled(enabled: Boolean) {
        if (!isSupported) return
        try {
            equalizer?.enabled = enabled
            Log.d(TAG, "Equalizer enabled=$enabled")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to set equalizer enabled: ${e.message}")
        }
    }

    /**
     * Set the gain of a single band.
     *
     * @param band  Zero-based band index (0..4).
     * @param millibels Gain in millibels. Clamped to [bandLevelRange] on the device.
     *
     * No-op if [isSupported] is false or [band] is out of range.
     */
    fun setBandLevel(band: Int, millibels: Int) {
        if (!isSupported) return
        val eq = equalizer ?: return
        if (band < 0 || band >= BAND_COUNT) return
        try {
            val clamped = clampToBandRange(millibels.toShort())
            eq.setBandLevel(band.toShort(), clamped)
            Log.d(TAG, "Band $band set to $clamped mB")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to set band $band: ${e.message}")
        }
    }

    /**
     * Apply all 5 band gains at once.
     * [gains] must contain exactly [BAND_COUNT] entries. Silently ignored otherwise.
     */
    fun applyBandGains(gains: List<Int>) {
        if (!isSupported) return
        if (gains.size != BAND_COUNT) return
        gains.forEachIndexed { band, mB -> setBandLevel(band, mB) }
    }

    /**
     * Release the underlying hardware effect. MUST be called in onDestroy of
     * [SonaraPlaybackService] before ExoPlayer.release().
     *
     * After release, this object must be discarded — do not call any other method.
     */
    fun release() {
        try {
            equalizer?.release()
            Log.i(TAG, "Equalizer released")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Equalizer release error: ${e.message}")
        } finally {
            equalizer = null
        }
    }

    private fun clampToBandRange(value: Short): Short {
        val range = bandLevelRange ?: return value
        return value.coerceIn(range.first, range.second)
    }
}
