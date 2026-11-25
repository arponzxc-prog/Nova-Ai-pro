package com.example

import com.example.camerapill.CameraPillLogic
import com.example.camerapill.CameraPillMode
import com.example.camerapill.CameraPillSettings
import com.example.camerapill.CutoutPlacement
import com.example.camerapill.PillAvatarState
import com.example.camerapill.PillSourcePhase
import com.example.camerapill.PillVoiceSignal
import com.example.camerapill.PxRect
import com.example.camerapill.blinkOpenness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPillLogicTest {

    private fun sig(active: Boolean, phase: PillSourcePhase) = PillVoiceSignal(active = active, phase = phase)

    @Test
    fun avatarState_mapsEachPhase() {
        assertEquals(PillAvatarState.LISTENING, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.LISTENING), true, false))
        assertEquals(PillAvatarState.THINKING, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.THINKING), true, false))
        assertEquals(PillAvatarState.SPEAKING, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.SPEAKING), true, false))
        assertEquals(PillAvatarState.ERROR, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.ERROR), true, false))
        assertEquals(PillAvatarState.IDLE, CameraPillLogic.deriveAvatarState(sig(false, PillSourcePhase.IDLE), true, false))
    }

    @Test
    fun avatarState_interruptedAndOffline() {
        assertEquals(PillAvatarState.INTERRUPTED, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.LISTENING), true, true))
        assertEquals(PillAvatarState.OFFLINE, CameraPillLogic.deriveAvatarState(sig(false, PillSourcePhase.IDLE), false, false))
        assertEquals(PillAvatarState.OFFLINE, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.THINKING), false, false))
        // speaking continues to show speaking even if connectivity drops mid-reply
        assertEquals(PillAvatarState.SPEAKING, CameraPillLogic.deriveAvatarState(sig(true, PillSourcePhase.SPEAKING), false, false))
    }

    @Test
    fun shouldShow_onlyWhileTalking() {
        val s = CameraPillSettings(enabled = true, mode = CameraPillMode.ONLY_WHILE_TALKING)
        val talking = sig(true, PillSourcePhase.SPEAKING)
        val idle = sig(true, PillSourcePhase.IDLE)
        assertTrue(CameraPillLogic.shouldShow(s, true, talking, false, false, false))
        assertFalse(CameraPillLogic.shouldShow(s, true, idle, true, false, false))
        assertTrue("held during idle delay", CameraPillLogic.shouldShow(s, true, idle, true, true, false))
    }

    @Test
    fun shouldShow_alwaysOn() {
        val s = CameraPillSettings(enabled = true, mode = CameraPillMode.ALWAYS_ON)
        assertTrue(CameraPillLogic.shouldShow(s, true, sig(false, PillSourcePhase.IDLE), true, false, false))
        assertFalse(CameraPillLogic.shouldShow(s, true, sig(false, PillSourcePhase.IDLE), false, false, false))
    }

    @Test
    fun shouldShow_requiresEnabledAndPermission_previewNeedsPermission() {
        val on = CameraPillSettings(enabled = true, mode = CameraPillMode.ALWAYS_ON)
        val talking = sig(true, PillSourcePhase.LISTENING)
        assertFalse("no overlay permission", CameraPillLogic.shouldShow(on, false, talking, true, false, false))
        assertFalse("disabled", CameraPillLogic.shouldShow(on.copy(enabled = false), true, talking, true, false, false))
        assertTrue("preview ignores enabled flag", CameraPillLogic.shouldShow(on.copy(enabled = false), true, sig(false, PillSourcePhase.IDLE), false, false, true))
        assertFalse("preview still needs permission", CameraPillLogic.shouldShow(on, false, sig(false, PillSourcePhase.IDLE), false, false, true))
    }

    @Test
    fun clampSettings_enforcesRanges() {
        val c = CameraPillLogic.clampSettings(CameraPillSettings(avatarSize = 9f, animationIntensity = -1f, idleHideDelayMs = 1L))
        assertEquals(CameraPillSettings.SIZE_MAX, c.avatarSize, 0f)
        assertEquals(CameraPillSettings.INTENSITY_MIN, c.animationIntensity, 0f)
        assertEquals(CameraPillSettings.IDLE_DELAY_MIN_MS, c.idleHideDelayMs)
    }

    @Test
    fun blink_isOpenMostOfTheTimeAndClosesBriefly() {
        assertEquals(1f, blinkOpenness(1.0f, true), 0f)
        assertTrue(blinkOpenness(0.08f, true) < 0.2f)
        assertEquals(1f, blinkOpenness(0.08f, false), 0f)
    }

    // ---- cutout placement (1080x2400 portrait, density 3) ----
    private fun place(cutouts: List<PxRect>, w: Int = 1080, h: Int = 2400) = CutoutPlacement.compute(
        screenWidth = w, screenHeight = h, cutouts = cutouts, statusBarTop = 100,
        collapsedWidth = 354, collapsedHeight = 90, windowWidth = 700, windowHeight = 220, gapPx = 18, minTopPx = 12
    )

    @Test
    fun placement_centrePunchHole_isCentredOnCutout() {
        val p = place(listOf(PxRect(510, 0, 570, 80)))
        assertTrue(p.anchoredToCutout)
        assertEquals(540, p.x + 350 + p.contentOffsetX)   // pill centre == cutout centre
    }

    @Test
    fun placement_cornerPunchHole_clampsWindowButKeepsPillOnCutout() {
        val p = place(listOf(PxRect(900, 0, 960, 80)))
        assertTrue(p.x >= 0 && p.x + 700 <= 1080)
        assertEquals(930, p.x + 350 + p.contentOffsetX)
    }

    @Test
    fun placement_noCutout_fallsBackToTopCentre() {
        val p = place(emptyList())
        assertFalse(p.anchoredToCutout)
        assertEquals(540, p.x + 350 + p.contentOffsetX)
        assertTrue(p.y >= 12)
    }

    @Test
    fun placement_landscapeLeftCutout_sitsBesideCamera() {
        val p = place(listOf(PxRect(0, 1000, 80, 1060)), w = 2400, h = 1080)
        assertTrue(p.anchoredToCutout)
        val centre = p.x + 350 + p.contentOffsetX
        assertEquals(80 + 18 + 177, centre)
    }

    @Test
    fun placement_neverOffScreenVertically() {
        val p = place(listOf(PxRect(510, 0, 570, 40)))
        assertTrue(p.y >= 0)
    }
}
