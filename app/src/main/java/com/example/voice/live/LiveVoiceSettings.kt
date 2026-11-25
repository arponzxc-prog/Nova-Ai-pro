/* © ARPON — original NEXA AI code. */
package com.example.voice.live

import com.example.voice.owner.OwnerVoiceStrictness

enum class VoiceMode { LEGACY, GEMINI_LIVE;
    companion object { fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: LEGACY }
}

/** Persisted (DataStore) voice-engine settings. The Gemini key itself lives only in SecureKeyStore. */
data class LiveVoiceSettings(
    val mode: VoiceMode = VoiceMode.LEGACY,
    val model: String = LiveConstants.DEFAULT_MODEL,
    val voiceName: String = LiveConstants.DEFAULT_VOICE,
    val handsFree: Boolean = false,
    val ownerVoiceEnabled: Boolean = false,
    val ownerStrictness: OwnerVoiceStrictness = OwnerVoiceStrictness.BALANCED
)
