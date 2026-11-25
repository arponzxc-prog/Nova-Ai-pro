/* © ARPON — original NEXA AI code. Pure element-matching helpers over NEXA's ScreenSnapshot. */
package com.example.automation.core

import com.example.accessibility.SnapshotNodeRef

object UiMatchers {

    fun byViewIds(elements: List<SnapshotNodeRef>, ids: List<String>): List<SnapshotNodeRef> =
        if (ids.isEmpty()) emptyList() else elements.filter { it.viewId != null && it.viewId in ids }

    /**
     * Label match on text or content description (case-insensitive).
     * [exact] = whole label equals one of [labels]; otherwise "contains". Anything containing an [exclude] term is skipped
     * (e.g. "subscribe" must not match "subscribed" / "unsubscribe").
     */
    fun matchesLabel(el: SnapshotNodeRef, labels: List<String>, exact: Boolean, exclude: List<String> = emptyList()): Boolean {
        if (el.isPassword) return false
        val t = el.text.trim().lowercase()
        val d = el.contentDesc.trim().lowercase()
        if (exclude.any { x -> x.lowercase().let { t.contains(it) || d.contains(it) } }) return false
        return labels.any { l ->
            val x = l.trim().lowercase()
            x.isNotEmpty() && if (exact) (t == x || d == x) else (t.contains(x) || d.contains(x))
        }
    }

    fun findByLabel(
        elements: List<SnapshotNodeRef>, labels: List<String>, exact: Boolean = false,
        exclude: List<String> = emptyList(), requireClickable: Boolean = false
    ): SnapshotNodeRef? = elements.firstOrNull { (!requireClickable || it.clickable) && matchesLabel(it, labels, exact, exclude) }

    fun screenWidth(elements: List<SnapshotNodeRef>): Int = elements.maxOfOrNull { it.bounds.right } ?: 0

    /**
     * The user's most recent SENT chat bubble: among elements with a bubble view id, those right-aligned
     * (centre in the right half of the screen) and, of those, the lowest on screen. Heuristic by design:
     * returns null rather than guessing when nothing qualifies.
     */
    fun lastOutgoingBubble(elements: List<SnapshotNodeRef>, bubbleIds: List<String>): SnapshotNodeRef? {
        val width = screenWidth(elements)
        if (width <= 0) return null
        return byViewIds(elements, bubbleIds)
            .filter { it.bounds.width() > 0 && it.bounds.height() > 0 && it.bounds.centerX() > width / 2 }
            .maxByOrNull { it.bounds.bottom }
    }

    /** Name tokens (>= 2 chars) of [contactName] that appear in [title]; used to confirm the right chat is open. */
    fun titleMatchesContact(title: String, contactName: String): Boolean {
        val t = title.lowercase()
        val tokens = contactName.lowercase().split(' ', '.', '_', '-').filter { it.length >= 2 }
        return tokens.isNotEmpty() && tokens.any { t.contains(it) }
    }
}
