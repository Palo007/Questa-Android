package com.example.data.sync

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * WP-8.1 -- PX-11. Port of Opti's transient retry rules (`sync.js` at 7d5d4f3):
 * - [LADDER_MS] = `SYNC_TRANSIENT_RETRY_DELAYS_MS` (:49), the 429/5xx backoff, one entry per retry.
 * - [RETRY_AFTER_MAX_MS] = `SYNC_RETRY_AFTER_MAX_MS` (:53), the longest Retry-After the round
 *   will sleep on; longer waits abort with an honest message (:2493-2496).
 * - [parseRetryAfterMs] = `_retryAfterMs` (:346-362): delta-seconds or an HTTP-date, clamped to
 *   one day. Opti returns 0 for "absent"; Kotlin returns null (card shape `retryAfterMs: Long?`).
 *
 * The round (WP-8.6) calls [decide] after a failed attempt; this WP only provides the policy.
 */
object RetryPolicy {
    val LADDER_MS = listOf(1_000L, 5_000L, 25_000L)
    const val RETRY_AFTER_MAX_MS = 60_000L

    /** Opti clamps a parsed Retry-After to one day (`Math.min(secs, 86400)`). */
    const val RETRY_AFTER_CLAMP_MS = 86_400_000L

    private val DIGITS = Regex("^\\d+$")

    fun parseRetryAfterMs(headerValue: String?, nowMs: Long = System.currentTimeMillis()): Long? { // clock-ok:transport-timer
        if (headerValue == null) return null
        val s = headerValue.trim()
        if (s.isEmpty()) return null
        if (DIGITS.matches(s)) {
            val secs = s.toLongOrNull() ?: return RETRY_AFTER_CLAMP_MS // more digits than a Long: clamp
            return minOf(secs, 86_400L) * 1000L
        }
        val whenMs = try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            return null
        }
        val delta = whenMs - nowMs
        return if (delta > 0) minOf(delta, RETRY_AFTER_CLAMP_MS) else null
    }

    /** Opti: `e.status === 429 || e.status >= 500`. */
    fun isTransient(status: Int): Boolean = status == 429 || status >= 500

    sealed interface Decision {
        /** Sleep [waitMs], then run attempt number `attempt + 1`. */
        data class Retry(val waitMs: Long) : Decision
        /** Stop now with this user-facing message (Retry-After longer than [RETRY_AFTER_MAX_MS]). */
        data class Abort(val message: String) : Decision
        /** Not retryable, or the ladder is used up: surface the original error. */
        object GiveUp : Decision
    }

    /**
     * Opti `_syncNowAttempt` catch block (:2484-2500). [attempt] is the 0-based count of retries
     * already made (`transientRetryCount`).
     */
    fun decide(status: Int, retryAfterMs: Long?, attempt: Int): Decision {
        if (!isTransient(status) || attempt >= LADDER_MS.size) return Decision.GiveUp
        val asked = retryAfterMs ?: 0L
        if (asked > RETRY_AFTER_MAX_MS) {
            val secs = (asked + 999L) / 1000L
            return Decision.Abort("Dropbox is rate limiting this app — try again in ${secs}s")
        }
        return Decision.Retry(maxOf(LADDER_MS[attempt], asked))
    }
}
