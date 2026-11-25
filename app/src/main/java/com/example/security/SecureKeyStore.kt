package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecureKeyStore: Android Keystore ভিত্তিক এনক্রিপশন ক্লাস।
 * - প্রতিটি মডেল কনফিগের নিজস্ব id অনুযায়ী আলাদা এন্ট্রি (`api_key_<configId>`, `api_key_iv_<configId>`) এবং আলাদা র‍্যান্ডম IV সংরক্ষণ করে।
 * - SecretKey ও ডিক্রিপ্ট করা Key মেমোরিতে নিরাপদে ক্যাশ করে।
 * - কখনোই API Key লগে বা এরর বার্তায় প্রকাশ করে না।
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_ai_prefs", Context.MODE_PRIVATE)
    private val keyAlias = "AiAssistantMasterKey"
    private val lock = Any()

    @Volatile
    private var cachedSecretKey: SecretKey? = null
    private val decryptedCache = ConcurrentHashMap<String, String>()

    companion object {
        const val KEY_PREFIX = "api_key_"
        const val IV_PREFIX = "api_key_iv_"
        private const val LEGACY_ENC_PREFIX = "enc_"
        private const val LEGACY_IV_PREFIX = "iv_"

        private val SECRET_QUERY_REGEX = Regex("([?&]key=)[^&\\s]+", RegexOption.IGNORE_CASE)
        private val SECRET_BEARER_REGEX = Regex("(Bearer\\s+)[^\\s]+", RegexOption.IGNORE_CASE)
        private val SECRET_TOKEN_REGEX = Regex("\\b(sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{10,})")

        /**
         * সঠিক কনফিগের নিজস্ব Key-এর শেষ ৪ অক্ষর ব্যবহার করে মাস্কড স্ট্রিং তৈরি করে।
         */
        fun maskKey(plainKey: String): String {
            val trimmed = plainKey.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.length <= 4) {
                "••••"
            } else {
                "••••${trimmed.takeLast(4)}"
            }
        }

        /**
         * এরর বার্তা বা URL থেকে সম্ভাব্য API Key অংশ নিরাপদভাবে মুছে ফেলে।
         */
        fun redactSecrets(text: String): String {
            if (text.isBlank()) return text
            var cleaned = SECRET_QUERY_REGEX.replace(text, "$1[REDACTED]")
            cleaned = SECRET_BEARER_REGEX.replace(cleaned, "$1[REDACTED]")
            cleaned = SECRET_TOKEN_REGEX.replace(cleaned, "[REDACTED]")
            return cleaned
        }
    }

    init {
        initKeyStore()
    }

    private fun initKeyStore() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val parameterSpec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(parameterSpec)
                cachedSecretKey = keyGenerator.generateKey()
            } else {
                cachedSecretKey = keyStore.getKey(keyAlias, null) as? SecretKey
            }
        } catch (_: Exception) {
        }
    }

    private fun getSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keyStore.getKey(keyAlias, null) as SecretKey).also { cachedSecretKey = it }
    }

    /**
     * নির্দিষ্ট configId-র অধীনে API Key নতুন র‍্যান্ডম IV দিয়ে এনক্রিপ্ট করে সেভ করে।
     * ফাঁকা id বা key দিলে অন্য কোনো কনফিগের ডেটা স্পর্শ করে না।
     */
    fun saveEncryptedKey(id: String, plainKey: String) {
        trySaveEncryptedKey(id, plainKey)
    }

    /**
     * [saveEncryptedKey]-এর মতোই, কিন্তু সফল হলে true ফেরত দেয়।
     * বাল্ক ইমপোর্টে এনক্রিপশন/সেভ ব্যর্থ হলে পুরো ইমপোর্ট রোলব্যাক করতে এটি ব্যবহৃত হয়।
     */
    fun trySaveEncryptedKey(id: String, plainKey: String): Boolean {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return false

        if (plainKey.isBlank()) {
            deleteKey(cleanId)
            return true
        }

        synchronized(lock) {
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
                val iv = cipher.iv
                val encryptedBytes = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))

                val encString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
                val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)

                val committed = prefs.edit()
                    .putString("$KEY_PREFIX$cleanId", encString)
                    .putString("$IV_PREFIX$cleanId", ivString)
                    .remove("$LEGACY_ENC_PREFIX$cleanId")
                    .remove("$LEGACY_IV_PREFIX$cleanId")
                    .commit()
                if (committed) decryptedCache[cleanId] = plainKey
                committed
            } catch (_: Exception) {
                // এনক্রিপশন ব্যর্থ হলে অসম্পূর্ণ বা ভুল Key ক্যাশে রাখবে না
                false
            }
        }
    }

    /**
     * নির্দিষ্ট configId-র এনক্রিপ্ট করা Key ডিক্রিপ্ট করে ফিরিয়ে দেয়।
     */
    fun getDecryptedKey(id: String): String {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return ""
        decryptedCache[cleanId]?.let { return it }

        synchronized(lock) {
            decryptedCache[cleanId]?.let { return it }

            val modernEnc = prefs.getString("$KEY_PREFIX$cleanId", null)
            val modernIv = prefs.getString("$IV_PREFIX$cleanId", null)
            val useLegacy = modernEnc == null || modernIv == null

            val encString = if (useLegacy) prefs.getString("$LEGACY_ENC_PREFIX$cleanId", null) else modernEnc
            val ivString = if (useLegacy) prefs.getString("$LEGACY_IV_PREFIX$cleanId", null) else modernIv
            if (encString == null || ivString == null) return ""

            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(128, Base64.decode(ivString, Base64.DEFAULT))
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
                val decodedBytes = cipher.doFinal(Base64.decode(encString, Base64.DEFAULT))
                val plain = String(decodedBytes, Charsets.UTF_8)
                decryptedCache[cleanId] = plain

                // পুরোনো `enc_`/`iv_` ফরম্যাট থেকে নতুন `api_key_`/`api_key_iv_` এ নিরাপদ মাইগ্রেশন
                if (useLegacy) {
                    prefs.edit()
                        .putString("$KEY_PREFIX$cleanId", encString)
                        .putString("$IV_PREFIX$cleanId", ivString)
                        .remove("$LEGACY_ENC_PREFIX$cleanId")
                        .remove("$LEGACY_IV_PREFIX$cleanId")
                        .commit()
                }
                return plain
            } catch (_: Exception) {
                return ""
            }
        }
    }

    /**
     * কেবল নির্দিষ্ট configId-র এনক্রিপ্ট করা Key ও IV মুছে ফেলে; অন্য কনফিগের Key অক্ষত থাকে।
     */
    fun deleteKey(id: String) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return
        synchronized(lock) {
            decryptedCache.remove(cleanId)
            prefs.edit()
                .remove("$KEY_PREFIX$cleanId")
                .remove("$IV_PREFIX$cleanId")
                .remove("$LEGACY_ENC_PREFIX$cleanId")
                .remove("$LEGACY_IV_PREFIX$cleanId")
                .commit()
        }
    }
}
