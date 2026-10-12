package com.example.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.merge.ConflictEvent
import com.example.ui.analytics.AnCard
import com.example.ui.analytics.AnChip
import com.example.ui.analytics.AnInput
import com.example.ui.analytics.AnMini
import com.example.ui.analytics.AnNote
import com.example.ui.analytics.AnalyticsCalc
import com.example.ui.analytics.CardK
import com.example.ui.analytics.InputBg
import com.example.ui.settings.FeedFilters
import com.example.ui.theme.OptiRootTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// =================================================================================================
// CR-KT-012: the Activity tab (tab 5, was "Logs"). The one place for the event feed, moved here
// from Analytics -> "Event log detail (live)" (Opti renderEventDetail, app.js:4974; chips
// :5043-5049, search :5030-5035, page size :4888). Read-only over repository.allLogs.
// =================================================================================================

private val T = OptiRootTokens
private const val DAY = 86_400_000L

/** Opti's feed page size. */
const val ACTIVITY_PAGE_SIZE = 25

/** Feed category chips (key to label), Opti app.js:5043-5049. */
val ACTIVITY_CATEGORIES = listOf("all" to "All", "habit" to "Habits ⚡", "daily" to "Dailies 📅", "todo" to "To-dos ☑️", "system" to "System 💾")

/**
 * The Activity date range: a snap ("7d" … "365d", "all"; Analytics' [AnalyticsCalc.SNAP_CHIPS])
 * or a window handed over from Analytics ([fromMs]..[toMs], inclusive; [toMs] null = open end,
 * so events logged later still show). Default = All.
 */
data class ActivityRange(val snap: String? = "all", val fromMs: Long? = null, val toMs: Long? = null) {
    val isCustom: Boolean get() = fromMs != null

    /** Inclusive (from, to) in epoch ms. A snap counts back from [now] and keeps everything newer. */
    fun bounds(now: Long): Pair<Long, Long> {
        if (fromMs != null) return fromMs to (toMs ?: Long.MAX_VALUE)
        val days = snap?.removeSuffix("d")?.toIntOrNull() ?: return Long.MIN_VALUE to Long.MAX_VALUE
        return (now - days * DAY) to Long.MAX_VALUE
    }

    companion object {
        fun window(fromMs: Long, toMs: Long) = ActivityRange(snap = null, fromMs = fromMs, toMs = toMs)
    }
}

/**
 * The range for Analytics' "See activity for this period →" (review MAJOR-1): "All" is the All
 * chip; a window that ends now ([toOff] 0) keeps its end open; a past window stays fixed.
 */
fun analyticsActivityRange(snap: String?, toOff: Int?, fromMs: Long, toMs: Long): ActivityRange = when {
    snap == "all" -> ActivityRange()
    (toOff ?: 0) == 0 -> ActivityRange(snap = null, fromMs = fromMs, toMs = null)
    else -> ActivityRange.window(fromMs, toMs)
}

/** The removable "this task only" filter (task edit sheet -> History). */
data class TaskFilter(val taskId: String, val title: String)

/** What the Dashboard hands the Activity tab: the range and the optional task filter. */
data class ActivityNav(val range: ActivityRange = ActivityRange(), val task: TaskFilter? = null) {
    /** Analytics "See activity for this period →": that window, no task filter. */
    fun forWindow(fromMs: Long, toMs: Long) = ActivityNav(ActivityRange.window(fromMs, toMs), null)
    fun forRange(range: ActivityRange) = ActivityNav(range, null)
    /** Task edit "History": that task over all time. */
    fun forTask(taskId: String, title: String) = ActivityNav(ActivityRange(), TaskFilter(taskId, title))
    fun clearTask() = copy(task = null)
}

/** The Activity tab's Dashboard index (kept from the old Logs tab). */
const val ACTIVITY_TAB = 5

/** A nav-bar tap on Activity starts without the task filter (review MINOR-3); History / Analytics set nav themselves. */
fun activityNavOnTabSelect(nav: ActivityNav, index: Int): ActivityNav = if (index == ACTIVITY_TAB) nav.clearTask() else nav

/** Keeps [ActivityNav] across rotation / process death. */
val ActivityNavSaver: Saver<ActivityNav, Any> = listSaver(
    save = { listOf(it.range.snap, it.range.fromMs, it.range.toMs, it.task?.taskId, it.task?.title) },
    restore = { l ->
        val id = l[3] as String?
        ActivityNav(ActivityRange(l[0] as String?, l[1] as Long?, l[2] as Long?), id?.let { TaskFilter(it, (l[4] as String?) ?: "") })
    },
)

/** [hiddenDiagnostics] / [shownDiagnostics]: diagnostic rows in the range that `hideSyncDiag` hides / lets through. */
data class ActivityFiltered(val events: List<EventLogEntity>, val hiddenDiagnostics: Int, val shownDiagnostics: Int = 0)

data class DayGroup(val label: String, val events: List<EventLogEntity>)

/**
 * Opti `isFeedNoise` (DIAGNOSTIC_KINDS). The Kotlin-only `syncCompleted` diagnostic, which the old
 * Analytics feed always dropped, rides on `hideSyncDiag` too.
 */
fun isFeedDiagnostic(kind: String): Boolean = kind in FeedFilters.DIAGNOSTIC_KINDS || kind == "syncCompleted"

private fun payloadString(payloadJson: String?, key: String): String? = payloadJson?.let { p ->
    runCatching { (Json.parseToJsonElement(p) as? kotlinx.serialization.json.JsonObject)?.get(key) as? kotlinx.serialization.json.JsonPrimitive }
        .getOrNull()?.takeIf { it.isString }?.content
}

/** Opti search (app.js:5156-5158): `taskTitle||taskType||kind||subText` (JS `||`: empty falls through), or the notes. */
private fun matchesQuery(e: EventLogEntity, q: String): Boolean {
    val title = listOf(e.taskTitle, e.taskType, e.kind).firstOrNull { !it.isNullOrEmpty() }
        ?: payloadString(e.payloadJson, "subText") ?: ""
    if (title.lowercase(Locale.ROOT).contains(q)) return true
    return (payloadString(e.payloadJson, "notes") ?: "").lowercase(Locale.ROOT).contains(q)
}

/**
 * The feed rows, newest first: inside [range] (inclusive), in [category] ("all" = any), matching
 * [query] ([matchesQuery], case-insensitive), of [taskId] when set, and shown by [feedFilters].
 * Opti renderEventDetail (app.js:5142-5150): the diagnostic counts cover the whole range, before
 * the chip, search and task filters; `conflictResolved` has its own pref and is never counted.
 */
fun activityFilter(
    events: List<EventLogEntity>,
    range: Pair<Long, Long>,
    category: String,
    query: String,
    taskId: String?,
    feedFilters: FeedFilters,
): ActivityFiltered {
    val q = query.trim().lowercase(Locale.ROOT)
    var hidden = 0
    var shown = 0
    val out = events.filter { e ->
        if (e.timestamp < range.first || e.timestamp > range.second) return@filter false
        if (e.kind == ConflictEvent.CONFLICT_RESOLVED) {
            if (feedFilters.hideConflictDecisions) return@filter false
        } else if (isFeedDiagnostic(e.kind)) {
            if (feedFilters.hideSyncDiag) { hidden++; return@filter false }
            shown++
        }
        if (taskId != null && e.taskId != taskId) return@filter false
        if (category != "all" && AnalyticsCalc.eventCategory(e) != category) return@filter false
        if (q.isNotEmpty() && !matchesQuery(e, q)) return@filter false
        true
    }.sortedByDescending { it.timestamp }
    return ActivityFiltered(out, hidden, shown)
}

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DAY_YEAR_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
private val DETAIL_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** "Today", "Yesterday", else "EEE d MMM" (+ " yyyy" when not [today]'s year). */
fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> (if (day.year == today.year) DAY_FMT else DAY_YEAR_FMT).format(day)
}

/** Consecutive runs of [events] on the same local day, in the given order, with their header. */
fun groupByDay(events: List<EventLogEntity>, zone: ZoneId, now: Long): List<DayGroup> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val out = mutableListOf<DayGroup>()
    var curDay: LocalDate? = null
    var cur = mutableListOf<EventLogEntity>()
    events.forEach { e ->
        val d = Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()
        if (d != curDay) {
            if (curDay != null) out += DayGroup(dayLabel(curDay!!, today), cur)
            curDay = d; cur = mutableListOf()
        }
        cur += e
    }
    if (curDay != null) out += DayGroup(dayLabel(curDay!!, today), cur)
    return out
}

/** A feed row's local time, `HH:mm:ss`. */
fun rowTime(ts: Long, zone: ZoneId): String = TIME_FMT.format(Instant.ofEpochMilli(ts).atZone(zone))

/** The detail sheet's rows (label to value), non-null fields only; the payload is [prettyPayload]. */
fun eventDetailFields(e: EventLogEntity, zone: ZoneId): List<Pair<String, String>> = buildList {
    add("Kind" to e.kind)
    val task = listOfNotNull(e.taskTitle, e.taskType?.let { if (e.taskTitle != null) "($it)" else it }).joinToString(" ")
    if (task.isNotEmpty()) add("Task" to task)
    e.taskId?.let { add("Task ID" to it) }
    add("Time" to DETAIL_TIME_FMT.format(Instant.ofEpochMilli(e.timestamp).atZone(zone)))
    e.damage?.let { add("Damage" to it.toString()) }
    e.reps?.let { add("Reps" to it.toString()) }
    e.value?.let { add("Value" to it.toString()) }
    e.cost?.let { add("Cost" to it.toString()) }
    e.streak?.let { add("Streak" to it.toString()) }
    e.direction?.let { add("Direction" to it.toString()) }
    e.dev?.let { add("Device" to it) }
    e.uid?.let { add("UID" to it) }
    e.imported?.let { add("Imported" to it.toString()) }
    e.republish?.let { add("Republish" to it.toString()) }
    if (e.synthetic) add("Synthetic" to "true")
}

private val PrettyJson = Json { prettyPrint = true }

/** [json] pretty-printed; the raw text when it does not parse; null for null. */
fun prettyPayload(json: String?): String? {
    if (json == null) return null
    return runCatching { PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json)) }.getOrDefault(json)
}

// ---- UI ------------------------------------------------------------------------------------------------

/**
 * The Activity tab. [nav] (range + task filter) is hoisted so Analytics and the task editor can
 * open it; category, search and page are local. [onToggleDiag] sets the Settings `hideSyncDiag`
 * pref (Opti evToggleDiag; same pref as Settings -> Activity Feed).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    logs: List<EventLogEntity>,
    feedFilters: FeedFilters,
    nav: ActivityNav,
    onNavChange: (ActivityNav) -> Unit,
    onToggleDiag: (hide: Boolean) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: () -> Long = { com.example.data.boot.BootGate.clock() },
) {
    var category by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    // Review MINOR-4: back to page 1 when the range, the task filter or the Settings feed filters change.
    var page by remember(nav.range, nav.task, feedFilters) { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<EventLogEntity?>(null) }
    val now = remember(logs, nav) { nowMs() }
    val bounds = nav.range.bounds(now)
    val res = remember(logs, bounds, category, query, nav.task, feedFilters) {
        activityFilter(logs, bounds, category, query, nav.task?.taskId, feedFilters)
    }
    val pages = max(1, (res.events.size + ACTIVITY_PAGE_SIZE - 1) / ACTIVITY_PAGE_SIZE)
    if (page >= pages) page = pages - 1
    val groups = remember(res, page, zone, now) { groupByDay(res.events.drop(page * ACTIVITY_PAGE_SIZE).take(ACTIVITY_PAGE_SIZE), zone, now) }

    Column(Modifier.fillMaxSize().testTag("activity_screen")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📋 Activity", color = T.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (nav.range.isCustom) {
                    AnChip(AnalyticsCalc.fmtDate(bounds.first, zone) + " → " + (nav.range.toMs?.let { AnalyticsCalc.fmtDate(it, zone) } ?: "now"), true,
                        modifier = Modifier.testTag("activity_range_custom")) {}
                }
                AnalyticsCalc.SNAP_CHIPS.forEach { (label, snap) ->
                    AnChip(label, !nav.range.isCustom && nav.range.snap == snap, modifier = Modifier.testTag("activity_range_$snap")) {
                        onNavChange(nav.copy(range = ActivityRange(snap = snap)))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                ACTIVITY_CATEGORIES.forEach { (k, label) ->
                    AnChip(label, category == k, modifier = Modifier.testTag("activity_cat_$k")) { category = k; page = 0 }
                }
            }
            nav.task?.let { tf ->
                val shape = RoundedCornerShape(5.dp)
                Row(Modifier.padding(vertical = 2.dp).background(T.Panel2, shape).border(1.dp, T.Accent, shape).padding(start = 6.dp).testTag("activity_task_chip"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Task: ${tf.title}", color = T.Ink, fontSize = 11.sp, maxLines = 1)
                    Text("×", color = T.Muted, fontSize = 16.sp, modifier = Modifier.clickable(role = Role.Button) { onNavChange(nav.clearTask()) }
                        .semantics { contentDescription = "Clear task filter" }.padding(horizontal = 8.dp).testTag("activity_task_clear"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                AnInput(query, { query = it; page = 0 }, "Search events or tasks...", Modifier.weight(1f), bg = InputBg, tag = "activity_search")
                if (query.isNotEmpty()) Text("×", color = T.Muted, fontSize = 18.sp, modifier = Modifier.clickable(role = Role.Button) { query = ""; page = 0 }
                    .semantics { contentDescription = "Clear search" }.padding(horizontal = 8.dp))
            }
            Text("${res.events.size} events shown in feed", color = T.Muted, fontSize = 11.sp)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("activity_list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 30.dp)) {
            if (res.events.isEmpty()) {
                item {
                    AnCard(Modifier.fillMaxWidth()) {
                        CardK("From the event log")
                        AnNote(
                            if (logs.isEmpty()) "No events recorded yet. Tap a habit, check a subtask, or complete a daily and it will appear here."
                            else "No matching events found. Widen the date range or clear the filters.",
                            align = TextAlign.Center,
                        )
                    }
                }
            }
            groups.forEach { g ->
                item(key = "day_${g.label}_${g.events.first().id}") {
                    Text(g.label, color = T.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                items(g.events, key = { "ev_${it.id}" }) { e ->
                    EventRow(e, zone, onTitle = { t -> query = t; page = 0 }, onOpen = { detail = e })
                }
            }
            if (pages > 1) item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    AnMini("← Newer", enabled = page > 0) { page-- }
                    Text("Page ${page + 1} / $pages · ${res.events.size} events", color = T.Muted, fontSize = 11.sp)
                    AnMini("Older →", enabled = page < pages - 1) { page++ }
                }
            }
            // Opti evDiagFooterHtml (app.js:4962-4966).
            if (feedFilters.hideSyncDiag && res.hiddenDiagnostics > 0) item {
                DiagFooter("${res.hiddenDiagnostics} hidden (diagnostics) · ", "Show", "activity_hidden_footer", "activity_show_hidden") { onToggleDiag(false) }
            }
            if (!feedFilters.hideSyncDiag && res.shownDiagnostics > 0) item {
                DiagFooter("Diagnostics shown · ", "Hide", "activity_shown_footer", "activity_hide_shown") { onToggleDiag(true) }
            }
            val syn = res.events.count { it.synthetic }
            if (syn > 0) item { AnNote("$syn event(s) in window backfilled from Habitica history.") }
        }
    }

    detail?.let { e ->
        val context = LocalContext.current
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheet, containerColor = T.Bg, contentColor = T.Ink,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), scrimColor = Color(0xA80A0514)) {
            EventDetailContent(e, zone) { json -> copyJson(context, json) }
        }
    }
}

/** One feed row (moved from Analytics' EventRow). Tap = detail; tap the title = search it. */
@Composable
internal fun EventRow(e: EventLogEntity, zone: ZoneId, onTitle: (String) -> Unit, onOpen: () -> Unit) {
    val cat = AnalyticsCalc.eventCategory(e)
    val title = e.taskTitle ?: ""
    val (icon, badge, desc) = when {
        e.kind == "create" -> Triple("🆕", cat, "Created ${e.taskType ?: "task"} $title")
        // Opti: 'Edited '+title+(_summ.length?' · '+_summ.join(', '):'') (CR-PWA-003 images part).
        e.kind == "edit" -> Triple("✏️", cat, "Edited $title" + EditChangeSummary.text(e.payloadJson).let { if (it.isEmpty()) "" else " · $it" })
        e.kind == "delete" -> Triple("🗑️", cat, "Deleted ${e.taskType ?: "task"} $title")
        e.kind == "uncomplete" -> Triple("↩️", cat, "Reverted $title")
        e.kind == "purchase" -> Triple("🛒", "system", "Bought $title")
        e.kind == "subtask" -> Triple("↳", cat, "Subtask on $title")
        cat == "habit" -> if (e.kind == "habitReps") Triple(if (e.direction == -1) "➖" else "⚡", "habit", "Logged" + (e.reps?.let { " ${abs(it)} rep" + if (abs(it) == 1) "" else "s" } ?: "") + (if ((e.direction ?: 0) < 0) " (removed)" else "") + " on $title")
            else Triple(if (e.direction == -1) "➖" else "⚡", "habit", if (e.direction == -1) "Tapped negative on $title" else "Tapped $title" + (e.reps?.takeIf { it > 1 }?.let { " ($it reps)" } ?: ""))
        cat == "daily" -> if (e.kind == "miss") Triple("❌", "daily", "Missed daily $title") else Triple("📅", "daily", "Completed daily $title")
        cat == "todo" -> Triple("☑️", "todo", "Completed to-do $title")
        cat == "conflict" -> Triple("⚖️", "conflict", "Sync conflict resolved · ${title.ifEmpty { "item" }}")
        e.kind == "import" -> Triple("📥", "system", "Imported backup data")
        e.kind == "export" -> Triple("📤", "system", "Exported progress data")
        e.kind == "devicename" -> Triple("🏷️", "system", "Device name updated")
        else -> Triple("⚙️", "system", title.ifEmpty { e.kind })
    }
    val badgeName = mapOf("habit" to "Habit", "daily" to "Daily", "todo" to "To-do", "system" to "System", "conflict" to "Conflict")[badge] ?: "Event"
    val right: Pair<String, Color>? = when {
        e.kind == "purchase" && (e.cost ?: 0.0) != 0.0 -> "-" + AnalyticsCalc.fixed1(abs(e.cost!!)) + "G" to T.Hp
        cat == "habit" && e.kind == "habitReps" && (e.reps ?: 0) != 0 -> (if ((e.direction ?: 0) < 0) "-" else "+") + abs(e.reps!!) + " reps" to T.Accent
        (cat == "daily" && e.kind == "miss") || (e.kind == "habitTap" && e.direction == -1) -> (e.damage?.let { "-" + AnalyticsCalc.fixed1(it) + " HP" } ?: "HP Loss") to T.Hp
        else -> null
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("activity_row_${e.id}").padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(desc, color = T.Ink, fontSize = 12.sp, modifier = if (title.isNotEmpty()) Modifier.clickable { onTitle(title) } else Modifier)
            if (e.kind == "edit") EditDetailRows(e.payloadJson)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(badgeName, color = T.Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(T.Panel2, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                Text(rowTime(e.timestamp, zone), color = T.Muted, fontSize = 10.sp)
                if (e.synthetic) Text("~ backfill", color = T.Muted, fontSize = 10.sp)
            }
        }
        right?.let { (t, c) -> Text(t, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

/** The read-only "Event detail" sheet body. [onCopy] gets the pretty-printed payload JSON. */
@Composable
fun EventDetailContent(e: EventLogEntity, zone: ZoneId, onCopy: (String) -> Unit) {
    val payload = prettyPayload(e.payloadJson)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("event_detail")) {
        Text("Event detail", color = T.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        eventDetailFields(e, zone).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("event_detail_$k"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(k, color = T.Muted, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                Text(v, color = T.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        if (payload != null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Payload", color = T.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                AnMini("Copy", modifier = Modifier.testTag("event_detail_copy")) { onCopy(payload) }
            }
            SelectionContainer(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("event_detail_Payload")) {
                Text(payload, color = T.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(T.Panel, RoundedCornerShape(8.dp)).border(1.dp, T.Line, RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }
    }
}

@Composable
private fun DiagFooter(text: String, action: String, tag: String, actionTag: String, onClick: () -> Unit) {
    Row(Modifier.padding(top = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = T.Muted, fontSize = 11.sp)
        Text(action, color = T.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(4.dp).testTag(actionTag))
    }
}

private fun copyJson(context: Context, json: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Questa event", json))
    Toast.makeText(context, "Event JSON copied", Toast.LENGTH_SHORT).show()
}

/**
 * CR-KT-016: the `edit` row's detail rows (Opti `_detail`, 027e371 app.js:5279-5293, font 11px,
 * line-height 1.5): notes diff and subtask rows, with Opti's `_evDiffText` colors.
 */
@Composable
private fun EditDetailRows(payloadJson: String?) {
    val lines = EditChangeSummary.details(payloadJson)
    if (lines.isEmpty()) return
    Column(Modifier.padding(top = 3.dp)) {
        lines.forEach { line ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    line.spans.forEach { sp ->
                        val style = when (sp.style) {
                            EditChangeSummary.Style.LABEL -> androidx.compose.ui.text.SpanStyle(color = T.Ink.copy(alpha = 0.7f))
                            EditChangeSummary.Style.FROM -> androidx.compose.ui.text.SpanStyle(color = Color(0xFFF74E52), textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            EditChangeSummary.Style.TO, EditChangeSummary.Style.CHECK -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF7EE787))
                            EditChangeSummary.Style.NEW -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF8B96A8), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            EditChangeSummary.Style.REMOVED -> androidx.compose.ui.text.SpanStyle(color = Color(0xFFF74E52), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            EditChangeSummary.Style.ITEM -> androidx.compose.ui.text.SpanStyle(color = T.Ink.copy(alpha = 0.8f))
                            EditChangeSummary.Style.PLAIN -> androidx.compose.ui.text.SpanStyle(color = T.Ink)
                        }
                        withStyle(style) { append(sp.text) }
                    }
                },
                fontSize = 11.sp, lineHeight = 16.5.sp,
                modifier = if (line.indent) Modifier.padding(start = 8.dp) else Modifier,
            )
        }
    }
}
