package com.example.tools

import androidx.annotation.StringRes
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

sealed interface ConfirmationOutcome {
    data class Confirmed(val editedSmsMessage: String? = null) : ConfirmationOutcome
    data class Cancelled(val reason: String) : ConfirmationOutcome
}

data class PendingActionConfirmation(
    val id: String,
    val toolName: String,
    val bengaliTitle: String,
    val riskLevel: RiskLevel,
    val recipientName: String,
    val phoneNumber: String,
    val smsMessage: String,
    val isSms: Boolean,
    val summaryText: String,
    val targetAppName: String = "",
    val targetButtonLabel: String = "",
    val actionContextDetails: String = "",
    val remainingSeconds: Int = 60,
    val isVoiceListeningForConfirm: Boolean = false,
    val deferred: CompletableDeferred<ConfirmationOutcome>
)

data class PendingContactSelection(
    val query: String,
    val matches: List<ContactMatch>,
    val deferred: CompletableDeferred<ContactMatch?>
)

data class PendingPermissionRequest(
    val permissions: List<String>,
    val requiresWriteSettings: Boolean,
    @StringRes val explanationRes: Int,
    val deferred: CompletableDeferred<Boolean>
)

/**
 * ভয়েস মোডে কনফার্মেশনের উত্তর (হ্যাঁ / না) কঠোরভাবে পার্স করে।
 * অস্পষ্ট কোনো উত্তরকে কখনোই 'হ্যাঁ' ধরে নেয় না — নিরাপদে বাতিল ধরে।
 */
object VoiceConfirmationParser {
    private val YES_KEYWORDS = setOf(
        "হ্যাঁ", "হ্যা", "হুম", "জি", "জ্বি", "করো", "করুন", "ঠিক আছে", "পাঠাও", "পাঠান",
        "কল করো", "নিশ্চিত", "ওকে", "yes", "yeah", "yep", "ok", "okay", "confirm", "send", "call", "do it"
    )

    private val NO_KEYWORDS = setOf(
        "না", "নাহ", "থাক", "বাতিল", "ক্যানসেল", "করো না", "পাঠাবে না", "থামো",
        "no", "nope", "cancel", "stop", "don't", "dont", "abort"
    )

    fun parseYesNo(spokenText: String): Boolean {
        val norm = spokenText.trim().lowercase()
        if (norm.isBlank()) return false

        // আগে না-সূচক শব্দ পরীক্ষা (নিরাপত্তার স্বার্থে অগ্রাধিকার)
        if (NO_KEYWORDS.any { norm == it || norm.contains(it) }) {
            return false
        }
        return YES_KEYWORDS.any { norm == it || norm.split(" ").contains(it) || norm.contains(it) }
    }
}
