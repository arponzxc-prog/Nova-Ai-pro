/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.OwnerVoiceManager — Vosk x-vector extraction).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: instance-based, profile stored encrypted through SecureKeyStore, fail-closed
 * verification, no dependency on JARVIS preferences. Modifications © ARPON.
 */
package com.example.voice.owner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer

/** Minimal secret storage; implemented over [com.example.security.SecureKeyStore] by AiRepository. */
interface SecretStore {
    fun get(id: String): String
    fun save(id: String, value: String): Boolean
    fun delete(id: String)
}

class OwnerVoiceManager(
    private val models: VoskModelManager,
    private val secrets: SecretStore
) {
    companion object {
        private const val TAG = "OwnerVoiceManager"
        const val PROFILE_SECRET_ID = "owner_voice_profile"
        /** Shortest audio that gives a usable embedding (JARVIS accepted 0.1 s; too weak to trust). */
        const val MIN_VERIFY_BYTES = 16_000 * 2 * 4 / 10     // 0.4 s
        const val MIN_ENROLL_BYTES = 16_000 * 2 * 12 / 10    // 1.2 s
        private const val SAMPLE_RATE = 16_000.0f
        private const val FEED_BLOCK = 4096
        private const val TRAILING_SILENCE_BYTES = 12_800    // 0.4 s: lets the x-vector pooling finish on short audio
        const val ENROLL_SAMPLES_REQUIRED = 3
    }

    private val extractLock = Any()
    @Volatile private var cachedProfile: OwnerVoiceProfile? = null
    @Volatile private var profileLoaded = false

    // ---- profile --------------------------------------------------------------------------

    /** Blocking (keystore + disk): call off the main thread. */
    fun profile(): OwnerVoiceProfile? {
        if (!profileLoaded) {
            synchronized(this) {
                if (!profileLoaded) {
                    cachedProfile = secrets.get(PROFILE_SECRET_ID).takeIf { it.isNotBlank() }?.let { OwnerVoiceProfile.fromJson(it) }
                    profileLoaded = true
                }
            }
        }
        return cachedProfile
    }

    fun hasProfile(): Boolean = profile() != null

    /** Blocking. Returns false if the vectors are unusable or the secure store refused the write. */
    fun saveProfile(vectors: List<FloatArray>): Boolean {
        val centroid = OwnerVoiceMath.centroid(vectors) ?: return false
        val dim = centroid.size
        val profile = OwnerVoiceProfile(centroid, vectors.filter { it.size == dim })
        if (!secrets.save(PROFILE_SECRET_ID, profile.toJson())) return false
        cachedProfile = profile; profileLoaded = true
        return true
    }

    fun clearProfile() {
        secrets.delete(PROFILE_SECRET_ID)
        cachedProfile = null; profileLoaded = true
    }

    // ---- extraction / verification --------------------------------------------------------

    /**
     * 128-d speaker x-vector for 16 kHz mono 16-bit PCM, or null if the models are not loaded, the audio is
     * too short, or no vector came out. CPU-heavy (hundreds of ms): never call on the main thread.
     */
    fun extractVector(pcm: ByteArray): FloatArray? {
        val loaded = models.loadedModels() ?: run { Log.w(TAG, "Vosk models not loaded"); return null }
        if (pcm.size < MIN_VERIFY_BYTES) return null
        synchronized(extractLock) {
            var rec: Recognizer? = null
            return try {
                rec = Recognizer(loaded.first, SAMPLE_RATE, loaded.second)
                var offset = 0
                while (offset < pcm.size) {
                    val len = minOf(FEED_BLOCK, pcm.size - offset)
                    rec.acceptWaveForm(pcm.copyOfRange(offset, offset + len), len)
                    offset += len
                }
                rec.acceptWaveForm(ByteArray(TRAILING_SILENCE_BYTES), TRAILING_SILENCE_BYTES)
                val result = rec.finalResult
                if (result.isNullOrBlank()) null else parseSpk(result)
            } catch (t: Throwable) {
                Log.w(TAG, "Vector extraction failed: ${t.javaClass.simpleName}")
                null
            } finally {
                runCatching { rec?.close() }
            }
        }
    }

    private fun parseSpk(json: String): FloatArray? = try {
        val o = JSONObject(json)
        if (!o.has("spk")) null else {
            val a = o.getJSONArray("spk")
            FloatArray(a.length()) { a.getDouble(it).toFloat() }.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) { null }

    /** Fail-closed: no profile, no vector or any error means "not the owner". */
    suspend fun verify(pcm: ByteArray, threshold: Float): VoiceMatchResult = withContext(Dispatchers.Default) {
        val profile = profile()
            ?: return@withContext VoiceMatchResult(false, 1f, 0, threshold, hasVector = false)
        OwnerVoiceMath.verify(extractVector(pcm), profile, threshold)
    }
}
