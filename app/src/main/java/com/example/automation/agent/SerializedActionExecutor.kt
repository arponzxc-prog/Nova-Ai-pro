/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.agent.SerializedActionExecutor).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: explicit outcome type (JARVIS returned null for "rejected", "timed out" and "block returned
 * null" alike), cancellation epoch wired to the automation kill switch, no Android dependencies. Modifications © ARPON.
 */
package com.example.automation.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface ActionOutcome<out T> {
    data class Completed<T>(val value: T) : ActionOutcome<T>
    data object TimedOut : ActionOutcome<Nothing>
    /** Cancelled (kill switch / user stop) before or while waiting for the lock. */
    data object Rejected : ActionOutcome<Nothing>
    data class Failed(val errorClass: String) : ActionOutcome<Nothing>
}

/**
 * Guarantees that UI-mutating actions (click, type, scroll, gestures, key presses) never overlap.
 * [cancelAll] invalidates everything queued or running; [startNewTask] opens a new epoch.
 */
object SerializedActionExecutor {
    private val mutex = Mutex()
    private val cancelled = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    val isBusy: Boolean get() = mutex.isLocked
    val isCancelled: Boolean get() = cancelled.get()

    fun cancelAll() {
        cancelled.set(true)
        generation.incrementAndGet()
    }

    /** Starts a fresh epoch; anything queued by an older epoch stays rejected. */
    fun startNewTask(): Long {
        cancelled.set(false)
        return generation.incrementAndGet()
    }

    suspend fun <T> execute(timeoutMs: Long = 15_000L, expectedGeneration: Long? = null, block: suspend () -> T): ActionOutcome<T> {
        val submitted = expectedGeneration ?: generation.get()
        if (cancelled.get() || submitted != generation.get()) return ActionOutcome.Rejected
        return mutex.withLock {
            if (cancelled.get() || submitted != generation.get()) return@withLock ActionOutcome.Rejected
            try {
                // Box the value so a legitimately-null/Unit result is distinguishable from a timeout.
                val boxed = withTimeoutOrNull(timeoutMs) { Box(block()) }
                if (boxed == null) ActionOutcome.TimedOut else ActionOutcome.Completed(boxed.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActionOutcome.Failed(e.javaClass.simpleName) // class only: messages may contain user text
            }
        }
    }

    private class Box<T>(val value: T)
}
