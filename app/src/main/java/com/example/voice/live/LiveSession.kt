/*
 * © ARPON — original NEXA AI code (runtime owner of the Gemini Live connection).
 */
package com.example.voice.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Owns one Gemini Live conversation: socket, reconnect/backoff (via [LiveSessionStateMachine]),
 * session resumption, network monitoring. All state transitions run on a single coroutine, so the
 * state machine needs no locks. Audio/text sends are thread-safe and non-blocking.
 *
 * Security: the API key is read through [apiKeyProvider] at connect time only (from SecureKeyStore),
 * is never stored in a field of this class and is never logged.
 */
class LiveSession(
    context: Context,
    private val scope: CoroutineScope,
    private val apiKeyProvider: () -> String,
    private val configProvider: () -> LiveSetupConfig,
    private val listener: Listener,
    private val socketFactory: GeminiLiveSocket = GeminiLiveSocket(),
    private val machine: LiveSessionStateMachine = LiveSessionStateMachine()
) {
    interface Listener {
        fun onStateChanged(state: LiveState)
        /** Called on the socket reader thread, in arrival order. Must return quickly. */
        fun onServerEvent(event: LiveServerEvent)
    }

    companion object {
        private const val TAG = "LiveSession"
        /** Drop outgoing audio instead of queueing without bound when the uplink stalls. */
        private const val MAX_SEND_QUEUE_BYTES = 256L * 1024L
    }

    private data class Internal(val generation: Int, val event: LiveEvent)

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val inbox = Channel<Internal>(Channel.UNLIMITED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var generation = 0
    @Volatile private var resumeHandle: String? = null
    private var loopJob: Job? = null
    private var timerJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val state: LiveState get() = machine.state
    fun isReady(): Boolean = ready && socket != null

    // ---- lifecycle ------------------------------------------------------------------------

    /** Starts a NEW conversation (any previous resumption handle is discarded). */
    fun start() {
        if (loopJob?.isActive == true) return
        resumeHandle = null
        loopJob = scope.launch {
            for (msg in inbox) {
                if (msg.generation != Int.MIN_VALUE && msg.generation != generation) continue // stale socket
                process(msg.event)
            }
        }
        registerNetworkCallback()
        val hasKey = apiKeyProvider().isNotBlank()
        inbox.trySend(Internal(Int.MIN_VALUE, LiveEvent.Start(hasKey)))
        if (hasKey && !isOnline()) inbox.trySend(Internal(Int.MIN_VALUE, LiveEvent.NetworkLost))
    }

    /** Idempotent. After this the instance must not be reused. */
    fun stop() {
        unregisterNetworkCallback()
        inbox.trySend(Internal(Int.MIN_VALUE, LiveEvent.Stop))
        inbox.close() // loop drains Stop, then ends
        ready = false
    }

    // ---- sending --------------------------------------------------------------------------

    fun sendAudio(pcm16k: ByteArray): Boolean {
        val ws = socket ?: return false
        if (!ready || pcm16k.isEmpty()) return false
        if (ws.queueSize() > MAX_SEND_QUEUE_BYTES) return false // uplink stalled: drop, never block the mic thread
        return ws.send(LiveProtocol.buildAudioInput(pcm16k))
    }

    fun sendAudioStreamEnd(): Boolean = ready && socket?.send(LiveProtocol.buildAudioStreamEnd()) == true
    fun sendText(text: String): Boolean = ready && text.isNotBlank() && socket?.send(LiveProtocol.buildClientText(text)) == true
    fun sendToolResponse(id: String, name: String, result: JSONObject): Boolean =
        ready && socket?.send(LiveProtocol.buildToolResponse(id, name, result)) == true

    // ---- state machine driver (single coroutine) ------------------------------------------

    private fun process(event: LiveEvent) {
        val before = machine.state
        val effects = machine.reduce(event)
        for (effect in effects) execute(effect)
        val after = machine.state
        if (after != before) {
            ready = after == LiveState.Live
            listener.onStateChanged(after)
        }
    }

    private fun execute(effect: LiveEffect) {
        when (effect) {
            is LiveEffect.OpenSocket -> openSocket(effect.queryAuth)
            LiveEffect.CloseSocket -> closeSocket()
            is LiveEffect.ScheduleReconnect -> {
                timerJob?.cancel()
                timerJob = scope.launch {
                    delay(effect.delayMs)
                    inbox.trySend(Internal(Int.MIN_VALUE, LiveEvent.ReconnectTimerFired))
                }
            }
            LiveEffect.CancelTimers -> { timerJob?.cancel(); timerJob = null }
        }
    }

    private fun openSocket(queryAuth: Boolean) {
        closeSocket()
        val gen = ++generation
        val key = apiKeyProvider()
        if (key.isBlank()) {
            inbox.trySend(Internal(gen, LiveEvent.SocketEnded(FailureKind.AUTH_INVALID)))
            return
        }
        try {
            socket = socketFactory.open(gen, key, queryAuth, object : GeminiLiveSocket.Callbacks {
                override fun onOpen(generation: Int, socket: WebSocket) {
                    if (generation != this@LiveSession.generation) return
                    socket.send(LiveProtocol.buildSetup(configProvider(), resumeHandle))
                    inbox.trySend(Internal(generation, LiveEvent.SocketOpened))
                }

                override fun onMessage(generation: Int, text: String) {
                    if (generation != this@LiveSession.generation) return
                    handleServerText(generation, text)
                }

                override fun onClosed(generation: Int, code: Int, reason: String) {
                    if (generation != this@LiveSession.generation) return
                    ready = false
                    inbox.trySend(Internal(generation, LiveEvent.SocketEnded(LiveProtocol.classifyFailure(0, code, reason))))
                }

                override fun onFailure(generation: Int, httpCode: Int, bodySnippet: String, errorClass: String) {
                    if (generation != this@LiveSession.generation) return
                    ready = false
                    // Only the exception CLASS name is logged: messages and URLs can contain the key.
                    Log.w(TAG, "Socket failure: http=$httpCode type=$errorClass")
                    inbox.trySend(Internal(generation, LiveEvent.SocketEnded(LiveProtocol.classifyFailure(httpCode, 0, bodySnippet))))
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Socket open failed: ${e.javaClass.simpleName}")
            inbox.trySend(Internal(gen, LiveEvent.SocketEnded(FailureKind.TRANSIENT)))
        }
    }

    private fun closeSocket() {
        ready = false
        generation++ // anything still in flight from the old socket is now stale
        val s = socket
        socket = null
        runCatching { s?.close(1000, "client") }
    }

    private fun handleServerText(gen: Int, text: String) {
        for (event in LiveProtocol.parseServerMessage(text)) {
            when (event) {
                LiveServerEvent.SetupComplete -> inbox.trySend(Internal(gen, LiveEvent.SetupComplete))
                is LiveServerEvent.GoAway -> inbox.trySend(Internal(gen, LiveEvent.GoAway))
                is LiveServerEvent.ResumptionHandle -> {
                    if (event.resumable && event.handle.isNotEmpty()) resumeHandle = event.handle
                }
                is LiveServerEvent.ServerError -> {
                    val kind = LiveProtocol.classifyFailure(0, 0, "${event.status} ${event.message}")
                    if (kind != FailureKind.TRANSIENT) inbox.trySend(Internal(gen, LiveEvent.SocketEnded(kind)))
                }
                else -> Unit
            }
            listener.onServerEvent(event)
        }
    }

    // ---- network --------------------------------------------------------------------------

    private fun isOnline(): Boolean {
        val net = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                inbox.trySend(Internal(Int.MIN_VALUE, LiveEvent.NetworkRestored))
            }
            override fun onLost(network: Network) {
                // onLost fires per network; only treat it as "offline" if nothing else is usable.
                if (!isOnline()) inbox.trySend(Internal(Int.MIN_VALUE, LiveEvent.NetworkLost))
            }
        }
        networkCallback = cb
        runCatching { connectivity.registerDefaultNetworkCallback(cb) }
            .onFailure { networkCallback = null; Log.w(TAG, "Network callback unavailable: ${it.javaClass.simpleName}") }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }
}
