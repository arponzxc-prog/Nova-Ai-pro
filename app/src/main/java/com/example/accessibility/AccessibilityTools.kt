package com.example.accessibility

import android.content.pm.PackageManager
import android.os.Build
import com.example.tools.ToolExecutionContext
import com.example.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ধাপ ৫-এর সমস্ত Accessibility ও Notification টুল এক্সিকিউটর।
 */
object AccessibilityTools {

    private fun appLabelFor(ctx: ToolExecutionContext, packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val pm = ctx.appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    suspend fun readScreen(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(
                success = false,
                message = "Accessibility সার্ভিস চালু নেই। সেটিংস → Accessibility ও স্ক্রিন অ্যাসিস্ট্যান্ট থেকে চালু করুন।"
            )

        val includeScreenshot = args.optBoolean("include_screenshot", false)
        val result = withContext(Dispatchers.Main) {
            service.readCurrentScreen(ctx.accessPolicy)
        }

        return when (result) {
            is SnapshotBuildResult.Blocked -> ToolResult(false, result.reasonBengali)
            is SnapshotBuildResult.Unreadable -> {
                // লেখা পড়া না গেলে এবং ভিশন ও স্ক্রিনশট অপশন চালু থাকলে ইন-মেমোরি স্ক্রিনশট চেষ্টা
                val screenshot = if (ctx.screenshotVisionEnabled && ctx.modelSupportsVision && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    withContext(Dispatchers.Main) { service.captureScreenshotBase64(ctx.accessPolicy) }
                } else null

                if (screenshot != null) {
                    ToolResult(
                        success = true,
                        message = "টেক্সট UI পড়া যায়নি, তবে অস্থায়ী স্ক্রিনশট ছবি সংযুক্ত করা হয়েছে।",
                        data = JSONObject()
                            .put("package_name", AssistantAccessibilityService.currentPackageName)
                            .put("vision_attached", true),
                        transientImageBase64 = screenshot
                    )
                } else {
                    ToolResult(false, result.reasonBengali)
                }
            }
            is SnapshotBuildResult.Success -> {
                val snap = result.snapshot
                val appLabel = appLabelFor(ctx, snap.packageName)
                val wrapped = ScreenSecurityGuard.wrapUntrustedScreenContent(snap.compactText)
                val shouldAttachVision = includeScreenshot &&
                    ctx.screenshotVisionEnabled &&
                    ctx.modelSupportsVision &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

                val screenshot = if (shouldAttachVision) {
                    withContext(Dispatchers.Main) { service.captureScreenshotBase64(ctx.accessPolicy) }
                } else null

                ToolResult(
                    success = true,
                    message = wrapped,
                    data = JSONObject()
                        .put("package_name", snap.packageName)
                        .put("app_label", appLabel)
                        .put("screen_label", snap.screenLabel)
                        .put("element_count", snap.elements.size)
                        .put("truncated", snap.truncated)
                        .put("partial_ui_warning", snap.hasPartialUiWarning)
                        .put("vision_attached", screenshot != null),
                    transientImageBase64 = screenshot
                )
            }
        }
    }

    suspend fun clickElement(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility সার্ভিস চালু নেই।")

        val elementId = if (args.has("element_id") && !args.isNull("element_id")) args.optInt("element_id", -1) else -1
        val query = args.optString("text", "").trim().ifBlank { args.optString("query", "").trim() }

        var nodeRef = when {
            elementId > 0 -> ScreenSnapshotBuilder.findElementById(elementId)
            query.isNotBlank() -> ScreenSnapshotBuilder.findElementByQuery(query)
            else -> null
        }

        // নোড না পেলে একবার সাম্প্রতিক স্ক্রিন রিফ্রেশ করে খোঁজা
        if (nodeRef == null) {
            val fresh = withContext(Dispatchers.Main) { service.readCurrentScreen(ctx.accessPolicy) }
            if (fresh is SnapshotBuildResult.Blocked) {
                return ToolResult(false, fresh.reasonBengali)
            }
            if (fresh is SnapshotBuildResult.Success) {
                nodeRef = when {
                    elementId > 0 -> ScreenSnapshotBuilder.findElementById(elementId)
                    query.isNotBlank() -> ScreenSnapshotBuilder.findElementByQuery(query)
                    else -> null
                }
            }
        }

        if (nodeRef == null) {
            return ToolResult(
                false,
                "উপাদানটি স্ক্রিনে খুঁজে পাওয়া যায়নি (সম্ভবত স্ক্রিন বদলে গেছে)। আবার read_screen চালিয়ে নতুন ID দেখুন।"
            )
        }

        val currentPkg = ScreenSnapshotBuilder.latestSnapshot?.packageName
            ?.takeIf { it.isNotBlank() } ?: AssistantAccessibilityService.currentPackageName
        val access = ScreenSecurityGuard.checkAppAccess(currentPkg, ctx.accessPolicy)
        if (!access.allowed) {
            return ToolResult(false, access.reasonBengali)
        }

        val clicked = withContext(Dispatchers.Main) {
            service.clickElement(nodeRef, ctx.accessPolicy)
        }
        if (!clicked) {
            return ToolResult(false, "উপাদানে ট্যাপ করা যায়নি (স্ক্রিন বদলে যেতে পারে বা বাটন সক্রিয় নয়)। আবার read_screen দেখুন।")
        }

        service.awaitScreenChange(timeoutMs = 1200L, debounceMs = 200L)
        val label = nodeRef.text.ifBlank { nodeRef.contentDesc.ifBlank { "#${nodeRef.id}" } }
        return ToolResult(
            success = true,
            message = "\"$label\" বাটনে ট্যাপ করা হয়েছে।",
            data = JSONObject()
                .put("clicked_id", nodeRef.id)
                .put("label", label)
        )
    }

    suspend fun typeText(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility সার্ভিস চালু নেই।")

        val textToType = args.optString("text", "")
        if (textToType.isBlank()) {
            return ToolResult(false, "বসানোর জন্য লেখা ফাঁকা রাখা যাবে না।")
        }

        val elementId = if (args.has("element_id") && !args.isNull("element_id")) args.optInt("element_id", -1) else -1
        val query = args.optString("target_hint", "").trim()

        var nodeRef = when {
            elementId > 0 -> ScreenSnapshotBuilder.findElementById(elementId)
            query.isNotBlank() -> ScreenSnapshotBuilder.findElementByQuery(query)
            else -> ScreenSnapshotBuilder.latestSnapshot?.elements?.firstOrNull { it.editable }
        }

        if (nodeRef == null) {
            val fresh = withContext(Dispatchers.Main) { service.readCurrentScreen(ctx.accessPolicy) }
            if (fresh is SnapshotBuildResult.Blocked) return ToolResult(false, fresh.reasonBengali)
            if (fresh is SnapshotBuildResult.Success) {
                nodeRef = when {
                    elementId > 0 -> ScreenSnapshotBuilder.findElementById(elementId)
                    query.isNotBlank() -> ScreenSnapshotBuilder.findElementByQuery(query)
                    else -> fresh.snapshot.elements.firstOrNull { it.editable }
                }
            }
        }

        if (nodeRef?.isPassword == true) {
            return ToolResult(false, "নিরাপত্তার কারণে পাসওয়ার্ড ফিল্ডে লেখা বসাতে পারি না।")
        }

        val typed = withContext(Dispatchers.Main) {
            service.typeText(nodeRef, textToType, ctx.accessPolicy)
        }
        if (!typed) {
            return ToolResult(false, "ইনপুট বক্সে লেখা বসানো যায়নি। আগে ইনপুট বক্সে ক্লিক করে বা read_screen চালিয়ে নিশ্চিত হোন।")
        }

        return ToolResult(
            success = true,
            message = "ইনপুট বক্সে লেখা বসানো হয়েছে (নিরাপত্তার জন্য নিজে থেকে Send চাপা হয়নি)।",
            data = JSONObject().put("length", textToType.length)
        )
    }

    suspend fun scroll(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility সার্ভিস চালু নেই।")

        val direction = args.optString("direction", "down").lowercase()
        val ok = withContext(Dispatchers.Main) {
            service.scrollScreen(direction, ctx.accessPolicy)
        }
        if (!ok) {
            return ToolResult(false, "স্ক্রল করা যায়নি (নিরাপত্তা নীতি বা স্ক্রিন স্ক্রলযোগ্য নয়)।")
        }
        service.awaitScreenChange(timeoutMs = 1000L, debounceMs = 180L)
        return ToolResult(true, "$direction দিকে স্ক্রল করা হয়েছে।")
    }

    suspend fun swipeGesture(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility সার্ভিস চালু নেই।")

        val currentPkg = AssistantAccessibilityService.currentPackageName
        val access = ScreenSecurityGuard.checkAppAccess(currentPkg.ifBlank { null }, ctx.accessPolicy)
        if (!access.allowed) return ToolResult(false, access.reasonBengali)

        val dm = ctx.appContext.resources.displayMetrics
        val startX = (args.optDouble("start_x_pct", 50.0).coerceIn(2.0, 98.0) / 100.0 * dm.widthPixels).toFloat()
        val startY = (args.optDouble("start_y_pct", 70.0).coerceIn(2.0, 98.0) / 100.0 * dm.heightPixels).toFloat()
        val endX = (args.optDouble("end_x_pct", 50.0).coerceIn(2.0, 98.0) / 100.0 * dm.widthPixels).toFloat()
        val endY = (args.optDouble("end_y_pct", 25.0).coerceIn(2.0, 98.0) / 100.0 * dm.heightPixels).toFloat()
        val duration = args.optLong("duration_ms", 320L)

        val ok = withContext(Dispatchers.Main) {
            service.dispatchSwipeGesture(startX, startY, endX, endY, duration)
        }
        if (!ok) return ToolResult(false, "সোয়াইপ জেসচার চালানো যায়নি।")

        service.awaitScreenChange(timeoutMs = 1000L, debounceMs = 180L)
        return ToolResult(true, "সোয়াইপ জেসচার সম্পন্ন হয়েছে।")
    }

    suspend fun globalAction(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility সার্ভিস চালু নেই।")

        val action = args.optString("action", "").lowercase()
        val ok = withContext(Dispatchers.Main) {
            service.executeGlobalAction(action)
        }
        if (!ok) return ToolResult(false, "'$action' গ্লোবাল অ্যাকশন চালানো যায়নি।")

        service.awaitScreenChange(timeoutMs = 1200L, debounceMs = 200L)
        return ToolResult(true, "সিস্টেম অ্যাকশন '$action' সম্পন্ন হয়েছে।")
    }

    suspend fun waitForScreenChange(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val service = AssistantAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility সার্ভিস চালু নেই।")

        val timeoutMs = args.optLong("timeout_ms", 2000L).coerceIn(400L, 6000L)
        val changed = service.awaitScreenChange(timeoutMs = timeoutMs, debounceMs = 240L)
        val read = withContext(Dispatchers.Main) {
            service.readCurrentScreen(ctx.accessPolicy)
        }

        return when (read) {
            is SnapshotBuildResult.Blocked -> ToolResult(false, read.reasonBengali)
            is SnapshotBuildResult.Unreadable -> ToolResult(
                success = changed,
                message = if (changed) "স্ক্রিন বদলেছে, তবে নতুন স্ক্রিনের টেক্সট পড়া যায়নি।" else "নির্দিষ্ট সময়ে স্ক্রিনে কোনো পরিবর্তন দেখা যায়নি।"
            )
            is SnapshotBuildResult.Success -> {
                val statusMsg = if (changed) "স্ক্রিন পরিবর্তিত হয়েছে।" else "সময়সীমায় বড় পরিবর্তন না থাকলেও বর্তমান স্ক্রিন পড়া হয়েছে।"
                ToolResult(
                    success = true,
                    message = "$statusMsg\n${ScreenSecurityGuard.wrapUntrustedScreenContent(read.snapshot.compactText)}",
                    data = JSONObject()
                        .put("changed", changed)
                        .put("package_name", read.snapshot.packageName)
                )
            }
        }
    }

    fun getNotifications(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        if (!AccessibilityHelper.isNotificationAccessEnabled(ctx.appContext)) {
            return ToolResult(
                success = false,
                message = "Notification Access চালু নেই। সেটিংস → নোটিফিকেশন পড়া থেকে অনুমতি দিন।"
            )
        }

        val limit = args.optInt("limit", 15).coerceIn(1, NotificationMemoryStore.MAX_ITEMS)
        val items = NotificationMemoryStore.getRecentNotifications(
            allowedPackages = ctx.notifAllowedPackages,
            limit = limit
        )
        val formatted = NotificationMemoryStore.formatForAi(items)
        val wrapped = ScreenSecurityGuard.wrapUntrustedNotifications(formatted)
        return ToolResult(
            success = true,
            message = wrapped,
            data = JSONObject().put("count", items.size)
        )
    }

    suspend fun sendWhatsAppMessage(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val recipe = WhatsAppSendRecipe()
        val res = recipe.execute(
            context = RecipeExecutionContext(
                appContext = ctx.appContext,
                accessPolicy = ctx.accessPolicy
            ),
            args = args
        )
        val finalResult = if (res.fallbackToUiLoop) {
            val extraData = (res.toolResult.data ?: JSONObject()).put("fallback_to_ui_loop", true)
            res.toolResult.copy(data = extraData)
        } else {
            res.toolResult
        }
        return finalResult
    }
}
