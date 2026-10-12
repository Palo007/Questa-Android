package com.example.data.inbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.8: the bytes Bubblewrap's InboxUploadWorker writes (`InboxRecord.toJson()`,
 * Questa-Bubblewrap 8954b55 quicklog/InboxRecord.kt, reproduced in [bubblewrapJson]) pass
 * [InboxValidator] with every field intact.
 */
class BubblewrapBytesAcceptedTest {
    @Test fun frozenContractExample_isAccepted() {
        val id = "0b3c5a2e-1111-4222-8333-944445555666"
        val body = bubblewrapJson(id, "h1", 1, 1790000000123L, -120)
        assertEquals(
            """{"v":1,"id":"$id","kind":"habit","habitId":"h1","dir":1,"ts":1790000000123,"tzOffsetMin":-120,"src":"android-shortcut"}""",
            body,
        )
        val r = InboxValidator.parse("$id.json", body).getOrThrow()
        assertEquals(id, r.id); assertEquals("h1", r.habitId); assertEquals(1, r.dir)
        assertEquals(1790000000123.0, r.ts, 0.0); assertEquals(-120.0, r.tzOffsetMin!!, 0.0)
        assertEquals("android-shortcut", r.src)
        val tap = r.toTapRecord()
        assertEquals("inbox-$id", tap.uid)
        assertEquals(1790000000123L, tap.ts)
        assertEquals(-120, tap.tzOffsetMin)
    }

    @Test fun downTap_andEscapedHabitIds_areAccepted() {
        val id = java.util.UUID.nameUUIDFromBytes("x".toByteArray()).toString()
        for (habit in listOf("h-1", "a\"b\\c", "tab\there", "line\u2028sep", "ctl\u0001", "\u010dajov\u00fd \ud83d\ude00")) {
            val r = InboxValidator.parse("$id.json", bubblewrapJson(id, habit, -1, 5L, 60)).getOrThrow()
            assertEquals(habit, r.habitId)
            assertEquals(-1, r.dir)
        }
    }

    @Test fun uuidUpperCase_andEveryTzSign_areAccepted() {
        val id = "0B3C5A2E-1111-4222-8333-944445555666"
        for (tz in listOf(-840, -120, 0, 60, 720)) {
            assertEquals(tz.toDouble(), InboxValidator.parse("$id.json", bubblewrapJson(id, "h1", 1, 5L, tz)).getOrThrow().tzOffsetMin!!, 0.0)
        }
    }
}

/**
 * Bubblewrap `parseHabitList` (quicklog/HabitList.kt, org.json), reproduced verbatim for the test:
 * the phone must read the Kotlin meta file exactly as it reads the PWA's.
 */
private data class BwHabit(val id: String, val title: String, val quickLog: Boolean, val dirs: List<Int>)

private fun bwParseHabitList(text: String): List<BwHabit>? {
    val o = try { JSONObject(text) } catch (e: Exception) { return null }
    if (o.optInt("v", 0) != 1) return null
    val arr = o.optJSONArray("habits") ?: return null
    val out = ArrayList<BwHabit>(arr.length())
    for (i in 0 until arr.length()) {
        val h = arr.optJSONObject(i) ?: continue
        val id = h.optString("id", "")
        if (id.isEmpty()) continue
        val title = h.optString("title", "").trim().ifEmpty { id }
        val quickLog = h.optBoolean("quickLog", false)
        out.add(BwHabit(id, title, quickLog, bwParseDirs(h.optJSONArray("dirs"), quickLog)))
    }
    return out
}

private fun bwParseDirs(arr: JSONArray?, quickLog: Boolean): List<Int> {
    if (arr == null) return if (quickLog) listOf(1) else emptyList()
    val out = ArrayList<Int>()
    for (i in 0 until arr.length()) {
        val n = (arr.opt(i) as? Number)?.toInt()
        if ((n == 1 || n == -1) && n !in out) out.add(n)
    }
    return out
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetaParsedByBubblewrapParserTest {
    @Test fun kotlinMeta_parsesToTheSameHabitsAndDirs() {
        val habits = QuickLogMeta.habits(g6Tasks())
        val parsed = bwParseHabitList(QuickLogMeta.body(habits, 1790000000000L))
        assertNotNull(parsed)
        assertEquals(habits.map { it.id }, parsed!!.map { it.id })
        assertEquals(habits.map { it.dirs }, parsed.map { it.dirs })
        assertEquals(habits.map { it.quickLog }, parsed.map { it.quickLog })
        // Bubblewrap shows the id for an empty title and trims; every other title survives the escapes.
        assertEquals("h6", parsed.single { it.id == "h6" }.title)
        assertEquals(habits.single { it.id == "h5" }.title.trim(), parsed.single { it.id == "h5" }.title)
    }

    @Test fun emptyList_isAValidV1File() {
        val parsed = bwParseHabitList(QuickLogMeta.body(emptyList(), 1L))
        assertTrue(parsed != null && parsed.isEmpty())
    }
}
