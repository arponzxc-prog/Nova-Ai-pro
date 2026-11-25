/*
 * Derived from the JARVIS project (YouTubeController / YouTubeAutomationManager and the YouTube handlers of
 * JarvisAccessibilityService). Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Rewritten for NEXA AI on the single AssistantAccessibilityService: intents first (no UI automation needed when an
 * API key resolves the video), selector lists from AutomationSelectors, verification after each UI step.
 * Modifications © ARPON.
 */
package com.example.automation.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.AiAssistantApplication
import com.example.accessibility.AssistantAccessibilityService
import com.example.accessibility.SnapshotBuildResult
import com.example.automation.agent.ActionVerifier
import com.example.automation.agent.SerializedActionExecutor
import com.example.automation.command.CommandParser
import com.example.automation.core.AutomationSelectors
import com.example.automation.core.AwaitResult
import com.example.automation.core.UiMatchers
import com.example.automation.core.UiRecipeKit
import com.example.tools.ToolExecutionContext
import com.example.tools.ToolResult
import org.json.JSONObject

object YouTubeAutomation {

    private fun youtubePackages() = AutomationSelectors.get("youtube_packages").toSet()

    private fun launch(ctx: Context, uri: Uri, preferApp: Boolean): Boolean {
        val pm = ctx.packageManager
        val ytPkg = if (preferApp) UiRecipeKit.installedPackage(pm, youtubePackages()) else null
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (ytPkg != null) setPackage(ytPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(intent); true }.getOrElse {
            // app refused the pinned intent: let the default handler (browser) try
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
    }

    suspend fun search(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return ToolResult(false, "কী সার্চ করতে হবে বলুন।")
        val ok = launch(ctx.appContext, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"), preferApp = true)
        return if (ok) ToolResult(true, "YouTube-এ '$query' সার্চ রেজাল্ট খোলা হয়েছে।") else ToolResult(false, "YouTube খোলা যায়নি।")
    }

    suspend fun play(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        if (CommandParser.isAmbiguousPlayQuery(query)) {
            return ToolResult(false, "কোন ভিডিও চালাতে চান তা স্পষ্ট নয়। ইউজারকে ভিডিওর নাম জিজ্ঞেস করুন।")
        }
        val repo = (ctx.appContext.applicationContext as? AiAssistantApplication)?.repository
        val key = repo?.getYoutubeApiKey().orEmpty()

        if (key.isNotBlank()) {
            when (val r = YouTubeApiClient().firstVideo(query, key)) {
                is YouTubeLookup.Found -> {
                    val v = r.video
                    val opened = launch(ctx.appContext, Uri.parse("https://www.youtube.com/watch?v=${v.id}"), preferApp = true)
                    return if (opened) ToolResult(true, "'${v.title}' (${v.channel}) YouTube-এ খোলা হয়েছে।", JSONObject().put("video_id", v.id))
                    else ToolResult(false, "YouTube ভিডিও খোলা যায়নি।")
                }
                YouTubeLookup.KeyRejected -> Unit // fall through to search + UI path, and tell the user
                YouTubeLookup.QuotaExceeded, YouTubeLookup.NetworkError, YouTubeLookup.NoResult -> Unit
            }
        }
        return playViaSearchUi(ctx, query, keyNote = if (key.isBlank()) "" else " (YouTube API সার্চ কাজ করেনি)")
    }

    private suspend fun playViaSearchUi(ctx: ToolExecutionContext, query: String, keyNote: String): ToolResult {
        SerializedActionExecutor.startNewTask()
        val searched = launch(ctx.appContext, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"), preferApp = true)
        if (!searched) return ToolResult(false, "YouTube খোলা যায়নি।")
        val a11y = AssistantAccessibilityService.instance
            ?: return ToolResult(true, "'$query'-এর সার্চ রেজাল্ট খোলা হয়েছে; Accessibility বন্ধ থাকায় ভিডিওটি নিজে বেছে নিতে হবে।$keyNote")
        val descs = AutomationSelectors.get("youtube_result_descs")
        val screen = UiRecipeKit.awaitScreen(a11y, ctx.accessPolicy, youtubePackages()) { snap ->
            UiMatchers.findByLabel(snap.elements, descs, exact = false, requireClickable = true) != null
        }
        val snap = when (screen) {
            is AwaitResult.Found -> screen.snapshot
            is AwaitResult.Blocked -> return ToolResult(false, screen.reason)
            is AwaitResult.Aborted -> return ToolResult(false, screen.reason)
            is AwaitResult.NotFound -> return ToolResult(true, "'$query'-এর সার্চ রেজাল্ট খোলা হয়েছে, কিন্তু প্রথম ভিডিও নিজে বেছে নেওয়া যায়নি; আপনি ট্যাপ করুন।$keyNote")
        }
        val first = UiMatchers.findByLabel(snap.elements, descs, exact = false, requireClickable = true)!!
        val clicked = UiRecipeKit.click(a11y, ctx.accessPolicy, first)
        return if (clicked) ToolResult(true, "'$query'-এর প্রথম ভিডিও চালানোর চেষ্টা করা হয়েছে (চলছে কিনা যাচাই করা যায়নি)।$keyNote")
        else ToolResult(true, "'$query'-এর সার্চ রেজাল্ট খোলা হয়েছে; ভিডিওতে ক্লিক করা যায়নি।$keyNote")
    }

    // ---- UI actions on the already-open YouTube app ------------------------------------------------

    suspend fun subscribe(ctx: ToolExecutionContext, @Suppress("UNUSED_PARAMETER") args: JSONObject): ToolResult {
        SerializedActionExecutor.startNewTask()
        val a11y = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility চালু নেই, তাই সাবস্ক্রাইব করা যাচ্ছে না।")
        val snap = when (val r = UiRecipeKit.awaitScreen(a11y, ctx.accessPolicy, youtubePackages(), attempts = 2)) {
            is AwaitResult.Found -> r.snapshot
            is AwaitResult.Blocked -> return ToolResult(false, r.reason)
            is AwaitResult.Aborted -> return ToolResult(false, r.reason)
            is AwaitResult.NotFound -> return ToolResult(false, "YouTube স্ক্রিনে নেই। আগে চ্যানেল বা ভিডিও খুলুন।")
        }
        val subscribed = AutomationSelectors.get("youtube_subscribed_texts")
        if (ActionVerifier.screenShowsAny(snap, subscribed) && UiMatchers.findByLabel(snap.elements, AutomationSelectors.get("youtube_subscribe_texts"), exact = true, exclude = subscribed) == null) {
            return ToolResult(true, "চ্যানেলটি ইতিমধ্যে সাবস্ক্রাইব করা আছে।")
        }
        // "subscribe" must be the whole label: never "subscribed", "unsubscribe" or a longer sentence.
        val btn = UiMatchers.findByLabel(snap.elements, AutomationSelectors.get("youtube_subscribe_texts"), exact = true, exclude = listOf("unsubscribe") + subscribed, requireClickable = true)
            ?: return ToolResult(false, "সাবস্ক্রাইব বাটন খুঁজে পাওয়া যায়নি (UI বদলেছে বা এটি চ্যানেল/ভিডিও পেজ নয়)।")
        if (!UiRecipeKit.click(a11y, ctx.accessPolicy, btn)) return ToolResult(false, "সাবস্ক্রাইব বাটনে ক্লিক করা যায়নি।")
        a11y.awaitScreenChange(timeoutMs = 1800L, debounceMs = 300L)
        val after = (UiRecipeKit.read(a11y, ctx.accessPolicy) as? SnapshotBuildResult.Success)?.snapshot
        return if (ActionVerifier.screenShowsAny(after, subscribed)) ToolResult(true, "চ্যানেল সাবস্ক্রাইব করা হয়েছে।")
        else ToolResult(true, "সাবস্ক্রাইব বাটনে ক্লিক করা হয়েছে, তবে সাবস্ক্রাইব হয়েছে কিনা স্ক্রিনে নিশ্চিত হওয়া যায়নি।")
    }

    suspend fun fullscreen(ctx: ToolExecutionContext, @Suppress("UNUSED_PARAMETER") args: JSONObject): ToolResult {
        SerializedActionExecutor.startNewTask()
        val a11y = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility চালু নেই, তাই ফুলস্ক্রিন করা যাচ্ছে না।")
        val snap = when (val r = UiRecipeKit.awaitScreen(a11y, ctx.accessPolicy, youtubePackages(), attempts = 2)) {
            is AwaitResult.Found -> r.snapshot
            is AwaitResult.Blocked -> return ToolResult(false, r.reason)
            is AwaitResult.Aborted -> return ToolResult(false, r.reason)
            is AwaitResult.NotFound -> return ToolResult(false, "YouTube ভিডিও স্ক্রিনে নেই।")
        }
        val btn = UiMatchers.findByLabel(snap.elements, AutomationSelectors.get("youtube_fullscreen_descs"), exact = false, requireClickable = true)
            ?: return ToolResult(false, "ফুলস্ক্রিন বাটন পাওয়া যায়নি (প্লেয়ার কন্ট্রোল লুকানো থাকতে পারে; ভিডিওতে একবার ট্যাপ করে আবার চেষ্টা করুন)।")
        return if (UiRecipeKit.click(a11y, ctx.accessPolicy, btn)) ToolResult(true, "ফুলস্ক্রিন বাটনে ক্লিক করা হয়েছে।")
        else ToolResult(false, "ফুলস্ক্রিন বাটনে ক্লিক করা যায়নি।")
    }
}
