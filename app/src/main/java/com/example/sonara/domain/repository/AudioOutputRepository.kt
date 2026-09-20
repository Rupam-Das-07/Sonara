package com.example.sonara.domain.repository

import com.example.sonara.domain.model.AudioOutputDevice
import com.example.sonara.domain.model.AudioOutputState
import kotlinx.coroutines.flow.StateFlow

/**
 * Authoritative port for discovering, observing, and routing audio output devices.
 */
interface AudioOutputRepository {
    /**
     * Observable stream representing the live active audio output route and all available routes.
     */
    val outputState: StateFlow<AudioOutputState>

    /**
     * Request the audio engine to route playback output to the specified [device].
     */
    fun selectDevice(device: AudioOutputDevice)

    /**
     * Force a refresh of the connected audio devices from the operating system.
     */
    fun refreshDevices()

    /**
     * Unregister system listeners and clean up resources.
     */
    fun release()
}
