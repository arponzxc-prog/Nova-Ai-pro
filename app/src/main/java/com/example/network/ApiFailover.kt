package com.example.network

import com.example.model.ModelConfig
import com.example.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

enum class FailoverReason {
    RATE_LIMIT_OR_QUOTA,
    BILLING_OR_PAYMENT,
    AUTH_FAILED,
    MODEL_NOT_FOUND,
    SERVER_ERROR,
    TIMEOUT,
    NETWORK_ERROR
}

enum class ModelHealthState {
    OK,
    COOLDOWN,
    NO_KEY
}

data class ModelCooldownInfo(
    val untilMs: Long,
    val reason: FailoverReason,
    val shortReason: String
)

data class FailoverCandidate(
    val config: ModelConfig,
    val apiKey: String,
    val provider: AiProvider
)

data class FailoverNotice(
    val fromModelName: String,
    val toModelName: String
)

/**
 * কোন ত্রুটিতে অটো-ফেইলওভার হবে এবং কোন ত্রুটিতে (যেমন 400 malformed request, cancellation)
 * ফেইলওভার হবে না তা নির্ধারণ করে।
 */
object FailoverClassifier {

    private val QUOTA_400_KEYWORDS = listOf(
        "quota", "rate limit", "rate_limit", "billing", "credit", "payment",
        "overloaded", "resource_exhausted", "capacity", "insufficient", "limit exceeded"
    )

    fun classify(error: Throwable): FailoverReason? {
        if (error is CancellationException) return null
        if (error is PartialStreamException) {
            val cause = error.cause ?: return FailoverReason.NETWORK_ERROR
            return classify(cause) ?: FailoverReason.NETWORK_ERROR
        }

        if (error is ApiHttpException) {
            return when (error.statusCode) {
                429 -> FailoverReason.RATE_LIMIT_OR_QUOTA
                402 -> FailoverReason.BILLING_OR_PAYMENT
                401, 403 -> FailoverReason.AUTH_FAILED
                404 -> FailoverReason.MODEL_NOT_FOUND
                408 -> FailoverReason.TIMEOUT
                in 500..599 -> FailoverReason.SERVER_ERROR
                400 -> {
                    val lower = error.responseBody.lowercase()
                    if (QUOTA_400_KEYWORDS.any { lower.contains(it) }) {
                        FailoverReason.RATE_LIMIT_OR_QUOTA
                    } else {
                        null // সাধারণ খারাপ রিকোয়েস্টে অন্য মডেলেও একই ত্রুটি হবে, তাই ফেইলওভার নয়
                    }
                }
                else -> null
            }
        }

        return when (error) {
            is SocketTimeoutException -> FailoverReason.TIMEOUT
            is IOException -> FailoverReason.NETWORK_ERROR
            else -> null
        }
    }

    fun cooldownDurationMs(reason: FailoverReason, error: Throwable): Long {
        val underlying = (error as? PartialStreamException)?.cause ?: error
        return when (reason) {
            FailoverReason.RATE_LIMIT_OR_QUOTA -> {
                val retrySec = (underlying as? ApiHttpException)?.retryAfterSeconds
                if (retrySec != null && retrySec > 0) {
                    (retrySec * 1000L).coerceIn(5_000L, 600_000L)
                } else {
                    60_000L
                }
            }
            FailoverReason.BILLING_OR_PAYMENT,
            FailoverReason.AUTH_FAILED,
            FailoverReason.MODEL_NOT_FOUND -> 600_000L
            FailoverReason.SERVER_ERROR,
            FailoverReason.TIMEOUT,
            FailoverReason.NETWORK_ERROR -> 30_000L
        }
    }
}

/**
 * প্রতিটি মডেলের বর্তমান স্বাস্থ্য ও কুলডাউন সময়সীমা ট্র্যাক করে।
 */
class ModelHealthTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val cooldowns = ConcurrentHashMap<String, ModelCooldownInfo>()
    private val _cooldownSnapshot = MutableStateFlow<Map<String, ModelCooldownInfo>>(emptyMap())
    val cooldownSnapshot: StateFlow<Map<String, ModelCooldownInfo>> = _cooldownSnapshot.asStateFlow()

    fun markFailure(modelId: String, error: Throwable): ModelCooldownInfo? {
        val reason = FailoverClassifier.classify(error) ?: return null
        val durationMs = FailoverClassifier.cooldownDurationMs(reason, error)
        val info = ModelCooldownInfo(
            untilMs = clockMs() + durationMs,
            reason = reason,
            shortReason = ErrorFormatter.shortReason(error)
        )
        cooldowns[modelId] = info
        publishSnapshot()
        return info
    }

    fun isInCooldown(modelId: String): Boolean {
        val entry = cooldowns[modelId] ?: return false
        if (clockMs() >= entry.untilMs) {
            cooldowns.remove(modelId)
            publishSnapshot()
            return false
        }
        return true
    }

    fun remainingCooldownSeconds(modelId: String): Long {
        val entry = cooldowns[modelId] ?: return 0L
        val diff = entry.untilMs - clockMs()
        if (diff <= 0L) {
            cooldowns.remove(modelId)
            publishSnapshot()
            return 0L
        }
        return (diff + 999L) / 1000L
    }

    fun statusFor(modelId: String, hasApiKey: Boolean): Pair<ModelHealthState, Long> {
        if (!hasApiKey) return ModelHealthState.NO_KEY to 0L
        val rem = remainingCooldownSeconds(modelId)
        return if (rem > 0L) ModelHealthState.COOLDOWN to rem else ModelHealthState.OK to 0L
    }

    fun clearCooldown(modelId: String) {
        if (cooldowns.remove(modelId) != null) {
            publishSnapshot()
        }
    }

    fun resetAll() {
        if (cooldowns.isNotEmpty()) {
            cooldowns.clear()
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        val now = clockMs()
        val active = cooldowns.filterValues { it.untilMs > now }
        _cooldownSnapshot.value = active
    }

    companion object {
        val shared = ModelHealthTracker()
    }
}

/**
 * Claude <-> Gemini <-> OpenAI প্রোভাইডার পরিবর্তনের সময় AgentTurnMessage ইতিহাস নিরাপদে রূপান্তর করে।
 * - ভিন্ন প্রোভাইডারের rawParts (যেমন Gemini thought/parts বা Anthropic content blocks) সরিয়ে দেয়।
 * - টেক্সট, toolCalls, toolCallId এবং toolName অক্ষত রাখে।
 */
object CrossProviderHistoryConverter {

    fun convertForProvider(
        messages: List<AgentTurnMessage>,
        targetProvider: ProviderType
    ): List<AgentTurnMessage> {
        return messages.map { msg ->
            if (msg.rawParts != null && msg.rawPartsProvider != targetProvider) {
                msg.copy(rawParts = null, rawPartsProvider = null)
            } else {
                msg
            }
        }
    }
}

/**
 * সক্রিয় মডেল, ব্যাকআপ প্রায়োরিটি তালিকা, API Key ও কুলডাউন বিবেচনায় চেষ্টা করার ক্রম তৈরি করে।
 */
object FailoverCandidateResolver {

    fun buildOrderedConfigs(
        activeModel: ModelConfig?,
        allModels: List<ModelConfig>,
        failoverOrderIds: List<String>,
        autoFailoverEnabled: Boolean,
        requiresVision: Boolean
    ): List<ModelConfig> {
        if (allModels.isEmpty()) return emptyList()

        val byId = allModels.associateBy { it.id }
        val ordered = mutableListOf<ModelConfig>()
        val seen = HashSet<String>()

        fun addIfEligible(cfg: ModelConfig?) {
            if (cfg == null) return
            if (requiresVision && !cfg.supportsVision) return
            if (seen.add(cfg.id)) ordered.add(cfg)
        }

        // সক্রিয় মডেল সবার আগে (যদি ভিশন রিকোয়ারমেন্ট পূরণ করে)
        addIfEligible(activeModel)

        if (autoFailoverEnabled || (requiresVision && activeModel?.supportsVision != true)) {
            // ইউজার-নির্ধারিত অগ্রাধিকার তালিকা অনুযায়ী ব্যাকআপ মডেল
            failoverOrderIds.forEach { id ->
                addIfEligible(byId[id])
            }
            // অবশিষ্ট সব মডেল
            allModels.forEach { cfg ->
                addIfEligible(cfg)
            }
        }

        return ordered
    }

    fun buildCandidates(
        activeModel: ModelConfig?,
        allModels: List<ModelConfig>,
        failoverOrderIds: List<String>,
        autoFailoverEnabled: Boolean,
        requiresVision: Boolean,
        keyResolver: (String) -> String,
        healthTracker: ModelHealthTracker,
        providerFactory: AiProviderFactory,
        skipCooldownModels: Boolean = true
    ): List<FailoverCandidate> {
        val ordered = buildOrderedConfigs(
            activeModel = activeModel,
            allModels = allModels,
            failoverOrderIds = failoverOrderIds,
            autoFailoverEnabled = autoFailoverEnabled,
            requiresVision = requiresVision
        )

        val keyed = ordered.mapNotNull { cfg ->
            val key = keyResolver(cfg.id).trim()
            if (key.isEmpty()) null
            else FailoverCandidate(
                config = cfg,
                apiKey = key,
                provider = providerFactory.getProvider(cfg.providerType)
            )
        }

        if (!skipCooldownModels) return keyed
        return keyed.filterNot { healthTracker.isInCooldown(it.config.id) }
    }
}
