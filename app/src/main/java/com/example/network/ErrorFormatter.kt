package com.example.network

import android.content.Context
import com.example.model.ProviderType
import com.example.security.SecureKeyStore
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * HTTP API এরর রিপ্রেজেন্ট করার জন্য টাইপড এক্সেপশন।
 * Retry-After হেডার ও প্রোভাইডার তথ্য ধরে রাখে এবং কখনোই API Key প্রকাশ করে না।
 */
class ApiHttpException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfterSeconds: Long? = null,
    val providerType: ProviderType? = null
) : IOException(
    "API Error ($statusCode): ${SecureKeyStore.redactSecrets(responseBody.take(300))}"
)

data class ModelAttemptFailure(
    val modelId: String,
    val modelName: String,
    val providerType: ProviderType,
    val shortReason: String,
    val error: Throwable
)

/**
 * প্রাইমারি ও সব ব্যাকআপ মডেল ব্যর্থ হলে প্রতিটি মডেলের সংক্ষিপ্ত কারণসহ সম্মিলিত এক্সেপশন।
 */
class AllModelsFailedException(
    val attempts: List<ModelAttemptFailure>
) : IOException(
    attempts.joinToString("; ") { "${it.modelName}: ${it.shortReason}" }
)

/**
 * HTTP Retry-After হেডার (integer seconds অথবা HTTP-date) পার্স করার ইউটিলিটি।
 */
object RetryAfterParser {
    private val httpDateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy"
    )

    fun parse(headerValue: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = headerValue?.trim().orEmpty()
        if (raw.isEmpty()) return null

        raw.toLongOrNull()?.let { seconds ->
            return seconds.coerceIn(0L, 3600L)
        }

        for (pattern in httpDateFormats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val parsed = sdf.parse(raw) ?: continue
                val diffSec = ((parsed.time - nowMs) / 1000L).coerceAtLeast(1L)
                return diffSec.coerceAtMost(3600L)
            } catch (_: Exception) {
            }
        }
        return null
    }
}

/**
 * Chat, Voice ও Background ফ্লোর জন্য শেয়ার্ড এরর ফরম্যাটার।
 * সবসময় API Key / Token গোপন রাখে (redactSecrets)।
 */
object ErrorFormatter {

    fun shortReason(error: Throwable): String {
        val raw = when (error) {
            is PartialStreamException -> shortReason(error.cause ?: error)
            is ApiHttpException -> when (error.statusCode) {
                429 -> if (error.retryAfterSeconds != null) {
                    "রেট লিমিট/কোটা শেষ (${error.retryAfterSeconds}স)"
                } else {
                    "রেট লিমিট বা কোটা শেষ (429)"
                }
                402 -> "বিলিং বা ক্রেডিট শেষ (402)"
                401, 403 -> "API Key সঠিক নয় বা অনুমতি নেই (${error.statusCode})"
                404 -> "মডেল পাওয়া যায়নি বা পুরোনো (404)"
                408 -> "রিকোয়েস্ট টাইমআউট (408)"
                in 500..599 -> "সার্ভার সাময়িকভাবে ব্যস্ত (${error.statusCode})"
                else -> "HTTP ${error.statusCode}"
            }
            is UnknownHostException -> "ইন্টারনেট বা হোস্ট পাওয়া যায়নি"
            is SocketTimeoutException -> "নেটওয়ার্ক টাইমআউট"
            is IOException -> "নেটওয়ার্ক সংযোগ সমস্যা"
            else -> error.message?.take(80).orEmpty().ifBlank { "অজানা সমস্যা" }
        }
        return SecureKeyStore.redactSecrets(raw)
    }

    fun format(context: Context?, error: Throwable): String {
        val formatted = when (error) {
            is AllModelsFailedException -> {
                if (error.attempts.isEmpty()) {
                    "কোনো কার্যকরী AI মডেল পাওয়া যায়নি। মডেল ও API Key যাচাই করুন।"
                } else {
                    buildString {
                        append("সব নির্বাচিত মডেল ব্যর্থ হয়েছে:\n")
                        error.attempts.forEach { attempt ->
                            append("• ")
                            append(attempt.modelName)
                            append(": ")
                            append(attempt.shortReason)
                            append('\n')
                        }
                        append("অনুগ্রহ করে API Key বা নেটওয়ার্ক যাচাই করে আবার চেষ্টা করুন।")
                    }.trimEnd()
                }
            }
            is PartialStreamException -> {
                val inner = error.cause
                if (inner != null) {
                    "মাঝপথে সংযোগ বিচ্ছিন্ন হয়েছে (${shortReason(inner)})।"
                } else {
                    "নেটওয়ার্ক সংযোগ মাঝপথে কেটে গেছে।"
                }
            }
            is ApiHttpException -> when (error.statusCode) {
                401, 403 ->
                    "API Key সঠিক নয় বা অনুমতি নেই (HTTP ${error.statusCode})। মডেল সেটিংস থেকে Key যাচাই করুন।"
                402 ->
                    "আপনার অ্যাকাউন্টের ক্রেডিট বা বিলিং সীমা শেষ (HTTP 402)।"
                404 ->
                    "মডেলটি পাওয়া যায়নি বা ডিপ্রেখেটেড হতে পারে (HTTP 404)। মডেল নাম যাচাই করুন (যেমন: gemini-2.5-flash বা gpt-4o)।"
                408 ->
                    "সার্ভার থেকে উত্তর আসতে দেরি হয়েছে (HTTP 408)। আবার চেষ্টা করুন।"
                429 -> {
                    val waitHint = error.retryAfterSeconds?.let { " প্রায় $it সেকেন্ড পর" } ?: " একটু পর"
                    "রিকোয়েস্ট লিমিট বা ফ্রি কোটা শেষ (HTTP 429)।" + "${waitHint} আবার চেষ্টা করুন।"
                }
                in 500..599 ->
                    "AI প্রোভাইডার সার্ভার এখন ব্যস্ত বা অস্থায়ী সমস্যায় আছে (HTTP ${error.statusCode})। কিছুক্ষণ পর আবার চেষ্টা করুন।"
                400 -> {
                    val snippet = error.responseBody.take(140).trim()
                    if (snippet.isNotBlank()) "রিকোয়েস্ট ফরম্যাট ত্রুটি (400): $snippet"
                    else "রিকোয়েস্ট ফরম্যাট সঠিক নয় (HTTP 400)।"
                }
                else -> "API ত্রুটি (${error.statusCode}): ${error.responseBody.take(150)}"
            }
            is UnknownHostException ->
                "ইন্টারনেট সংযোগ নেই বা সার্ভার ঠিকানা পাওয়া যাচ্ছে না। নেটওয়ার্ক ও Base URL যাচাই করুন।"
            is SocketTimeoutException ->
                "সার্ভার থেকে সাড়া পেতে বেশি সময় লেগেছে (Timeout)। আবার চেষ্টা করুন।"
            is IOException ->
                "নেটওয়ার্ক সমস্যা হয়েছে: ${error.message ?: "সংযোগ ব্যর্থ"}"
            else -> error.message ?: "অজানা সমস্যা হয়েছে"
        }
        return SecureKeyStore.redactSecrets(formatted)
    }
}
