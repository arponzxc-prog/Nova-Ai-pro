/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.agent.ScreenSignatureCalculator).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: computed from NEXA's ScreenSnapshot (already privacy-filtered: password text is never read),
 * pure Kotlin so it is unit-testable. Modifications © ARPON.
 */
package com.example.automation.agent

import com.example.accessibility.ScreenSnapshot
import com.example.accessibility.SnapshotNodeRef

data class ScreenSignature(
    val packageName: String,
    val screenLabel: String,
    val elementCount: Int,
    val contentHash: Long,
    val primaryTexts: List<String>
) {
    /** Same screen, same content (layout positions included). */
    fun isEquivalentTo(other: ScreenSignature): Boolean =
        packageName == other.packageName && screenLabel == other.screenLabel &&
            elementCount == other.elementCount && contentHash == other.contentHash
}

object ScreenSignatureCalculator {
    private const val SEED = 1125899906842597L
    private const val MAX_ELEMENTS = 300
    private const val MAX_TEXTS = 15

    val EMPTY = ScreenSignature("", "", 0, 0L, emptyList())

    fun compute(snapshot: ScreenSnapshot?): ScreenSignature {
        if (snapshot == null) return EMPTY
        var hash = SEED
        val texts = ArrayList<String>(MAX_TEXTS)
        var count = 0
        for (el in snapshot.elements) {
            if (count >= MAX_ELEMENTS) break
            count++
            hash = mix(hash, el)
            val t = el.text.trim()
            val d = el.contentDesc.trim()
            if (texts.size < MAX_TEXTS) {
                when {
                    el.isPassword -> Unit // never include field content
                    t.isNotEmpty() -> texts.add(t)
                    d.isNotEmpty() -> texts.add(d)
                }
            }
        }
        return ScreenSignature(snapshot.packageName, snapshot.screenLabel, count, hash, texts)
    }

    private fun mix(h0: Long, el: SnapshotNodeRef): Long {
        var h = h0
        // Password text is excluded from the hash for the same reason as above.
        h = 31 * h + (if (el.isPassword) 0 else el.text.hashCode())
        h = 31 * h + el.contentDesc.hashCode()
        h = 31 * h + (el.viewId ?: "").hashCode()
        h = 31 * h + (if (el.clickable) 1 else 0)
        h = 31 * h + (if (el.editable) 2 else 0)
        h = 31 * h + el.bounds.centerX()
        h = 31 * h + el.bounds.centerY()
        return h
    }
}
