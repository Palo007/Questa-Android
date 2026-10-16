package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.FIXED_NOW_MS
import com.example.data.sync.merge.MergeTestSupport.FUTURE_CEIL
import com.example.data.sync.merge.MergeTestSupport.TODAY
import com.example.data.sync.merge.MergeTestSupport.obj
import com.example.data.sync.merge.MergeTestSupport.stable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * CR-KT-022 rev 3 (622529c): the completion-history backfill stamps `updatedAt = hlcClock.now()`
 * on each task it changes. These tests run the REAL [OptiSnapshotMerger.mergeCollection] over a
 * backfill-stamped task and a PWA copy that was also edited, and pin what the merger does today.
 * Each name says whether the outcome is intended or a known residual risk
 * (fix-plans/shared/CR-KT-022-rev3.draft.md). No production change.
 *
 * Clock: the generator's FIXED_NOW (2026-09-24 12:00 UTC), zone UTC, localToday 20260924.
 */
class CrKt022BackfillMergeTest {

    private val merger = OptiSnapshotMerger()

    private fun ctx(localDev: String, remoteDev: String) = MergeContext(
        localDev = localDev, remoteDev = remoteDev, futureCeil = FUTURE_CEIL,
        nowPhysical = FIXED_NOW_MS, localToday = TODAY, zone = ZoneOffset.UTC,
    )

    private fun backfilled() = obj("""{"h":$BACKFILLED}""")["h"]!!

    private fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, c: MergeContext): JsonObject {
        val out = merger.mergeCollection(
            base?.let { JsonArray(listOf(it)) }, JsonArray(listOf(local)), JsonArray(listOf(remote)),
            null, c, ConflictSink { },
        )
        assertEquals(1, out.size)
        return out[0].jsonObject
    }

    private companion object {
        const val DAY = 86_400_000L
        /** 2026-09-24 10:00 UTC (today). */
        const val DONE_TODAY = FIXED_NOW_MS - 2 * 3_600_000L
        /** 2026-09-23 10:00 UTC (yesterday). */
        const val DONE_YESTERDAY = DONE_TODAY - DAY
        /** The stamp both copies last agreed on. */
        const val U_BASE = FIXED_NOW_MS - 6 * 3_600_000L
        /** The PWA's own later edit. */
        const val U_PWA = FIXED_NOW_MS - 3_600_000L
        /** The backfill's fresh HLC stamp (newest). */
        const val U_STAMP = FIXED_NOW_MS - 60_000L

        /** Two completion points the backfill restored from the local event_logs. */
        val BACKFILLED = """[{"date":${DONE_YESTERDAY - DAY},"value":1,"completed":true},{"date":$DONE_YESTERDAY,"value":1,"completed":true}]"""
    }

    // (1) todo, both changed --------------------------------------------------------------------

    @Test fun todo_bothChanged_stampedLocalWinsWholeTaskLww_backfilledHistoryKept_intended() {
        val base = obj("""{"id":"t1","type":"todo","title":"Write","updatedAt":$U_BASE,"history":[]}""")
        val phone = obj("""{"id":"t1","type":"todo","title":"Write","updatedAt":$U_STAMP,"history":$BACKFILLED}""")
        val pwa = obj("""{"id":"t1","type":"todo","title":"Write report","updatedAt":$U_PWA,"history":[]}""")

        val w = merge(base, phone, pwa, ctx("phone", "pwa"))

        assertEquals(stable(phone), stable(w))
        assertEquals(stable(backfilled()), stable(w["history"]))
        // Normal LWW cost (rev-3 draft): the concurrent PWA title edit loses.
        assertEquals("\"Write\"", w["title"].toString())
    }

    // (2) daily, same event day -> rule 3 -------------------------------------------------------

    @Test fun daily_sameEventDay_rule3KeepsStampedLocal_backfilledHistoryKept_intended() {
        val base = obj("""{"id":"d1","type":"daily","title":"Run","done":true,"doneAt":$DONE_TODAY,"updatedAt":$U_BASE,"history":[]}""")
        val phone = obj("""{"id":"d1","type":"daily","title":"Run","done":true,"doneAt":$DONE_TODAY,"updatedAt":$U_STAMP,"history":$BACKFILLED}""")
        val pwa = obj("""{"id":"d1","type":"daily","title":"Run 5k","done":true,"doneAt":$DONE_TODAY,"updatedAt":$U_PWA,"history":[]}""")

        val w = merge(base, phone, pwa, ctx("phone", "pwa"))

        assertEquals(stable(phone), stable(w))
        assertEquals(stable(backfilled()), stable(w["history"]))
    }

    // (3) daily, PWA has a later event day -> rule 1 --------------------------------------------

    @Test fun daily_pwaLaterEventDay_rule1PwaWins_backfilledHistoryLost_knownRisk() {
        val base = obj("""{"id":"d1","type":"daily","title":"Run","done":true,"doneAt":$DONE_YESTERDAY,"updatedAt":$U_BASE,"history":[]}""")
        // The backfill does not touch doneAt/missedOn, so the phone's event day stays yesterday.
        val phone = obj("""{"id":"d1","type":"daily","title":"Run","done":true,"doneAt":$DONE_YESTERDAY,"updatedAt":$U_STAMP,"history":$BACKFILLED}""")
        val pwa = obj("""{"id":"d1","type":"daily","title":"Run","done":true,"doneAt":$DONE_TODAY,"updatedAt":$U_PWA,"history":[]}""")

        val w = merge(base, phone, pwa, ctx("phone", "pwa"))

        // Rule 1 decides before updatedAt: the newer stamp does not help.
        assertEquals(stable(pwa), stable(w))
        assertEquals("[]", stable(w["history"]))
    }

    // (4) echo: stale-cron reset copy on the phone, stamped by the backfill ---------------------

    /** The PWA's ticked copy, already uploaded (= the PWA's base). */
    private val pwaDone = obj("""{"id":"d1","type":"daily","title":"Run","done":true,"doneAt":$DONE_TODAY,"updatedAt":$U_PWA,"history":[]}""")

    /** The phone's stale-cron reset copy: done=false, doneAt today, same RAW updatedAt as [pwaDone]. */
    private val phoneReset = obj("""{"id":"d1","type":"daily","title":"Run","done":false,"doneAt":$DONE_TODAY,"updatedAt":$U_PWA,"history":[]}""")

    @Test fun echo_unstampedResetCopy_guard2KeepsPwaTick_intendedControl() {
        // Same reset copy with backfilled points but WITHOUT the stamp (pre-622529c behaviour).
        val unstamped = Js.assign(phoneReset, "history" to backfilled())
        val c = ctx("pwa", "phone")
        assertTrue(merger.isCronEchoReset(pwaDone, unstamped, c))

        // PWA-side merge: local = PWA tick (unchanged vs base), remote = phone echo.
        val w = merge(pwaDone, pwaDone, unstamped, c)

        assertEquals(stable(pwaDone), stable(w))
        assertEquals("true", w["done"].toString())
    }

    @Test fun echo_stampedResetCopy_noLongerEcho_pwaTickReverted_knownRisk() {
        val stamped = Js.assign(phoneReset, "history" to backfilled(), "updatedAt" to JsonPrimitive(U_STAMP))
        val c = ctx("pwa", "phone")
        // The stamp breaks the RAW updatedAt equality, so the echo guard no longer matches.
        assertFalse(merger.isCronEchoReset(pwaDone, stamped, c))

        // PWA-side merge: GUARD 1 sees the newer remote stamp and takes the phone copy.
        val w = merge(pwaDone, pwaDone, stamped, c)

        assertEquals(stable(stamped), stable(w))
        assertEquals("false", w["done"].toString())
    }
}
