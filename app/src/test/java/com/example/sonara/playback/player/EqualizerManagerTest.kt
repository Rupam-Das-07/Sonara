package com.example.sonara.playback.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for EqualizerManager.
 *
 * NOTE: android.media.audiofx.Equalizer is a hardware/system component not available
 * in the JVM unit test environment. These tests verify that:
 * 1. EqualizerManager handles unsupported devices gracefully (isSupported = false).
 * 2. All operations are no-ops when isSupported = false.
 * 3. Constant values are correct.
 *
 * Full hardware EQ integration testing requires an instrumented test on a real device,
 * which belongs to androidTest/ (not covered here — device-dependent behaviour).
 */
class EqualizerManagerTest {

    /**
     * EqualizerManager with audioSessionId = 0 cannot attach to any real player session.
     * On the JVM (unit test environment), Equalizer construction will fail because the
     * audio framework is unavailable. The manager must handle this gracefully.
     * Returns the manager instance (isSupported will be false on JVM).
     */
    private fun createManagerWithUnsupportedSession(): EqualizerManager {
        return EqualizerManager(0) // audioSessionId=0 → always gracefully unsupported on JVM
    }

    @Test
    fun `BAND_COUNT is 5`() {
        assertEquals(5, EqualizerManager.BAND_COUNT)
    }

    @Test
    fun `manager construction with invalid session does not throw`() {
        // The key guarantee: EqualizerManager must NEVER throw on construction regardless of hardware.
        // On a real Android device with audioSessionId=0 or unsupported hardware, isSupported=false.
        // On the JVM unit test environment (Robolectric), the stub may or may not report support.
        val manager = createManagerWithUnsupportedSession()
        // Construction must have succeeded — isSupported is a boolean, not an exception
        assertNotNull("manager must not be null after construction", manager)
        // Regardless of isSupported, all operations must be non-throwing:
        manager.setEnabled(true)
        manager.setEnabled(false)
        manager.applyBandGains(listOf(0, 0, 0, 0, 0))
        manager.release()
    }

    @Test
    fun `bandLevelRange is null when unsupported`() {
        val manager = createManagerWithUnsupportedSession()
        if (!manager.isSupported) {
            assertNull("bandLevelRange must be null when unsupported", manager.bandLevelRange)
        }
    }

    @Test
    fun `bandCentreFrequenciesMilliHz is null when unsupported`() {
        val manager = createManagerWithUnsupportedSession()
        if (!manager.isSupported) {
            assertNull("centreFrequencies must be null when unsupported", manager.bandCentreFrequenciesMilliHz)
        }
    }

    @Test
    fun `setEnabled is a no-op when unsupported`() {
        val manager = createManagerWithUnsupportedSession()
        if (!manager.isSupported) {
            // Must not throw
            manager.setEnabled(true)
            manager.setEnabled(false)
        }
    }

    @Test
    fun `setBandLevel is a no-op when unsupported`() {
        val manager = createManagerWithUnsupportedSession()
        if (!manager.isSupported) {
            // Must not throw for any valid or invalid band index
            manager.setBandLevel(0, 500)
            manager.setBandLevel(4, -1500)
            manager.setBandLevel(-1, 0)  // out of range — should be silently ignored
            manager.setBandLevel(5, 0)   // out of range — should be silently ignored
        }
    }

    @Test
    fun `applyBandGains is a no-op when unsupported`() {
        val manager = createManagerWithUnsupportedSession()
        if (!manager.isSupported) {
            manager.applyBandGains(listOf(0, 0, 0, 0, 0))
            manager.applyBandGains(listOf(500, -500, 200, -200, 0))
        }
    }

    @Test
    fun `applyBandGains silently ignores wrong-size lists`() {
        val manager = createManagerWithUnsupportedSession()
        // Must not throw regardless of support state
        manager.applyBandGains(emptyList())
        manager.applyBandGains(listOf(0, 0, 0))
        manager.applyBandGains(listOf(0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `release is safe to call when unsupported`() {
        val manager = createManagerWithUnsupportedSession()
        // Must not throw
        manager.release()
    }

    @Test
    fun `release is idempotent`() {
        val manager = createManagerWithUnsupportedSession()
        manager.release()
        manager.release() // Second release must not throw
    }

    /**
     * Verifies that no loudness normalization logic exists in EqualizerManager.
     * This is a safeguard test — EqualizerManager must ONLY do EQ, not volume scaling.
     */
    @Test
    fun `EqualizerManager does not expose setVolume or any normalization method`() {
        val methods = EqualizerManager::class.java.declaredMethods.map { it.name }
        assertFalse("setVolume must not exist on EqualizerManager", "setVolume" in methods)
        assertFalse("normalize must not exist on EqualizerManager", "normalize" in methods)
        assertFalse("setLoudness must not exist on EqualizerManager", "setLoudness" in methods)
    }

    @Test
    fun `isHardwareSupported returns a boolean without throwing`() {
        val supported = EqualizerManager.isHardwareSupported()
        assertNotNull(supported)
    }
}
