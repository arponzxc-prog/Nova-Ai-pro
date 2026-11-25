package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.example.R
import com.example.model.SpeechLanguageOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class SpeechInputState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val rmsNormalized: Float = 0f,
    @StringRes val errorMessageRes: Int? = null
)

/**
 * Android SpeechRecognizer-এর র‍্যাপার ক্লাস।
 * SpeechRecognizer অবশ্যই Main থ্রেডে চালাতে হয়, তাই সব কল Main Looper-এ নিরাপদভাবে করা হয়েছে।
 */
class SpeechInputManager(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow(SpeechInputState())
    val state: StateFlow<SpeechInputState> = _state.asStateFlow()

    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((Int) -> Unit)? = null

    fun startListening(
        languageOption: SpeechLanguageOption,
        onFinalResult: (String) -> Unit,
        onError: (Int) -> Unit = {}
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _state.update {
                    it.copy(
                        isListening = false,
                        errorMessageRes = R.string.speech_error_no_service
                    )
                }
                onError(R.string.speech_error_no_service)
                return@post
            }

            onFinalResultCallback = onFinalResult
            onErrorCallback = onError

            // আগের রিকগনাইজার থাকলে পরিষ্কার করা
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }

            val languageTag = when (languageOption) {
                SpeechLanguageOption.BENGALI -> "bn-BD"
                SpeechLanguageOption.ENGLISH -> "en-US"
                SpeechLanguageOption.AUTO -> {
                    val sysLang = Locale.getDefault().language
                    if (sysLang == "bn") "bn-BD" else "en-US"
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            _state.value = SpeechInputState(
                isListening = true,
                partialText = "",
                rmsNormalized = 0f,
                errorMessageRes = null
            )

            try {
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        isListening = false,
                        errorMessageRes = R.string.speech_error_no_service
                    )
                }
                onError(R.string.speech_error_no_service)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
            _state.update { it.copy(isListening = false, rmsNormalized = 0f) }
        }
    }

    fun cancel() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            _state.update { it.copy(isListening = false, rmsNormalized = 0f, partialText = "") }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessageRes = null) }
    }

    fun release() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.update { it.copy(isListening = true, errorMessageRes = null) }
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // মাইকের ভলিউম (-2 থেকে 10 dB) 0f..1f রেঞ্জে রূপান্তর করে অর্ব/ওয়েভ অ্যানিমেশনের জন্য পাঠানো হয়
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(rmsNormalized = normalized) }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.update { it.copy(isListening = false, rmsNormalized = 0f) }
        }

        override fun onError(error: Int) {
            val errorRes = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.speech_error_no_match
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.speech_error_network
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.speech_error_busy
                SpeechRecognizer.ERROR_AUDIO -> R.string.speech_error_audio
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.mic_permission_denied_polite
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_CLIENT -> R.string.speech_error_unknown
                else -> R.string.speech_error_unknown
            }
            _state.update {
                it.copy(
                    isListening = false,
                    rmsNormalized = 0f,
                    errorMessageRes = errorRes
                )
            }
            onErrorCallback?.invoke(errorRes)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val finalText = matches?.firstOrNull()?.trim().orEmpty()
            _state.update {
                it.copy(
                    isListening = false,
                    rmsNormalized = 0f,
                    partialText = finalText
                )
            }
            if (finalText.isNotBlank()) {
                onFinalResultCallback?.invoke(finalText)
            } else {
                _state.update { it.copy(errorMessageRes = R.string.speech_error_no_match) }
                onErrorCallback?.invoke(R.string.speech_error_no_match)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotBlank()) {
                _state.update { it.copy(partialText = partial) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
