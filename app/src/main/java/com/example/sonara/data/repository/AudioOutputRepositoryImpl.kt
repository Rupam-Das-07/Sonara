package com.example.sonara.data.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.sonara.domain.model.AudioDeviceType
import com.example.sonara.domain.model.AudioOutputDevice
import com.example.sonara.domain.model.AudioOutputState
import com.example.sonara.domain.repository.AudioOutputRepository
import com.example.sonara.playback.client.MediaControllerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Android AudioOutputRepository managing dynamic audio device discovery,
 * real-time route callbacks, and playback output switching via ExoPlayer.
 */
class AudioOutputRepositoryImpl(
    private val context: Context,
    private val mediaControllerClient: MediaControllerClient
) : AudioOutputRepository {

    companion object {
        private const val TAG = "AudioOutputRepo"
    }

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _outputState = MutableStateFlow(AudioOutputState())
    override val outputState: StateFlow<AudioOutputState> = _outputState.asStateFlow()

    private var preferredDeviceId: Int? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices added: count=${addedDevices?.size}")
            // When an external device connects, clear any previous manual override
            val hasExternal = addedDevices?.any {
                it.isSink && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE &&
                it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            } ?: false

            if (hasExternal) {
                preferredDeviceId = null
                mediaControllerClient.setPreferredAudioDevice(0)
            }

            refreshDevices()
            mainHandler.postDelayed({ refreshDevices() }, 300)
            mainHandler.postDelayed({ refreshDevices() }, 800)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices removed: count=${removedDevices?.size}")
            // If the preferred device was unplugged/disconnected, clear override
            if (removedDevices != null && preferredDeviceId != null) {
                if (removedDevices.any { it.id == preferredDeviceId }) {
                    preferredDeviceId = null
                    mediaControllerClient.setPreferredAudioDevice(0)
                }
            }
            refreshDevices()
            mainHandler.postDelayed({ refreshDevices() }, 300)
        }
    }

    private val routingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Audio routing broadcast intent received: $action")
            if (action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" ||
                action == AudioManager.ACTION_HEADSET_PLUG ||
                action == "android.hardware.usb.action.USB_DEVICE_ATTACHED" ||
                action == "android.hardware.usb.action.USB_DEVICE_DETACHED" ||
                action == AudioManager.ACTION_AUDIO_BECOMING_NOISY
            ) {
                preferredDeviceId = null
                mediaControllerClient.setPreferredAudioDevice(0)
            }
            refreshDevices()
            mainHandler.postDelayed({ refreshDevices() }, 200)
            mainHandler.postDelayed({ refreshDevices() }, 600)
        }
    }

    init {
        try {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register AudioDeviceCallback: ${e.message}")
        }

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
                addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(routingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(routingReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register routing BroadcastReceiver: ${e.message}")
        }

        refreshDevices()
    }

    override fun refreshDevices() {
        val manager = audioManager ?: run {
            _outputState.value = AudioOutputState()
            return
        }

        try {
            val rawDevices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }

            // Deduplicate devices by id and name (e.g. A2DP + SCO profiles for same Bluetooth device)
            val mappedDevices = mutableListOf<AudioOutputDevice>()
            val seenNames = mutableSetOf<String>()
            var speakerDevice: AudioOutputDevice? = null

            // Prioritize A2DP output over SCO for Bluetooth media fidelity
            val sortedRaw = rawDevices.sortedByDescending { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

            for (raw in sortedRaw) {
                val mapped = mapDeviceInfo(raw)
                Log.d(TAG, "RawDevice: id=${raw.id}, rawType=${raw.type}, productName=${raw.productName}, mappedName=${mapped.name}, mappedType=${mapped.type}")
                if (mapped.type == AudioDeviceType.PHONE_SPEAKER) {
                    if (speakerDevice == null) {
                        speakerDevice = mapped
                    }
                } else {
                    val key = "${mapped.type}_${mapped.name.lowercase().trim()}"
                    if (seenNames.add(key)) {
                        mappedDevices.add(mapped)
                    }
                }
            }

            // Ensure Phone Speaker is always present with clean name "Speakers"
            val defaultSpeaker = speakerDevice ?: AudioOutputDevice(
                id = 0,
                name = "Speakers",
                type = AudioDeviceType.PHONE_SPEAKER
            )

            val fullList = mutableListOf<AudioOutputDevice>()
            // External devices first (Wired, USB, Bluetooth), then Phone Speaker
            fullList.addAll(mappedDevices)
            fullList.add(defaultSpeaker)

            // Determine active device
            val activeDevice = resolveActiveDevice(fullList)

            // Mark active state in the list
            val finalizedList = fullList.map { dev ->
                dev.copy(isCurrent = (dev.id == activeDevice.id && dev.type == activeDevice.type))
            }

            for (dev in finalizedList) {
                Log.d(TAG, "FinalDevice: id=${dev.id}, name=${dev.name}, type=${dev.type}, isCurrent=${dev.isCurrent}")
            }

            _outputState.update {
                it.copy(
                    activeDevice = activeDevice.copy(isCurrent = true),
                    availableDevices = finalizedList,
                    isSwitching = false,
                    errorMessage = null
                )
            }
            Log.d(TAG, "Refreshed audio devices: active=${activeDevice.name}, total=${finalizedList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating audio devices: ${e.message}")
            _outputState.update {
                it.copy(
                    isSwitching = false,
                    errorMessage = e.message
                )
            }
        }
    }

    override fun selectDevice(device: AudioOutputDevice) {
        Log.i(TAG, "Selecting audio output: id=${device.id}, name=${device.name}, type=${device.type}")
        _outputState.update { it.copy(isSwitching = true, errorMessage = null) }

        try {
            preferredDeviceId = device.id
            mediaControllerClient.setPreferredAudioDevice(device.id)
            refreshDevices()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route to audio device ${device.name}: ${e.message}")
            _outputState.update {
                it.copy(
                    isSwitching = false,
                    errorMessage = e.message ?: "Failed to switch audio output"
                )
            }
        }
    }

    override fun release() {
        try {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering AudioDeviceCallback: ${e.message}")
        }
        try {
            context.unregisterReceiver(routingReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering routing BroadcastReceiver: ${e.message}")
        }
    }

    /**
     * Resolves the active route based on user preference or Android routing priority.
     */
    private fun resolveActiveDevice(availableList: List<AudioOutputDevice>): AudioOutputDevice {
        val prefId = preferredDeviceId
        Log.d(TAG, "resolveActiveDevice: preferredDeviceId=$prefId, availableCount=${availableList.size}")

        if (prefId != null && prefId != 0) {
            val matching = availableList.find { it.id == prefId }
            if (matching != null) {
                Log.d(TAG, "resolveActiveDevice: matched preferredDeviceId=$prefId -> ${matching.name}")
                return matching
            }
        }

        val bluetooth = availableList.find {
            it.type == AudioDeviceType.BLUETOOTH_HEADPHONES ||
            it.type == AudioDeviceType.BLUETOOTH_TWS ||
            it.type == AudioDeviceType.BLUETOOTH_SPEAKER
        }
        val wired = availableList.find {
            it.type == AudioDeviceType.WIRED_HEADPHONES ||
            it.type == AudioDeviceType.USB_AUDIO
        }

        // Active routing determination:
        // Case A: Both Bluetooth and Wired are present -> check if Bluetooth A2DP is active
        if (bluetooth != null && wired != null) {
            val isBtActive = try { audioManager?.isBluetoothA2dpOn == true } catch (_: Exception) { false }
            if (isBtActive) {
                Log.d(TAG, "resolveActiveDevice: active route -> Bluetooth: ${bluetooth.name} (id=${bluetooth.id})")
                return bluetooth
            } else {
                Log.d(TAG, "resolveActiveDevice: active route -> Wired: ${wired.name} (id=${wired.id})")
                return wired
            }
        }

        // Case B: Wired headphones connected (3.5mm or USB-C)
        if (wired != null) {
            Log.d(TAG, "resolveActiveDevice: active route -> Wired: ${wired.name} (id=${wired.id})")
            return wired
        }

        // Case C: Bluetooth connected
        if (bluetooth != null) {
            Log.d(TAG, "resolveActiveDevice: active route -> Bluetooth: ${bluetooth.name} (id=${bluetooth.id})")
            return bluetooth
        }

        // Case D: Default to Phone Speaker ("Speakers")
        val speaker = availableList.find { it.type == AudioDeviceType.PHONE_SPEAKER }
        val finalSpeaker = speaker ?: availableList.firstOrNull() ?: AudioOutputDevice(
            id = 0,
            name = "Speakers",
            type = AudioDeviceType.PHONE_SPEAKER
        )
        Log.d(TAG, "resolveActiveDevice: active route -> Speakers: ${finalSpeaker.name}")
        return finalSpeaker
    }

    private fun mapDeviceInfo(device: AudioDeviceInfo): AudioOutputDevice {
        val rawType = device.type
        val rawProductName = device.productName?.toString()?.trim().orEmpty()

        val (type, fallbackName) = when (rawType) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_TELEPHONY -> {
                AudioDeviceType.PHONE_SPEAKER to "Speakers"
            }
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> {
                val lower = rawProductName.lowercase()
                val btType = when {
                    lower.contains("buds") || lower.contains("earbuds") || lower.contains("tws") ||
                    lower.contains("airpods") || lower.contains("earphones") || lower.contains("in-ear") ->
                        AudioDeviceType.BLUETOOTH_TWS
                    lower.contains("speaker") || lower.contains("soundbar") || lower.contains("box") ->
                        AudioDeviceType.BLUETOOTH_SPEAKER
                    else ->
                        AudioDeviceType.BLUETOOTH_HEADPHONES
                }
                val defaultBtName = when (btType) {
                    AudioDeviceType.BLUETOOTH_TWS -> "Bluetooth Earbuds"
                    AudioDeviceType.BLUETOOTH_SPEAKER -> "Bluetooth Speaker"
                    else -> "Bluetooth Headphones"
                }
                btType to defaultBtName
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                AudioDeviceType.WIRED_HEADPHONES to "Wired Headphones"
            }
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                AudioDeviceType.USB_AUDIO to "Wired Headphones"
            }
            else -> {
                AudioDeviceType.OTHER to "Audio Device"
            }
        }

        val resolvedBtName = if (type == AudioDeviceType.BLUETOOTH_TWS ||
            type == AudioDeviceType.BLUETOOTH_HEADPHONES ||
            type == AudioDeviceType.BLUETOOTH_SPEAKER
        ) {
            resolveBluetoothDeviceName(device)
        } else {
            ""
        }

        val finalName = if (type == AudioDeviceType.PHONE_SPEAKER) {
            "Speakers"
        } else if (type == AudioDeviceType.WIRED_HEADPHONES) {
            // For analog 3.5mm headphones, Android reports phone internal board model or generic "headset"
            if (rawProductName.isNotBlank() &&
                !isInternalOrNumericId(rawProductName) &&
                !isGenericDeviceName(rawProductName) &&
                !isPhoneModelName(rawProductName)
            ) {
                rawProductName
            } else {
                "Wired Headphones"
            }
        } else if (type == AudioDeviceType.USB_AUDIO) {
            // For USB Type-C DAC / earphones, use product descriptor if non-generic, otherwise "Wired Headphones"
            if (rawProductName.isNotBlank() &&
                !isInternalOrNumericId(rawProductName) &&
                !isGenericDeviceName(rawProductName) &&
                !isPhoneModelName(rawProductName)
            ) {
                rawProductName
            } else {
                "Wired Headphones"
            }
        } else if (rawProductName.isNotBlank() &&
            !isInternalOrNumericId(rawProductName) &&
            !isGenericDeviceName(rawProductName) &&
            !isPhoneModelName(rawProductName)
        ) {
            rawProductName
        } else if (resolvedBtName.isNotBlank() &&
            !isInternalOrNumericId(resolvedBtName) &&
            !isGenericDeviceName(resolvedBtName) &&
            !isPhoneModelName(resolvedBtName)
        ) {
            resolvedBtName
        } else {
            fallbackName
        }

        return AudioOutputDevice(
            id = device.id,
            name = finalName,
            type = type,
            isCurrent = false,
            rawType = rawType,
            address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.address
            } else {
                null
            }
        )
    }

    private fun resolveBluetoothDeviceName(raw: AudioDeviceInfo): String {
        val directName = raw.productName?.toString()?.trim().orEmpty()
        if (directName.isNotBlank() && !isInternalOrNumericId(directName) && !isGenericDeviceName(directName) && !isPhoneModelName(directName)) {
            return directName
        }

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                if (adapter != null && adapter.isEnabled) {
                    val rawAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        raw.address
                    } else {
                        null
                    }
                    if (!rawAddress.isNullOrBlank()) {
                        val match = adapter.bondedDevices?.find { it.address.equals(rawAddress, ignoreCase = true) }
                        val btName = match?.name?.trim().orEmpty()
                        if (btName.isNotBlank() && !isPhoneModelName(btName)) return btName
                    }
                    val bonded = adapter.bondedDevices?.filter {
                        val devClass = it.bluetoothClass?.majorDeviceClass
                        devClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
                        it.name?.isNotBlank() == true
                    }
                    if (bonded != null && bonded.isNotEmpty()) {
                        val singleName = bonded.first().name?.trim().orEmpty()
                        if (singleName.isNotBlank() && !isPhoneModelName(singleName)) return singleName
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth name lookup fallback: ${e.message}")
        }

        return ""
    }

    private fun isPhoneModelName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.equals(Build.MODEL, ignoreCase = true) ||
               trimmed.equals(Build.DEVICE, ignoreCase = true) ||
               trimmed.equals(Build.PRODUCT, ignoreCase = true) ||
               trimmed.equals(Build.BOARD, ignoreCase = true) ||
               trimmed.equals(Build.HARDWARE, ignoreCase = true) ||
               trimmed.equals(Build.MANUFACTURER, ignoreCase = true) ||
               trimmed.equals(Build.BRAND, ignoreCase = true) ||
               trimmed.startsWith(Build.MODEL, ignoreCase = true) ||
               trimmed.startsWith(Build.DEVICE, ignoreCase = true)
    }

    private fun isInternalOrNumericId(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.all { it.isDigit() || it == '-' || it == '_' || it == '.' } ||
               trimmed.startsWith("0x", ignoreCase = true) ||
               trimmed.startsWith("audio_device", ignoreCase = true) ||
               trimmed.startsWith("device_", ignoreCase = true) ||
               (trimmed.length >= 6 && trimmed.take(5).all { it.isDigit() }) // e.g. Xiaomi 22101316I
    }

    private fun isGenericDeviceName(name: String): Boolean {
        return when (name.trim().lowercase()) {
            "speaker", "speakers", "built-in speaker", "built-in", "earpiece", "phone", "this phone",
            "wired_headphones", "wired_headset", "headset", "headphones", "headphone", "wired headset",
            "bluetooth", "bt", "a2dp", "sco", "ble", "usb", "usb_audio", "usb_device", "usb-audio",
            "audio_device", "unknown", "null", "device", "line out", "line_out" -> true
            else -> false
        }
    }
}
