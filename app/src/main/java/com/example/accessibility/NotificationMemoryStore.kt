package com.example.accessibility

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampMs: Long
)

/**
 * সাম্প্রতিক নোটিফিকেশন কেবল মেমোরিতে (RAM) রাখে (সর্বোচ্চ ৫০টি, ২ ঘণ্টা মেয়াদ)।
 * কখনো ডিস্ক বা ডেটাবেসে সেভ করে না এবং OTP/ব্যাংকিং নোটিফিকেশন ডিফল্টে বাদ দেয়।
 */
object NotificationMemoryStore {

    const val MAX_ITEMS = 50
    const val TTL_MS = 2 * 60 * 60 * 1000L // ২ ঘণ্টা পর অটো-মুছে যাবে

    private val buffer = ArrayDeque<CapturedNotification>()

    private val OTP_NOTIF_KEYWORDS = listOf(
        "otp", "one-time", "one time password", "verification code", "verify code",
        "security code", "cvv", "pin is", "pin:", "bkash pin", "nagad pin",
        "ওটিপি", "ভেরিফিকেশন কোড", "গোপন পিন", "পিন নম্বর"
    )

    @Synchronized
    fun addNotification(
        item: CapturedNotification,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        pruneExpiredLocked(nowMs)
        if (isSensitiveNotification(item.packageName, item.title, item.text)) {
            return false
        }

        // একই নোটিফিকেশন আপডেট হলে পুরনোটি সরিয়ে নতুনটি সামনে যোগ
        buffer.removeAll { it.key == item.key }
        buffer.addFirst(
            item.copy(
                title = ScreenSecurityGuard.maskSensitivePatterns(item.title, aggressiveOtpMask = true),
                text = ScreenSecurityGuard.maskSensitivePatterns(item.text, aggressiveOtpMask = true)
            )
        )
        while (buffer.size > MAX_ITEMS) {
            buffer.removeLast()
        }
        return true
    }

    @Synchronized
    fun getRecentNotifications(
        allowedPackages: Set<String> = emptySet(),
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis()
    ): List<CapturedNotification> {
        pruneExpiredLocked(nowMs)
        return buffer
            .asSequence()
            .filter { item ->
                !isSensitiveNotification(item.packageName, item.title, item.text) &&
                    (allowedPackages.isEmpty() || allowedPackages.any { it.equals(item.packageName, ignoreCase = true) })
            }
            .take(limit.coerceIn(1, MAX_ITEMS))
            .toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    fun isSensitiveNotification(packageName: String, title: String, text: String): Boolean {
        val access = ScreenSecurityGuard.checkAppAccess(packageName, AppAccessPolicy())
        if (!access.allowed) return true
        val combined = "$title $text".lowercase(Locale.ROOT)
        return OTP_NOTIF_KEYWORDS.any { combined.contains(it) }
    }

    fun formatForAi(items: List<CapturedNotification>): String {
        if (items.isEmpty()) return "সাম্প্রতিক কোনো সাধারণ নোটিফিকেশন নেই।"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return items.joinToString("\n") { n ->
            val timeStr = fmt.format(Date(n.timestampMs))
            "[$timeStr] ${n.appLabel} (${n.packageName}): ${n.title} — ${n.text}"
        }
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        while (buffer.isNotEmpty() && nowMs - buffer.last().timestampMs > TTL_MS) {
            buffer.removeLast()
        }
    }
}
