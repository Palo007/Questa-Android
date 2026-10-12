package com.example.ui.analytics

import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Analytics tag-vocabulary fix (Option 2b).
 *
 * `task.tags` carries a tag **id** on PWA rows but a tag **name** on rows the Kotlin editor wrote,
 * while `AnView.tags`, `tagRows` and the golden fixture all speak ids. These tests pin the
 * canonicalisation at the analytics boundary: one tag = one bucket key = one row under either
 * spelling, unknown tokens still pass through, and id-only inputs reproduce pre-fix output.
 *
 * Fixture tags mirror `TagFilterTest`: id `msyepixjvzfh8` -> "Old", id `mrn5jp2py482q` -> "TOP".
 */
class AnalyticsTagVocabularyTest {
    private val zone = ZoneId.of("Europe/Bratislava")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private val oldTag = TagEntity(id = "msyepixjvzfh8", name = "Old", color = "#50b5e9", createdAt = 1L, updatedAt = 1L)
    private val topTag = TagEntity(id = "mrn5jp2py482q", name = "TOP", color = "#123456", createdAt = 2L, updatedAt = 2L)
    private val tags = listOf(topTag, oldTag)

    private fun task(id: String, tagTokens: List<String>, created: Long) =
        TaskEntity(id = id, type = "todo", title = id, tags = tagTokens, createdAt = created, updatedAt = created)

    private val from = ms(2026, 9, 1)
    private val to = ms(2026, 9, 30)
    private val now = to

    private fun byTagView(source: String = "created", chart: String = "bar", filter: List<String> = emptyList()) =
        AnView(id = "v", name = "By tag", source = source, group = "tag", chart = chart, tags = filter)

    private fun rows(bucket: AnBucket): List<AnRow> = (bucket as AnBucket.Cat).rows

    // ---- viewItems (site 1) ---------------------------------------------------------------------------

    @Test
    fun viewItems_idFilter_matchesNameStoredAndIdStoredTasks() {
        val tasks = listOf(
            task("a", listOf("Old"), ms(2026, 9, 3)),
            task("b", listOf("msyepixjvzfh8"), ms(2026, 9, 4)),
            task("c", listOf("mrn5jp2py482q"), ms(2026, 9, 5)),
        )
        val v = byTagView(filter = listOf("msyepixjvzfh8"))
        assertEquals(listOf("a", "b"), AnalyticsCalc.viewItems(v, tasks, tags).map { it.id })
    }

    @Test
    fun viewItems_nameFilter_matchesIdStoredTask() {
        val tasks = listOf(task("b", listOf("msyepixjvzfh8"), ms(2026, 9, 4)))
        val v = byTagView(filter = listOf("Old"))
        assertEquals(listOf("b"), AnalyticsCalc.viewItems(v, tasks, tags).map { it.id })
    }

    @Test
    fun renderView_streaksList_idFilterMatchesNameStoredDaily() {
        val daily = TaskEntity(
            id = "d1", type = "daily", title = "Stretch", tags = listOf("Old"),
            createdAt = ms(2026, 9, 1), updatedAt = ms(2026, 9, 1), streak = 5,
        )
        val v = AnView(id = "v", name = "Streaks", source = "streaks", group = "tag", chart = "list", tags = listOf("msyepixjvzfh8"))
        val body = AnalyticsCalc.renderView(v, AnData(tasks = listOf(daily), tags = tags), AnPrefs(), from, to, now, zone)
        val list = (body as ViewBody.ListRows).rows
        assertEquals(listOf("Stretch"), list.map { it.label })
        assertEquals(listOf(5.0), list.map { it.v })
    }

    // ---- bucket / snapshotRows (sites 2 and 3) ---------------------------------------------------------

    @Test
    fun bucket_tagGroup_oneTagInBothSpellings_singleRowWithRealNameAndColor() {
        val d = AnData(
            tasks = listOf(
                task("a", listOf("Old"), ms(2026, 9, 3)),
                task("b", listOf("msyepixjvzfh8"), ms(2026, 9, 4)),
            ),
            tags = tags,
        )
        val events = AnalyticsCalc.sourceEvents(byTagView(), d, AnPrefs())
        val r = rows(AnalyticsCalc.bucket(events, "tag", from, to, tags, zone))
        assertEquals(listOf("Old"), r.map { it.label })
        assertEquals(listOf(2.0), r.map { it.v })
        assertEquals(listOf("#50b5e9"), r.map { it.color })
    }

    @Test
    fun bucket_tagGroup_taskStoringBothSpellings_countsItOnce() {
        val d = AnData(
            tasks = listOf(task("a", listOf("Old", "msyepixjvzfh8"), ms(2026, 9, 3))),
            tags = tags,
        )
        val events = AnalyticsCalc.sourceEvents(byTagView(), d, AnPrefs())
        val r = rows(AnalyticsCalc.bucket(events, "tag", from, to, tags, zone))
        assertEquals(listOf("Old"), r.map { it.label })
        assertEquals(listOf(1.0), r.map { it.v })
    }

    @Test
    fun snapshotRows_tagcount_oneTagInBothSpellings_singleRowWithRealNameAndColor() {
        val d = AnData(
            tasks = listOf(
                task("a", listOf("Old"), ms(2026, 9, 3)),
                task("b", listOf("msyepixjvzfh8"), ms(2026, 9, 4)),
                task("u", emptyList(), ms(2026, 9, 5)),
            ),
            tags = tags,
        )
        val r = AnalyticsCalc.snapshotRows(byTagView(source = "tagcount"), d, now, zone)
        assertEquals(listOf("Old", "(untagged)"), r.map { it.label })
        assertEquals(listOf(2.0, 1.0), r.map { it.v })
        assertEquals(listOf("#50b5e9", "var(--muted)"), r.map { it.color })
    }

    // ---- tagSummary (site 4) ---------------------------------------------------------------------------

    @Test
    fun tagSummary_countsNameStoredAndIdStoredTasksUnderOneRow() {
        val d = AnData(
            tasks = listOf(
                task("a", listOf("Old"), ms(2026, 9, 3)),
                task("b", listOf("msyepixjvzfh8"), ms(2026, 9, 4)),
            ),
            tags = tags,
        )
        val body = AnalyticsCalc.tagSummary(d, from, to) as ViewBody.TagSummary
        assertEquals(2, body.rows.first { it.name == "Old" }.count)
        assertEquals(0, body.rows.first { it.name == "TOP" }.count)
        assertEquals(listOf("Old", "TOP"), body.bars.map { it.label })
    }

    // ---- unknown tokens --------------------------------------------------------------------------------

    @Test
    fun deletedTagToken_passesThroughAndStillFilters() {
        val tasks = listOf(
            task("a", listOf("ghost-id"), ms(2026, 9, 3)),
            task("b", listOf("Old"), ms(2026, 9, 4)),
        )
        val v = byTagView(filter = listOf("ghost-id"))
        assertEquals(listOf("a"), AnalyticsCalc.viewItems(v, tasks, tags).map { it.id })

        val events = AnalyticsCalc.sourceEvents(v, AnData(tasks = tasks, tags = tags), AnPrefs())
        val r = rows(AnalyticsCalc.bucket(events, "tag", from, to, tags, zone))
        assertEquals(listOf("(untagged)"), r.map { it.label })
        assertEquals(listOf(1.0), r.map { it.v })
    }

    // ---- id-only regression guard (golden parity) -------------------------------------------------------

    @Test
    fun idOnlyInputs_reproducePreFixOutputExactly() {
        val d = AnData(
            tasks = listOf(
                task("a", listOf("msyepixjvzfh8"), ms(2026, 9, 3)),
                task("b", listOf("mrn5jp2py482q"), ms(2026, 9, 4)),
                task("u", emptyList(), ms(2026, 9, 5)),
            ),
            tags = tags,
        )
        // bucket/snapshotRows/renderView key order follows first-seen task order: Old, TOP, (untagged)
        val expectedLabels = listOf("Old", "TOP", "(untagged)")
        val expectedValues = listOf(1.0, 1.0, 1.0)
        val expectedColors = listOf("#50b5e9", "#123456", "var(--muted)")

        val v = byTagView(filter = listOf("msyepixjvzfh8"))
        assertEquals(listOf("a"), AnalyticsCalc.viewItems(v, d.tasks, tags).map { it.id })

        val events = AnalyticsCalc.sourceEvents(byTagView(), d, AnPrefs())
        val r = rows(AnalyticsCalc.bucket(events, "tag", from, to, tags, zone))
        assertEquals(expectedLabels, r.map { it.label })
        assertEquals(expectedValues, r.map { it.v })
        assertEquals(expectedColors, r.map { it.color })

        val s = AnalyticsCalc.snapshotRows(byTagView(source = "tagcount"), d, now, zone)
        assertEquals(expectedLabels, s.map { it.label })
        assertEquals(expectedValues, s.map { it.v })
        assertEquals(expectedColors, s.map { it.color })

        // tagSummary iterates the tag list, so its order is TOP, Old
        val body = AnalyticsCalc.tagSummary(d, from, to) as ViewBody.TagSummary
        assertEquals(listOf("TOP", "Old"), body.rows.map { it.name })
        assertEquals(listOf(1, 1), body.rows.map { it.count })
        assertEquals(listOf("TOP", "Old"), body.bars.map { it.label })

        val view = AnalyticsCalc.renderView(byTagView(), d, AnPrefs(), from, to, now, zone) as ViewBody.Bars
        assertEquals(expectedLabels, view.rows.map { it.label })
        assertEquals(expectedValues, view.rows.map { it.v })
        assertEquals(expectedColors, view.rows.map { it.color })
    }

    @Test
    fun renderView_createdByTag_countsBothSpellingsInOneBucket() {
        val d = AnData(
            tasks = listOf(
                task("a", listOf("Old"), ms(2026, 9, 3)),
                task("b", listOf("msyepixjvzfh8"), ms(2026, 9, 4)),
                task("c", listOf("mrn5jp2py482q"), ms(2026, 9, 5)),
            ),
            tags = tags,
        )
        val view = AnalyticsCalc.renderView(byTagView(), d, AnPrefs(), from, to, now, zone) as ViewBody.Bars
        assertEquals(listOf("Old", "TOP"), view.rows.map { it.label })
        assertEquals(listOf(2.0, 1.0), view.rows.map { it.v })
        assertEquals(listOf("#50b5e9", "#123456"), view.rows.map { it.color })
    }
}
