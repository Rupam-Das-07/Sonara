package com.example.sonara.domain.model

/**
 * Categorized audio output hardware types.
 */
enum class AudioDeviceType {
    PHONE_SPEAKER,
    BLUETOOTH_HEADPHONES,
    BLUETOOTH_TWS,
    BLUETOOTH_SPEAKER,
    WIRED_HEADPHONES,
    USB_AUDIO,
    OTHER
}

/**
 * Domain model representing a physical or virtual audio output destination.
 *
 * [id]: Android [android.media.AudioDeviceInfo.getId] or 0 for default phone speaker.
 * [name]: Dynamic human-readable label obtained from the operating system (e.g. "Pixel Buds Pro").
 * [type]: Categorized hardware type for icon and subtitle representation.
 * [isCurrent]: True if this route is the currently active audio output.
 * [rawType]: Android [android.media.AudioDeviceInfo.getType] constant.
 */
data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val type: AudioDeviceType,
    val isCurrent: Boolean = false,
    val rawType: Int = 0,
    val address: String? = null
)

/**
 * Observable UI/domain state for audio device routing.
 */
data class AudioOutputState(
    val activeDevice: AudioOutputDevice = AudioOutputDevice(
        id = 0,
        name = "Speakers",
        type = AudioDeviceType.PHONE_SPEAKER,
        isCurrent = true
    ),
    val availableDevices: List<AudioOutputDevice> = listOf(
        AudioOutputDevice(
            id = 0,
            name = "Speakers",
            type = AudioDeviceType.PHONE_SPEAKER,
            isCurrent = true
        )
    ),
    val isSwitching: Boolean = false,
    val errorMessage: String? = null
)
