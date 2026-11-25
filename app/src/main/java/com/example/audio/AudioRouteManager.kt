/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.HeadsetAudioRouter).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: snapshot/restore of the device audio state instead of forcing speakerphone,
 * Bluetooth-only communication mode, AudioDeviceCallback only (no broadcast receiver), and a flag so
 * AssistantService does not mistake our own MODE_IN_COMMUNICATION for a phone call. Modifications © ARPON.
 */
package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRoute { DEVICE, WIRED, BLUETOOTH }

/**
 * Watches headset connect/disconnect while a live voice session runs and, only when a Bluetooth
 * headset is present, switches to communication mode so its microphone and speaker are used.
 *
 * Wired/USB headsets are routed by the platform automatically, so nothing is forced for them.
 * With no headset the previous audio state is restored — the speakerphone is never forced on.
 */
class AudioRouteManager(
    context: Context,
    private val onRouteChanged: (AudioRoute) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioRouteManager"

        /** True while this app itself holds MODE_IN_COMMUNICATION (so it is not an incoming/outgoing call). */
        @Volatile
        var ownsCommunicationMode: Boolean = false
            private set

        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

        /** Pure policy: Bluetooth wins over wired, wired over the built-in devices. */
        fun chooseRoute(deviceTypes: Collection<Int>): AudioRoute = when {
            deviceTypes.any { it in BLUETOOTH_TYPES } -> AudioRoute.BLUETOOTH
            deviceTypes.any { it in WIRED_TYPES } -> AudioRoute.WIRED
            else -> AudioRoute.DEVICE
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _route = MutableStateFlow(AudioRoute.DEVICE)
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private var started = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false
    private var scoStarted = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun isHeadsetConnected(): Boolean = _route.value != AudioRoute.DEVICE

    /** Must be called on the main thread. */
    fun start() {
        if (started) return
        started = true
        savedMode = audioManager.mode
        @Suppress("DEPRECATION")
        savedSpeakerphone = audioManager.isSpeakerphoneOn
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refresh()
    }

    /** Must be called on the main thread. Restores the audio state captured in [start]. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        releaseCommunicationRoute()
        _route.value = AudioRoute.DEVICE
    }

    private fun refresh() {
        if (!started) return
        val types = currentOutputTypes()
        val route = chooseRoute(types)
        if (route == AudioRoute.BLUETOOTH) {
            if (!engageBluetooth()) {
                // Bluetooth routing unavailable (permission/policy): behave like a wired/device route.
                applyRoute(AudioRoute.DEVICE)
                return
            }
        } else {
            releaseCommunicationRoute()
        }
        applyRoute(route)
    }

    private fun applyRoute(route: AudioRoute) {
        if (_route.value != route) {
            _route.value = route
            Log.i(TAG, "Audio route -> $route")
            onRouteChanged(route)
        }
    }

    private fun currentOutputTypes(): List<Int> =
        runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }
            .getOrDefault(emptyList())

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun engageBluetooth(): Boolean {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted; Bluetooth routing skipped")
            return false
        }
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsCommunicationMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                target != null && audioManager.setCommunicationDevice(target)
            } else {
                startLegacySco()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth routing denied: ${e.javaClass.simpleName}")
            releaseCommunicationRoute()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth routing failed: ${e.javaClass.simpleName}")
            releaseCommunicationRoute()
            false
        }
    }

    /** Pre-Android-12 path: classic Bluetooth SCO. */
    @Suppress("DEPRECATION")
    private fun startLegacySco(): Boolean {
        if (audioManager.isBluetoothScoAvailableOffCall && !scoStarted) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoStarted = true
        }
        return true
    }

    private fun releaseCommunicationRoute() {
        if (!ownsCommunicationMode && !scoStarted) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        }
        if (scoStarted) {
            @Suppress("DEPRECATION")
            runCatching { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false }
            scoStarted = false
        }
        runCatching { audioManager.mode = savedMode }
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = savedSpeakerphone }
        ownsCommunicationMode = false
    }
}
