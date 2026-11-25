package com.example.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.example.tools.ContactAndPhoneHelper
import com.example.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * WhatsApp-এর সিলেক্টর ও প্যাকেজ কনফিগারেশন — UI আপডেট হলে শুধু এই কনফিগ বদলালেই হবে।
 */
object WhatsAppSelectorsConfig {
    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    val SEND_BUTTON_VIEW_IDS = listOf(
        "com.whatsapp:id/send",
        "com.whatsapp.w4b:id/send",
        "com.whatsapp:id/conversation_entry_action_button",
        "com.whatsapp.w4b:id/conversation_entry_action_button"
    )

    val SEND_BUTTON_CONTENT_DESCS = listOf(
        "send", "পাঠান", "পাঠাও", "send message", "মেসেজ পাঠান"
    )

    val SEND_BUTTON_TEXTS = listOf(
        "send", "পাঠান"
    )

    val MESSAGE_INPUT_VIEW_IDS = listOf(
        "com.whatsapp:id/entry",
        "com.whatsapp.w4b:id/entry"
    )
}

data class RecipeExecutionContext(
    val appContext: Context,
    val accessPolicy: AppAccessPolicy,
    val serviceProvider: () -> AssistantAccessibilityService? = { AssistantAccessibilityService.instance }
)

data class RecipeResult(
    val toolResult: ToolResult,
    val fallbackToUiLoop: Boolean = false
)

interface AppRecipe {
    val id: String
    suspend fun execute(context: RecipeExecutionContext, args: JSONObject): RecipeResult
}

/**
 * WhatsApp-এ সরাসরি মেসেজ পাঠানোর ধাপভিত্তিক রেসিপি:
 * ১) WhatsApp ইনস্টল ও Accessibility চালু আছে কিনা যাচাই
 * ২) wa.me ডিপ-লিংক দিয়ে চ্যাট খোলা ও মেসেজ বক্সে লেখা বসানো
 * ৩) ইভেন্ট-ভিত্তিক স্ক্রিন পরিবর্তনের জন্য অপেক্ষা (স্থির sleep ছাড়া)
 * ৪) WhatsAppSelectorsConfig থেকে Send বাটন খুঁজে ক্লিক ও মেসেজ গেছে কিনা যাচাই
 * ৫) UI বদলে গেলে বা না পেলে পরিষ্কার বার্তা ও সাধারণ UI লুপে fallback নির্দেশ
 */
class WhatsAppSendRecipe : AppRecipe {
    override val id: String = "whatsapp_send"

    override suspend fun execute(context: RecipeExecutionContext, args: JSONObject): RecipeResult {
        val rawNumber = args.optString("phone_number", "").trim()
        val contactName = args.optString("contact_name", "").trim()
        val message = args.optString("message", "").trim()

        if (message.isBlank()) {
            return RecipeResult(ToolResult(false, "পাঠানোর জন্য মেসেজের লেখা উল্লেখ করা হয়নি।"))
        }

        val waNumber = ContactAndPhoneHelper.toWhatsAppNumber(rawNumber)
        if (waNumber.isBlank()) {
            return RecipeResult(ToolResult(false, "WhatsApp মেসেজের জন্য বৈধ ফোন নম্বর পাওয়া যায়নি।"))
        }

        val pm = context.appContext.packageManager
        val installedPkg = WhatsAppSelectorsConfig.WHATSAPP_PACKAGES.firstOrNull { isInstalled(pm, it) }
            ?: return RecipeResult(ToolResult(false, "এই ডিভাইসে WhatsApp বা WhatsApp Business ইনস্টল করা নেই।"))

        // নিরাপত্তা নীতি (ব্লকলিস্ট/অ্যালাউলিস্ট) যাচাই
        val policyCheck = ScreenSecurityGuard.checkAppAccess(installedPkg, context.accessPolicy)
        if (!policyCheck.allowed) {
            return RecipeResult(ToolResult(false, policyCheck.reasonBengali))
        }

        val a11y = context.serviceProvider()
            ?: return RecipeResult(
                ToolResult(
                    false,
                    "Accessibility চালু নেই, তাই অটো-সেন্ড করা যাচ্ছে না। সেটিংস → Accessibility ও স্ক্রিন অ্যাসিস্ট্যান্ট থেকে চালু করুন।"
                )
            )

        val uri = Uri.parse("https://wa.me/$waNumber?text=${Uri.encode(message)}")
        val launchIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(installedPkg)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val launched = runCatching {
            context.appContext.startActivity(launchIntent)
            true
        }.getOrElse {
            // প্যাকেজ পিন কাজ না করলে সাধারণ ভিউ ইনটেন্ট চেষ্টা
            runCatching {
                context.appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrElse { false }
        }

        if (!launched) {
            return RecipeResult(ToolResult(false, "WhatsApp চ্যাট খোলা যায়নি।"))
        }

        // স্ক্রিন পরিবর্তন ও WhatsApp চ্যাট লোড হওয়ার জন্য ইভেন্ট-ভিত্তিক অপেক্ষা
        var snapshot: ScreenSnapshot? = null
        for (attempt in 0 until 5) {
            a11y.awaitScreenChange(timeoutMs = 1800L, debounceMs = 280L)
            val read = withContext(Dispatchers.Main) {
                a11y.readCurrentScreen(context.accessPolicy)
            }
            if (read is SnapshotBuildResult.Blocked) {
                return RecipeResult(ToolResult(false, read.reasonBengali))
            }
            if (read is SnapshotBuildResult.Success &&
                WhatsAppSelectorsConfig.WHATSAPP_PACKAGES.contains(read.snapshot.packageName)
            ) {
                snapshot = read.snapshot
                val sendBtn = findSendButton(read.snapshot)
                if (sendBtn != null) break
            }
        }

        val activeSnapshot = snapshot ?: return RecipeResult(
            toolResult = ToolResult(
                false,
                "WhatsApp চ্যাট স্ক্রিন পড়া যায়নি; অনুগ্রহ করে সাধারণ UI টুল দিয়ে স্ক্রিন দেখে চেষ্টা করুন।"
            ),
            fallbackToUiLoop = true
        )

        val sendNode = findSendButton(activeSnapshot)
        if (sendNode == null) {
            return RecipeResult(
                toolResult = ToolResult(
                    false,
                    "WhatsApp-এর Send বাটন খুঁজে পাওয়া যায়নি (সম্ভবত প্রাপক নম্বরে WhatsApp নেই বা UI বদলেছে)। ডায়ালগ/স্ক্রিন দেখে সাধারণ UI টুল দিয়ে চেষ্টা করুন।"
                ),
                fallbackToUiLoop = true
            )
        }

        val clicked = withContext(Dispatchers.Main) {
            a11y.clickElement(sendNode, context.accessPolicy)
        }
        if (!clicked) {
            return RecipeResult(
                toolResult = ToolResult(false, "WhatsApp-এর Send বাটনে ক্লিক করা যায়নি।"),
                fallbackToUiLoop = true
            )
        }

        // Send করার পর ইনপুট বক্স খালি হয়েছে কিনা সংক্ষেপে যাচাই
        a11y.awaitScreenChange(timeoutMs = 1500L, debounceMs = 250L)
        val afterRead = withContext(Dispatchers.Main) {
            a11y.readCurrentScreen(context.accessPolicy)
        }
        val recipientLabel = if (contactName.isNotBlank()) "$contactName ($waNumber)" else waNumber

        if (afterRead is SnapshotBuildResult.Success) {
            val entryStillHasDraft = afterRead.snapshot.elements.any { el ->
                el.editable &&
                    (el.viewId in WhatsAppSelectorsConfig.MESSAGE_INPUT_VIEW_IDS) &&
                    el.text.contains(message.take(20))
            }
            if (entryStillHasDraft) {
                return RecipeResult(
                    toolResult = ToolResult(
                        false,
                        "Send বাটনে ক্লিক করা হলেও মেসেজ বক্সে লেখা রয়ে গেছে। স্ক্রিন দেখে নিশ্চিত হোন।"
                    ),
                    fallbackToUiLoop = true
                )
            }
        }

        return RecipeResult(
            toolResult = ToolResult(
                success = true,
                message = "$recipientLabel-এ WhatsApp মেসেজ পাঠানো হয়েছে।",
                data = JSONObject()
                    .put("whatsapp_number", waNumber)
                    .put("contact_name", contactName)
                    .put("recipe", id)
            )
        )
    }

    internal fun findSendButton(snapshot: ScreenSnapshot): SnapshotNodeRef? {
        // ১) নির্দিষ্ট resource-id ম্যাচ
        snapshot.elements.firstOrNull { el ->
            el.viewId != null && el.viewId in WhatsAppSelectorsConfig.SEND_BUTTON_VIEW_IDS
        }?.let { return it }

        // ২) contentDescription বা টেক্সট ম্যাচ (ইংরেজি ও বাংলা)
        return snapshot.elements.firstOrNull { el ->
            val desc = el.contentDesc.trim().lowercase(Locale.ROOT)
            val txt = el.text.trim().lowercase(Locale.ROOT)
            val matchesDesc = WhatsAppSelectorsConfig.SEND_BUTTON_CONTENT_DESCS.any { it == desc }
            val matchesTxt = WhatsAppSelectorsConfig.SEND_BUTTON_TEXTS.any { it == txt }
            (matchesDesc || matchesTxt) && (el.clickable || el.type == "button")
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
