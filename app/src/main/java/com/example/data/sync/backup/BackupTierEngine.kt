package com.example.data.sync.backup

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * WP-8.9 (PX-63) -- Opti's four backup tiers (sync.js:2794-2799, `BK_TIERS`).
 * [key] is the Opti tier key (`S.prefs.autoBackupEnabled` and the bookkeeping JSON use it).
 */
enum class BkTier(val key: String, val prefix: String, val count: Int, val cadenceMs: Long?) {
    FOUR_HOUR("fourHour", "4hour", 10, 4 * 3_600_000L),
    DAILY("daily", "daily", 7, null),
    WEEKLY("weekly", "weekly", 4, null),
    MONTHLY("monthly", "monthly", 4, null);

    companion object {
        /** Opti iteration order `['fourHour','daily','weekly','monthly']` (sync.js:2984). */
        val ORDER: List<BkTier> = listOf(FOUR_HOUR, DAILY, WEEKLY, MONTHLY)
        fun fromKey(k: String): BkTier? = entries.firstOrNull { it.key == k }
    }
}

/**
 * WP-8.9 -- the pure part of Opti's tiered Dropbox backups (sync.js:2802-2957): path, stamp,
 * boundary and rotation. No I/O, no clock read: every time value is passed in. The device-local
 * calendar is [TimeZone] (Opti `new Date()` local accessors), never UTC.
 */
object BackupTierEngine {
    /** Opti `'/questa-backups/'` -- the listing path keeps the trailing slash (sync.js:2816). */
    const val FOLDER = "/questa-backups/"

    /** `syncDeviceId().slice(-6)` (sync.js:2802). */
    fun deviceShort(deviceId: String): String = if (deviceId.length <= 6) deviceId else deviceId.substring(deviceId.length - 6)

    /** `_bkStamp(ts)`: local `YYYYMMDD-HHMMSS` (sync.js:2803-2807). */
    fun stamp(ts: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz, Locale.US).apply { timeInMillis = ts }
        return String.format(
            Locale.US, "%d%02d%02d-%02d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
    }

    /** `_bkPath(tierKey, slot, ts)` with the stamp already made (sync.js:2808-2811). */
    fun path(t: BkTier, dev6: String, slot: Int, stampLocal: String): String =
        FOLDER + t.prefix + "-" + dev6 + "-" + slot.toString().padStart(2, '0') + "-" + stampLocal + ".json"

    /**
     * `_bkNextBoundary(tierKey, lastTs)` (sync.js:2821-2849). The boundary is derived from
     * [lastTs] (the tier's last fire), NOT from now; `lastTs == 0` is due at once (0).
     * (The card names the parameter `nowMs`; Opti passes the tier's `lastTs`, see G8.)
     */
    fun nextBoundary(t: BkTier, lastTs: Long, tz: TimeZone): Long {
        if (lastTs == 0L) return 0L
        if (t == BkTier.FOUR_HOUR) return lastTs + t.cadenceMs!!
        val base = Calendar.getInstance(tz, Locale.US).apply { timeInMillis = lastTs }
        fun localMidnight(y: Int, m: Int, d: Int): Calendar = Calendar.getInstance(tz, Locale.US).apply {
            clear()
            isLenient = true
            set(y, m, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val y = base.get(Calendar.YEAR)
        val m = base.get(Calendar.MONTH)
        val d = base.get(Calendar.DAY_OF_MONTH)
        return when (t) {
            BkTier.DAILY -> localMidnight(y, m, d + 1).timeInMillis
            BkTier.WEEKLY -> {
                val next = localMidnight(y, m, d + 1)
                // `while(next.getDay() !== 1) next.setDate(next.getDate() + 1)`
                while (next.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) next.add(Calendar.DAY_OF_MONTH, 1)
                next.timeInMillis
            }
            BkTier.MONTHLY -> localMidnight(y, m + 1, 1).timeInMillis
            BkTier.FOUR_HOUR -> error("unreachable")
        }
    }

    private fun escapeRegex(s: String): String = Regex.escape(s)

    /** `_bkListTier` filter: `^<prefix>-<dev6>-\d+-` (sync.js:2814-2820). */
    fun tierFiles(listing: List<String>, t: BkTier, dev6: String): List<String> {
        val re = Regex("^" + escapeRegex(t.prefix) + "-" + escapeRegex(dev6) + "-\\d+-")
        return listing.filter { re.containsMatchIn(it) }
    }

    /** JS `parseInt(s, 10)` for the slot part: leading digits, else null (NaN). */
    private fun parseIntJs(s: String): Int? {
        val t = s.trimStart()
        var i = 0
        if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
        val start = i
        while (i < t.length && t[i].isDigit()) i++
        if (i == start) return null
        return t.substring(0, i).toBigInteger().let { if (it > Int.MAX_VALUE.toBigInteger()) Int.MAX_VALUE else it.toInt() }
    }

    /**
     * The self-heal slot (sync.js:2869-2881): `(maxSlot + 1) % count` over this tier's files,
     * where a file's slot is `parseInt(name.split('-')[2])`.
     * [DEFERRED_OPEN_QUESTION] a device id whose last 6 chars hold a '-' shifts `split('-')[2]`
     * (Opti behaviour, kept). Kotlin ids are `dev-` + 8 hex chars, so their last 6 have none.
     */
    fun healedIndex(listing: List<String>, t: BkTier, dev6: String): Int {
        var maxSlot = -1
        for (name in tierFiles(listing, t, dev6)) {
            val parts = name.split('-')
            if (parts.size >= 3) {
                val s = parseIntJs(parts[2])
                if (s != null && s > maxSlot) maxSlot = s
            }
        }
        return (maxSlot + 1) % t.count
    }

    private data class Bk(val name: String, val stamp: String, val path: String)

    /**
     * The rotation of `_bkFire` (sync.js:2896-2945) as a pure function: the paths to delete, in
     * Opti's delete order, given the folder [listing] (file names) after the upload of
     * [justWritten] into [slot]. First every slot keeps only its newest file (the just-written
     * path pinned first); then, while more than `count` slots are occupied, the slots with the
     * oldest newest-stamp are removed (never [slot]).
     */
    fun rotation(listing: List<String>, t: BkTier, dev6: String, slot: Int = -1, justWritten: String? = null): List<String> {
        val slotMap = sortedMapOf<Int, MutableList<Bk>>()
        for (name in tierFiles(listing, t, dev6)) {
            val parts = name.split('-')
            if (parts.size < 4) continue
            val s = parseIntJs(parts[2]) ?: continue
            val stamp = parts.drop(3).joinToString("-").removeSuffix(".json")
            slotMap.getOrPut(s) { mutableListOf() }.add(Bk(name, stamp, FOLDER + name))
        }
        val deletes = mutableListOf<String>()
        // `for(const s in slotMap)`: integer keys iterate ascending.
        for ((s, files) in slotMap) {
            if (files.size > 1) {
                val sorted = files.sortedWith { a, b -> b.stamp.compareTo(a.stamp) }.toMutableList()
                val jw = sorted.indexOfFirst { it.path == justWritten }
                if (jw > 0) sorted.add(0, sorted.removeAt(jw))
                for (i in 1 until sorted.size) deletes += sorted[i].path
                slotMap[s] = mutableListOf(sorted[0])
            }
        }
        val occupied = slotMap.keys.toList()
        if (occupied.size > t.count) {
            val slotOldest = occupied.map { it to slotMap.getValue(it)[0].stamp }.sortedWith { a, b -> a.second.compareTo(b.second) }
            var toDelete = occupied.size - t.count
            for ((s, _) in slotOldest) {
                if (toDelete <= 0) break
                if (s == slot) continue
                slotMap.getValue(s).forEach { deletes += it.path }
                slotMap.remove(s)
                toDelete--
            }
        }
        return deletes
    }
}
