package com.example.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

enum class WakeWordStatus {
    STOPPED,
    LISTENING,
    COOLDOWN,
    MISSING_AAR,
    MISSING_MODEL,
    NO_PERMISSION,
    ERROR
}

data class WakeWordEngineState(
    val status: WakeWordStatus = WakeWordStatus.STOPPED,
    val rmsLevel: Float = 0f,
    val activeKeyword: String = "Hey Jarvis",
    val threshold: Float = 0.5f,
    val audioSessionId: Int = -1,
    val errorDetail: String? = null,
    val isLibraryPresent: Boolean = false,
    val isModelPresent: Boolean = false
)

data class WakeWordDetectionEvent(
    val keyword: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSimulated: Boolean = false
)

/**
 * Wake Word ইঞ্জিনের বিমূর্ত ইন্টারফেস — যাতে ভবিষ্যতে sherpa-onnx বাদে অন্য ইঞ্জিনও সহজে যুক্ত করা যায়।
 */
interface WakeWordEngine {
    val state: StateFlow<WakeWordEngineState>
    val events: Flow<WakeWordDetectionEvent>
    val recentDetections: StateFlow<List<WakeWordDetectionEvent>>

    fun refreshDiagnostics()
    fun updateConfig(keywordPhrase: String, threshold: Float)
    fun start(monitorAudioEvenIfModelMissing: Boolean = false)
    fun stop()
    fun simulateTrigger()
    fun release()
}

/**
 * sherpa-onnx ভিত্তিক অফলাইন Wake Word ইঞ্জিন (প্রথম ইমপ্লিমেন্টেশন)।
 * - 16kHz মনো AudioRecord ব্যবহার করে, অডিও কখনো ডিস্কে বা নেটওয়ার্কে যায় না।
 * - লাইব্রেরি (AAR) বা মডেল ফাইল না থাকলে ক্র্যাশ না করে স্পষ্ট কারণ জানায়।
 * - ডিটেক্ট হওয়ার পর ভুল ট্রিগার কমাতে ২ সেকেন্ড cooldown প্রয়োগ করে।
 */
class SherpaOnnxWakeWordEngine(private val appContext: Context) : WakeWordEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        const val COOLDOWN_MS = 2000L
        const val ASSETS_KWS_DIR = "sherpa-onnx-kws"
        private const val KWS_CLASS_NAME = "com.k2fsa.sherpa.onnx.KeywordSpotter"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private var keywordSpotterObj: Any? = null
    private var onlineStreamObj: Any? = null
    private var lastTriggerTimeMs: Long = 0L

    private val _state = MutableStateFlow(WakeWordEngineState())
    override val state: StateFlow<WakeWordEngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WakeWordDetectionEvent>(extraBufferCapacity = 8)
    override val events: Flow<WakeWordDetectionEvent> = _events.asSharedFlow()

    private val _recentDetections = MutableStateFlow<List<WakeWordDetectionEvent>>(emptyList())
    override val recentDetections: StateFlow<List<WakeWordDetectionEvent>> = _recentDetections.asStateFlow()

    init {
        refreshDiagnostics()
    }

    override fun refreshDiagnostics() {
        val libPresent = isSherpaClassAvailable()
        val modelPresent = hasKwsAssetsModel()
        _state.update {
            it.copy(
                isLibraryPresent = libPresent,
                isModelPresent = modelPresent
            )
        }
    }

    override fun updateConfig(keywordPhrase: String, threshold: Float) {
        val cleanPhrase = keywordPhrase.trim().ifBlank { "Hey Jarvis" }
        val clampedThreshold = threshold.coerceIn(0.1f, 0.95f)
        val changed = cleanPhrase != _state.value.activeKeyword || clampedThreshold != _state.value.threshold
        _state.update { it.copy(activeKeyword = cleanPhrase, threshold = clampedThreshold) }

        if (changed && _state.value.status == WakeWordStatus.LISTENING) {
            // কনফিগ বদলালে ও লিসেনিং চালু থাকলে নতুন কীবোর্ড ফাইল আপডেট করে পুনরায় শুরু
            val wasMonitorMode = !(_state.value.isLibraryPresent && _state.value.isModelPresent)
            stop()
            start(monitorAudioEvenIfModelMissing = wasMonitorMode)
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(monitorAudioEvenIfModelMissing: Boolean) {
        if (captureJob?.isActive == true) return

        refreshDiagnostics()
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            _state.update { it.copy(status = WakeWordStatus.NO_PERMISSION, rmsLevel = 0f) }
            return
        }

        val libPresent = _state.value.isLibraryPresent
        val modelPresent = _state.value.isModelPresent

        // ব্যাকগ্রাউন্ড সার্ভিসে লাইব্রেরি বা মডেল না থাকলে অকারণে মাইক ধরে রাখবে না (ব্যাটারি ও প্রাইভেসি রক্ষা)
        if (!libPresent && !monitorAudioEvenIfModelMissing) {
            _state.update { it.copy(status = WakeWordStatus.MISSING_AAR, rmsLevel = 0f) }
            return
        }
        if (libPresent && !modelPresent && !monitorAudioEvenIfModelMissing) {
            _state.update { it.copy(status = WakeWordStatus.MISSING_MODEL, rmsLevel = 0f) }
            return
        }

        val spotterReady = if (libPresent && modelPresent) initializeSherpaSpotter() else false

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 5)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    status = WakeWordStatus.ERROR,
                    errorDetail = e.localizedMessage
                )
            }
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            _state.update {
                it.copy(
                    status = WakeWordStatus.ERROR,
                    errorDetail = "AudioRecord শুরু করা যায়নি"
                )
            }
            return
        }

        audioRecord = record
        val sessionId = record.audioSessionId

        val initialStatus = when {
            spotterReady -> WakeWordStatus.LISTENING
            !libPresent -> WakeWordStatus.MISSING_AAR
            !modelPresent -> WakeWordStatus.MISSING_MODEL
            else -> WakeWordStatus.ERROR
        }

        _state.update {
            it.copy(
                status = initialStatus,
                audioSessionId = sessionId,
                errorDetail = null
            )
        }

        captureJob = scope.launch {
            try {
                record.startRecording()
                // ১৬kHz মনোতে প্রতি ১০০ms-এ ১৬০০ স্যাম্পল পড়া (কম CPU ব্যবহার)
                val shortBuffer = ShortArray(1600)
                val floatBuffer = FloatArray(1600)

                while (isActive) {
                    val read = record.read(shortBuffer, 0, shortBuffer.size)
                    if (read > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until read) {
                            val norm = shortBuffer[i] / 32768.0f
                            floatBuffer[i] = norm
                            sumSquares += norm * norm
                        }
                        val rms = sqrt(sumSquares / read).toFloat().coerceIn(0f, 1f)
                        val scaledRms = (rms * 4.5f).coerceIn(0f, 1f)

                        val now = System.currentTimeMillis()
                        val inCooldown = now - lastTriggerTimeMs < COOLDOWN_MS

                        if (spotterReady && !inCooldown) {
                            val detected = feedSherpaAndCheckKeyword(floatBuffer, read)
                            if (detected != null) {
                                lastTriggerTimeMs = now
                                emitDetection(detected, isSimulated = false)
                            }
                        }

                        val displayStatus = when {
                            spotterReady && inCooldown -> WakeWordStatus.COOLDOWN
                            spotterReady -> WakeWordStatus.LISTENING
                            !libPresent -> WakeWordStatus.MISSING_AAR
                            !modelPresent -> WakeWordStatus.MISSING_MODEL
                            else -> WakeWordStatus.LISTENING
                        }

                        _state.update {
                            it.copy(
                                status = displayStatus,
                                rmsLevel = scaledRms
                            )
                        }
                    } else {
                        delay(20L)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = WakeWordStatus.ERROR,
                        rmsLevel = 0f,
                        errorDetail = e.localizedMessage
                    )
                }
            } finally {
                releaseAudioRecordOnly()
            }
        }
    }

    override fun stop() {
        captureJob?.cancel()
        captureJob = null
        releaseAudioRecordOnly()
        releaseSherpaSpotter()
        _state.update {
            it.copy(
                status = WakeWordStatus.STOPPED,
                rmsLevel = 0f,
                audioSessionId = -1
            )
        }
    }

    override fun simulateTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < COOLDOWN_MS) return
        lastTriggerTimeMs = now
        val phrase = _state.value.activeKeyword
        val event = WakeWordDetectionEvent(keyword = phrase, isSimulated = true)
        _recentDetections.update { (listOf(event) + it).take(20) }
        val emittedSync = _events.tryEmit(event)
        _state.update { it.copy(status = WakeWordStatus.COOLDOWN) }
        scope.launch {
            if (!emittedSync) {
                _events.emit(event)
            }
            delay(COOLDOWN_MS)
            if (captureJob?.isActive == true) {
                val nextStatus = when {
                    keywordSpotterObj != null -> WakeWordStatus.LISTENING
                    !_state.value.isLibraryPresent -> WakeWordStatus.MISSING_AAR
                    !_state.value.isModelPresent -> WakeWordStatus.MISSING_MODEL
                    else -> WakeWordStatus.LISTENING
                }
                _state.update { it.copy(status = nextStatus) }
            }
        }
    }

    private suspend fun emitDetection(keyword: String, isSimulated: Boolean) {
        val event = WakeWordDetectionEvent(keyword = keyword, isSimulated = isSimulated)
        _recentDetections.update { (listOf(event) + it).take(20) }
        _events.emit(event)
    }

    private fun releaseAudioRecordOnly() {
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    private fun isSherpaClassAvailable(): Boolean {
        return runCatching { Class.forName(KWS_CLASS_NAME) }.isSuccess
    }

    private fun hasKwsAssetsModel(): Boolean {
        return runCatching {
            val files = appContext.assets.list(ASSETS_KWS_DIR).orEmpty()
            files.any { it == "tokens.txt" } && files.any { it.endsWith(".onnx") }
        }.getOrDefault(false)
    }

    /**
     * রিফ্লেকশন ভিত্তিক sherpa-onnx KeywordSpotter ইনিশিয়ালাইজেশন:
     * লাইব্রেরি ও assets/sherpa-onnx-kws/ ফোল্ডার থাকলে স্বয়ংক্রিয়ভাবে চালু হবে,
     * আর না থাকলে নিরাপদে false ফেরত দিয়ে বাকি অ্যাপ সচল রাখবে।
     */
    private fun initializeSherpaSpotter(): Boolean {
        return runCatching {
            val modelDir = copyAssetsDirToFilesIfNeeded() ?: return@runCatching false
            val files = modelDir.listFiles().orEmpty().associateBy { it.name }
            val tokensFile = files["tokens.txt"] ?: return@runCatching false
            val encoderFile = files.values.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
                ?: return@runCatching false
            val decoderFile = files.values.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
                ?: return@runCatching false
            val joinerFile = files.values.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }
                ?: return@runCatching false

            // ইউজারের কাস্টম wake word-এর জন্য ডাইনামিক keywords.txt তৈরি (যদি না থাকে বা বদলায়)
            val keywordsFile = File(modelDir, "custom_keywords.txt")
            val activePhrase = _state.value.activeKeyword.uppercase()
            val threshold = _state.value.threshold
            // sherpa-onnx ফরম্যাট: প্রতিটি শব্দ স্পেস বা BPE টোকেন অনুযায়ী — fallback হিসেবে মূল keywords.txt কপি বা কাস্টম লেখা
            val bundledKeywords = files["keywords.txt"]
            val keywordsPath = if (bundledKeywords != null && bundledKeywords.exists()) {
                bundledKeywords.absolutePath
            } else {
                keywordsFile.writeText("$activePhrase :${String.format(java.util.Locale.US, "%.2f", threshold)}\n")
                keywordsFile.absolutePath
            }

            val transducerClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
            val transducer = transducerClass.getDeclaredConstructor().newInstance().apply {
                transducerClass.getMethod("setEncoder", String::class.java).invoke(this, encoderFile.absolutePath)
                transducerClass.getMethod("setDecoder", String::class.java).invoke(this, decoderFile.absolutePath)
                transducerClass.getMethod("setJoiner", String::class.java).invoke(this, joinerFile.absolutePath)
            }

            val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance().apply {
                modelConfigClass.getMethod("setTransducer", transducerClass).invoke(this, transducer)
                modelConfigClass.getMethod("setTokens", String::class.java).invoke(this, tokensFile.absolutePath)
                modelConfigClass.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(this, 1)
            }

            val kwsConfigClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterConfig")
            val kwsConfig = kwsConfigClass.getDeclaredConstructor().newInstance().apply {
                kwsConfigClass.getMethod("setModelConfig", modelConfigClass).invoke(this, modelConfig)
                kwsConfigClass.getMethod("setKeywordsFile", String::class.java).invoke(this, keywordsPath)
                kwsConfigClass.getMethod("setKeywordsThreshold", Float::class.javaPrimitiveType).invoke(this, threshold)
            }

            val spotterClass = Class.forName(KWS_CLASS_NAME)
            val ctor = spotterClass.constructors.firstOrNull { it.parameterTypes.size == 2 }
                ?: spotterClass.constructors.first()
            val spotter = if (ctor.parameterTypes.size == 2) {
                ctor.newInstance(null, kwsConfig)
            } else {
                ctor.newInstance(kwsConfig)
            }
            val stream = spotterClass.getMethod("createStream").invoke(spotter)
            keywordSpotterObj = spotter
            onlineStreamObj = stream
            true
        }.getOrElse {
            releaseSherpaSpotter()
            false
        }
    }

    private fun feedSherpaAndCheckKeyword(samples: FloatArray, count: Int): String? {
        val spotter = keywordSpotterObj ?: return null
        val stream = onlineStreamObj ?: return null
        return runCatching {
            val slice = if (count == samples.size) samples else samples.copyOf(count)
            val streamClass = stream.javaClass
            val spotterClass = spotter.javaClass

            streamClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(stream, slice, SAMPLE_RATE)

            val isReadyMethod = spotterClass.getMethod("isReady", streamClass)
            val decodeMethod = spotterClass.getMethod("decode", streamClass)
            while (isReadyMethod.invoke(spotter, stream) as Boolean) {
                decodeMethod.invoke(spotter, stream)
            }

            val resultObj = spotterClass.getMethod("getResult", streamClass).invoke(spotter) ?: return@runCatching null
            val keyword = resultObj.javaClass.getMethod("getKeyword").invoke(resultObj) as? String
            if (!keyword.isNullOrBlank()) {
                runCatching { spotterClass.getMethod("resetStream", streamClass).invoke(spotter, stream) }
                keyword
            } else {
                null
            }
        }.getOrNull()
    }

    private fun copyAssetsDirToFilesIfNeeded(): File? {
        val assetFiles = appContext.assets.list(ASSETS_KWS_DIR) ?: return null
        if (assetFiles.isEmpty()) return null
        val outDir = File(appContext.filesDir, ASSETS_KWS_DIR)
        if (!outDir.exists()) outDir.mkdirs()

        for (fileName in assetFiles) {
            val outFile = File(outDir, fileName)
            if (!outFile.exists()) {
                appContext.assets.open("$ASSETS_KWS_DIR/$fileName").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return outDir
    }

    private fun releaseSherpaSpotter() {
        runCatching {
            onlineStreamObj?.let { it.javaClass.getMethod("release").invoke(it) }
        }
        runCatching {
            keywordSpotterObj?.let { it.javaClass.getMethod("release").invoke(it) }
        }
        onlineStreamObj = null
        keywordSpotterObj = null
    }

    override fun release() {
        stop()
        scope.cancel()
    }
}
