package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * Stage 4 / T4.5 -- plain-JVM tests against [EventSyncEngine]'s pure functions (no Android/Room/
 * network dependency needed; same convention as [CharacterMergeAndLastCronTest]/[AnViewsMetricsTest]).
 * Covers the "pure-logic units" portion of T4.5's required scenario list: [EventSyncEngine.evtUploadable]
 * filter branches, [EventSyncEngine.evtMonthKey]'s UTC-regardless-of-default-timezone edge,
 * [EventSyncEngine.evtParseFileName] accept/reject cases, and [EventSyncEngine.evtIncomingFilter]'s
 * dedup/reject branches (existing-uid dedup + in-batch "seen" dedup, which also back scenario 3 --
 * "foreign event dedup" -- at the pure-logic level; see `EventSyncPushPullTest` for the Room-backed,
 * full-pull-cycle version of the same rule).
 *
 * See `plans/sync_format_spec.md` §3 addendum for the rule numbers referenced in comments below.
 */
class EventSyncEngineTest {

    private fun event(
        uid: String? = "dev-a-1-x1",
        dev: String? = "dev-a",
        kind: String = "complete",
        ts: Long = 5000L,
        synthetic: Boolean = false
    ) = EventLogEntity(timestamp = ts, uid = uid, dev = dev, kind = kind, synthetic = synthetic)

    // -----------------------------------------------------------------------------------------
    // Rule 1: evtUploadable
    // -----------------------------------------------------------------------------------------

    @Test
    fun evtUploadable_includesOwnDeviceRealEventNewerThanWatermark() {
        val e = event(ts = 6000L)
        val result = EventSyncEngine.evtUploadable(listOf(e), myDev = "dev-a", sinceTs = 5000L)
        assertEquals(listOf(e), result)
    }

    @Test
    fun evtUploadable_excludesMissingUid() {
        val e = event(uid = null, ts = 6000L)
        assertTrue(EventSyncEngine.evtUploadable(listOf(e), "dev-a", 0L).isEmpty())
    }

    @Test
    fun evtUploadable_excludesOtherDevice() {
        val e = event(dev = "dev-b", ts = 6000L)
        assertTrue(EventSyncEngine.evtUploadable(listOf(e), "dev-a", 0L).isEmpty())
    }

    @Test
    fun evtUploadable_excludesSynthetic() {
        val e = event(synthetic = true, ts = 6000L)
        assertTrue(EventSyncEngine.evtUploadable(listOf(e), "dev-a", 0L).isEmpty())
    }

    @Test
    fun evtUploadable_excludesLifecycleKind() {
        val e = event(kind = "lifecycle", ts = 6000L)
        assertTrue(EventSyncEngine.evtUploadable(listOf(e), "dev-a", 0L).isEmpty())
    }

    @Test
    fun evtUploadable_excludesTimestampAtOrBelowWatermark() {
        val atWatermark = event(ts = 5000L)
        val belowWatermark = event(ts = 4000L)
        val result = EventSyncEngine.evtUploadable(listOf(atWatermark, belowWatermark), "dev-a", sinceTs = 5000L)
        // strictly-greater-than: ts == sinceTs must NOT be re-included (would re-push the exact
        // event that just advanced the watermark).
        assertTrue(result.isEmpty())
    }

    // NOTE: evtUploadable has no age-limit branch in the production code -- EVENT_AGE_LIMIT_MS
    // only gates the PULL/ingest side (evtIncomingFilter, evtMonthOlderThan), never the push/
    // evtUploadable side (a device always re-uploads its own recent events regardless of age; the
    // ONLY push-side cutoff is the `sinceTs` watermark exercised above). See this task's final
    // report "open questions" for this discrepancy against the task brief's phrasing.

    // -----------------------------------------------------------------------------------------
    // Rule 3: evtMonthKey -- UTC month-boundary edge, independent of JVM default timezone
    // -----------------------------------------------------------------------------------------

    private lateinit var savedDefaultTz: TimeZone

    @Before
    fun saveDefaultTimeZone() {
        savedDefaultTz = TimeZone.getDefault()
    }

    @After
    fun restoreDefaultTimeZone() {
        TimeZone.setDefault(savedDefaultTz)
    }

    @Test
    fun evtMonthKey_utcMonthBoundary_isCorrectRegardlessOfJvmDefaultTimeZone() {
        // Pacific/Kiritimati is UTC+14 -- about as far from UTC as a real IANA zone gets, chosen so
        // a bug that accidentally used the JVM default zone instead of an explicit UTC Calendar
        // would shift the boundary by a whole calendar day and flip these two assertions.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))

        val julyFirstUtcMidnight = 1782864000000L // 2026-07-01T00:00:00.000Z
        val juneLastMsUtc = julyFirstUtcMidnight - 1 // 2026-06-30T23:59:59.999Z

        assertEquals("202607", EventSyncEngine.evtMonthKey(julyFirstUtcMidnight))
        assertEquals("202606", EventSyncEngine.evtMonthKey(juneLastMsUtc))
    }

    @Test
    fun evtMonthKey_utcMonthBoundary_matchesUnderUtcDefaultToo() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val julyFirstUtcMidnight = 1782864000000L
        assertEquals("202607", EventSyncEngine.evtMonthKey(julyFirstUtcMidnight))
        assertEquals("202606", EventSyncEngine.evtMonthKey(julyFirstUtcMidnight - 1))
    }

    // -----------------------------------------------------------------------------------------
    // Rule 9: evtParseFileName -- accept/reject
    // -----------------------------------------------------------------------------------------

    @Test
    fun evtParseFileName_accepts_simpleDeviceIdAndMonth() {
        assertEquals("dev1" to "202607", EventSyncEngine.evtParseFileName("dev1-202607.json"))
    }

    @Test
    fun evtParseFileName_accepts_deviceIdContainingHyphensAndDigits_greedyRightmostMonth() {
        // Rule 9: greedy `.+` guarantees the month is the RIGHTMOST 6-digit group even if the
        // deviceId itself contains digits/hyphens.
        assertEquals("my-device-99" to "202607", EventSyncEngine.evtParseFileName("my-device-99-202607.json"))
    }

    @Test
    fun evtParseFileName_rejects_noHyphenOrMonthAtAll() {
        assertNull(EventSyncEngine.evtParseFileName("invalid.json"))
    }

    @Test
    fun evtParseFileName_rejects_nonDigitMonth() {
        assertNull(EventSyncEngine.evtParseFileName("device-abcdef.json"))
    }

    @Test
    fun evtParseFileName_rejects_tooFewMonthDigits() {
        assertNull(EventSyncEngine.evtParseFileName("device-20260.json"))
    }

    @Test
    fun evtParseFileName_rejects_tooManyMonthDigits_singleHyphen() {
        assertNull(EventSyncEngine.evtParseFileName("device-2026078.json"))
    }

    @Test
    fun evtParseFileName_rejects_wrongExtension() {
        assertNull(EventSyncEngine.evtParseFileName("device-202607.json.bak"))
    }

    // -----------------------------------------------------------------------------------------
    // Rule 25/16-19/27: evtIncomingFilter -- reject branches + two-layer uid dedup + id stripping
    // -----------------------------------------------------------------------------------------

    private fun record(
        uid: String = "dev-b-1-x1",
        dev: String = "dev-b",
        ts: Long = 5000L,
        kind: String = "complete",
        synthetic: JsonPrimitive? = null,
        withId: String? = null
    ): JsonObject = buildJsonObject {
        put("uid", JsonPrimitive(uid))
        put("dev", JsonPrimitive(dev))
        put("ts", JsonPrimitive(ts))
        put("kind", JsonPrimitive(kind))
        synthetic?.let { put("synthetic", it) }
        withId?.let { put("id", JsonPrimitive(it)) }
    }

    @Test
    fun evtIncomingFilter_acceptsForeignRealRecentEvent_andStripsId() {
        val r = record(withId = "123")
        val out = EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), myDev = "dev-a", nowMs = 5000L, ageLimitMs = 1_000_000L)
        assertEquals(1, out.size)
        assertFalse("surviving records must never carry the local Room 'id' field (rule 27)", out[0].containsKey("id"))
    }

    @Test
    fun evtIncomingFilter_rejectsMissingUid() {
        val r = buildJsonObject { put("dev", JsonPrimitive("dev-b")); put("ts", JsonPrimitive(5000L)); put("kind", JsonPrimitive("complete")) }
        assertTrue(EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", 5000L, 1_000_000L).isEmpty())
    }

    @Test
    fun evtIncomingFilter_rejectsNonNumericTs() {
        val r = buildJsonObject {
            put("uid", JsonPrimitive("dev-b-1-x1")); put("dev", JsonPrimitive("dev-b"))
            put("ts", JsonPrimitive("not-a-number")); put("kind", JsonPrimitive("complete"))
        }
        assertTrue(EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", 5000L, 1_000_000L).isEmpty())
    }

    @Test
    fun evtIncomingFilter_rejectsSynthetic_booleanForm() {
        val r = record(synthetic = JsonPrimitive(true))
        assertTrue(EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", 5000L, 1_000_000L).isEmpty())
    }

    @Test
    fun evtIncomingFilter_rejectsSynthetic_legacyIntForm() {
        // sync.js legacy encodes booleans as 0/1 ints in some paths -- evtIncomingFilter's
        // `p.booleanOrNull ?: (p.intOrNull == 1)` fallback must treat int 1 as truthy too.
        val r = record(synthetic = JsonPrimitive(1))
        assertTrue(EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", 5000L, 1_000_000L).isEmpty())
    }

    @Test
    fun evtIncomingFilter_rejectsLifecycleKind() {
        val r = record(kind = "lifecycle")
        assertTrue(EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", 5000L, 1_000_000L).isEmpty())
    }

    @Test
    fun evtIncomingFilter_rejectsOwnDevice() {
        val r = record(dev = "dev-a")
        assertTrue(EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), myDev = "dev-a", nowMs = 5000L, ageLimitMs = 1_000_000L).isEmpty())
    }

    @Test
    fun evtIncomingFilter_rejectsOlderThanAgeLimit() {
        val r = record(ts = 1000L)
        val out = EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", nowMs = 1_000_000L, ageLimitMs = 500_000L)
        assertTrue(out.isEmpty())
    }

    @Test
    fun evtIncomingFilter_acceptsExactlyAtAgeLimitBoundary() {
        // nowMs - ts > ageLimitMs is the reject condition -- EQUAL must survive.
        val r = record(ts = 500_000L)
        val out = EventSyncEngine.evtIncomingFilter(listOf(r), emptySet(), "dev-a", nowMs = 1_000_000L, ageLimitMs = 500_000L)
        assertEquals(1, out.size)
    }

    @Test
    fun evtIncomingFilter_dedupsAgainstExistingUidSet() {
        val r = record(uid = "dup-uid")
        val out = EventSyncEngine.evtIncomingFilter(listOf(r), existingUidSet = setOf("dup-uid"), myDev = "dev-a", nowMs = 5000L, ageLimitMs = 1_000_000L)
        assertTrue("a uid already present in Room for this month must never be re-inserted (rule 16-18)", out.isEmpty())
    }

    @Test
    fun evtIncomingFilter_dedupsInBatchDuplicates_keepsOnlyFirst() {
        // Two records in the SAME downloaded file sharing a uid -- only the first survives.
        val first = record(uid = "same-uid", kind = "complete")
        val second = record(uid = "same-uid", kind = "habitTap")
        val out = EventSyncEngine.evtIncomingFilter(listOf(first, second), emptySet(), "dev-a", nowMs = 5000L, ageLimitMs = 1_000_000L)
        assertEquals(1, out.size)
        assertEquals("complete", out[0]["kind"]?.jsonPrimitive?.contentOrNull)
    }

    // -----------------------------------------------------------------------------------------
    // buildEventPushJson / parseIncomingEventJson -- shape sanity (feeds the above; not itself one
    // of T4.5's named scenarios, but load-bearing for them, so covered defensively)
    // -----------------------------------------------------------------------------------------

    @Test
    fun buildEventPushJson_neverEmitsLocalRoomId() {
        val e = EventLogEntity(id = 42L, timestamp = 1000L, uid = "u1", dev = "dev-a", kind = "complete")
        val json = EventSyncEngine.buildEventPushJson(e)
        assertFalse(json.containsKey("id"))
        assertEquals("u1", json["uid"]?.jsonPrimitive?.contentOrNull)
        assertEquals("dev-a", json["dev"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun parseIncomingEventJson_roundTripsKnownFields_andStashesExtrasInPayloadJson() {
        val obj = buildJsonObject {
            put("ts", JsonPrimitive(9000L))
            put("uid", JsonPrimitive("dev-b-1-x1"))
            put("dev", JsonPrimitive("dev-b"))
            put("kind", JsonPrimitive("complete"))
            put("taskTitle", JsonPrimitive("Do the thing"))
            put("someUnknownExtra", JsonPrimitive("keepme"))
        }
        val entity = EventSyncEngine.parseIncomingEventJson(obj)
        assertEquals(9000L, entity.timestamp)
        assertEquals("dev-b-1-x1", entity.uid)
        assertEquals("dev-b", entity.dev)
        assertEquals("Do the thing", entity.taskTitle)
        assertFalse("an incoming record must never be marked synthetic", entity.synthetic)
        assertNotNull("unmodeled fields must be preserved via payloadJson", entity.payloadJson)
        assertTrue(entity.payloadJson!!.contains("someUnknownExtra"))
    }
}
