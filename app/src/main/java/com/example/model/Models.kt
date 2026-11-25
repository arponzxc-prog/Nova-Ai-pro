package com.example.model

import java.util.UUID

enum class ProviderType(val displayName: String, val defaultBaseUrl: String) {
    OPENAI("OpenAI / Compatible", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com/v1"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta")
}

// মেসেজটি টেক্সট নাকি ভয়েস থেকে এসেছে তা ট্র্যাক করার জন্য
enum class InputMode {
    TEXT,
    VOICE
}

// ভয়েস ইনপুট ভাষা পছন্দ
enum class SpeechLanguageOption(val tag: String) {
    AUTO("auto"),
    BENGALI("bn-BD"),
    ENGLISH("en-US")
}

// AI-এর কাছে পাঠানো হিস্টোরি মেমোরির আকার (গতি ও টোকেন সাশ্রয়ের জন্য)
enum class HistoryLimitPreset(val maxMessages: Int, val maxTokens: Int) {
    SHORT(maxMessages = 6, maxTokens = 1500),
    MEDIUM(maxMessages = 12, maxTokens = 3500),
    LONG(maxMessages = 24, maxTokens = 7000)
}

// প্রতিটি AI রিকোয়েস্টের ল্যাটেন্সি ও টোকেন পরিমাপ (API key বা মেসেজ কন্টেন্ট ছাড়া)
data class RequestLatencyMetrics(
    val requestBuildMs: Long = 0L,
    val firstTokenMs: Long = 0L,
    val totalMs: Long = 0L,
    val messageCount: Int = 0,
    val estimatedInputTokens: Int = 0,
    val toolCount: Int = 0,
    val usedStreaming: Boolean = false
)

data class ModelConfig(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseUrl: String,
    val modelName: String,
    val temperature: Float = 0.7f,
    val isActive: Boolean = false,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String = "",
    val role: String, // "user", "assistant", "system"
    val content: String,
    val inputMode: InputMode = InputMode.TEXT,
    val timestamp: Long = System.currentTimeMillis()
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false
)

enum class ActionCardStatus {
    PENDING,
    WAITING_CONFIRMATION,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class ActionCardItem(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val displayTitle: String,
    val summary: String,
    val status: ActionCardStatus,
    val detailMessage: String = ""
)

data class ActionLog(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val argsSummary: String,
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class VoiceCharacter(
    val id: String,
    val nameBengali: String,
    val nameEnglish: String,
    val descriptionBengali: String,
    val descriptionEnglish: String,
    val gender: String, // "Female", "Male", "Robot", "Custom"
    val speechRate: Float,
    val speechPitch: Float,
    val previewText: String = "হ্যালো! আমি আপনার NOVA AI অ্যাসিস্ট্যান্ট। আমি এই মিষ্টি কণ্ঠে আপনাকে সাহায্য করব।"
)

object VoicePersonas {
    val ALL: List<VoiceCharacter> = listOf(
        VoiceCharacter(
            id = "nova",
            nameBengali = "নোভা (Nova)",
            nameEnglish = "Nova (Warm Female)",
            descriptionBengali = "প্রাণবন্ত, মিষ্টি ও স্বাভাবিক নারী কণ্ঠ",
            descriptionEnglish = "Vibrant, friendly, and natural female voice",
            gender = "Female",
            speechRate = 1.0f,
            speechPitch = 1.25f,
            previewText = "হ্যালো! আমি নোভা। আমি এই মিষ্টি নারী কণ্ঠে আপনাকে সাহায্য করতে পারি।"
        ),
        VoiceCharacter(
            id = "aria",
            nameBengali = "আরিয়া (Aria)",
            nameEnglish = "Aria (Calm Female)",
            descriptionBengali = "কোমল, শান্ত ও ধীরস্থির নারী কণ্ঠ",
            descriptionEnglish = "Soft, gentle, and soothing female voice",
            gender = "Female",
            speechRate = 0.92f,
            speechPitch = 1.10f,
            previewText = "নমস্কার! আমি আরিয়া। একটি শান্ত ও মিষ্টি কণ্ঠে আমি আপনার সাথে কথা বলব।"
        ),
        VoiceCharacter(
            id = "kavya",
            nameBengali = "ক্যাভি (Kavya)",
            nameEnglish = "Kavya (Smart & Fast)",
            descriptionBengali = "স্মার্ট, চটপটে ও দ্রুত সহকারী কণ্ঠ",
            descriptionEnglish = "Smart, crisp, and energetic assistant",
            gender = "Female",
            speechRate = 1.15f,
            speechPitch = 1.18f,
            previewText = "হ্যালো! আমি ক্যাভি। দ্রুত এবং নিখুঁতভাবে আপনার সব কাজ সম্পন্ন করে দেব।"
        ),
        VoiceCharacter(
            id = "echo",
            nameBengali = "ইকো (Echo)",
            nameEnglish = "Echo (Deep Male)",
            descriptionBengali = "গম্ভীর, প্রফেশনাল ও স্পষ্ট পুরুষ কণ্ঠ",
            descriptionEnglish = "Deep, authoritative, and professional male voice",
            gender = "Male",
            speechRate = 0.96f,
            speechPitch = 0.82f,
            previewText = "হ্যালো, আমি ইকো। প্রফেশনাল ও স্পষ্ট কণ্ঠে আপনার সেবায় প্রস্তুত।"
        ),
        VoiceCharacter(
            id = "rihan",
            nameBengali = "রিহান (Rihan)",
            nameEnglish = "Rihan (Friendly Male)",
            descriptionBengali = "বন্ধুত্বপূর্ণ, স্বাভাবিক ও সাবলীল পুরুষ কণ্ঠ",
            descriptionEnglish = "Warm, natural, and friendly brotherly tone",
            gender = "Male",
            speechRate = 1.02f,
            speechPitch = 0.94f,
            previewText = "কেমন আছেন? আমি রিহান। যেকোনো প্রশ্ন বা তথ্যের জন্য আমি আপনার সাথে আছি।"
        ),
        VoiceCharacter(
            id = "atlas",
            nameBengali = "অ্যাটলাস (Atlas)",
            nameEnglish = "Atlas (Wise & Calm)",
            descriptionBengali = "ধীর, পরিপক্ব ও গুরুগম্ভীর পুরুষ কণ্ঠ",
            descriptionEnglish = "Mature, wise, and calm storytelling pace",
            gender = "Male",
            speechRate = 0.88f,
            speechPitch = 0.74f,
            previewText = "হ্যালো, আমি অ্যাটলাস। গভীর ও শান্তভাবে আপনার সাথে কথা বলব।"
        ),
        VoiceCharacter(
            id = "cyber",
            nameBengali = "সাইবার-১ (Cyber-1)",
            nameEnglish = "Cyber-1 (Futuristic Bot)",
            descriptionBengali = "ফিউচারিস্টিক ও আধুনিক এআই রোবট কণ্ঠ",
            descriptionEnglish = "Futuristic electronic synth AI assistant",
            gender = "Robot",
            speechRate = 1.10f,
            speechPitch = 0.62f,
            previewText = "সিস্টেম সক্রিয়। আমি সাইবার ওয়ান এআই প্রোটোকল। বলুন কী নির্দেশ দিতে চান।"
        ),
        VoiceCharacter(
            id = "custom",
            nameBengali = "কাস্টম (Custom)",
            nameEnglish = "Custom (Manual Tuning)",
            descriptionBengali = "নিজের পছন্দমতো গতি ও কণ্ঠের সুর নির্ধারণ করুন",
            descriptionEnglish = "Fine-tune speed and pitch manually with sliders",
            gender = "Custom",
            speechRate = 1.0f,
            speechPitch = 1.0f,
            previewText = "এটি আপনার কাস্টম টিউন করা ভয়েস প্রিভিউ।"
        )
    )

    fun getById(id: String): VoiceCharacter {
        return ALL.find { it.id == id } ?: ALL.first()
    }
}
