/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.contacts.ContactResolver — matching tiers only).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: pure ranking over (name, number) pairs. JARVIS's hard-coded "+91" prefixing and its
 * auto-pick of a fuzzy winner were NOT ported: this only proposes candidates, the existing NEXA picker /
 * confirmation flow decides. Modifications © ARPON.
 */
package com.example.automation.contacts

import java.util.Locale
import kotlin.math.min

data class NamedNumber(val name: String, val number: String)

enum class MatchTier { EXACT, NICKNAME, WORD, FUZZY }

data class RankedContact(val contact: NamedNumber, val tier: MatchTier, val score: Float)

object ContactRanking {

    private val NICKNAMES: Map<String, List<String>> = mapOf(
        "mom" to listOf("mom", "mother", "mummy", "maa", "ammi", "amma", "আম্মু", "মা"),
        "dad" to listOf("dad", "father", "papa", "daddy", "abbu", "baba", "আব্বু", "বাবা"),
        "bro" to listOf("brother", "bro", "bhai", "ভাই"),
        "sis" to listOf("sister", "sis", "didi", "apu", "আপু", "বোন"),
        "wife" to listOf("wife", "biwi"),
        "husband" to listOf("husband", "hubby")
    )
    const val FUZZY_MIN_SIMILARITY = 0.70f
    const val FUZZY_MAX_RESULTS = 3

    /**
     * Returns candidates for [query] from the best non-empty tier only (exact > nickname > word > fuzzy).
     * Several candidates are expected and fine: the caller must let the user choose.
     */
    fun rank(query: String, entries: List<NamedNumber>): List<RankedContact> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty() || entries.isEmpty()) return emptyList()

        entries.filter { it.name.lowercase(Locale.ROOT) == q }
            .takeIf { it.isNotEmpty() }?.let { return it.map { c -> RankedContact(c, MatchTier.EXACT, 1f) } }

        val aliases = NICKNAMES[q] ?: NICKNAMES.entries.firstOrNull { q in it.value }?.value
        if (aliases != null) {
            val m = entries.filter { c -> val n = c.name.lowercase(Locale.ROOT); aliases.any { a -> n.contains(a) } }
            if (m.isNotEmpty()) return m.map { RankedContact(it, MatchTier.NICKNAME, 0.9f) }
        }

        val words = entries.filter { c ->
            c.name.lowercase(Locale.ROOT).split(' ', '.', '_', '-').any { t -> t == q || (q.length >= 2 && t.startsWith(q)) }
        }
        if (words.isNotEmpty()) return words.map { RankedContact(it, MatchTier.WORD, 0.8f) }

        return entries.map { RankedContact(it, MatchTier.FUZZY, similarity(q, it.name.lowercase(Locale.ROOT))) }
            .filter { it.score >= FUZZY_MIN_SIMILARITY }
            .sortedByDescending { it.score }
            .take(FUZZY_MAX_RESULTS)
    }

    /** 1 - editDistance/maxLen, best of full-name and per-word comparison ("Rahim" vs "Rahman Khan"). */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val full = sim(a, b)
        val best = b.split(' ', '.', '_', '-').filter { it.isNotEmpty() }.maxOfOrNull { sim(a, it) } ?: 0f
        return maxOf(full, best)
    }

    private fun sim(a: String, b: String): Float {
        val longer = maxOf(a.length, b.length)
        if (longer == 0) return 1f
        return (longer - levenshtein(a, b)).toFloat() / longer
    }

    internal fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var next = IntArray(lhs.length + 1)
        for (i in 1..rhs.length) {
            next[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                next[j] = min(min(cost[j] + 1, next[j - 1] + 1), cost[j - 1] + match)
            }
            val t = cost; cost = next; next = t
        }
        return cost[lhs.length]
    }
}
