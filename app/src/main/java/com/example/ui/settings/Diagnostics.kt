package com.example.ui.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * WP-8.14-R1: the read-only Diagnostics screen's data, replacing Opti's web debug overlay
 * (`showSyncDebugOverlay`, app.js:654) and its "Download All" bundle (`questaFullDiagnostic`,
 * app.js:820-889). Values only; no tokens, no PKCE verifier, no account id (see [Diagnostics.redact]).
 */
data class DiagnosticsSnapshot(
    val generatedAt: Long = 0L,
    val appVersion: String = "",
    val buildType: String = "",
    val sdkInt: Int = 0,
    val realAccountUnlocked: Boolean = false,
    val authState: String = "NOT_LINKED",
    val dropboxEnabled: Boolean = false,
    /** Opti `refreshToken: !!_sc.refreshToken` (presence only). */
    val refreshTokenPresent: Boolean = false,
    val accessTokenPresent: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    val lastBackupError: String? = null,
    val deviceId: String = "",
    /** WP-8.22-C: what Settings shows (see [Diagnostics.shownDeviceName]). */
    val deviceName: String = "",
    /** The Room base row's rev (sync base). */
    val baseRev: String? = null,
    /** The base row's app key (never its account id). */
    val baseAppKey: String? = null,
    val appKeyOfData: String? = null,
    val hlcLastIssued: Long = 0L,
    val roomVersion: Int = 0,
    val rowCounts: Map<String, Int> = emptyMap(),
    val evtFileCounts: Map<String, String> = emptyMap(),
    val evtFileRevCount: Int = 0,
    val evtPushBlocked: Boolean = false,
    val evtPushBlockedFiles: List<String> = emptyList(),
    val evtBadRevs: List<String> = emptyList(),
    val evtLastUploadTs: Long = 0L,
    val evtLastPullAt: Long = 0L,
    val evtFullScanAt: Long = 0L,
    val evtFullPushAt: Long = 0L,
    val inboxLastRunAt: Long = 0L,
    val inboxHabitsHash: String? = null,
    val inboxRemindersHash: String? = null,
    val pendingTaps: Int = 0,
    val armedReminders: List<String> = emptyList(),
    val autoBackupTiers: String = "Off",
    /** 8.7's [com.example.data.sync.events.DiagRing], oldest first (Opti `__qDiag.errors`). */
    val diagRing: List<JsonObject> = emptyList(),
    /** WP-8.12-T1: the debug app clock offset (always 0 in release). */
    val clockOffsetMs: Long = 0L,
)

/** A snapshot plus the stored secret values, used only to scrub the export (never shown or written). */
class DiagnosticsCapture(val snapshot: DiagnosticsSnapshot, val secrets: List<String>)

object Diagnostics {
    const val REDACTED = "[redacted]"

    /**
     * WP-8.22-C: the device name as the Settings sheet shows it: this device's `devices` row name
     * (SettingsViewModel), else the field's placeholder [SettingsUiState.deviceShort].
     */
    fun shownDeviceName(devices: List<com.example.data.local.entities.DeviceEntity>, deviceId: String): String =
        devices.firstOrNull { it.id == deviceId }?.name?.takeIf { it.isNotEmpty() }
            ?: SettingsUiState(deviceId = deviceId).deviceShort

    /** Opti `"questa-fulldiag-" + new Date().toISOString().replace(/[:.]/g,"-") + ".json"`. */
    fun fileName(nowMs: Long): String =
        "questa-fulldiag-" + Instant.ofEpochMilli(nowMs).toString().let(::isoMillis).replace(Regex("[:.]"), "-") + ".json"

    /** JS `toISOString()` always has milliseconds; [Instant.toString] drops them when zero. */
    private fun isoMillis(s: String): String {
        val z = s.removeSuffix("Z")
        val dot = z.indexOf('.')
        val base = if (dot < 0) z else z.substring(0, dot)
        val frac = if (dot < 0) "" else z.substring(dot + 1)
        return base + "." + (frac + "000").take(3) + "Z"
    }

    fun iso(ms: Long): JsonElement = if (ms <= 0L) JsonNull else JsonPrimitive(isoMillis(Instant.ofEpochMilli(ms).toString()))

    /**
     * Opti's bundle shape: `manifest`, `meta`, `errors`, then this app's equivalents of `localStorage` / `indexedDB`.
     * WP-8.22-C decision (orchestrator guess, logged): NO raw storage dump (Opti's full `localStorage` /
     * `indexedDB` copy). A raw DataStore/Room dump would carry tokens, the PKCE verifier, the account id
     * and user data; the export stays an allow-list of named fields, scrubbed by [redact].
     */
    fun toJson(s: DiagnosticsSnapshot): JsonObject = buildJsonObject {
        putJsonObject("manifest") {
            put("schemaVersion", 1)
            put("generatedAt", iso(s.generatedAt))
            put("appVersion", s.appVersion)
            put("syncEventlogHardeningVersion", 2)
            put("note", "Read this block first to triage. Native Android build: no tokens, no PKCE verifier, no account id (presence only).")
            putJsonObject("counts") {
                put("errors", s.diagRing.size)
                putJsonObject("room") { s.rowCounts.forEach { (k, v) -> put(k, v) } }
            }
        }
        putJsonObject("meta") {
            put("generatedAt", iso(s.generatedAt))
            put("appVersion", s.appVersion)
            put("buildType", s.buildType)
            put("androidSdk", s.sdkInt)
            put("realAccountUnlocked", s.realAccountUnlocked)
        }
        put("errors", JsonArray(s.diagRing))
        putJsonObject("sync") {
            put("authState", s.authState)
            put("enabled", s.dropboxEnabled)
            put("refreshToken", s.refreshTokenPresent)
            put("accessToken", s.accessTokenPresent)
            put("lastSyncAt", iso(s.lastSyncAt))
            put("lastError", s.lastSyncError?.let(::JsonPrimitive) ?: JsonNull)
            put("lastBackupError", s.lastBackupError?.let(::JsonPrimitive) ?: JsonNull)
            put("deviceId", s.deviceId)
            put("deviceName", s.deviceName)
            // WP-8.22-C: no legacy `lastRev` (DROPBOX_LAST_REV is never written); the real rev is baseRev.
            put("baseRev", s.baseRev?.let(::JsonPrimitive) ?: JsonNull)
            put("baseAppKey", s.baseAppKey?.let(::JsonPrimitive) ?: JsonNull)
            put("appKeyOfData", s.appKeyOfData?.let(::JsonPrimitive) ?: JsonNull)
            put("hlcLastIssued", s.hlcLastIssued)
        }
        putJsonObject("room") {
            put("version", s.roomVersion)
            putJsonObject("rowCounts") { s.rowCounts.forEach { (k, v) -> put(k, v) } }
        }
        putJsonObject("events") {
            putJsonObject("evtFileCounts") { s.evtFileCounts.forEach { (k, v) -> put(k, v) } }
            put("evtFileRevs", s.evtFileRevCount)
            put("evtPushBlocked", s.evtPushBlocked)
            put("evtPushBlockedFiles", JsonArray(s.evtPushBlockedFiles.map(::JsonPrimitive)))
            put("evtBadRevs", JsonArray(s.evtBadRevs.map(::JsonPrimitive)))
            put("evtLastUploadTs", iso(s.evtLastUploadTs))
            put("evtLastPullAt", iso(s.evtLastPullAt))
            put("evtFullScanAt", iso(s.evtFullScanAt))
            put("evtFullPushAt", iso(s.evtFullPushAt))
        }
        putJsonObject("inbox") {
            put("lastRunAt", iso(s.inboxLastRunAt))
            put("habitsHash", s.inboxHabitsHash?.let(::JsonPrimitive) ?: JsonNull)
            put("remindersHash", s.inboxRemindersHash?.let(::JsonPrimitive) ?: JsonNull)
        }
        put("pendingTaps", s.pendingTaps)
        putJsonObject("reminders") {
            put("armed", s.armedReminders.size)
            put("keys", JsonArray(s.armedReminders.map(::JsonPrimitive)))
        }
        putJsonObject("backup") {
            put("autoBackupTiers", s.autoBackupTiers)
            put("lastBackupError", s.lastBackupError?.let(::JsonPrimitive) ?: JsonNull)
        }
        putJsonObject("clock") { put("offsetMs", s.clockOffsetMs) }
    }

    /** Dropbox short-lived tokens start with `sl.`; account ids with `dbid:`. */
    private val SECRET_PATTERNS = listOf(Regex("""\bsl\.[A-Za-z0-9_\-.]+"""), Regex("""dbid:[A-Za-z0-9_\-]+"""), Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE))
    private val SECRET_KEY = Regex("token|verifier|authorization|accountid|account_id|dbid|pkce", RegexOption.IGNORE_CASE)

    /**
     * Scrubs every string: any stored secret value (≥ 6 chars), `sl.…`, `dbid:…` and `Bearer …`
     * become [REDACTED]; a key that names a secret keeps only a boolean. Applied to the whole
     * bundle, so a secret that leaked into an error text or the diag ring is removed too.
     */
    fun redact(e: JsonElement, secrets: Collection<String>): JsonElement {
        val live = secrets.filter { it.length >= 6 }.distinct().sortedByDescending { it.length }
        fun str(v: String): String {
            var out = v
            for (sec in live) if (out.contains(sec)) out = out.replace(sec, REDACTED)
            for (p in SECRET_PATTERNS) out = p.replace(out, REDACTED)
            return out
        }
        fun walk(x: JsonElement): JsonElement = when (x) {
            is JsonObject -> JsonObject(x.mapKeys { (k, _) -> str(k) }.mapValues { (k, v) ->
                if (SECRET_KEY.containsMatchIn(k) && !(v is JsonPrimitive && !v.isString)) {
                    JsonPrimitive(v !is JsonNull && !(v is JsonPrimitive && v.content.isEmpty()))
                } else walk(v)
            })
            is JsonArray -> JsonArray(x.map(::walk))
            is JsonPrimitive -> if (x.isString) JsonPrimitive(str(x.content)) else x
            JsonNull -> x
        }
        return walk(e)
    }

    /** The export file text: the bundle, scrubbed, pretty-printed. */
    fun exportText(c: DiagnosticsCapture): String =
        PrettyJson.encodeToString(JsonElement.serializer(), redact(toJson(c.snapshot), c.secrets))

    /** The screen's rows, scrubbed the same way as the file. */
    fun rows(c: DiagnosticsCapture): List<Pair<String, String>> {
        val r = redact(toJson(c.snapshot), c.secrets) as JsonObject
        val out = mutableListOf<Pair<String, String>>()
        fun flat(prefix: String, e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> flat(if (prefix.isEmpty()) k else "$prefix.$k", v) }
                is JsonPrimitive -> out += prefix to e.content
                JsonNull -> out += prefix to "—"
                is JsonArray -> out += prefix to (if (e.isEmpty()) "[]" else e.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() })
            }
        }
        r.forEach { (k, v) -> if (k != "errors" && k != "manifest") flat(k, v) }
        return out
    }

    /** The ring for the screen: newest first, scrubbed. */
    fun ringNewestFirst(c: DiagnosticsCapture): List<String> =
        c.snapshot.diagRing.asReversed().map { redact(it, c.secrets).toString() }

    private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
}
