package com.example.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

data class SnapshotNodeRef(
    val id: Int,
    val type: String,
    val text: String,
    val contentDesc: String,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val isPassword: Boolean
)

data class ScreenSnapshot(
    val packageName: String,
    val screenLabel: String,
    val elements: List<SnapshotNodeRef>,
    val compactText: String,
    val truncated: Boolean,
    val hasPartialUiWarning: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed interface SnapshotBuildResult {
    data class Success(val snapshot: ScreenSnapshot) : SnapshotBuildResult
    data class Blocked(val reasonBengali: String) : SnapshotBuildResult
    data class Unreadable(val reasonBengali: String) : SnapshotBuildResult
}

/**
 * AccessibilityNodeInfo গাছ থেকে AI-এর জন্য সংক্ষিপ্ত ও নিরাপদ UI Snapshot তৈরি করে।
 * পাসওয়ার্ড ফিল্ডের লেখা কখনো পড়ে না, সর্বোচ্চ ১৫০টি উপাদান রাখে এবং ID ম্যাপ সংরক্ষণ করে।
 */
object ScreenSnapshotBuilder {

    const val MAX_ELEMENTS = 150
    private const val MAX_TREE_DEPTH = 40
    private const val MAX_VISITED_NODES = 1200

    @Volatile
    var latestSnapshot: ScreenSnapshot? = null
        private set

    fun findElementById(id: Int): SnapshotNodeRef? {
        return latestSnapshot?.elements?.firstOrNull { it.id == id }
    }

    fun findElementByQuery(query: String): SnapshotNodeRef? {
        val clean = query.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return null
        val list = latestSnapshot?.elements.orEmpty()
        return list.firstOrNull {
            it.text.lowercase(Locale.ROOT) == clean || it.contentDesc.lowercase(Locale.ROOT) == clean
        } ?: list.firstOrNull {
            it.text.lowercase(Locale.ROOT).contains(clean) || it.contentDesc.lowercase(Locale.ROOT).contains(clean)
        }
    }

    fun updateLatestSnapshot(snapshot: ScreenSnapshot) {
        latestSnapshot = snapshot
    }

    fun clearSnapshot() {
        latestSnapshot = null
    }

    fun buildFromRoot(
        root: AccessibilityNodeInfo?,
        packageName: String,
        activityName: String?,
        policy: AppAccessPolicy
    ): SnapshotBuildResult {
        val accessDecision = ScreenSecurityGuard.checkAppAccess(packageName, policy)
        if (!accessDecision.allowed) {
            clearSnapshot()
            return SnapshotBuildResult.Blocked(accessDecision.reasonBengali)
        }

        if (root == null) {
            clearSnapshot()
            return SnapshotBuildResult.Unreadable(
                "এই স্ক্রিন পড়া যাচ্ছে না (সম্ভবত FLAG_SECURE সুরক্ষিত স্ক্রিন, কাস্টম/গেম UI বা কোনো সক্রিয় উইন্ডো নেই)।"
            )
        }

        val collected = mutableListOf<SnapshotNodeRef>()
        val rawTextsForSecurityCheck = mutableListOf<String>()
        var hasPasswordField = false
        var hasWebOrCustomSurface = false
        var truncated = false
        var visited = 0
        var nextId = 1

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_TREE_DEPTH || visited >= MAX_VISITED_NODES) return
            visited++

            val clsName = node.className?.toString().orEmpty()
            val clsLower = clsName.lowercase(Locale.ROOT)
            if (clsLower.contains("webview") || clsLower.contains("surfaceview") || clsLower.contains("textureview")) {
                hasWebOrCustomSurface = true
            }

            if (!node.isVisibleToUser) return

            val isPwd = node.isPassword
            if (isPwd) hasPasswordField = true

            // পাসওয়ার্ড ফিল্ডের আসল লেখা কখনোই পড়া হবে না
            val safeText = if (isPwd) {
                "[লুকানো]"
            } else {
                ScreenSecurityGuard.maskSensitivePatterns(node.text?.toString()?.trim().orEmpty())
            }
            val safeDesc = if (isPwd) {
                ""
            } else {
                ScreenSecurityGuard.maskSensitivePatterns(node.contentDescription?.toString()?.trim().orEmpty())
            }

            if (!isPwd) {
                if (safeText.isNotBlank()) rawTextsForSecurityCheck.add(safeText)
                if (safeDesc.isNotBlank()) rawTextsForSecurityCheck.add(safeDesc)
            }

            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val checkable = node.isCheckable

            val hasMeaning = safeText.isNotBlank() || safeDesc.isNotBlank() || clickable || editable || scrollable || checkable
            if (hasMeaning) {
                if (collected.size < MAX_ELEMENTS) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        val type = classifyNodeType(clsLower, clickable, editable, scrollable, checkable)
                        collected.add(
                            SnapshotNodeRef(
                                id = nextId++,
                                type = type,
                                text = safeText.take(160),
                                contentDesc = safeDesc.take(120),
                                viewId = node.viewIdResourceName,
                                bounds = rect,
                                clickable = clickable,
                                editable = editable,
                                scrollable = scrollable,
                                checked = if (checkable) node.isChecked else null,
                                isPassword = isPwd
                            )
                        )
                    }
                } else {
                    truncated = true
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { child.recycle() }
                }
            }
        }

        traverse(root, 0)

        if (collected.isEmpty()) {
            clearSnapshot()
            return SnapshotBuildResult.Unreadable(
                "এই স্ক্রিন পড়া যাচ্ছে না (স্ক্রিন ফাঁকা অথবা FLAG_SECURE / কাস্টম গেম স্ক্রিন)।"
            )
        }

        if (ScreenSecurityGuard.isLoginOrOtpScreen(hasPasswordField, rawTextsForSecurityCheck)) {
            clearSnapshot()
            return SnapshotBuildResult.Blocked(
                "নিরাপত্তার কারণে এই লগইন, পাসওয়ার্ড বা OTP স্ক্রিনে কাজ করি না।"
            )
        }

        val screenLabel = activityName?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "Main"
        val formatted = formatCompactText(
            packageName = packageName,
            screenLabel = screenLabel,
            elements = collected,
            truncated = truncated,
            hasPartialUiWarning = hasWebOrCustomSurface
        )

        val snapshot = ScreenSnapshot(
            packageName = packageName,
            screenLabel = screenLabel,
            elements = collected,
            compactText = formatted,
            truncated = truncated,
            hasPartialUiWarning = hasWebOrCustomSurface
        )
        updateLatestSnapshot(snapshot)
        return SnapshotBuildResult.Success(snapshot)
    }

    internal fun formatCompactText(
        packageName: String,
        screenLabel: String,
        elements: List<SnapshotNodeRef>,
        truncated: Boolean,
        hasPartialUiWarning: Boolean
    ): String {
        val sb = StringBuilder()
        sb.appendLine("App: $packageName | Screen: $screenLabel | Elements: ${elements.size}")
        if (hasPartialUiWarning) {
            sb.appendLine("Notice: স্ক্রিনে WebView বা কাস্টম ড্রয়িং আছে; কিছু উপাদান অসম্পূর্ণ হতে পারে।")
        }
        for (el in elements) {
            sb.append("[${el.id}] ${el.type}")
            if (el.text.isNotBlank()) sb.append(" \"${el.text.replace("\n", " ")}\"")
            if (el.contentDesc.isNotBlank() && el.contentDesc != el.text) {
                sb.append(" desc=\"${el.contentDesc.replace("\n", " ")}\"")
            }
            sb.append(" pos=(${el.bounds.centerX()},${el.bounds.centerY()})")
            val flags = buildList {
                if (el.clickable) add("clickable")
                if (el.editable) add("editable")
                if (el.scrollable) add("scrollable")
                el.checked?.let { add(if (it) "checked" else "unchecked") }
            }
            if (flags.isNotEmpty()) sb.append(" [${flags.joinToString(",")}]")
            sb.appendLine()
        }
        if (truncated) {
            sb.appendLine("[সতর্কতা: স্ক্রিনে আরও উপাদান আছে; টোকেন বাঁচাতে প্রথম $MAX_ELEMENTS টি দেখানো হয়েছে।]")
        }
        return sb.toString().trim()
    }

    private fun classifyNodeType(
        clsLower: String,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        checkable: Boolean
    ): String {
        return when {
            editable || clsLower.contains("edittext") -> "input"
            clsLower.contains("switch") || clsLower.contains("toggle") -> "switch"
            checkable || clsLower.contains("checkbox") || clsLower.contains("radio") -> "checkbox"
            clsLower.contains("button") -> "button"
            clsLower.contains("recyclerview") || clsLower.contains("listview") || scrollable -> "list"
            clsLower.contains("webview") -> "webview"
            clsLower.contains("imageview") || clsLower.contains("imagebutton") -> if (clickable) "button" else "image"
            clsLower.contains("textview") -> if (clickable) "button" else "text"
            clickable -> "button"
            else -> "view"
        }
    }
}
