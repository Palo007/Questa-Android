package com.example.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * P15 / KT-57: an event's `synthetic` flag (`sy` in a backup) survives the round trip exactly
 * as Opti keeps it (Opti 7d5d4f3).
 *
 * Opti rules this test pins:
 *  - app.js:7124 (`_detokenizeEvents`): `sy` -> `e.synthetic = (v===null) ? null : !!v`, so
 *    `sy:0` becomes `synthetic:false`, `sy:null` stays `null`, and an absent `sy` stays absent.
 *  - app.js:1445-1461 (`reparentEventsForImport`): an event this device owns keeps every field,
 *    `synthetic` included. (Kotlin keeps each event's own uid/dev on import -- see
 *    `SectionImporter`'s [DEFERRED_OPEN_QUESTION] -- so it follows this "owned" branch.)
 *  - app.js:7064-7069 (`_tokenizeEvents`): every own key is walked, and a present `synthetic`
 *    is written as `sy = (v==null) ? null : (v ? 1 : 0)`. An absent key writes nothing.
 *  - sync.js:3074-3077 (`evtOwnMonthRecords`): the uploaded record is the stored record with
 *    only `id` removed, so a stored `synthetic:false` is uploaded as `synthetic:false`.
 *
 * Opti never writes `synthetic:false` on a live event itself, so events without the key must
 * come out byte-unchanged (no churn).
 */
class EventSyntheticFlagRoundTripTest {

    private val envelope: JsonObject = Json.parseToJsonElement(
        """
        {"E":[
          {"t":1000,"uid":"dA-a","dev":"dA","k":0,"sy":0},
          {"t":2000,"uid":"dA-b","dev":"dA","k":0,"sy":1},
          {"t":3000,"uid":"dA-c","dev":"dA","k":0},
          {"t":4000,"uid":"dA-d","dev":"dA","k":0,"sy":null}
        ],"K":["complete"]}
        """.trimIndent()
    ).jsonObject

    /** Import (detokenize + Room entity) then export (entity -> event JSON -> tokenize). */
    private fun roundTrip(): List<String> {
        val entities = SerializationEngine.detokenizeEvents(envelope).map { SerializationEngine.parseEventEntity(it) }
        val tokenized = SerializationEngine.tokenizeEvents(entities.map { SerializationEngine.buildEventJson(it) })
        assertEquals(listOf("complete"), tokenized.K)
        return tokenized.E.map { it.toString() }
    }

    @Test
    fun syFalse_isKeptAsZero() {
        // Opti app.js:7124 -> synthetic:false; app.js:7069 -> sy:0.
        assertEquals("""{"t":1000,"uid":"dA-a","dev":"dA","k":0,"sy":0}""", roundTrip()[0])
    }

    @Test
    fun syTrue_isKeptAsOne() {
        // Opti app.js:7124 -> synthetic:true; app.js:7069 -> sy:1.
        assertEquals("""{"t":2000,"uid":"dA-b","dev":"dA","k":0,"sy":1}""", roundTrip()[1])
    }

    @Test
    fun noSy_staysWithoutSy_byteUnchanged() {
        // Opti: no `synthetic` key -> the tokenizer loop writes no `sy` (app.js:7053-7069).
        assertEquals("""{"t":3000,"uid":"dA-c","dev":"dA","k":0}""", roundTrip()[2])
    }

    @Test
    fun syNull_isKeptAsNull() {
        // Opti app.js:7124 keeps null; app.js:7069 writes sy:null.
        assertEquals("""{"t":4000,"uid":"dA-d","dev":"dA","k":0,"sy":null}""", roundTrip()[3])
    }

    @Test
    fun syFalse_isUploadedAsSyntheticFalse_andNoSyIsNotAdded() {
        // Opti sync.js:3077: the upload record is the stored record minus `id`.
        val entities = SerializationEngine.detokenizeEvents(envelope).map { SerializationEngine.parseEventEntity(it) }
        val a = EventSyncEngine.buildEventPushJson(entities[0])
        assertEquals(JsonPrimitive(false), a["synthetic"])
        assertFalse(entities[0].synthetic)
        val c = EventSyncEngine.buildEventPushJson(entities[2])
        assertFalse("an event without the flag must not gain one", c.containsKey("synthetic"))
        assertEquals("""{"ts":3000,"uid":"dA-c","dev":"dA","kind":"complete"}""", c.toString())
    }

    @Test
    fun syTrue_staysTyped_soUploadFilterStillExcludesIt() {
        val entities = SerializationEngine.detokenizeEvents(envelope).map { SerializationEngine.parseEventEntity(it) }
        assertTrue(entities[1].synthetic)
        assertFalse(entities[0].synthetic)
        assertFalse(entities[2].synthetic)
        assertFalse(entities[3].synthetic)
    }

    @Test
    fun ingestedSyntheticFalse_isKeptForBackupExport() {
        // Sync ingest path: an incoming record's `synthetic:false` stays with the record.
        val rec = Json.parseToJsonElement("""{"ts":5000,"uid":"dB-e","dev":"dB","kind":"complete","synthetic":false}""").jsonObject
        val e = EventSyncEngine.parseIncomingEventJson(rec)
        val tok = SerializationEngine.tokenizeEvents(listOf(SerializationEngine.buildEventJson(e)))
        assertEquals("""{"t":5000,"uid":"dB-e","dev":"dB","k":0,"sy":0}""", tok.E[0].toString())
    }
}
