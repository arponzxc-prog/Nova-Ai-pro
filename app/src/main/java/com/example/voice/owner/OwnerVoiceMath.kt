/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.OwnerVoiceManager — vector maths and
 * threshold presets). Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: pure/Android-free, centroid maths fixed for mismatched vectors, strict decision rule
 * (distance <= threshold; the JARVIS "threshold × 1.35" slack and 2-minute owner lease are NOT ported),
 * profile serialisation for encrypted storage. Modifications © ARPON.
 */
package com.example.voice.owner

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Cosine-distance thresholds for 128-d Vosk/Kaldi x-vectors. Values come from JARVIS' field notes
 * (same speaker ≈ 0.40–0.68, different speakers ≈ 0.85+) and have NOT been re-measured for NEXA:
 * use the Owner Voice test screen (it shows the distance) to calibrate on your own devices.
 */
enum class OwnerVoiceStrictness(val threshold: Float) {
    STRICT(0.62f), BALANCED(0.70f), RELAXED(0.78f);

    companion object {
        fun fromName(name: String?): OwnerVoiceStrictness =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BALANCED
    }
}

data class VoiceMatchResult(
    val isMatch: Boolean,
    val distance: Float,
    val similarityPercent: Int,
    val threshold: Float,
    /** False when no speaker vector could be extracted (too little voiced audio / model missing). */
    val hasVector: Boolean = true
)

/** Enrolled owner voice: the normalised centroid plus every individual sample vector. */
class OwnerVoiceProfile(val centroid: FloatArray, val samples: List<FloatArray>) {
    val dimension: Int get() = centroid.size

    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("centroid", centroid.toJsonArray())
        put("samples", JSONArray().also { arr -> samples.forEach { arr.put(it.toJsonArray()) } })
    }.toString()

    companion object {
        fun fromJson(json: String): OwnerVoiceProfile? = try {
            val o = JSONObject(json)
            val centroid = o.getJSONArray("centroid").toFloatArray()
            val samplesArr = o.optJSONArray("samples") ?: JSONArray()
            val samples = (0 until samplesArr.length()).map { samplesArr.getJSONArray(it).toFloatArray() }
            if (centroid.isEmpty() || samples.any { it.size != centroid.size }) null
            else OwnerVoiceProfile(centroid, samples)
        } catch (_: Exception) { null }

        private fun FloatArray.toJsonArray() = JSONArray().also { a -> forEach { a.put(it.toDouble()) } }
        private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
    }
}

object OwnerVoiceMath {

    /** 1 − cosine similarity, range 0..2. Mismatched/empty/zero vectors return 1.0 ("unrelated"). */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 1.0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble(); val y = b[i].toDouble()
            dot += x * y; na += x * x; nb += y * y
        }
        if (na <= 0.0 || nb <= 0.0) return 1.0f
        val sim = (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0)
        return (1.0 - sim).toFloat().coerceIn(0f, 2f)
    }

    /** Display-only mapping of distance to a 0..100 "similarity" figure. */
    fun similarityPercent(distance: Float): Int =
        ((1f - distance / 1.35f) * 100f).toInt().coerceIn(0, 100)

    /** Normalised mean of the vectors that share the dimension of the first one; null if none usable. */
    fun centroid(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        if (dim == 0) return null
        val usable = vectors.filter { it.size == dim }
        val avg = FloatArray(dim)
        for (v in usable) for (i in 0 until dim) avg[i] += v[i]
        for (i in 0 until dim) avg[i] /= usable.size
        var norm = 0.0
        for (x in avg) norm += x.toDouble() * x
        val n = sqrt(norm).toFloat()
        if (n <= 0f) return null
        for (i in 0 until dim) avg[i] /= n
        return avg
    }

    /**
     * Smallest distance to the centroid or any enrolled sample. Strict rule: match ⇔ distance <= threshold.
     * [vector] == null (nothing extracted) is never a match.
     */
    fun verify(vector: FloatArray?, profile: OwnerVoiceProfile, threshold: Float): VoiceMatchResult {
        if (vector == null || vector.isEmpty()) {
            return VoiceMatchResult(false, 1.0f, 0, threshold, hasVector = false)
        }
        var best = cosineDistance(vector, profile.centroid)
        for (s in profile.samples) best = minOf(best, cosineDistance(vector, s))
        return VoiceMatchResult(best <= threshold, best, similarityPercent(best), threshold)
    }
}
