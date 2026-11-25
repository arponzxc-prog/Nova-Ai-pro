/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.agent.ActionVerifier).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: pure before/after comparison on [ScreenSignature]/[ScreenSnapshot] (no service access, no
 * sleeping), so callers decide when to re-read the screen. Same honesty rule as JARVIS: a changed screen is
 * "changed", never automatically "goal reached". Modifications © ARPON.
 */
package com.example.automation.agent

import com.example.accessibility.ScreenSnapshot

data class VerificationResult(
    val isVerified: Boolean,
    val reason: String,
    val stateChanged: Boolean = false,
    val isRetryable: Boolean = false
)

object ActionVerifier {

    /** Collapses whitespace/newlines so "a  b\r\n" equals "a b". */
    internal fun normalize(s: String) = s.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()

    /**
     * Typing is verified only against an editable, non-password field in [after]. When [targetElementId] is given
     * that exact element must hold the text (a different focused field never counts as success).
     */
    fun verifyTyping(after: ScreenSnapshot?, expectedText: String, targetElementId: Int? = null): VerificationResult {
        val expected = normalize(expectedText)
        if (expected.isEmpty()) return VerificationResult(true, "Empty text to type")
        if (after == null) return VerificationResult(false, "Screen could not be read to verify typing", isRetryable = true)
        val candidates = after.elements.filter { it.editable && !it.isPassword && (targetElementId == null || it.id == targetElementId) }
        if (candidates.any { normalize(it.text) == expected }) {
            return VerificationResult(true, "Text verified in target field", stateChanged = true)
        }
        return VerificationResult(false, "Text mismatch: the target input did not contain the requested text", isRetryable = true)
    }

    /** A click is never "verified" by a screen change alone; callers must check for the expected destination. */
    fun verifyClick(pre: ScreenSignature, post: ScreenSignature): VerificationResult = when {
        post.packageName != pre.packageName || post.screenLabel != pre.screenLabel ->
            VerificationResult(false, "Window changed, but the requested destination was not checked", stateChanged = true)
        !post.isEquivalentTo(pre) ->
            VerificationResult(false, "UI content changed, but the requested result was not checked", stateChanged = true)
        else ->
            VerificationResult(false, "No detectable UI change after click", stateChanged = false, isRetryable = true)
    }

    fun verifyScroll(pre: ScreenSignature, post: ScreenSignature): VerificationResult =
        if (post.isEquivalentTo(pre)) VerificationResult(false, "No visible scroll progress; the end of the list is unproven")
        else VerificationResult(true, "Scroll revealed new content", stateChanged = true)

    /** True if [snapshot] shows any of [needles] (case-insensitive) in visible text/description: goal evidence helper. */
    fun screenShowsAny(snapshot: ScreenSnapshot?, needles: List<String>): Boolean {
        if (snapshot == null || needles.isEmpty()) return false
        val lower = needles.map { it.lowercase() }
        return snapshot.elements.any { el ->
            !el.isPassword && lower.any { n -> el.text.lowercase().contains(n) || el.contentDesc.lowercase().contains(n) }
        }
    }
}
