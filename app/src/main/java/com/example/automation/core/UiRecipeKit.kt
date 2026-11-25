/* © ARPON — original NEXA AI code. Shared plumbing for accessibility "recipes" (WhatsApp call/delete, YouTube). */
package com.example.automation.core

import com.example.accessibility.AppAccessPolicy
import com.example.accessibility.AssistantAccessibilityService
import com.example.accessibility.ScreenSnapshot
import com.example.accessibility.SnapshotBuildResult
import com.example.accessibility.SnapshotNodeRef
import com.example.automation.agent.ActionOutcome
import com.example.automation.agent.SerializedActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface AwaitResult {
    data class Found(val snapshot: ScreenSnapshot) : AwaitResult
    data class Blocked(val reason: String) : AwaitResult
    data class Aborted(val reason: String) : AwaitResult
    /** Never reached an acceptable screen; [last] is the most recent readable one (may be null). */
    data class NotFound(val last: ScreenSnapshot?) : AwaitResult
}

internal object UiRecipeKit {
    const val MAX_ATTEMPTS = 6

    /** Non-null when the kill switch is on or the running task was cancelled (stop button / voice stop / kill switch). */
    fun abortReason(): String? = when {
        AutomationSwitches.settings.killSwitch -> "অটোমেশন কিল সুইচ চালু হওয়ায় কাজ থামানো হয়েছে।"
        SerializedActionExecutor.isCancelled -> "কাজটি বাতিল করা হয়েছে।"
        else -> null
    }

    suspend fun read(a11y: AssistantAccessibilityService, policy: AppAccessPolicy): SnapshotBuildResult =
        withContext(Dispatchers.Main) { a11y.readCurrentScreen(policy) }

    /** Waits (event-driven, bounded) for a screen of one of [packages] that satisfies [accept]. */
    suspend fun awaitScreen(
        a11y: AssistantAccessibilityService, policy: AppAccessPolicy, packages: Set<String>,
        attempts: Int = MAX_ATTEMPTS, accept: (ScreenSnapshot) -> Boolean = { true }
    ): AwaitResult {
        var last: ScreenSnapshot? = null
        repeat(attempts) {
            abortReason()?.let { return AwaitResult.Aborted(it) }
            a11y.awaitScreenChange(timeoutMs = 1800L, debounceMs = 260L)
            when (val r = read(a11y, policy)) {
                is SnapshotBuildResult.Blocked -> return AwaitResult.Blocked(r.reasonBengali)
                is SnapshotBuildResult.Success -> {
                    if (r.snapshot.packageName in packages) {
                        last = r.snapshot
                        if (accept(r.snapshot)) return AwaitResult.Found(r.snapshot)
                    }
                }
                is SnapshotBuildResult.Unreadable -> Unit
            }
        }
        return AwaitResult.NotFound(last)
    }

    suspend fun click(a11y: AssistantAccessibilityService, policy: AppAccessPolicy, node: SnapshotNodeRef): Boolean {
        abortReason()?.let { return false }
        val out = SerializedActionExecutor.execute(timeoutMs = 6_000L) {
            withContext(Dispatchers.Main) { a11y.clickElement(node, policy) }
        }
        return out is ActionOutcome.Completed && out.value
    }

    suspend fun longPress(a11y: AssistantAccessibilityService, node: SnapshotNodeRef): Boolean {
        abortReason()?.let { return false }
        val out = SerializedActionExecutor.execute(timeoutMs = 6_000L) {
            a11y.dispatchLongPress(node.bounds.exactCenterX(), node.bounds.exactCenterY())
        }
        return out is ActionOutcome.Completed && out.value
    }

    suspend fun back(a11y: AssistantAccessibilityService) {
        withContext(Dispatchers.Main) { a11y.executeGlobalAction("back") }
    }

    fun installedPackage(pm: android.content.pm.PackageManager, candidates: Collection<String>): String? =
        candidates.firstOrNull { pkg -> runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false) }
}
