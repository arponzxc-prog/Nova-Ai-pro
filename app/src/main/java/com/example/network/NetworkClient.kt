package com.example.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * পুরো অ্যাপ জুড়ে একটিই শেয়ার্ড OkHttpClient সিঙ্গলটন:
 * - HTTP/2 এবং কানেকশন পুল রিইউজ করে (প্রতি রিকোয়েস্টে নতুন TCP/TLS হ্যান্ডশেক এড়ায়)
 * - সাধারণ ও streaming উভয় ক্লায়েন্ট একই ConnectionPool এবং Dispatcher শেয়ার করে
 */
object NetworkClient {
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 5,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Streaming (SSE)-এর জন্য দীর্ঘমেয়াদী readTimeout, কিন্তু একই কানেকশন পুল ও থ্রেড পুল ব্যবহার করে
    val streamingClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
