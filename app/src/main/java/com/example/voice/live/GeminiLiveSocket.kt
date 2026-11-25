/*
 * Derived from the JARVIS project (com.jarvis.assistant.network.GeminiLiveWebSocket, OkHttp usage).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Rewritten for NEXA AI: key sent in a header by default (query fallback), no response/URL logging,
 * generation-tagged callbacks so late events from a dead socket are ignored. Modifications © ARPON.
 */
package com.example.voice.live

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** Thin OkHttp wrapper. It never logs and never exposes the request URL or key. */
class GeminiLiveSocket(private val http: OkHttpClient = sharedClient) {

    interface Callbacks {
        fun onOpen(generation: Int, socket: WebSocket)
        fun onMessage(generation: Int, text: String)
        fun onClosed(generation: Int, code: Int, reason: String)
        /** [httpCode] is 0 when there was no HTTP response; [bodySnippet] is for classification only. */
        fun onFailure(generation: Int, httpCode: Int, bodySnippet: String, errorClass: String)
    }

    companion object {
        /** Dedicated client: no logging interceptor, no read timeout (long-lived stream), 30 s ping to detect dead links. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun open(generation: Int, apiKey: String, queryAuth: Boolean, callbacks: Callbacks): WebSocket {
        val base = LiveConstants.WS_URL.replaceFirst("wss://", "https://").toHttpUrl()
        val builder = Request.Builder()
        if (queryAuth) {
            builder.url(base.newBuilder().addQueryParameter("key", apiKey).build())
        } else {
            builder.url(base).header(LiveConstants.API_KEY_HEADER, apiKey)
        }
        return http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) =
                callbacks.onOpen(generation, webSocket)

            override fun onMessage(webSocket: WebSocket, text: String) =
                callbacks.onMessage(generation, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                callbacks.onMessage(generation, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                callbacks.onClosed(generation, code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val body = try { response?.peekBody(2048)?.string().orEmpty() } catch (_: Exception) { "" }
                response?.close()
                callbacks.onFailure(generation, code, body, t.javaClass.simpleName)
            }
        })
    }
}
