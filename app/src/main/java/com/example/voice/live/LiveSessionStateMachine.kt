/*
 * © ARPON — original NEXA AI code. Replaces the ad-hoc reconnect logic of the JARVIS
 * GeminiLiveWebSocket (fixed 500 ms retry loop, no cap) with a pure, unit-testable state machine.
 */
package com.example.voice.live

import kotlin.math.min
import kotlin.random.Random

enum class LiveFailure { API_KEY_MISSING, API_KEY_INVALID, QUOTA, MODEL_REJECTED, MAX_RETRIES }

sealed interface LiveState {
    data object Idle : LiveState
    data object Connecting : LiveState
    data object SettingUp : LiveState
    data object Live : LiveState
    data class Reconnecting(val attempt: Int, val delayMs: Long) : LiveState
    data object WaitingForNetwork : LiveState
    data class Failed(val reason: LiveFailure) : LiveState
    data object Stopped : LiveState
}

sealed interface LiveEvent {
    data class Start(val hasApiKey: Boolean) : LiveEvent
    data object Stop : LiveEvent
    data object SocketOpened : LiveEvent
    data object SetupComplete : LiveEvent
    data class SocketEnded(val kind: FailureKind) : LiveEvent
    data object GoAway : LiveEvent
    data object ReconnectTimerFired : LiveEvent
    data object NetworkLost : LiveEvent
    data object NetworkRestored : LiveEvent
}

/** Side effects the owner (LiveSession) must perform after a transition. */
sealed interface LiveEffect {
    /** Open a socket; [queryAuth] = send the key as `?key=` instead of the `x-goog-api-key` header. */
    data class OpenSocket(val queryAuth: Boolean) : LiveEffect
    data object CloseSocket : LiveEffect
    data class ScheduleReconnect(val delayMs: Long) : LiveEffect
    data object CancelTimers : LiveEffect
}

data class BackoffPolicy(
    val baseMs: Long = 1_000L,
    val maxMs: Long = 30_000L,
    val quotaMinMs: Long = 5_000L,
    val maxAttempts: Int = 8,
    /** 0..1: fraction of the delay that is randomised downward. */
    val jitter: Double = 0.25
) {
    fun delayFor(attempt: Int, quota: Boolean, random: Random): Long {
        val exp = baseMs shl min(attempt - 1, 10).coerceAtLeast(0)
        var d = min(exp, maxMs)
        if (quota) d = maxOf(d, quotaMinMs)
        val j = (d * jitter * random.nextDouble()).toLong()
        return (d - j).coerceAtLeast(0L)
    }
}

/**
 * Pure transition table for the Gemini Live connection. No threads, no Android types, no clock:
 * feed events with [reduce], execute the returned effects, expose [state].
 *
 * Policy summary:
 *  - header auth first; one automatic retry with query-param auth if the handshake is rejected as AUTH;
 *  - transient failures → exponential backoff with jitter, capped at [BackoffPolicy.maxAttempts], then Failed(MAX_RETRIES);
 *  - QUOTA → backoff with a 5 s floor; invalid key / rejected model are fatal (never retried blindly);
 *  - goAway → planned reconnect without consuming retry budget;
 *  - network lost → pause (no retries burned), network restored → reconnect immediately.
 */
class LiveSessionStateMachine(
    private val policy: BackoffPolicy = BackoffPolicy(),
    private val random: Random = Random.Default
) {
    @Volatile
    var state: LiveState = LiveState.Idle
        private set

    private var attempts = 0
    private var queryAuth = false
    private var queryAuthTried = false
    private var wasLiveThisConnection = false

    fun reduce(event: LiveEvent): List<LiveEffect> {
        // Stopped is terminal until a new Start.
        if (state == LiveState.Stopped && event !is LiveEvent.Start) return emptyList()

        return when (event) {
            is LiveEvent.Start -> onStart(event)
            LiveEvent.Stop -> {
                state = LiveState.Stopped
                listOf(LiveEffect.CancelTimers, LiveEffect.CloseSocket)
            }
            LiveEvent.SocketOpened -> {
                if (state == LiveState.Connecting) state = LiveState.SettingUp
                emptyList()
            }
            LiveEvent.SetupComplete -> {
                if (state == LiveState.SettingUp || state == LiveState.Connecting) {
                    state = LiveState.Live
                    wasLiveThisConnection = true
                    attempts = 0
                }
                emptyList()
            }
            is LiveEvent.SocketEnded -> onSocketEnded(event.kind)
            LiveEvent.GoAway -> {
                if (state == LiveState.Live) {
                    state = LiveState.Reconnecting(attempt = 0, delayMs = 0L)
                    wasLiveThisConnection = false
                    listOf(LiveEffect.CloseSocket, LiveEffect.ScheduleReconnect(0L))
                } else emptyList()
            }
            LiveEvent.ReconnectTimerFired -> {
                if (state is LiveState.Reconnecting) {
                    state = LiveState.Connecting
                    wasLiveThisConnection = false
                    listOf(LiveEffect.OpenSocket(queryAuth))
                } else emptyList()
            }
            LiveEvent.NetworkLost -> {
                when (state) {
                    LiveState.Connecting, LiveState.SettingUp, LiveState.Live, is LiveState.Reconnecting -> {
                        state = LiveState.WaitingForNetwork
                        wasLiveThisConnection = false
                        listOf(LiveEffect.CancelTimers, LiveEffect.CloseSocket)
                    }
                    else -> emptyList()
                }
            }
            LiveEvent.NetworkRestored -> {
                if (state == LiveState.WaitingForNetwork) {
                    state = LiveState.Connecting
                    attempts = 0
                    listOf(LiveEffect.OpenSocket(queryAuth))
                } else emptyList()
            }
        }
    }

    private fun onStart(e: LiveEvent.Start): List<LiveEffect> {
        if (state == LiveState.Connecting || state == LiveState.SettingUp || state == LiveState.Live) return emptyList()
        attempts = 0
        queryAuth = false
        queryAuthTried = false
        wasLiveThisConnection = false
        if (!e.hasApiKey) {
            state = LiveState.Failed(LiveFailure.API_KEY_MISSING)
            return emptyList()
        }
        state = LiveState.Connecting
        return listOf(LiveEffect.OpenSocket(queryAuth = false))
    }

    private fun onSocketEnded(kind: FailureKind): List<LiveEffect> {
        when (state) {
            LiveState.Idle, LiveState.WaitingForNetwork, is LiveState.Failed, LiveState.Stopped -> return emptyList()
            is LiveState.Reconnecting -> return emptyList() // a planned close we initiated; timer already scheduled
            else -> Unit
        }
        // A session that was healthy before it dropped does not count against the retry budget.
        if (wasLiveThisConnection) attempts = 0
        wasLiveThisConnection = false

        return when (kind) {
            FailureKind.AUTH_INVALID -> {
                if (!queryAuthTried) {
                    queryAuthTried = true
                    queryAuth = true
                    state = LiveState.Connecting
                    listOf(LiveEffect.OpenSocket(queryAuth = true))
                } else {
                    state = LiveState.Failed(LiveFailure.API_KEY_INVALID)
                    listOf(LiveEffect.CancelTimers, LiveEffect.CloseSocket)
                }
            }
            FailureKind.MODEL_REJECTED -> {
                state = LiveState.Failed(LiveFailure.MODEL_REJECTED)
                listOf(LiveEffect.CancelTimers)
            }
            FailureKind.QUOTA -> scheduleRetry(quota = true)
            FailureKind.TRANSIENT -> scheduleRetry(quota = false)
        }
    }

    private fun scheduleRetry(quota: Boolean): List<LiveEffect> {
        attempts++
        if (attempts > policy.maxAttempts) {
            state = LiveState.Failed(if (quota) LiveFailure.QUOTA else LiveFailure.MAX_RETRIES)
            return listOf(LiveEffect.CancelTimers)
        }
        val delay = policy.delayFor(attempts, quota, random)
        state = LiveState.Reconnecting(attempts, delay)
        return listOf(LiveEffect.ScheduleReconnect(delay))
    }
}
