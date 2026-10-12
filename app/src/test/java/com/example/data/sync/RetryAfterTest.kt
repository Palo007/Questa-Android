package com.example.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** WP-8.1 -- PX-11: Opti `_retryAfterMs` (sync.js :346-362) and the round's retry rule (:2484-2500). */
class RetryAfterTest {

    @Test fun constantsMatchOpti() {
        assertEquals(listOf(1_000L, 5_000L, 25_000L), RetryPolicy.LADDER_MS)
        assertEquals(60_000L, RetryPolicy.RETRY_AFTER_MAX_MS)
    }

    @Test fun absentOrUnparseable_isNull() {
        assertNull(RetryPolicy.parseRetryAfterMs(null))
        assertNull(RetryPolicy.parseRetryAfterMs(""))
        assertNull(RetryPolicy.parseRetryAfterMs("   "))
        assertNull(RetryPolicy.parseRetryAfterMs("-5"))
        assertNull(RetryPolicy.parseRetryAfterMs("1.5"))
        assertNull(RetryPolicy.parseRetryAfterMs("soon"))
    }

    @Test fun deltaSeconds() {
        assertEquals(0L, RetryPolicy.parseRetryAfterMs("0"))
        assertEquals(3_000L, RetryPolicy.parseRetryAfterMs(" 3 "))
        assertEquals(300_000L, RetryPolicy.parseRetryAfterMs("300"))
    }

    @Test fun clampedToOneDay_notToTheSleepCap() {
        assertEquals(86_400_000L, RetryPolicy.parseRetryAfterMs("999999"))
        assertEquals(86_400_000L, RetryPolicy.parseRetryAfterMs("99999999999999999999999"))
    }

    @Test fun httpDate() {
        val now = 1_790_000_000_000L
        val fmt = DateTimeFormatter.RFC_1123_DATE_TIME
        val in90s = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now + 90_000L), ZoneOffset.UTC).format(fmt)
        assertEquals(90_000L, RetryPolicy.parseRetryAfterMs(in90s, now))
        val past = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now - 1_000L), ZoneOffset.UTC).format(fmt)
        assertNull(RetryPolicy.parseRetryAfterMs(past, now))
    }

    @Test fun decide_transientOnly() {
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(400, null, 0))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(409, null, 0))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(0, null, 0))
        assertEquals(RetryPolicy.Decision.Retry(1_000L), RetryPolicy.decide(500, null, 0))
        assertEquals(RetryPolicy.Decision.Retry(5_000L), RetryPolicy.decide(503, null, 1))
        assertEquals(RetryPolicy.Decision.Retry(25_000L), RetryPolicy.decide(429, null, 2))
        assertEquals(RetryPolicy.Decision.GiveUp, RetryPolicy.decide(429, null, 3))
    }

    @Test fun decide_retryAfterWinsWhenLonger() {
        assertEquals(RetryPolicy.Decision.Retry(3_000L), RetryPolicy.decide(429, 3_000L, 0))
        assertEquals(RetryPolicy.Decision.Retry(5_000L), RetryPolicy.decide(429, 3_000L, 1))
        assertEquals(RetryPolicy.Decision.Retry(60_000L), RetryPolicy.decide(429, 60_000L, 0))
    }

    @Test fun decide_tooLongRetryAfter_abortsWithOptiMessage() {
        assertEquals(
            RetryPolicy.Decision.Abort("Dropbox is rate limiting this app — try again in 300s"),
            RetryPolicy.decide(429, 300_000L, 0)
        )
        assertEquals(
            RetryPolicy.Decision.Abort("Dropbox is rate limiting this app — try again in 61s"),
            RetryPolicy.decide(429, 60_001L, 0)
        )
    }
}
