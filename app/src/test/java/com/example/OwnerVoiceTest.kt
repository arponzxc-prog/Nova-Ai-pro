package com.example

import com.example.voice.owner.OwnerVoiceGate
import com.example.voice.owner.OwnerVoiceGate.Action
import com.example.voice.owner.OwnerVoiceGate.Phase
import com.example.voice.owner.OwnerVoiceMath
import com.example.voice.owner.OwnerVoiceProfile
import com.example.voice.owner.OwnerVoiceStrictness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwnerVoiceTest {

    private fun v(vararg x: Float) = floatArrayOf(*x)

    // ---- math ----

    @Test fun cosineDistance_identicalOrthogonalOpposite() {
        assertEquals(0f, OwnerVoiceMath.cosineDistance(v(1f, 2f, 3f), v(2f, 4f, 6f)), 1e-6f)
        assertEquals(1f, OwnerVoiceMath.cosineDistance(v(1f, 0f), v(0f, 1f)), 1e-6f)
        assertEquals(2f, OwnerVoiceMath.cosineDistance(v(1f, 0f), v(-1f, 0f)), 1e-6f)
    }

    @Test fun cosineDistance_degenerateInputsAreUnrelated() {
        assertEquals(1f, OwnerVoiceMath.cosineDistance(v(), v()), 0f)
        assertEquals(1f, OwnerVoiceMath.cosineDistance(v(1f), v(1f, 2f)), 0f)
        assertEquals(1f, OwnerVoiceMath.cosineDistance(v(0f, 0f), v(1f, 1f)), 0f)
    }

    @Test fun centroid_isUnitLength_andIgnoresMismatchedDimensionsCorrectly() {
        val c = OwnerVoiceMath.centroid(listOf(v(2f, 0f), v(0f, 2f), v(9f, 9f, 9f)))!!
        assertEquals(2, c.size)
        assertEquals(1.0, Math.sqrt((c[0] * c[0] + c[1] * c[1]).toDouble()), 1e-5)
        assertEquals(c[0], c[1], 1e-5f) // mean of the two usable vectors, NOT divided by 3
        assertNull(OwnerVoiceMath.centroid(emptyList()))
        assertNull(OwnerVoiceMath.centroid(listOf(v(0f, 0f), v(0f, 0f))))
    }

    @Test fun similarityPercent_isClamped() {
        assertEquals(100, OwnerVoiceMath.similarityPercent(0f)); assertEquals(0, OwnerVoiceMath.similarityPercent(2f))
    }

    // ---- decision rule ----

    private val owner = OwnerVoiceProfile(v(1f, 0f), listOf(v(1f, 0f), v(0.9f, 0.1f)))

    @Test fun verify_acceptsOwner_rejectsStranger_strictThreshold() {
        val th = OwnerVoiceStrictness.BALANCED.threshold
        assertTrue(OwnerVoiceMath.verify(v(0.95f, 0.05f), owner, th).isMatch)
        val stranger = OwnerVoiceMath.verify(v(0f, 1f), owner, th) // distance ≈ 1.0
        assertFalse(stranger.isMatch)
    }

    @Test fun verify_noSlackAboveThreshold_unlikeJarvisTimes1_35() {
        // distance 0.80 would have been accepted by "threshold × 1.35" (0.945) but must be rejected here.
        val th = 0.70f
        val cosSim = 1.0 - 0.80
        val x = cosSim.toFloat(); val y = Math.sqrt(1.0 - cosSim * cosSim).toFloat()
        val r = OwnerVoiceMath.verify(v(x, y), OwnerVoiceProfile(v(1f, 0f), emptyList()), th)
        assertEquals(0.80f, r.distance, 1e-3f)
        assertFalse(r.isMatch)
    }

    @Test fun verify_nullOrEmptyVector_isNeverAMatch() {
        assertFalse(OwnerVoiceMath.verify(null, owner, 0.9f).isMatch)
        val r = OwnerVoiceMath.verify(v(), owner, 0.9f)
        assertFalse(r.isMatch); assertFalse(r.hasVector)
    }

    @Test fun strictness_ordering_andParsing() {
        assertTrue(OwnerVoiceStrictness.STRICT.threshold < OwnerVoiceStrictness.BALANCED.threshold)
        assertTrue(OwnerVoiceStrictness.BALANCED.threshold < OwnerVoiceStrictness.RELAXED.threshold)
        assertEquals(OwnerVoiceStrictness.BALANCED, OwnerVoiceStrictness.fromName("garbage"))
        assertEquals(OwnerVoiceStrictness.STRICT, OwnerVoiceStrictness.fromName("strict"))
    }

    // ---- profile serialisation ----

    @Test fun profile_jsonRoundTrip_andRejectsCorruption() {
        val back = OwnerVoiceProfile.fromJson(owner.toJson())!!
        assertEquals(2, back.dimension); assertEquals(2, back.samples.size)
        assertEquals(0.9f, back.samples[1][0], 1e-6f)
        assertNull(OwnerVoiceProfile.fromJson("garbage"))
        assertNull(OwnerVoiceProfile.fromJson("""{"centroid":[],"samples":[]}"""))
        assertNull(OwnerVoiceProfile.fromJson("""{"centroid":[1,2],"samples":[[1,2,3]]}"""))
        assertNotNull(OwnerVoiceProfile.fromJson("""{"centroid":[1,2]}"""))
    }

    // ---- gate ----

    private val cfg = OwnerVoiceGate.Config(enforce = true, verifyAtVoicedMs = 200, shortUtteranceMinMs = 100, utteranceEndSilenceMs = 100, prerollMs = 40, maxBufferMs = 1000, passedIdleMs = 300)
    private fun chunk(tag: Int) = ByteArray(640) { tag.toByte() } // 20 ms
    private val loud = 0.5f
    private val quiet = 0.0f

    /** Feeds [n] chunks and returns the first non-None action (or None). */
    private fun OwnerVoiceGate.feed(n: Int, amp: Float, tag: Int = 1): Action {
        var last: Action = Action.None
        repeat(n) { val a = onFrame(chunk(tag), amp); if (a !is Action.None && last is Action.None) last = a }
        return last
    }

    @Test fun disabledGate_passesEverythingThrough() {
        val g = OwnerVoiceGate(OwnerVoiceGate.Config(enforce = false))
        assertTrue(g.onFrame(chunk(1), quiet) is Action.Forward)
        assertTrue(g.onFrame(ByteArray(0), quiet) is Action.None)
    }

    @Test fun nothingIsForwardedBeforeVerification() {
        val g = OwnerVoiceGate(cfg)
        var forwarded = 0
        repeat(12) { if (g.onFrame(chunk(2), loud) is Action.Forward) forwarded++ }
        assertEquals(0, forwarded)
        assertEquals(Phase.VERIFYING, g.phase)
    }

    @Test fun silenceAlone_neverStartsCollecting() {
        val g = OwnerVoiceGate(cfg)
        assertTrue(g.feed(100, quiet) is Action.None); assertEquals(Phase.IDLE, g.phase)
    }

    @Test fun verifyRequested_once_withPrerollIncluded() {
        val g = OwnerVoiceGate(cfg)
        g.feed(5, quiet, tag = 9)             // preroll material
        val a = g.feed(15, loud, tag = 1)
        assertTrue(a is Action.Verify)
        assertTrue((a as Action.Verify).pcm.size >= 640 * 3)
        assertEquals(Phase.VERIFYING, g.phase)
    }

    @Test fun passed_forwardsBufferedAudioThenLiveFrames() {
        val g = OwnerVoiceGate(cfg)
        g.feed(15, loud)
        val buffered = g.onVerified(true) as Action.Forward
        assertTrue(buffered.chunks.isNotEmpty()); assertEquals(Phase.PASSED, g.phase)
        assertTrue(g.onFrame(chunk(3), loud) is Action.Forward)
    }

    @Test fun rejected_dropsEverything_untilSilence_thenReturnsToIdle() {
        val g = OwnerVoiceGate(cfg)
        g.feed(15, loud)
        assertTrue(g.onVerified(false) is Action.None); assertEquals(Phase.REJECTED, g.phase)
        repeat(10) { assertTrue(g.onFrame(chunk(4), loud) is Action.None) } // stranger keeps talking: still blocked
        assertEquals(Phase.REJECTED, g.phase)
        g.feed(10, quiet)
        assertEquals(Phase.IDLE, g.phase)
    }

    @Test fun nextUtterance_mustBeVerifiedAgain_afterModelTurnComplete() {
        val g = OwnerVoiceGate(cfg)
        g.feed(15, loud); g.onVerified(true)
        g.onModelTurnComplete(); assertEquals(Phase.IDLE, g.phase)
        assertTrue(g.onFrame(chunk(5), loud) is Action.None) // no lease: audio is held again
    }

    @Test fun passedGate_closesAfterLongSilence() {
        val g = OwnerVoiceGate(cfg)
        g.feed(15, loud); g.onVerified(true)
        g.feed(20, quiet)
        assertEquals(Phase.IDLE, g.phase)
    }

    @Test fun shortUtterance_isVerifiedAtEndOfSpeech_tooShortIsDiscarded() {
        val g = OwnerVoiceGate(cfg.copy(verifyAtVoicedMs = 2000, shortUtteranceMinMs = 100))
        g.feed(4, loud)                      // 80 ms of speech... (start needs 60 ms)
        g.feed(1, loud)                      // 100 ms voiced
        assertTrue(g.feed(10, quiet) is Action.Verify)
        val g2 = OwnerVoiceGate(cfg.copy(verifyAtVoicedMs = 2000, shortUtteranceMinMs = 400))
        g2.feed(4, loud)
        assertTrue(g2.feed(10, quiet) is Action.None); assertEquals(Phase.IDLE, g2.phase)
    }

    @Test fun verdictInWrongPhase_isIgnored_andMutedMarkerIsDropped() {
        val g = OwnerVoiceGate(cfg)
        assertTrue(g.onVerified(true) is Action.None); assertEquals(Phase.IDLE, g.phase)
        assertTrue(g.onFrame(ByteArray(0), loud) is Action.None)
    }

    @Test fun reset_clearsEverything() {
        val g = OwnerVoiceGate(cfg); g.feed(15, loud); g.reset(); assertEquals(Phase.IDLE, g.phase)
        assertTrue(g.onVerified(true) is Action.None)
    }
}
