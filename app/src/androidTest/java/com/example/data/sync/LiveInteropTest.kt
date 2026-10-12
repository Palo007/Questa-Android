package com.example.data.sync

import android.os.Process
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.QuestaApplication
import com.example.data.boot.BootGate
import com.example.data.clock.StartupBarrier
import com.example.data.local.AppDatabase
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.auth.AccountInterlock
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.backup.BkTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/**
 * WP-8.12 live gate, phone side (§6.2 scenarios 1-17). NOT part of any automatic run: it does
 * nothing unless the instrumentation arguments `live=yes`, `scenario=N` and `step=<op>` are all
 * passed. tools/live/run_live.mjs drives it one step at a time; see plans/stage8/WP-8.12_RUN_SHEET.md.
 *
 * Token rule (D30, WP-8.12 safety): this class never reads, holds, logs or writes a Dropbox token.
 * Every Dropbox call is made by the app's own engines ([SyncManager], [ForceOpsEngine],
 * [FirstConnect], the round steps) with the tokens they read from the encrypted store. The only
 * network hook is a recorder that keeps the endpoint, the `Dropbox-API-Arg` path/mode and the
 * status code; it never reads the Authorization header. The D7 interlock runs before every step
 * that can reach Dropbox ([NETWORK_STEPS]).
 *
 * Output: `<app files>/live/s<N>-<step>.json`, read by the PC with `run-as ... cat`.
 */
@RunWith(AndroidJUnit4::class)
class LiveInteropTest {

    private data class Call(val endpoint: String, val arg: String?, val body: String?, val status: Int)

    private val calls = mutableListOf<Call>()
    private var killAfterClaim = false

    private val recorder = Interceptor { chain ->
        val req = chain.request()
        val endpoint = req.url.encodedPath
        val arg = req.header("Dropbox-API-Arg") // path + mode only; the Authorization header is never read
        val rpcBody = if (endpoint.endsWith("/files/move_v2") || endpoint.endsWith("/files/delete_v2")) {
            req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        } else null
        val res = chain.proceed(req)
        synchronized(calls) { calls += Call(endpoint, arg, rpcBody, res.code) }
        if (killAfterClaim && res.code == 200 && endpoint.endsWith("/files/move_v2") &&
            rpcBody != null && rpcBody.contains("\"to_path\":\"/inbox-claimed/")
        ) {
            // Scenario 5: the crash lands right after the claim move, before apply/finish.
            Process.killProcess(Process.myPid())
        }
        res
    }

    @Test
    fun liveStep() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("live steps run only with -e live yes", args.getString("live") == "yes")
        val scenario = args.getString("scenario")?.toIntOrNull()
        val step = args.getString("step")
        assumeTrue("-e scenario 1..17 is required", scenario != null && scenario in 1..17)
        assumeTrue("-e step <op> is required", !step.isNullOrBlank())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("run against the debug build (com.example.dev)", ctx.packageName.endsWith(".dev"))
        val app = ctx.applicationContext as QuestaApplication

        StartupBarrier.await()
        withTimeoutOrNull(60_000) { BootGate.pending.first { !it } }
        // D7/D30: a step that can reach Dropbox stops here for any other (or no) account. Local-only
        // steps (status, snapshot, edits) skip it so they also work while NOT_LINKED (scenarios 10, 17);
        // DropboxService still checks the interlock before every HTTP call on its own.
        if (step in NETWORK_STEPS) AccountInterlock(app.syncDataStore).assertAllowed()

        val original = DropboxService.client
        DropboxService.client = original.newBuilder().addInterceptor(recorder).build()
        val out = try {
            run(app, step!!, args)
        } finally {
            DropboxService.client = original
        }
        val dir = File(ctx.filesDir, "live").apply { mkdirs() }
        val full = buildJsonObject {
            put("scenario", scenario)
            put("step", step)
            put("atMs", System.currentTimeMillis())
            put("result", out)
            put("calls", buildJsonArray {
                synchronized(calls) {
                    for (c in calls) add(buildJsonObject {
                        put("endpoint", c.endpoint)
                        put("arg", c.arg?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("body", c.body?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("status", c.status)
                    })
                }
            })
        }
        File(dir, "s$scenario-$step.json").writeText(full.toString() + "\n")
    }

    private fun a(args: android.os.Bundle, k: String): String =
        args.getString(k) ?: error("missing -e $k")

    private suspend fun run(app: QuestaApplication, step: String, args: android.os.Bundle): JsonElement {
        val repo = app.repository
        val sm = app.syncManager
        return when (step) {
            "status" -> status(app, args.getString("since")?.toLongOrNull() ?: 0L)
            "snapshot" -> sm.buildLocalSnapshot()
            "round" -> desc(sm.sync(SyncTrigger.MANUAL))
            "round-kill-after-claim" -> {
                killAfterClaim = true
                desc(sm.sync(SyncTrigger.MANUAL)) // normally never returns
            }
            "first-connect" -> desc(
                app.firstConnect.resolveFirstConnect(
                    when (a(args, "choice")) {
                        "pull" -> FirstConnectChoice.PullFromDropbox
                        "push" -> FirstConnectChoice.PushThisDevice
                        else -> error("choice must be pull or push")
                    }
                )
            )
            "force-push" -> desc(app.forceOps.forcePush())
            "force-pull" -> desc(app.forceOps.forcePull())
            "add-task" -> {
                val type = a(args, "type")
                require(type in setOf("habit", "daily", "todo"))
                val stamp = repo.hlcClock.now()
                repo.insertTask(
                    TaskEntity(
                        id = a(args, "id"), type = type, title = a(args, "title"), createdAt = stamp, updatedAt = stamp,
                        up = if (type == "habit") true else null, down = if (type == "habit") true else null,
                        repeat = if (type == "daily") List(7) { true } else null,
                    )
                )
                taskJson(repo.getTaskByIdSync(a(args, "id")))
            }
            "edit-title" -> {
                val t = repo.getTaskByIdSync(a(args, "id")) ?: error("no task ${a(args, "id")}")
                repo.updateTask(t.copy(title = a(args, "title"), updatedAt = repo.hlcClock.now()))
                taskJson(repo.getTaskByIdSync(t.id))
            }
            "delete-task" -> { repo.deleteTaskById(a(args, "id")); JsonPrimitive(true) }
            "complete" -> JsonPrimitive(repo.completeTask(a(args, "id")))
            "tap" -> {
                val id = a(args, "id")
                val n = a(args, "n").toInt()
                repeat(n) { if (a(args, "dir") == "-1") repo.scoreHabitDown(id) else repo.scoreHabitUp(id) }
                taskJson(repo.getTaskByIdSync(id))
            }
            "pause" -> { repo.setPause(a(args, "on") == "true"); JsonPrimitive(repo.pauseState().toString()) }
            "backup-due" -> {
                val tier = BkTier.ORDER.first { it.key == a(args, "tier") }
                val prefs = BackupPrefs(app.applicationContext.syncDataStore)
                prefs.setAutoBackupEnabled(prefs.autoBackupEnabled().with(tier, true))
                val st = prefs.tierStates().toMutableMap()
                st[tier] = st.getValue(tier).copy(lastTs = 0L) // due now; idx/bkVersion kept (rotation stays real)
                prefs.saveTierStates(st)
                desc(sm.sync(SyncTrigger.MANUAL))
            }
            "disconnect" -> JsonPrimitive(sm.disconnect())
            else -> error("unknown step $step")
        }
    }

    /** "Ok", "NeedsFirstConnectChoice", "Error: <msg>", ... ; null = the lock was busy. */
    private fun desc(x: Any?): JsonPrimitive = JsonPrimitive(
        when (x) {
            null -> "busy"
            else -> {
                val name = x::class.simpleName ?: "?"
                val text = x.toString()
                if (text.startsWith(name + "(")) name + ": " + text.removePrefix(name) else name
            }
        }
    )

    private fun taskJson(t: TaskEntity?): JsonElement = if (t == null) JsonNull else buildJsonObject {
        put("id", t.id); put("type", t.type); put("title", t.title); put("updatedAt", t.updatedAt)
        put("cUp", t.cUp); put("cDown", t.cDown); put("done", t.done)
        put("missedOn", t.missedOn?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** Non-secret state only. DataStore VALUES are written for [PUBLIC_KEYS] only; other keys by name. */
    private suspend fun status(app: QuestaApplication, since: Long): JsonObject {
        val prefs: Preferences = app.applicationContext.syncDataStore.data.first()
        val base = RoomBaseStore(AppDatabase.getDatabase(app).syncBaseDao()).read()
        val db = AppDatabase.getDatabase(app).openHelper.readableDatabase
        val counts = buildJsonObject {
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(0)
                for (n in names.sorted()) db.query("SELECT COUNT(*) FROM `$n`").use { q -> q.moveToFirst(); put(n, q.getLong(0)) }
            }
        }
        val logs = app.repository.getLogsSinceSync(since)
        val now = System.currentTimeMillis()
        return buildJsonObject {
            put("nowMs", now)
            put("tzOffsetMin", TimeZone.getDefault().getOffset(now) / 60_000)
            put("roomVersion", db.version)
            put("rowCounts", counts)
            put("prefKeyNames", JsonArray(prefs.asMap().keys.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
            fun pv(k: Preferences.Key<*>): JsonElement = prefs.asMap()[k]?.let { JsonPrimitive(it.toString()) } ?: JsonNull
            put("authState", pv(SyncKeys.AUTH_STATE))
            put("deviceId", pv(SyncKeys.DEVICE_ID))
            put("lastRev", pv(SyncKeys.DROPBOX_LAST_REV))
            put("appKeyOfData", pv(SyncKeys.APP_KEY_OF_DATA))
            put("evtFileCounts", pv(SyncKeys.EVT_FILE_COUNTS))
            put("prefs", buildJsonObject {
                for ((k, v) in prefs.asMap()) if (k.name in PUBLIC_KEYS) put(k.name, JsonPrimitive(v.toString()))
            })
            put("baseRev", base?.rev?.let { JsonPrimitive(it) } ?: JsonNull)
            put("baseAppKey", base?.appKey?.let { JsonPrimitive(it) } ?: JsonNull)
            put("preSwitchFiles", JsonArray((app.filesDir.list() ?: emptyArray()).filter { it.startsWith("pre-appkey-switch-") }.sorted().map { JsonPrimitive(it) }))
            put("conflictEvents", logs.count { it.kind == "conflict" })
            put("events", buildJsonArray {
                for (e in logs) add(buildJsonObject {
                    put("kind", e.kind); put("taskId", e.taskId?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("uid", e.uid?.let { JsonPrimitive(it) } ?: JsonNull); put("timestamp", e.timestamp)
                    put("direction", e.direction?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("payload", e.payloadJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
                })
            })
        }
    }

    companion object {
        val NETWORK_STEPS = setOf(
            "round", "round-kill-after-claim", "first-connect", "force-push", "force-pull", "backup-due", "disconnect",
        )

        /** DataStore keys whose values are safe to write out (ids, revs, states; never tokens). */
        val PUBLIC_KEYS = setOf(
            SyncKeys.APP_KEY_OF_DATA.name, SyncKeys.AUTH_STATE.name, SyncKeys.EVT_FILE_COUNTS.name,
            SyncKeys.DEVICE_ID.name, SyncKeys.DROPBOX_LAST_REV.name, SyncKeys.EVT_FILE_REVS.name,
        )
    }
}
