/*
 * Derived from the JARVIS project (WhatsAppAssistant.makeCall / deleteLastMessage and the "armed" WhatsApp handlers of
 * JarvisAccessibilityService). Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Rewritten for NEXA AI as AppRecipe implementations on the single AssistantAccessibilityService:
 *  - every UI step is verified on a fresh snapshot before the next one (JARVIS fired blind, staged clicks),
 *  - delete never falls back to another option or clicks arbitrary "delete"/"everyone" text,
 *  - phone numbers use ContactAndPhoneHelper.toWhatsAppNumber (JARVIS hard-coded a "+91" prefix),
 *  - every step honours the kill switch / cancellation and the user's app access policy.
 * Modifications © ARPON.
 */
package com.example.automation.whatsapp

import android.content.Intent
import android.net.Uri
import com.example.accessibility.AppRecipe
import com.example.accessibility.RecipeExecutionContext
import com.example.accessibility.RecipeResult
import com.example.accessibility.ScreenSecurityGuard
import com.example.accessibility.WhatsAppSelectorsConfig
import com.example.automation.agent.ScreenSignatureCalculator
import com.example.automation.core.AutomationSelectors
import com.example.automation.core.AwaitResult
import com.example.automation.core.UiMatchers
import com.example.automation.core.UiRecipeKit
import com.example.automation.agent.SerializedActionExecutor
import com.example.tools.ContactAndPhoneHelper
import com.example.tools.ToolResult
import org.json.JSONObject

private const val NO_A11Y = "Accessibility চালু নেই। সেটিংস → Accessibility থেকে স্ক্রিন অ্যাসিস্ট্যান্ট চালু করুন।"

private fun fail(msg: String, fallback: Boolean = false) = RecipeResult(ToolResult(false, msg), fallbackToUiLoop = fallback)

/** Opens the chat for [waNumber] in WhatsApp (no prefilled text). Returns the launched package or null. */
private fun openChat(ctx: RecipeExecutionContext, waNumber: String, pkg: String): Boolean {
    val uri = Uri.parse("https://wa.me/$waNumber")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(pkg); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return runCatching { ctx.appContext.startActivity(intent); true }.getOrDefault(false)
}

class WhatsAppCallRecipe : AppRecipe {
    override val id = "whatsapp_call"

    override suspend fun execute(context: RecipeExecutionContext, args: JSONObject): RecipeResult {
        SerializedActionExecutor.startNewTask()
        val video = args.optBoolean("video", false)
        val kind = if (video) "ভিডিও" else "ভয়েস"
        val contact = args.optString("contact_name", "").trim()
        val waNumber = ContactAndPhoneHelper.toWhatsAppNumber(args.optString("phone_number", "").trim())
        if (waNumber.isBlank()) return fail("WhatsApp কলের জন্য বৈধ ফোন নম্বর পাওয়া যায়নি।")

        val pkg = UiRecipeKit.installedPackage(context.appContext.packageManager, WhatsAppSelectorsConfig.WHATSAPP_PACKAGES)
            ?: return fail("এই ডিভাইসে WhatsApp ইনস্টল করা নেই।")
        ScreenSecurityGuard.checkAppAccess(pkg, context.accessPolicy).let { if (!it.allowed) return fail(it.reasonBengali) }
        val a11y = context.serviceProvider() ?: return fail(NO_A11Y)

        if (!openChat(context, waNumber, pkg)) return fail("WhatsApp চ্যাট খোলা যায়নি।")

        val idKey = if (video) "whatsapp_video_call_ids" else "whatsapp_voice_call_ids"
        val descKey = if (video) "whatsapp_video_call_descs" else "whatsapp_voice_call_descs"
        fun findCallButton(els: List<com.example.accessibility.SnapshotNodeRef>) =
            UiMatchers.byViewIds(els, AutomationSelectors.get(idKey)).firstOrNull()
                ?: UiMatchers.findByLabel(els, AutomationSelectors.get(descKey), exact = true, requireClickable = true)

        val chat = when (val r = UiRecipeKit.awaitScreen(a11y, context.accessPolicy, WhatsAppSelectorsConfig.WHATSAPP_PACKAGES) { findCallButton(it.elements) != null }) {
            is AwaitResult.Found -> r.snapshot
            is AwaitResult.Blocked -> return fail(r.reason)
            is AwaitResult.Aborted -> return fail(r.reason)
            is AwaitResult.NotFound -> return fail("WhatsApp-এ ${kind} কলের বাটন পাওয়া যায়নি (UI বদলেছে বা নম্বরে WhatsApp নেই)।", fallback = true)
        }
        val button = findCallButton(chat.elements)!!
        if (!UiRecipeKit.click(a11y, context.accessPolicy, button)) return fail("কলের বাটনে ক্লিক করা যায়নি।", fallback = true)

        // Optional confirmation dialog: only buttons that are plainly the call confirmation, only in WhatsApp.
        a11y.awaitScreenChange(timeoutMs = 1500L, debounceMs = 250L)
        val after = UiRecipeKit.read(a11y, context.accessPolicy)
        if (after is com.example.accessibility.SnapshotBuildResult.Success && after.snapshot.packageName in WhatsAppSelectorsConfig.WHATSAPP_PACKAGES) {
            val els = after.snapshot.elements
            val confirm = UiMatchers.byViewIds(els, AutomationSelectors.get("whatsapp_call_confirm_ids")).firstOrNull { it.clickable }
                ?: UiMatchers.findByLabel(els, AutomationSelectors.get("whatsapp_call_confirm_texts"), exact = true, requireClickable = true)
            if (confirm != null && confirm.id != button.id) UiRecipeKit.click(a11y, context.accessPolicy, confirm)
        }
        val who = if (contact.isNotBlank()) contact else waNumber
        return RecipeResult(
            ToolResult(true, "$who-কে WhatsApp ${kind} কল শুরু করার চেষ্টা করা হয়েছে (কল সংযুক্ত হয়েছে কিনা যাচাই করা যায়নি)।",
                JSONObject().put("whatsapp_number", waNumber).put("video", video).put("recipe", id))
        )
    }
}

class WhatsAppDeleteRecipe : AppRecipe {
    override val id = "whatsapp_delete_message"

    override suspend fun execute(context: RecipeExecutionContext, args: JSONObject): RecipeResult {
        SerializedActionExecutor.startNewTask()
        val forEveryone = args.optBoolean("for_everyone", false)
        val contact = args.optString("contact_name", "").trim()
        val waNumber = ContactAndPhoneHelper.toWhatsAppNumber(args.optString("phone_number", "").trim())
        if (waNumber.isBlank()) return fail("চ্যাট খুলতে বৈধ ফোন নম্বর পাওয়া যায়নি; মেসেজ মোছা হয়নি।")

        val pkg = UiRecipeKit.installedPackage(context.appContext.packageManager, WhatsAppSelectorsConfig.WHATSAPP_PACKAGES)
            ?: return fail("এই ডিভাইসে WhatsApp ইনস্টল করা নেই।")
        ScreenSecurityGuard.checkAppAccess(pkg, context.accessPolicy).let { if (!it.allowed) return fail(it.reasonBengali) }
        val a11y = context.serviceProvider() ?: return fail(NO_A11Y)
        if (!openChat(context, waNumber, pkg)) return fail("WhatsApp চ্যাট খোলা যায়নি।")

        val bubbleIds = AutomationSelectors.get("whatsapp_message_text_ids")
        val chat = when (val r = UiRecipeKit.awaitScreen(a11y, context.accessPolicy, WhatsAppSelectorsConfig.WHATSAPP_PACKAGES) {
            UiMatchers.lastOutgoingBubble(it.elements, bubbleIds) != null
        }) {
            is AwaitResult.Found -> r.snapshot
            is AwaitResult.Blocked -> return fail(r.reason)
            is AwaitResult.Aborted -> return fail(r.reason)
            is AwaitResult.NotFound -> return fail("চ্যাটে আপনার পাঠানো কোনো মেসেজ খুঁজে পাওয়া যায়নি; কিছু মোছা হয়নি।", fallback = true)
        }

        // Irreversible action: the open chat must be the requested one (when we know the name).
        if (contact.isNotBlank() && !contact.all { it.isDigit() || it == '+' || it == ' ' }) {
            val title = UiMatchers.byViewIds(chat.elements, AutomationSelectors.get("whatsapp_chat_title_ids")).firstOrNull()?.text.orEmpty()
            if (title.isBlank() || !UiMatchers.titleMatchesContact(title, contact)) {
                return fail("খোলা চ্যাটটি '$contact'-এর বলে নিশ্চিত হওয়া যায়নি; নিরাপত্তার জন্য কিছু মোছা হয়নি।", fallback = true)
            }
        }

        val bubble = UiMatchers.lastOutgoingBubble(chat.elements, bubbleIds)!!
        val bubbleText = bubble.text
        val preSig = ScreenSignatureCalculator.compute(chat)

        if (!UiRecipeKit.longPress(a11y, bubble)) return fail("মেসেজটি সিলেক্ট করা যায়নি; কিছু মোছা হয়নি।", fallback = true)

        // Step 2: the selection action bar must show a delete icon.
        val selected = when (val r = UiRecipeKit.awaitScreen(a11y, context.accessPolicy, WhatsAppSelectorsConfig.WHATSAPP_PACKAGES, attempts = 4) { snap ->
            deleteIcon(snap.elements) != null
        }) {
            is AwaitResult.Found -> r.snapshot
            is AwaitResult.Blocked -> return fail(r.reason)
            is AwaitResult.Aborted -> { UiRecipeKit.back(a11y); return fail(r.reason) }
            is AwaitResult.NotFound -> { UiRecipeKit.back(a11y); return fail("মেসেজ সিলেক্ট হলেও ডিলিট আইকন পাওয়া যায়নি; কিছু মোছা হয়নি।", fallback = true) }
        }
        if (!UiRecipeKit.click(a11y, context.accessPolicy, deleteIcon(selected.elements)!!)) {
            UiRecipeKit.back(a11y); return fail("ডিলিট আইকনে ক্লিক করা যায়নি; কিছু মোছা হয়নি।", fallback = true)
        }

        // Step 3: choose EXACTLY the requested option. No fallback to the other option, ever.
        val optionLabels = AutomationSelectors.get(if (forEveryone) "whatsapp_delete_for_everyone_texts" else "whatsapp_delete_for_me_texts")
        val dialog = when (val r = UiRecipeKit.awaitScreen(a11y, context.accessPolicy, WhatsAppSelectorsConfig.WHATSAPP_PACKAGES, attempts = 4) { snap ->
            UiMatchers.findByLabel(snap.elements, optionLabels, exact = true, requireClickable = true) != null
        }) {
            is AwaitResult.Found -> r.snapshot
            is AwaitResult.Blocked -> return fail(r.reason)
            is AwaitResult.Aborted -> { UiRecipeKit.back(a11y); return fail(r.reason) }
            is AwaitResult.NotFound -> {
                UiRecipeKit.back(a11y); UiRecipeKit.back(a11y)
                val why = if (forEveryone) "'সবার জন্য মুছুন' বিকল্প আসেনি (মেসেজ পুরোনো বা সময়সীমা পেরিয়ে গেছে)" else "'আমার জন্য মুছুন' বিকল্প আসেনি"
                return fail("$why; তাই কিছু মোছা হয়নি।")
            }
        }
        val option = UiMatchers.findByLabel(dialog.elements, optionLabels, exact = true, requireClickable = true)!!
        if (!UiRecipeKit.click(a11y, context.accessPolicy, option)) {
            UiRecipeKit.back(a11y); return fail("মোছার বিকল্পে ক্লিক করা যায়নি; কিছু মোছা হয়নি।", fallback = true)
        }

        // Step 4: evidence. The bubble should be gone or replaced by a "deleted" marker.
        a11y.awaitScreenChange(timeoutMs = 1800L, debounceMs = 300L)
        val verified = when (val r = UiRecipeKit.read(a11y, context.accessPolicy)) {
            is com.example.accessibility.SnapshotBuildResult.Success -> {
                val still = r.snapshot.elements.any { it.viewId in bubbleIds && bubbleText.isNotBlank() && it.text == bubbleText }
                !still || !preSig.isEquivalentTo(ScreenSignatureCalculator.compute(r.snapshot))
            }
            else -> false
        }
        val scope = if (forEveryone) "সবার জন্য" else "শুধু আপনার জন্য"
        return RecipeResult(
            ToolResult(true,
                if (verified) "সর্বশেষ পাঠানো মেসেজটি $scope মোছা হয়েছে।" else "মেসেজ $scope মোছার ধাপ শেষ হয়েছে, তবে মোছা হয়েছে কিনা স্ক্রিনে নিশ্চিত হওয়া যায়নি।",
                JSONObject().put("for_everyone", forEveryone).put("verified", verified).put("recipe", id))
        )
    }

    private fun deleteIcon(els: List<com.example.accessibility.SnapshotNodeRef>) =
        UiMatchers.byViewIds(els, AutomationSelectors.get("whatsapp_delete_ids")).firstOrNull()
            ?: UiMatchers.findByLabel(els, AutomationSelectors.get("whatsapp_delete_descs"), exact = true, requireClickable = true)
}
