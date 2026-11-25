package com.example

import android.media.AudioDeviceInfo
import com.example.audio.AudioRoute
import com.example.audio.AudioRouteManager
import com.example.voice.live.VoiceMode
import com.example.voice.owner.VoskDownloadConfig
import com.example.voice.owner.ZipSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceEnginePlumbingTest {

    @Test fun route_bluetoothBeatsWiredBeatsDevice() {
        assertEquals(AudioRoute.DEVICE, AudioRouteManager.chooseRoute(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        assertEquals(AudioRoute.WIRED, AudioRouteManager.chooseRoute(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        assertEquals(AudioRoute.WIRED, AudioRouteManager.chooseRoute(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertEquals(AudioRoute.BLUETOOTH, AudioRouteManager.chooseRoute(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(AudioRoute.DEVICE, AudioRouteManager.chooseRoute(emptyList()))
    }

    @Test fun ownsCommunicationMode_isFalseByDefault() = assertFalse(AudioRouteManager.ownsCommunicationMode)

    @Test fun voiceMode_unknownValuesFallBackToLegacy() {
        assertEquals(VoiceMode.LEGACY, VoiceMode.fromName(null)); assertEquals(VoiceMode.LEGACY, VoiceMode.fromName("???"))
        assertEquals(VoiceMode.GEMINI_LIVE, VoiceMode.fromName("GEMINI_LIVE"))
    }

    @Test fun downloadIsOffUntilMaintainerConfiguresVerifiedHashes() {
        assertNull(VoskDownloadConfig.EN_US); assertNull(VoskDownloadConfig.SPEAKER)
    }

    @Test fun zipSlip_isBlocked() {
        val dest = File(System.getProperty("java.io.tmpdir"), "zipslip-test").apply { mkdirs() }
        val ok = ZipSafety.resolve(dest, "am/final.mdl")
        assertEquals(File(dest, "am/final.mdl").canonicalPath, ok.canonicalPath)
        try { ZipSafety.resolve(dest, "../evil.txt"); fail("expected block") } catch (_: IOException) { }
        try { ZipSafety.resolve(dest, "a/../../evil.txt"); fail("expected block") } catch (_: IOException) { }
    }
}
