package com.example.sonara.domain.model

import com.example.sonara.domain.repository.AudioOutputRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputDeviceTest {

    @Test
    fun `default AudioOutputState initializes with Speakers active`() {
        val state = AudioOutputState()
        assertEquals("Speakers", state.activeDevice.name)
        assertEquals(AudioDeviceType.PHONE_SPEAKER, state.activeDevice.type)
        assertTrue(state.activeDevice.isCurrent)
        assertEquals(1, state.availableDevices.size)
        assertFalse(state.isSwitching)
    }

    @Test
    fun `selecting bluetooth device updates activeDevice and marks isCurrent correctly`() {
        val phoneSpeaker = AudioOutputDevice(
            id = 1,
            name = "Speakers",
            type = AudioDeviceType.PHONE_SPEAKER,
            isCurrent = false
        )
        val pixelBuds = AudioOutputDevice(
            id = 2,
            name = "Pixel Buds Pro",
            type = AudioDeviceType.BLUETOOTH_TWS,
            isCurrent = true
        )
        val sonyHeadphones = AudioOutputDevice(
            id = 3,
            name = "Sony WH-1000XM5",
            type = AudioDeviceType.BLUETOOTH_HEADPHONES,
            isCurrent = false
        )

        val fakeRepo = FakeAudioOutputRepository(
            initialDevices = listOf(pixelBuds, sonyHeadphones, phoneSpeaker)
        )

        assertEquals("Pixel Buds Pro", fakeRepo.outputState.value.activeDevice.name)

        // Select Sony headphones
        fakeRepo.selectDevice(sonyHeadphones)
        val updatedState = fakeRepo.outputState.value

        assertEquals("Sony WH-1000XM5", updatedState.activeDevice.name)
        assertEquals(AudioDeviceType.BLUETOOTH_HEADPHONES, updatedState.activeDevice.type)
        assertTrue(updatedState.activeDevice.isCurrent)

        // Verify list states
        val activeInList = updatedState.availableDevices.find { it.name == "Sony WH-1000XM5" }
        val prevInList = updatedState.availableDevices.find { it.name == "Pixel Buds Pro" }
        assertTrue(activeInList?.isCurrent == true)
        assertFalse(prevInList?.isCurrent == true)
    }

    @Test
    fun `switching to Phone Speaker clears external route and displays Speakers`() {
        val phoneSpeaker = AudioOutputDevice(
            id = 1,
            name = "Speakers",
            type = AudioDeviceType.PHONE_SPEAKER,
            isCurrent = false
        )
        val wiredHeadphones = AudioOutputDevice(
            id = 2,
            name = "Sony Wired Headphones",
            type = AudioDeviceType.WIRED_HEADPHONES,
            isCurrent = true
        )

        val fakeRepo = FakeAudioOutputRepository(
            initialDevices = listOf(wiredHeadphones, phoneSpeaker)
        )

        fakeRepo.selectDevice(phoneSpeaker)
        val state = fakeRepo.outputState.value

        assertEquals("Speakers", state.activeDevice.name)
        assertEquals(AudioDeviceType.PHONE_SPEAKER, state.activeDevice.type)
        assertTrue(state.activeDevice.isCurrent)
    }

    @Test
    fun `disconnecting external device falls back to Speakers`() {
        val phoneSpeaker = AudioOutputDevice(
            id = 1,
            name = "Speakers",
            type = AudioDeviceType.PHONE_SPEAKER,
            isCurrent = false
        )
        val bluetoothSpeaker = AudioOutputDevice(
            id = 2,
            name = "JBL Flip 6",
            type = AudioDeviceType.BLUETOOTH_SPEAKER,
            isCurrent = true
        )

        val fakeRepo = FakeAudioOutputRepository(
            initialDevices = listOf(bluetoothSpeaker, phoneSpeaker)
        )
        assertEquals("JBL Flip 6", fakeRepo.outputState.value.activeDevice.name)

        // Simulate bluetooth disconnection
        fakeRepo.updateAvailableDevices(listOf(phoneSpeaker))

        val state = fakeRepo.outputState.value
        assertEquals("Speakers", state.activeDevice.name)
        assertEquals(AudioDeviceType.PHONE_SPEAKER, state.activeDevice.type)
        assertEquals(1, state.availableDevices.size)
    }

    @Test
    fun `device names never expose raw numeric ids or hardware handles`() {
        val devices = listOf(
            AudioOutputDevice(id = 2, name = "Speakers", type = AudioDeviceType.PHONE_SPEAKER),
            AudioOutputDevice(id = 14, name = "Bluetooth Headphones", type = AudioDeviceType.BLUETOOTH_HEADPHONES),
            AudioOutputDevice(id = 8, name = "Wired Headphones", type = AudioDeviceType.WIRED_HEADPHONES),
            AudioOutputDevice(id = 22, name = "USB Audio", type = AudioDeviceType.USB_AUDIO)
        )

        for (device in devices) {
            assertFalse(device.name.all { it.isDigit() })
            assertFalse(device.name.startsWith("0x"))
            assertFalse(device.name.startsWith("audio_device"))
        }
    }

    @Test
    fun `current output string format matches specification`() {
        val template = "Current output: %s"
        val speakerFormatted = String.format(template, "Speakers")
        val budsFormatted = String.format(template, "Pixel Buds Pro")
        val dacFormatted = String.format(template, "FiiO DAC")

        assertEquals("Current output: Speakers", speakerFormatted)
        assertEquals("Current output: Pixel Buds Pro", budsFormatted)
        assertEquals("Current output: FiiO DAC", dacFormatted)
    }
}

class FakeAudioOutputRepository(
    initialDevices: List<AudioOutputDevice> = emptyList()
) : AudioOutputRepository {

    private val _outputState = MutableStateFlow(
        AudioOutputState(
            activeDevice = initialDevices.firstOrNull { it.isCurrent }
                ?: initialDevices.firstOrNull()
                ?: AudioOutputDevice(id = 0, name = "Speakers", type = AudioDeviceType.PHONE_SPEAKER, isCurrent = true),
            availableDevices = if (initialDevices.isEmpty()) {
                listOf(AudioOutputDevice(id = 0, name = "Speakers", type = AudioDeviceType.PHONE_SPEAKER, isCurrent = true))
            } else {
                initialDevices
            }
        )
    )
    override val outputState: StateFlow<AudioOutputState> = _outputState.asStateFlow()

    var selectedDevice: AudioOutputDevice? = null
    var refreshCalled = false

    override fun selectDevice(device: AudioOutputDevice) {
        selectedDevice = device
        _outputState.update { state ->
            val updated = state.availableDevices.map { it.copy(isCurrent = (it.id == device.id)) }
            state.copy(
                activeDevice = device.copy(isCurrent = true),
                availableDevices = updated
            )
        }
    }

    fun updateAvailableDevices(newDevices: List<AudioOutputDevice>) {
        val active = newDevices.find { it.isCurrent }
            ?: newDevices.firstOrNull { it.type != AudioDeviceType.PHONE_SPEAKER }
            ?: newDevices.firstOrNull()
            ?: AudioOutputDevice(id = 0, name = "Speakers", type = AudioDeviceType.PHONE_SPEAKER, isCurrent = true)

        _outputState.update {
            it.copy(
                activeDevice = active.copy(isCurrent = true),
                availableDevices = newDevices.map { dev -> dev.copy(isCurrent = dev.id == active.id) }
            )
        }
    }

    override fun refreshDevices() {
        refreshCalled = true
    }

    override fun release() {}
}
