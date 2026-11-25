package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Android TextToSpeech এবং AudioFocus পরিচালনার ক্লাস।
 * বড় উত্তর এবং স্ট্রিমিং উত্তর বাক্যে ভাগ করে queue-তে চালায় এবং বাংলা/ইংরেজি অ্যাক্সেন্ট ঠিক করে।
 */
class TtsManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _missingBengaliVoice = MutableStateFlow(false)
    val missingBengaliVoice: StateFlow<Boolean> = _missingBengaliVoice.asStateFlow()

    private var currentFinalUtteranceId: String? = null
    private var lastCompletedUtteranceId: String? = null
    private var onCompletedCallback: (() -> Unit)? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var speechRate: Float = 1.0f
    private var speechPitch: Float = 1.0f

    // স্ট্রিমিং TTS সেশন স্টেট (বাক্য শেষ হলেই সাথে সাথে queue-তে যুক্ত করে)
    private val streamingLock = Any()
    private val streamingChunker = StreamingSentenceChunker()
    private var isStreamingSession = false
    private var streamingHasQueuedAny = false
    private var streamingCheckedBengali = false
    private var streamingBengaliMissing = false
    private var streamingLastUtteranceId: String? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // কল বা অন্য কোনো মিডিয়া অ্যাপ অডিও ফোকাস নিলে সাথে সাথে TTS থামানো হয়
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            stop()
        }
    }

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        lastCompletedUtteranceId = utteranceId
                        if (utteranceId != null && utteranceId == currentFinalUtteranceId) {
                            handlePlaybackFinished(invokeCallback = true)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        lastCompletedUtteranceId = utteranceId
                        if (utteranceId != null && utteranceId == currentFinalUtteranceId) {
                            handlePlaybackFinished(invokeCallback = false)
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        lastCompletedUtteranceId = utteranceId
                        if (utteranceId != null && utteranceId == currentFinalUtteranceId) {
                            handlePlaybackFinished(invokeCallback = false)
                        }
                    }
                })
            }
        }
    }

    fun updateVoiceParams(rate: Float, pitch: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        speechPitch = pitch.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)
    }

    fun previewVoice(
        text: String,
        rate: Float,
        pitch: Float,
        onDone: (() -> Unit)? = null
    ) {
        if (!isInitialized) {
            onDone?.invoke()
            return
        }
        stop(invokeCallback = false)
        requestAudioFocus()
        val r = rate.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(r)
        tts?.setPitch(p)
        val utteranceId = "preview_" + UUID.randomUUID().toString()
        currentFinalUtteranceId = utteranceId
        onCompletedCallback = onDone
        _isSpeaking.value = true
        _speakingMessageId.value = "preview"
        val isBengali = TextSanitizer.containsBengali(text)
        val locale = if (isBengali) Locale("bn", "BD") else Locale.US
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun dismissMissingBengaliDialog() {
        _missingBengaliVoice.value = false
    }

    fun beginStreamingSession(
        messageId: String?,
        onCompleted: (() -> Unit)? = null
    ) {
        stop(invokeCallback = false)
        synchronized(streamingLock) {
            streamingChunker.reset()
            isStreamingSession = true
            streamingHasQueuedAny = false
            streamingCheckedBengali = false
            streamingBengaliMissing = false
            streamingLastUtteranceId = null
            lastCompletedUtteranceId = null
            currentFinalUtteranceId = null
            onCompletedCallback = onCompleted
            _speakingMessageId.value = messageId
        }
    }

    fun enqueueStreamingDelta(delta: String) {
        if (!isInitialized || delta.isEmpty()) return
        val sentences = synchronized(streamingLock) {
            if (!isStreamingSession || streamingBengaliMissing) return
            streamingChunker.append(delta)
        }
        sentences.forEach { enqueueSentenceSegments(it) }
    }

    fun finishStreamingSession(fallbackFullText: String? = null) {
        val tail: String?
        val hadAny: Boolean
        synchronized(streamingLock) {
            if (!isStreamingSession) {
                if (!fallbackFullText.isNullOrBlank()) {
                    speak(_speakingMessageId.value, fallbackFullText, onCompletedCallback)
                }
                return
            }
            tail = streamingChunker.flush()
            hadAny = streamingHasQueuedAny
        }

        if (!tail.isNullOrBlank()) {
            enqueueSentenceSegments(tail)
        } else if (!hadAny && !fallbackFullText.isNullOrBlank()) {
            val cb = onCompletedCallback
            val msgId = _speakingMessageId.value
            synchronized(streamingLock) { isStreamingSession = false }
            speak(msgId, fallbackFullText, cb)
            return
        }

        val finalId: String?
        val alreadyDone: Boolean
        val anythingQueued: Boolean
        synchronized(streamingLock) {
            isStreamingSession = false
            finalId = streamingLastUtteranceId
            anythingQueued = streamingHasQueuedAny
            currentFinalUtteranceId = finalId
            alreadyDone = finalId != null && lastCompletedUtteranceId == finalId
        }

        if (!anythingQueued || alreadyDone) {
            handlePlaybackFinished(invokeCallback = true)
        }
    }

    private fun enqueueSentenceSegments(rawSentence: String) {
        if (!isInitialized || rawSentence.isBlank()) return
        val codeNotice = if (TextSanitizer.containsBengali(rawSentence)) {
            context.getString(R.string.tts_code_spoken)
        } else {
            "Code block shown."
        }
        val cleanText = TextSanitizer.cleanForSpeech(rawSentence, codeNotice)
        val segments = TextSanitizer.splitIntoLanguageSegments(cleanText)
        if (segments.isEmpty()) return

        synchronized(streamingLock) {
            if (streamingBengaliMissing) return
            if (!streamingCheckedBengali && segments.any { it.locale.language == "bn" }) {
                streamingCheckedBengali = true
                val availability = tts?.isLanguageAvailable(Locale("bn", "BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                    streamingBengaliMissing = true
                    _missingBengaliVoice.value = true
                    return
                }
            }

            if (!streamingHasQueuedAny) {
                requestAudioFocus()
                _isSpeaking.value = true
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(speechPitch)
            }

            segments.forEach { segment ->
                val utteranceId = UUID.randomUUID().toString()
                val queueMode = if (!streamingHasQueuedAny) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                streamingHasQueuedAny = true
                streamingLastUtteranceId = utteranceId
                tts?.language = segment.locale
                tts?.speak(segment.text, queueMode, null, utteranceId)
            }
        }
    }

    fun speak(
        messageId: String?,
        rawText: String,
        onCompleted: (() -> Unit)? = null
    ) {
        if (!isInitialized || rawText.isBlank()) {
            onCompleted?.invoke()
            return
        }

        val codeNotice = if (TextSanitizer.containsBengali(rawText)) {
            context.getString(R.string.tts_code_spoken)
        } else {
            "Code block shown."
        }

        val cleanText = TextSanitizer.cleanForSpeech(rawText, codeNotice)
        val segments = TextSanitizer.splitIntoLanguageSegments(cleanText)
        if (segments.isEmpty()) {
            onCompleted?.invoke()
            return
        }

        // বাংলা টেক্সট থাকলে বাংলা ভয়েস ডেটা আছে কিনা নিরাপদে যাচাই করা (ক্র্যাশ এড়ানো)
        if (segments.any { it.locale.language == "bn" }) {
            val availability = tts?.isLanguageAvailable(Locale("bn", "BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                _missingBengaliVoice.value = true
                onCompleted?.invoke()
                return
            }
        }

        stop(invokeCallback = false)
        requestAudioFocus()

        onCompletedCallback = onCompleted
        _speakingMessageId.value = messageId
        _isSpeaking.value = true

        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)

        segments.forEachIndexed { index, segment ->
            val utteranceId = UUID.randomUUID().toString()
            if (index == segments.lastIndex) {
                currentFinalUtteranceId = utteranceId
            }
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.language = segment.locale
            tts?.speak(segment.text, queueMode, null, utteranceId)
        }
    }

    fun stop(invokeCallback: Boolean = false) {
        synchronized(streamingLock) {
            isStreamingSession = false
            streamingChunker.reset()
            streamingHasQueuedAny = false
            streamingLastUtteranceId = null
        }
        currentFinalUtteranceId = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        handlePlaybackFinished(invokeCallback = invokeCallback)
    }

    private fun handlePlaybackFinished(invokeCallback: Boolean) {
        _isSpeaking.value = false
        _speakingMessageId.value = null
        abandonAudioFocus()
        val cb = onCompletedCallback
        onCompletedCallback = null
        if (invokeCallback) {
            cb?.invoke()
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    fun release() {
        stop(invokeCallback = false)
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        isInitialized = false
    }
}
