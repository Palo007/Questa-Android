package com.example.data.sync

import com.example.data.local.entities.CharAbsEntry
import com.example.data.local.entities.CharacterEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.3 (PX-23, SY-15): the wire/backup `char` never carries Kotlin-only keys (Opti's
 * syncSubset copies `S.char` verbatim, sync.js:810), and `char.abs` is the device map.
 */
class CharWireKeysTest {

    private val polluted = CharacterEntity(
        id = 1, name = "A", needsDeathAcknowledge = true, lastCron = "20260924", dayStart = 4, updatedAt = 5,
        extraJson = """{"name":"A","id":1,"needsDeathAcknowledge":true,"lastCron":20260923,"dayStart":4,"future":"x"}"""
    )

    @Test
    fun wireCharHasNoKotlinOnlyKeysAndKeepsUnknownOnes() {
        val wire = SerializationEngine.buildCharWireJson(polluted)
        assertTrue(wire.keys.intersect(SerializationEngine.CHAR_LOCAL_ONLY_KEYS).isEmpty())
        assertEquals("unknown PWA keys must pass through", "\"x\"", wire.getValue("future").toString())
    }

    @Test
    fun localWireStateAndBackupUseTheWireChar() {
        val wire = LocalWireState.fromEntities(
            polluted, emptyList(), emptyList(), emptyList(), emptyList(), 20260924,
            kotlinx.serialization.json.JsonArray(emptyList()), emptyList(), emptyList(),
            kotlinx.serialization.json.JsonArray(emptyList()), null, emptyList(), emptyList()
        )
        assertTrue(wire.char.keys.intersect(SerializationEngine.CHAR_LOCAL_ONLY_KEYS).isEmpty())
        val env = buildBackupFile(FullLocalState(wire), emptyList(), null, false, 1L, "d")
        val fm = env.getValue("FM").jsonObject.entries.associate { (k, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content to k }
        val s = SerializationEngine.detokenizeDeep(env.getValue("S"), fm) as JsonObject
        assertTrue(s.getValue("char").jsonObject.keys.intersect(SerializationEngine.CHAR_LOCAL_ONLY_KEYS).isEmpty())
    }

    @Test
    fun absIsDeviceKeyedMapAndRoundTrips() {
        val src = Json.parseToJsonElement(
            """{"name":"A","face":"x","cls":"Warrior","lvl":1,"xp":0,"hp":50,"maxHp":50,"mp":0,"gold":0,"updatedAt":1,
               "abs":{"dev-a":{"ua":10,"xp":3,"gold":1.5,"mp":0},"dev-b":{"ua":11,"xp":0,"gold":0,"mp":2}}}"""
        ).jsonObject
        val c = SerializationEngine.parseCharacterEntity(src)
        assertEquals(CharAbsEntry(ua = 10, xp = 3.0, gold = 1.5, mp = 0.0), c.abs?.get("dev-a"))
        assertEquals(CanonicalJson.stringify(src), CanonicalJson.stringify(SerializationEngine.buildCharWireJson(c)))
        val bumped = c.copy(abs = c.abs!! + ("dev-c" to CharAbsEntry(ua = 12, xp = 1.0)))
        assertEquals(
            """{"dev-a":{"ua":10,"xp":3,"gold":1.5,"mp":0},"dev-b":{"ua":11,"xp":0,"gold":0,"mp":2},"dev-c":{"ua":12,"xp":1,"gold":0,"mp":0}}""",
            CanonicalJson.stringify(SerializationEngine.buildCharWireJson(bumped).getValue("abs"))
        )
    }
}
