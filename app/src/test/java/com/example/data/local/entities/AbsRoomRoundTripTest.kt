package com.example.data.local.entities

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.sync.SerializationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.22-E review 43 (F1/F2): odd `char.abs` inputs must survive parse -> Room write -> Room read
 * -> serialize. Opti keeps a number Infinity in memory, `JSON.stringify` writes it as `null`, and
 * after a reload `_num(null)` is 0 (sync.js:1672). So Room stores 0 for a non-finite value, while
 * the wire keeps the original bytes when the map is unchanged, and writes `null` when it changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbsRoomRoundTripTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun charJson(xpRaw: String) =
        """{"name":"A","lvl":1,"xp":0,"gold":0,"mp":0,"updatedAt":5,"abs":{"d":{"ua":1,"xp":$xpRaw,"gold":2,"mp":3}}}"""

    @Test
    fun `each odd xp input survives a Room write and read, and the wire keeps the original bytes`() = runBlocking {
        // raw -> (in-memory value right after parse, value after the Room round trip)
        val vectors = listOf(
            "\"Infinity\"" to (Double.POSITIVE_INFINITY to 0.0),
            "\"+Infinity\"" to (Double.POSITIVE_INFINITY to 0.0),
            "1e999" to (Double.POSITIVE_INFINITY to 0.0),
            "[\"Infinity\"]" to (Double.POSITIVE_INFINITY to 0.0),
            "\"-Infinity\"" to (0.0 to 0.0),
            "\"NaN\"" to (0.0 to 0.0),
            "\"\"" to (0.0 to 0.0),
            "\"abc\"" to (0.0 to 0.0),
            "\" 5 \"" to (5.0 to 5.0),
            "\"0x10\"" to (16.0 to 16.0),
            "\"true\"" to (0.0 to 0.0),
            "true" to (1.0 to 1.0),
            "[1,2]" to (0.0 to 0.0),
            "2.5" to (2.5 to 2.5),
        )
        for ((raw, expected) in vectors) {
            val obj = Json.parseToJsonElement(charJson(raw)).jsonObject
            val parsed = SerializationEngine.parseCharacterEntity(obj)
            assertEquals("parse xp=$raw", expected.first, parsed.abs!!["d"]!!.xp, 0.0)

            db.characterDao().insertCharacter(parsed) // must not throw (F1)
            val back = db.characterDao().getCharacterSync()!!
            assertEquals("room xp=$raw", expected.second, back.abs!!["d"]!!.xp, 0.0)
            assertEquals("room gold=$raw", 2.0, back.abs!!["d"]!!.gold, 0.0)

            // Unchanged map: both before and after the reload, the original abs bytes go out. The
            // one exception is a NUMBER Infinity (1e999): the extraJson copy already holds it as
            // null, which is exactly JSON.stringify's output in Opti.
            val wireAbs = if (raw == "1e999") Json.parseToJsonElement(charJson("null")).jsonObject["abs"] else obj["abs"]
            assertEquals("wire before reload xp=$raw", wireAbs, SerializationEngine.buildCharWireJson(parsed)["abs"])
            assertEquals("wire after reload xp=$raw", wireAbs, SerializationEngine.buildCharWireJson(back)["abs"])
        }
    }

    @Test
    fun `a changed abs map carrying Infinity serializes the number as null, like JSON stringify`() {
        val obj = Json.parseToJsonElement(charJson("1")).jsonObject
        val parsed = SerializationEngine.parseCharacterEntity(obj)
        val changed = parsed.copy(abs = mapOf("d" to CharAbsEntry(ua = 7, xp = Double.POSITIVE_INFINITY, gold = 2.0, mp = 3.0)))
        val wire = SerializationEngine.buildCharWireJson(changed)["abs"]!!.jsonObject["d"]!!.jsonObject
        assertEquals(JsonNull, wire["xp"])
        assertEquals("7", wire["ua"].toString())
    }

    @Test
    fun `updating a char whose abs holds Infinity in memory does not throw and stores 0`() = runBlocking {
        val parsed = SerializationEngine.parseCharacterEntity(Json.parseToJsonElement(charJson("\"Infinity\"")).jsonObject)
        db.characterDao().insertCharacter(parsed.copy(abs = null))
        db.characterDao().updateCharacter(parsed.copy(gold = 9.0))
        assertEquals(0.0, db.characterDao().getCharacterSync()!!.abs!!["d"]!!.xp, 0.0)
    }
}
