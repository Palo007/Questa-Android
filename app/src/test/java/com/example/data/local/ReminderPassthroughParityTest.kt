package com.example.data.local

import com.example.data.sync.SerializationEngine
import com.example.data.sync.Wp89TestBase
import com.example.reminders.FIXED_NOW_MS
import com.example.reminders.RecordingNotifier
import com.example.reminders.ReminderDbTestBase
import com.example.reminders.ReminderEditorRules
import com.example.reminders.ReminderEngine
import com.example.reminders.UTC
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4 / KT-38 (DEC-3 "match Opti"): a reminder element that does not decode as a [ReminderSpec]
 * ("junk", {"time":5}, null, ...) must be KEPT, unchanged and at its index, exactly as Opti
 * 7d5d4f3 keeps it. It must never be scheduled and never crash.
 *
 * Every expected value below comes from reading Opti 7d5d4f3 app.js / sync.js (line refs in each
 * test). Opti has three paths:
 *  (a) load/import: `normalizeTaskReminders` (app.js:2069-2072) only turns a NON-array into `[]`;
 *      an array is kept as it is, bad elements too;
 *  (b) fire: `checkReminders` loops `t.reminders.forEach(r => ...)` (:2177-2178) and sets
 *      `r.lastFiredKey = reminderFireKey(r, now)` (:2198) on the fired element only, in place;
 *      a bad element fails `reminderAppliesToday` (`if (!r.enabled) return false`, :2083) so it
 *      is neither due (:2105-2106) nor missed (:2118-2119), and is left alone;
 *  (c) editor save: `saveTask` writes `EDIT.reminders = [r]` (:6084) or `[]` (:6086); `r.id` and
 *      `r.lastFiredKey` come from `_prevRem = EDIT.reminders[0] || null` (:6061-6066), then the
 *      burn rule runs when `_prevRem.time !== r.time` (:6072-6075).
 *
 * Fixed clock: FIXED_NOW_MS = 2026-09-24T12:00Z, zone UTC, so Opti `reminderFireKey(r, now)`
 * (app.js:2075-2076) is "20260924-" + r.time.
 *
 * Failing-first (code before the P4 fix): `Converters.toReminderSpecList` used
 * `arr.mapNotNull { try decode catch null }`, so every bad element was dropped on the first
 * decode. Then (a)/(b)/live see a shorter list, the scheduler index shifts, and the editor's
 * `reminders[0]` is the next VALID element instead of Opti's raw `reminders[0]`.
 */
internal object Kt38 {
    const val ALL7 = "[true,true,true,true,true,true,true]"

    /**
     * A valid reminder in the exact shape and key order Opti's editor writes for a habit:
     * kind "weekly" (app.js:6059), keys id, enabled, kind, time, lastFiredKey (:6061-6067),
     * then days (:6079-6080).
     */
    fun v(time: String, lastFiredKey: String = "", id: String = "v1"): String =
        """{"id":"$id","enabled":true,"kind":"weekly","time":"$time","lastFiredKey":"$lastFiredKey","days":$ALL7}"""

    fun taskJson(reminders: String): JsonObject = Json.parseToJsonElement(
        """{"id":"t1","type":"habit","title":"H","createdAt":1,"updatedAt":1000,"reminders":$reminders}"""
    ).jsonObject

    fun arr(s: String): JsonArray = Json.parseToJsonElement(s) as JsonArray
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderPassthroughParityTest : ReminderDbTestBase() {

    private val c = Converters()

    // ---------------------------------------------------------------------------------------
    // (a) import / export
    // ---------------------------------------------------------------------------------------

    /**
     * (a) app.js:2069-2072: the input is an array, so Opti leaves it untouched; the export
     * (JSON.stringify of S) therefore carries all 4 elements, same order.
     * FAILS before the fix: decode drops "junk", {"time":5} and null, so the typed list is
     * `[V]`, differs from the original, and SerializationEngine's keepOrTyped
     * (SerializationEngine.kt:741-743) writes the 1-element typed list.
     */
    @Test
    fun import_export_keepsBadElements_matchesOptiNormalize() {
        val input = """[${Kt38.v("09:00")},"junk",{"time":5},null]"""
        val t = SerializationEngine.parseTaskEntity(Kt38.taskJson(input))
        assertEquals(4, t.reminders?.size)
        assertEquals(Kt38.arr(input), SerializationEngine.buildTaskJson(t)["reminders"])
        // Byte round-trip through the Room converter pair (the column content).
        assertEquals(input, c.fromReminderSpecList(c.toReminderSpecList(input)))
    }

    /** (a) through the real Room column (no schema change: only the JSON text in `reminders`). */
    @Test
    fun roomColumn_keepsBadElements_matchesOptiNormalize() = runBlocking {
        val input = """[${Kt38.v("09:00")},"junk",{"time":5},null]"""
        dao.insertTask(SerializationEngine.parseTaskEntity(Kt38.taskJson(input)))
        val back = dao.getTaskByIdSync("t1")!!
        assertEquals(input, c.fromReminderSpecList(back.reminders))
        assertEquals(Kt38.arr(input), SerializationEngine.buildTaskJson(back)["reminders"])
    }

    // ---------------------------------------------------------------------------------------
    // (b) fire in place
    // ---------------------------------------------------------------------------------------

    /**
     * (b) app.js:2177-2178 loop + :2198 in-place write. At 12:00 V is due (:2105-2110) and gets
     * lastFiredKey "20260924-12:00" (:2076); "junk" and {"time":5} have no truthy `enabled`, so
     * :2083 returns false for both due (:2106) and missed (:2119): no notification, no change,
     * same index. No `null` here: Opti `isReminderDue(t, null)` throws (null.enabled, :2083 via
     * :2178), Momus 28; the null case is in [badElements_neverScheduled_keepOptiRawIndex].
     * FAILS before the fix: after decode the list is `[V]`, so the rewritten column is `[V']`.
     */
    @Test
    fun markFired_keepsBadElementsInPlace_matchesOptiFire() = runBlocking {
        val input = """[${Kt38.v("12:00")},"junk",{"time":5}]"""
        val expected = """[${Kt38.v("12:00", lastFiredKey = "20260924-12:00")},"junk",{"time":5}]"""
        dao.insertTask(SerializationEngine.parseTaskEntity(Kt38.taskJson(input)))
        val n = RecordingNotifier()
        assertEquals(1, checker(n).checkReminders(FIXED_NOW_MS))
        assertEquals(1, n.posted.size)
        val after = dao.getTaskByIdSync("t1")!!
        assertEquals(expected, c.fromReminderSpecList(after.reminders))
        assertEquals(Kt38.arr(expected), SerializationEngine.buildTaskJson(after)["reminders"])
        assertEquals(1000L, after.updatedAt) // D10: a fire does not bump updatedAt
    }

    /**
     * Scheduler: Opti inbox meta (sync.js:691-692) walks the RAW array with its index and skips
     * `!r || !r.enabled || !r.time`, so for ["junk", {"time":5}, V, null] the only item is key
     * "t1#2". Kotlin must keep the raw index (ReminderEngine.items, key = taskId#idx) and must
     * not crash on null (Kotlin is stricter than Opti here: Opti checkReminders would throw).
     * FAILS before the fix: decode leaves `[V]`, so the key is "t1#0" (index drift, KT-38).
     */
    @Test
    fun badElements_neverScheduled_keepOptiRawIndex() = runBlocking {
        val input = """["junk",{"time":5},${Kt38.v("12:00")},null]"""
        val t = SerializationEngine.parseTaskEntity(Kt38.taskJson(input))
        assertEquals(listOf("t1#2"), ReminderEngine.items(listOf(t)).map { it.key })
        dao.insertTask(t)
        val n = RecordingNotifier()
        assertEquals(1, checker(n).checkReminders(FIXED_NOW_MS))
        assertEquals(1, n.posted.size)
        assertEquals(
            """["junk",{"time":5},${Kt38.v("12:00", lastFiredKey = "20260924-12:00")},null]""",
            c.fromReminderSpecList(dao.getTaskByIdSync("t1")!!.reminders)
        )
    }

    // ---------------------------------------------------------------------------------------
    // (c) editor: [r] or [] (guards; Opti drops every element after index 0, bad or valid)
    // ---------------------------------------------------------------------------------------

    private fun draft(time: String, enabled: Boolean = true) =
        ReminderEditorRules.Draft(enabled, time, "2026-09-24", List(7) { true })

    private fun save(prev: String, d: ReminderEditorRules.Draft): String? = c.fromReminderSpecList(
        ReminderEditorRules.save(c.toReminderSpecList(prev), d, "habit", null, FIXED_NOW_MS, UTC) { "NEW" }
    )

    /**
     * GUARD (passes before and after the fix). [V, "junk"], time moved to 13:00:
     * `_prevRem` = V (:6061) -> id "v1" (:6062); time changed -> burn rule (:6072), "13:00" is
     * not < "12:00" -> lastFiredKey "" (:6074); days = temp days (:6080); `EDIT.reminders = [r]`
     * (:6084) drops "junk". Disabled -> `[]` (:6086).
     */
    @Test
    fun editorSave_dropsAllButR_likeOpti() {
        val prev = """[${Kt38.v("09:00")},"junk"]"""
        assertEquals("[${Kt38.v("13:00")}]", save(prev, draft("13:00")))
        assertEquals("[]", save(prev, draft("13:00", enabled = false)))
    }

    /**
     * A bad element at index 0 is Opti's `_prevRem` (:6061, any truthy value):
     *  - "junk": `"junk".id` is undefined, so JSON.stringify writes no `id` key (:6062);
     *    `_prevRem.time` (undefined) !== "09:00" -> burn (:6072); "09:00" < "12:00" ->
     *    lastFiredKey "20260924-09:00" (:6074, :2076).
     *  - {"id":"x","time":5,"lastFiredKey":"k0"}: id "x" (:6062); 5 !== "09:00" -> burn -> key
     *    "20260924-09:00".
     *  - {"id":"x","time":"09:00","lastFiredKey":"k0","days":"bad"}: id "x"; same time -> no
     *    burn, lastFiredKey "k0" kept (:6066).
     * In every case V (index 1) is dropped (:6084).
     * FAILS before the fix: the bad element is gone at decode, so `reminders[0]` is V
     * (id "v1", same time, lastFiredKey "").
     */
    @Test
    fun editorSave_badFirstElement_likeOpti() {
        val v = Kt38.v("09:00")
        assertEquals(
            """[{"enabled":true,"kind":"weekly","time":"09:00","lastFiredKey":"20260924-09:00","days":${Kt38.ALL7}}]""",
            save("""["junk",$v]""", draft("09:00"))
        )
        assertEquals(
            "[${Kt38.v("09:00", lastFiredKey = "20260924-09:00", id = "x")}]",
            save("""[{"id":"x","time":5,"lastFiredKey":"k0"},$v]""", draft("09:00"))
        )
        assertEquals(
            "[${Kt38.v("09:00", lastFiredKey = "k0", id = "x")}]",
            save("""[{"id":"x","time":"09:00","lastFiredKey":"k0","days":"bad"},$v]""", draft("09:00"))
        )
    }

    /**
     * P4 review #6. A JS-falsy element at index 0 (null, 0, false, ""): app.js:6061
     * `(EDIT.reminders && EDIT.reminders[0]) || null` gives `_prevRem = null`, so id = uid()
     * (:6062, here "NEW"), lastFiredKey "" (:6066), then the burn rule runs because `!_prevRem`
     * (:6072): "09:00" < "12:00" -> "20260924-09:00" (:6074, :2076). V is dropped (:6084).
     * FAILS on acc4f9b: the opaque entry was used as prev, so no id was written.
     */
    @Test
    fun editorSave_falsyFirstElement_getsFreshId_likeOpti() {
        val expected = "[${Kt38.v("09:00", lastFiredKey = "20260924-09:00", id = "NEW")}]"
        for (falsy in listOf("null", "0", "false", "\"\"")) {
            assertEquals("first = $falsy", expected, save("[$falsy,${Kt38.v("09:00")}]", draft("09:00")))
        }
    }

    /**
     * Editor open, app.js:5863: `hasRem = reminders[0] && reminders[0].enabled`; for "junk" that
     * is undefined -> the sheet opens with the defaults (:5869-5872): off, 09:00, today, all days.
     * FAILS before the fix: `reminders[0]` is V (enabled), so the sheet opens enabled.
     */
    @Test
    fun editorOpen_badFirstElement_opensDisabled_likeOpti() {
        val list = c.toReminderSpecList("""["junk",${Kt38.v("10:00")}]""")
        assertEquals(
            ReminderEditorRules.Draft(false, "09:00", "2026-09-24", List(7) { true }),
            ReminderEditorRules.draftFrom(list, FIXED_NOW_MS, UTC)
        )
    }
}

/**
 * (a) on the upload / live-export path: LiveBackupSource.fullLocalState() builds the tasks with
 * SerializationEngine.buildTaskJson over the Room rows (LocalWireState.fromEntities), the same
 * wire the sync upload and the manual export use. Expected = input (app.js:2069-2072).
 * FAILS before the fix for the same reason as (a).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderPassthroughLiveSnapshotTest : Wp89TestBase() {

    @Test
    fun liveSnapshotPath_keepsBadElements() = runBlocking {
        val input = """[${Kt38.v("09:00")},"junk",{"time":5},null]"""
        val d = device89("p4")
        d.db.taskDao().insertTask(SerializationEngine.parseTaskEntity(Kt38.taskJson(input)))
        val task = d.live.fullLocalState().wire.tasks
            .map { it.jsonObject }
            .first { it["id"]?.jsonPrimitive?.content == "t1" }
        assertEquals(Kt38.arr(input), task["reminders"])
    }
}
