package com.example

import com.example.voice.live.BackoffPolicy
import com.example.voice.live.FailureKind
import com.example.voice.live.LiveEffect
import com.example.voice.live.LiveEvent
import com.example.voice.live.LiveFailure
import com.example.voice.live.LiveSessionStateMachine
import com.example.voice.live.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure JVM tests of the Gemini Live connection state machine. */
class LiveSessionStateMachineTest {

    // jitter 0 → deterministic delays
    private fun machine(maxAttempts: Int = 3) =
        LiveSessionStateMachine(BackoffPolicy(baseMs = 1000, maxMs = 8000, quotaMinMs = 5000, maxAttempts = maxAttempts, jitter = 0.0), Random(1))

    private fun LiveSessionStateMachine.goLive() {
        reduce(LiveEvent.Start(true)); reduce(LiveEvent.SocketOpened); reduce(LiveEvent.SetupComplete)
        assertEquals(LiveState.Live, state)
    }

    @Test fun start_withoutKey_failsWithoutOpeningSocket() {
        val m = machine()
        val fx = m.reduce(LiveEvent.Start(false))
        assertEquals(LiveState.Failed(LiveFailure.API_KEY_MISSING), m.state)
        assertTrue(fx.isEmpty())
    }

    @Test fun happyPath_idle_connecting_settingUp_live() {
        val m = machine()
        assertEquals(listOf<LiveEffect>(LiveEffect.OpenSocket(false)), m.reduce(LiveEvent.Start(true)))
        assertEquals(LiveState.Connecting, m.state)
        m.reduce(LiveEvent.SocketOpened); assertEquals(LiveState.SettingUp, m.state)
        m.reduce(LiveEvent.SetupComplete); assertEquals(LiveState.Live, m.state)
    }

    @Test fun duplicateStart_whileRunning_isIgnored() {
        val m = machine(); m.goLive()
        assertTrue(m.reduce(LiveEvent.Start(true)).isEmpty())
        assertEquals(LiveState.Live, m.state)
    }

    @Test fun transientFailure_backsOffExponentially_thenReconnects() {
        val m = machine(maxAttempts = 5)
        m.reduce(LiveEvent.Start(true))
        var fx = m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT))
        assertEquals(LiveState.Reconnecting(1, 1000), m.state)
        assertEquals(listOf<LiveEffect>(LiveEffect.ScheduleReconnect(1000)), fx)
        fx = m.reduce(LiveEvent.ReconnectTimerFired)
        assertEquals(listOf<LiveEffect>(LiveEffect.OpenSocket(false)), fx)
        m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)); assertEquals(LiveState.Reconnecting(2, 2000), m.state)
        m.reduce(LiveEvent.ReconnectTimerFired)
        m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)); assertEquals(LiveState.Reconnecting(3, 4000), m.state)
        m.reduce(LiveEvent.ReconnectTimerFired)
        m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)); assertEquals(LiveState.Reconnecting(4, 8000), m.state) // capped
        m.reduce(LiveEvent.ReconnectTimerFired)
        m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)); assertEquals(LiveState.Reconnecting(5, 8000), m.state)
    }

    @Test fun retryBudgetExhausted_failsWithMaxRetries() {
        val m = machine(maxAttempts = 2)
        m.reduce(LiveEvent.Start(true))
        repeat(2) { m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)); m.reduce(LiveEvent.ReconnectTimerFired) }
        m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT))
        assertEquals(LiveState.Failed(LiveFailure.MAX_RETRIES), m.state)
    }

    @Test fun healthySessionThatDrops_resetsRetryBudget() {
        val m = machine(maxAttempts = 2)
        repeat(3) {
            m.goLiveFromAnywhere()
            m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT))
            assertEquals(LiveState.Reconnecting(1, 1000), m.state) // always attempt 1: budget was reset by the live period
        }
    }

    private fun LiveSessionStateMachine.goLiveFromAnywhere() {
        when (state) {
            LiveState.Idle, LiveState.Stopped, is LiveState.Failed -> reduce(LiveEvent.Start(true))
            is LiveState.Reconnecting -> reduce(LiveEvent.ReconnectTimerFired)
            else -> Unit
        }
        reduce(LiveEvent.SocketOpened); reduce(LiveEvent.SetupComplete)
        assertEquals(LiveState.Live, state)
    }

    @Test fun authRejected_triesQueryAuthOnce_thenFailsInvalidKey() {
        val m = machine()
        m.reduce(LiveEvent.Start(true))
        val fx = m.reduce(LiveEvent.SocketEnded(FailureKind.AUTH_INVALID))
        assertEquals(listOf<LiveEffect>(LiveEffect.OpenSocket(true)), fx)
        assertEquals(LiveState.Connecting, m.state)
        m.reduce(LiveEvent.SocketEnded(FailureKind.AUTH_INVALID))
        assertEquals(LiveState.Failed(LiveFailure.API_KEY_INVALID), m.state)
    }

    @Test fun modelRejected_isFatal_noRetry() {
        val m = machine(); m.reduce(LiveEvent.Start(true))
        val fx = m.reduce(LiveEvent.SocketEnded(FailureKind.MODEL_REJECTED))
        assertEquals(LiveState.Failed(LiveFailure.MODEL_REJECTED), m.state)
        assertTrue(fx.none { it is LiveEffect.ScheduleReconnect })
    }

    @Test fun quota_usesFiveSecondFloor() {
        val m = machine(); m.reduce(LiveEvent.Start(true))
        m.reduce(LiveEvent.SocketEnded(FailureKind.QUOTA))
        assertEquals(LiveState.Reconnecting(1, 5000), m.state)
    }

    @Test fun goAway_reconnectsImmediately_withoutConsumingBudget() {
        val m = machine(maxAttempts = 1); m.goLive()
        val fx = m.reduce(LiveEvent.GoAway)
        assertEquals(listOf(LiveEffect.CloseSocket, LiveEffect.ScheduleReconnect(0)), fx)
        // the close we initiated must not be counted as a failure
        assertTrue(m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)).isEmpty())
        assertEquals(LiveState.Reconnecting(0, 0), m.state)
        assertEquals(listOf<LiveEffect>(LiveEffect.OpenSocket(false)), m.reduce(LiveEvent.ReconnectTimerFired))
    }

    @Test fun networkLoss_pausesWithoutBurningRetries_andRestoreReconnects() {
        val m = machine(maxAttempts = 1); m.goLive()
        val fx = m.reduce(LiveEvent.NetworkLost)
        assertEquals(LiveState.WaitingForNetwork, m.state)
        assertTrue(LiveEffect.CloseSocket in fx && LiveEffect.CancelTimers in fx)
        assertTrue(m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)).isEmpty()) // late close ignored
        assertEquals(listOf<LiveEffect>(LiveEffect.OpenSocket(false)), m.reduce(LiveEvent.NetworkRestored))
        assertEquals(LiveState.Connecting, m.state)
    }

    @Test fun networkRestored_whenNotWaiting_isIgnored() {
        val m = machine(); m.goLive()
        assertTrue(m.reduce(LiveEvent.NetworkRestored).isEmpty())
        assertEquals(LiveState.Live, m.state)
    }

    @Test fun stop_isTerminalUntilNewStart() {
        val m = machine(); m.goLive()
        val fx = m.reduce(LiveEvent.Stop)
        assertEquals(LiveState.Stopped, m.state)
        assertTrue(LiveEffect.CloseSocket in fx)
        assertTrue(m.reduce(LiveEvent.SocketEnded(FailureKind.TRANSIENT)).isEmpty())
        assertTrue(m.reduce(LiveEvent.ReconnectTimerFired).isEmpty())
        assertTrue(m.reduce(LiveEvent.NetworkRestored).isEmpty())
        assertEquals(LiveState.Stopped, m.state)
        m.reduce(LiveEvent.Start(true)); assertEquals(LiveState.Connecting, m.state)
    }

    @Test fun backoffPolicy_jitterNeverExceedsDelay_andRespectsCap() {
        val p = BackoffPolicy(baseMs = 1000, maxMs = 30000, jitter = 0.25)
        val r = Random(7)
        for (attempt in 1..20) {
            val d = p.delayFor(attempt, false, r)
            assertTrue(d in 0..30000)
        }
        assertTrue(p.delayFor(20, false, Random(1)) >= 22500) // 30000 minus at most 25 %
    }
}
