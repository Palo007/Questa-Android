package com.example.data.sync

import com.example.data.local.entities.CharHistoryEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * WP-8.6 review 26 C1: rows that Opti does not key by `id` must still reach Room.
 * charHistory is keyed by `date` (its Room PK); Opti `S.charHistory = subset.charHistory` and
 * `S.devices = cleanDevices(subset.devices)` replace the whole array, so Room must equal the
 * merged array after the apply (same-day losers and dropped devices removed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncApplyKeyedRowsTest : RoundTestBase() {

    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private val dayX = LocalDate.of(2026, 9, 20)
    private val dayY = LocalDate.of(2026, 9, 21)

    private fun row(date: Long, lvl: Int, xp: Int) = buildJsonObject {
        put("date", JsonPrimitive(date)); put("hp", JsonPrimitive(40)); put("maxHp", JsonPrimitive(50))
        put("xp", JsonPrimitive(xp)); put("mp", JsonPrimitive(0)); put("gold", JsonPrimitive(3)); put("lvl", JsonPrimitive(lvl))
    }

    private fun entity(date: Long, lvl: Int, xp: Int) =
        CharHistoryEntity(date = date, hp = 40.0, maxHp = 50.0, xp = xp, mp = 0, gold = 3.0, lvl = lvl)

    @Test fun remoteCharHistoryDay_reachesRoom_afterARound_andIsExported() = runBlocking {
        val d = device(merger = OptiSnapshotMerger())
        d.link()
        val local = d.manager.buildLocalSnapshot()
        val remoteDate = at(dayX, 9)
        val rev = seedRemote(JsonObject(local + ("charHistory" to JsonArray(listOf(row(remoteDate, 2, 15))))))
        d.seedBase(rev, CanonicalJson.canonicalJsonString(local))

        assertEquals(SyncResult.Ok, d.manager.sync())

        val rows = d.repository.getAllCharHistorySync()
        assertEquals(listOf(remoteDate), rows.map { it.date })
        assertEquals(2, rows.single().lvl)
        assertEquals(15, rows.single().xp)

        // round trip: the (tokenized) export built from Room carries the day back through import
        val exported = d.manager.exportSnapshotToFile().getOrThrow()
        val back = SerializationEngine.importBackup(exported).charHistory
        assertEquals(listOf(remoteDate), back.map { it.date })
        assertEquals(2, back.single().lvl)
    }

    @Test fun sameDayLoser_isRemovedFromRoom() = runBlocking {
        val d = device(merger = OptiSnapshotMerger())
        d.link()
        val loser = at(dayX, 8)
        val kept = at(dayY, 8)
        d.repository.insertCharHistory(entity(loser, 1, 10))
        d.repository.insertCharHistory(entity(kept, 1, 20))
        val local = d.manager.buildLocalSnapshot()
        val winner = at(dayX, 20) // same local day as `loser`, higher lvl -> wins the bucket
        val remoteRows = JsonArray(listOf(row(winner, 2, 5)))
        val rev = seedRemote(JsonObject(local + ("charHistory" to remoteRows)))
        d.seedBase(rev, CanonicalJson.canonicalJsonString(local))

        assertEquals(SyncResult.Ok, d.manager.sync())

        assertEquals("Room equals the merged charHistory", setOf(winner, kept), d.repository.getAllCharHistorySync().map { it.date }.toSet())
        val merged = (d.manager.buildLocalSnapshot()["charHistory"] as JsonArray).map { (it.jsonObject["date"] as JsonPrimitive).longOrNull }
        assertEquals(listOf(winner, kept), merged)
    }

    @Test fun deviceMissingFromTheMergedArray_isRemoved() = runBlocking {
        val d = device()
        d.link()
        d.repository.insertDevice(DeviceEntity(id = "keep", name = "A", updatedAt = 5L))
        d.repository.insertDevice(DeviceEntity(id = "drop", name = "B", updatedAt = 6L))
        val local = d.manager.buildLocalSnapshot()
        val onlyKeep = JsonArray((local["devices"] as JsonArray).filter { it.jsonObject["id"] == JsonPrimitive("keep") })
        d.manager.applyRemoteAsLocal(JsonObject(local + ("devices" to onlyKeep)))
        assertEquals(listOf("keep"), d.repository.getAllDevicesSync().map { it.id })
        assertTrue(d.repository.getAllTombstonesSync().isEmpty())
    }
}
