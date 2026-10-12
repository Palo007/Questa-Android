package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.CharacterDao
import com.example.data.local.dao.EventLogDao
import com.example.data.local.dao.RewardDao
import com.example.data.local.dao.TaskDao
import com.example.data.local.dao.TagDao
import com.example.data.local.dao.DeviceDao
import com.example.data.local.dao.CharHistoryDao
import com.example.data.local.dao.TombstoneDao
import com.example.data.local.dao.SnapshotExtrasDao
import com.example.data.local.dao.MetricsExtrasDao
import com.example.data.local.dao.SyncBaseDao
import com.example.data.local.dao.SyncPrefsDao
import com.example.data.local.dao.CronDao
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.local.entities.CharHistoryEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.entities.SnapshotExtrasEntity
import com.example.data.local.entities.MetricsExtrasEntity
import com.example.data.local.entities.SyncBaseEntity
import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.local.entities.AttachmentUploadEntity
import com.example.data.local.dao.AttachmentUploadDao
import com.example.data.local.dao.CustomViewDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Database(
    entities = [
        CharacterEntity::class,
        TaskEntity::class,
        RewardEntity::class,
        EventLogEntity::class,
        TagEntity::class,
        DeviceEntity::class,
        CharHistoryEntity::class,
        TombstoneEntity::class,
        CustomViewEntity::class,
        SnapshotExtrasEntity::class,
        MetricsExtrasEntity::class,
        SyncBaseEntity::class,
        SyncPrefsEntity::class,
        AttachmentUploadEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun taskDao(): TaskDao
    abstract fun rewardDao(): RewardDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun tagDao(): TagDao
    abstract fun deviceDao(): DeviceDao
    abstract fun charHistoryDao(): CharHistoryDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun customViewDao(): CustomViewDao
    abstract fun snapshotExtrasDao(): SnapshotExtrasDao
    abstract fun metricsExtrasDao(): MetricsExtrasDao
    abstract fun syncBaseDao(): SyncBaseDao
    // WP-8.10: the sync_prefs (pause) DAO and the one-read cron inputs (§5.0 B10).
    abstract fun syncPrefsDao(): SyncPrefsDao
    abstract fun cronDao(): CronDao
    // CR-KT-015: pending attachment blob uploads.
    abstract fun attachmentUploadDao(): AttachmentUploadDao

    companion object {
        // Stage 3 / T3.1: v4 -> v5 adds the `extraJson` passthrough column to every
        // per-record entity (spec §7 D3 "unknown-field passthrough") plus the new
        // single-row `snapshot_extras` table for top-level `S` extras (`prefs`, decoy/
        // future fields, etc. -- see SnapshotExtrasEntity's class doc). All six ALTER
        // TABLE ADD COLUMN statements (tasks/rewards/tags/devices/character/char_history
        // -- char_history amended in via opus-review FIX 3, see below) are additive/
        // nullable, so no data conversion is needed for existing rows (they simply get
        // `extraJson = NULL`, i.e. "no known PWA original to overlay onto" -- exactly
        // the pre-T3.1 behavior for anything already in the database before this
        // migration runs).
        //
        // NOTE re: `fallbackToDestructiveMigration(true)` below -- this migration is
        // wired in via `addMigrations()` specifically so a real device upgrading from
        // v4 does NOT get its existing tasks/rewards/tags/devices/character wiped by
        // the destructive fallback (which remains in place only as a safety net for
        // version jumps this migration list doesn't cover, matching the pre-existing
        // project convention of not maintaining a full historical Migration chain --
        // see AppDatabase's pre-T3.1 history, which had no Migration objects at all and
        // relied solely on fallbackToDestructiveMigration for every prior version bump
        // (v1->v4)). Losing user data on a schema bump is precisely the kind of
        // data-loss T3.1/Stage 3 exists to eliminate, so this migration is real, not a
        // no-op placeholder -- unlike v1->v4's history, this one bump onward is meant
        // to be safe.
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN extraJson TEXT")
                db.execSQL("ALTER TABLE rewards ADD COLUMN extraJson TEXT")
                db.execSQL("ALTER TABLE tags ADD COLUMN extraJson TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN extraJson TEXT")
                db.execSQL("ALTER TABLE character ADD COLUMN extraJson TEXT")
                // T3.1 opus-review FIX 3: char_history didn't get its own extraJson in the initial
                // T3.1 pass (flagged as "Open risk #3" in the stage log); amended into this SAME
                // v4->v5 migration rather than bumping to v6, since v5 has not shipped/released
                // anywhere yet (confirmed via stage log/task tracking before amending) -- a shipped
                // migration must never be edited after the fact, but an unshipped one safely can.
                db.execSQL("ALTER TABLE char_history ADD COLUMN extraJson TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS snapshot_extras (" +
                        "id INTEGER NOT NULL PRIMARY KEY, extraJson TEXT)"
                )
                // Stage 3 / T3.2 (spec §3 "an.views / an.metrics shape") -- amended into this SAME
                // v4->v5 migration rather than bumping to v6, for the identical reason opus-review
                // FIX 3 amended char_history's extraJson column in here (v5 confirmed not shipped/
                // released anywhere yet at the time of this edit either -- see T3.1 log entry
                // above; a shipped migration must never be edited after the fact, but an unshipped
                // one safely can). `custom_views` gains the PWA `an.views` shape fields (additive/
                // nullable except `tags`/`types`, which use the existing List<String> TypeConverter
                // and get a `'[]'` default so pre-migration rows -- and any insert that omits them
                // -- decode to an empty list rather than null); `metrics_extras` is the new
                // opaque single-row `an.metrics` passthrough table (see MetricsExtrasEntity).
                db.execSQL("ALTER TABLE custom_views ADD COLUMN name TEXT")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN `group` TEXT")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN chart TEXT")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN types TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN metricId TEXT")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN updatedAt INTEGER")
                db.execSQL("ALTER TABLE custom_views ADD COLUMN extraJson TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS metrics_extras (" +
                        "id INTEGER NOT NULL PRIMARY KEY, json TEXT)"
                )
                // Stage 3 / T3.5 (D12, opus-review finding #3 on T3.1) -- amended into this SAME
                // v4->v5 migration for the identical "still unshipped, safe to amend in place"
                // reason as the two amendments above (re-confirmed unshipped at the time of this
                // edit). Adds the `orderIndex` array-position ordinal to tasks/rewards/tags (see
                // each entity's class doc). `NOT NULL DEFAULT 0` (required for `ALTER TABLE ADD
                // COLUMN` on a non-empty table, same reasoning T3.2 already established for
                // `custom_views.tags`/`.types`), followed by a backfill so existing rows land at
                // a sensible starting ordinal instead of all colliding at 0:
                //  - tasks: `orderIndex = -createdAt` reproduces the CURRENT `createdAt DESC`
                //    display order exactly under the new `orderIndex ASC` clause (negating a
                //    descending sort key of the same field yields the same relative order under
                //    ascending sort) -- i.e. this backfill is deliberately behavior-NEUTRAL for
                //    pre-existing local task order on the day a device upgrades, matching PWA's
                //    unshift-based (newest-first) task insertion this project already emulated
                //    via `createdAt DESC` before this migration.
                //  - rewards/tags: `orderIndex = createdAt` is a deliberate behavior CHANGE
                //    (oldest-first, ASC) -- PWA appends new rewards/tags via `.push()`
                //    (app.js:4763, 1912), so the pre-T3.5 `createdAt DESC` (newest-first) ordering
                //    was backwards relative to PWA for these two tables specifically (tasks alone
                //    used `unshift`); this backfill corrects existing rows to the PWA-equivalent
                //    order rather than freezing in the wrong one.
                db.execSQL("ALTER TABLE tasks ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rewards ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tags ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET orderIndex = -createdAt")
                db.execSQL("UPDATE rewards SET orderIndex = createdAt")
                db.execSQL("UPDATE tags SET orderIndex = createdAt")
            }
        }

        // Stage 4 / T4.2-T4.3 (spec §7 D6): adds the `uid`/`dev`/`synthetic` columns to
        // `event_logs` needed by the Dropbox `/events/*.json` push/pull pipeline (see
        // EventLogEntity's class doc for why these didn't exist before -- no prior feature ever
        // needed a stable per-event dedup id or origin-device tag). All three are additive; `uid`
        // and `dev` are nullable (pre-migration rows simply have no stamp and are therefore never
        // "uploadable" per addendum rule 1 -- correct, since they predate the feature and PWA
        // would treat any real event object missing `uid` identically: permanently ineligible for
        // push). `synthetic` requires `NOT NULL DEFAULT 0` (same reasoning MIGRATION_4_5 already
        // established for `custom_views.tags`/`.types` on a non-empty table) -- `0` here means
        // "not synthetic", i.e. every pre-existing row keeps its prior (implicit, unmarked)
        // eligibility rather than being retroactively excluded.
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE event_logs ADD COLUMN uid TEXT")
                db.execSQL("ALTER TABLE event_logs ADD COLUMN dev TEXT")
                db.execSQL("ALTER TABLE event_logs ADD COLUMN synthetic INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Stage 8 / WP-8.2 (D9, D8, D16, §4.3, §4.5 8.2 row) -- v6 -> v7:
        //  1. New tables `sync_base` (D8/D16 -- BaseStoreMigration lands data here later, at
        //     startup, not in this migration -- migrating a schema must never itself reach into
        //     DataStore) and `sync_prefs` (Opti `S.pause`, WP-8.10 owns real read/write behavior).
        //  2. `character.abs` and `event_logs.imported`/`republish`: simple nullable ADD COLUMNs
        //     (D9 typed backfill targets).
        //  3. `tasks` gains 8 new nullable columns (up/down/quickLog/reminders/completedAt/
        //     doneDay/cResetOn/cAbs) AND `streak` changes from `NOT NULL` to nullable (D9: "null =
        //     absent"). SQLite's `ALTER TABLE` cannot drop a `NOT NULL` constraint, so `tasks` is
        //     rebuilt (create the v7-shaped table, copy every row, drop the old table, rename) --
        //     the standard Room-recommended pattern for a nullability/type change, not a
        //     destructive fallback: every row and every existing column value survives.
        //  4. A single Kotlin cursor loop per table (D9: "no json_extract, minSdk is 24") backfills
        //     every new typed column from `extraJson` (`payloadJson` for `event_logs`, which has no
        //     `extraJson` column) using kotlinx.serialization -- never SQL JSON functions. A row
        //     whose `extraJson` is null (task/char created fresh in Kotlin, no PWA original) or does
        //     not parse as a JSON object (`parseJsonObjectOrNull` returns null) is left
        //     with its new columns at NULL and its `streak` UNCHANGED (there is no "PWA original"
        //     to declare the field absent from). A row whose `extraJson` IS present but does not
        //     contain a given key has that column backfilled to NULL ("absent" per the §5 card),
        //     except `streak`, which keeps the live column value (review r1 item 1, r2 minor 5): a
        //     row whose `extraJson` carries a `streak` key keeps the live column (extraJson's copy
        //     can be stale); a row whose `extraJson` has no `streak` key becomes `streak = NULL`
        //     only when the live column is 0, else keeps it (see the cursor loop below).
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- 1. New tables ---
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_base (" +
                        "id INTEGER NOT NULL PRIMARY KEY, appKey TEXT NOT NULL, accountId TEXT, " +
                        "rev TEXT NOT NULL, json TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_prefs (" +
                        "id INTEGER NOT NULL PRIMARY KEY, paused INTEGER, pausedDays TEXT, " +
                        "pausedAt INTEGER, extraJson TEXT)"
                )

                // --- 2. Simple additive columns ---
                // review r1 blocker 3: `character.abs` is a device-keyed object map
                // (CharAbsEntry), stored as JSON TEXT -- not a scalar INTEGER.
                db.execSQL("ALTER TABLE character ADD COLUMN abs TEXT")
                db.execSQL("ALTER TABLE event_logs ADD COLUMN imported INTEGER")
                db.execSQL("ALTER TABLE event_logs ADD COLUMN republish INTEGER")

                // --- 3. Rebuild `tasks` (new columns + streak nullability) ---
                // review r1 blocker 4: `tasks.cAbs` is a device-keyed object map (TaskAbsEntry),
                // stored as JSON TEXT -- not a scalar INTEGER.
                db.execSQL(
                    "CREATE TABLE tasks_new (" +
                        "id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, " +
                        "notes TEXT NOT NULL, difficulty TEXT NOT NULL, value REAL NOT NULL, " +
                        "done INTEGER NOT NULL, streak INTEGER, repeat TEXT, " +
                        "checklist TEXT NOT NULL, tags TEXT NOT NULL, history TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, doneAt INTEGER, " +
                        "up INTEGER, down INTEGER, quickLog TEXT, reminders TEXT, " +
                        "completedAt INTEGER, doneDay INTEGER, cResetOn INTEGER, cAbs TEXT, " +
                        "missedOn INTEGER, cUp INTEGER NOT NULL, cDown INTEGER NOT NULL, " +
                        "resetFreq TEXT, repsPerTap INTEGER, gr TEXT, extraJson TEXT, " +
                        "orderIndex INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO tasks_new (id, type, title, notes, difficulty, value, done, " +
                        "streak, repeat, checklist, tags, history, createdAt, updatedAt, doneAt, " +
                        "missedOn, cUp, cDown, resetFreq, repsPerTap, gr, extraJson, orderIndex) " +
                        "SELECT id, type, title, notes, difficulty, value, done, streak, repeat, " +
                        "checklist, tags, history, createdAt, updatedAt, doneAt, missedOn, cUp, " +
                        "cDown, resetFreq, repsPerTap, gr, extraJson, orderIndex FROM tasks"
                )
                db.execSQL("DROP TABLE tasks")
                db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

                // --- 4. Kotlin backfill from extraJson/payloadJson (D9: no json_extract) ---
                backfillTasks(db)
                backfillCharacter(db)
                backfillEventLogs(db)
            }

            private fun backfillTasks(db: SupportSQLiteDatabase) {
                val cursor = db.query("SELECT id, extraJson, streak FROM tasks")
                cursor.use {
                    val idIdx = it.getColumnIndexOrThrow("id")
                    val extraIdx = it.getColumnIndexOrThrow("extraJson")
                    val streakIdx = it.getColumnIndexOrThrow("streak")
                    while (it.moveToNext()) {
                        val id = it.getString(idIdx)
                        val extraJson = if (it.isNull(extraIdx)) null else it.getString(extraIdx)
                        val currentStreak = if (it.isNull(streakIdx)) null else it.getInt(streakIdx)
                        val obj = extraJson?.let { s -> parseJsonObjectOrNull(s) }

                        // review r1 blocker 1: extraJson is only ever written when a REMOTE record
                        // is parsed (SerializationEngine.kt:452ff); a purely-local completion
                        // (RpgEngine.kt:162) updates only the live `streak` column and never
                        // touches extraJson. So extraJson's own `streak` value can be stale -- it
                        // must never overwrite the live column: present (any value) -> keep the
                        // live column. review r2 minor 5: when the key is ABSENT, only null the
                        // column when it is already 0 -- a nonzero live column (a real local streak
                        // that simply predates this WP's extraJson-based backfill logic) is never
                        // regressed to null just because extraJson never modeled the field.
                        val newStreak: Int? = when {
                            obj == null -> currentStreak
                            obj.containsKey("streak") -> currentStreak
                            currentStreak == 0 -> null
                            else -> currentStreak
                        }

                        val up = (obj?.get("up") as? JsonPrimitive)?.booleanOrNull
                        val down = (obj?.get("down") as? JsonPrimitive)?.booleanOrNull
                        // review r1 blocker 2: ports Opti's quickLogMode(t) (app.js:2408-2414)
                        // exactly via QuickLogMode.fromWire, not an ad hoc string match. Absent
                        // key -> Room NULL (§5 "null = absent"); present (including `false`) ->
                        // the derived off/up/down/both mode, stored by its Room enum name.
                        val quickLog = if (obj != null && obj.containsKey("quickLog")) {
                            com.example.data.local.entities.QuickLogMode.fromWire(obj["quickLog"]).name
                        } else null
                        val completedAt = (obj?.get("completedAt") as? JsonPrimitive)?.longOrNull
                        val doneDay = (obj?.get("doneDay") as? JsonPrimitive)?.intOrNull
                        val cResetOn = (obj?.get("cResetOn") as? JsonPrimitive)?.intOrNull
                        // review r1 blocker 4: `cAbs` is `t.cAbs[deviceId] = {ua,cUp,cDown}`
                        // (sync.js:1859-1872), a device-keyed object map -- backfill it verbatim as
                        // JSON text only when it actually parses as an object; a legacy/malformed
                        // non-object value (e.g. a stray scalar) is dropped rather than guessed at.
                        val cAbs = (obj?.get("cAbs") as? JsonObject)?.toString()
                        // review r1 major 8 (reminders:null/non-array cases): reminders stays raw
                        // JSON text (Converters decodes it), but only when the extra object's
                        // `reminders` value actually IS an array -- `null` or any other shape (a
                        // stray string, an object) backfills to Room NULL instead of the literal
                        // text "null", which would otherwise round-trip as a non-empty-looking
                        // column that still decodes to nothing.
                        val reminders = (obj?.get("reminders") as? JsonArray)?.toString()

                        db.execSQL(
                            "UPDATE tasks SET streak = ?, up = ?, down = ?, quickLog = ?, " +
                                "reminders = ?, completedAt = ?, doneDay = ?, cResetOn = ?, cAbs = ? " +
                                "WHERE id = ?",
                            arrayOf<Any?>(newStreak, up?.toIntFlag(), down?.toIntFlag(), quickLog, reminders, completedAt, doneDay, cResetOn, cAbs, id)
                        )
                    }
                }
            }

            private fun backfillCharacter(db: SupportSQLiteDatabase) {
                val cursor = db.query("SELECT id, extraJson FROM character")
                cursor.use {
                    val idIdx = it.getColumnIndexOrThrow("id")
                    val extraIdx = it.getColumnIndexOrThrow("extraJson")
                    while (it.moveToNext()) {
                        val id = it.getInt(idIdx)
                        val extraJson = if (it.isNull(extraIdx)) null else it.getString(extraIdx)
                        val obj = extraJson?.let { s -> parseJsonObjectOrNull(s) }
                        // review r1 blocker 3: `char.abs` is a device-keyed object map
                        // (sync.js:1699-1712) -- same treatment as task.cAbs above.
                        val abs = (obj?.get("abs") as? JsonObject)?.toString()
                        db.execSQL("UPDATE character SET abs = ? WHERE id = ?", arrayOf<Any?>(abs, id))
                    }
                }
            }

            private fun backfillEventLogs(db: SupportSQLiteDatabase) {
                val cursor = db.query("SELECT id, payloadJson FROM event_logs")
                cursor.use {
                    val idIdx = it.getColumnIndexOrThrow("id")
                    val payloadIdx = it.getColumnIndexOrThrow("payloadJson")
                    while (it.moveToNext()) {
                        val id = it.getLong(idIdx)
                        val payloadJson = if (it.isNull(payloadIdx)) null else it.getString(payloadIdx)
                        val obj = payloadJson?.let { s -> parseJsonObjectOrNull(s) }
                        val imported = (obj?.get("imported") as? JsonPrimitive)?.booleanOrNull
                        val republish = (obj?.get("republish") as? JsonPrimitive)?.booleanOrNull
                        db.execSQL(
                            "UPDATE event_logs SET imported = ?, republish = ? WHERE id = ?",
                            arrayOf<Any?>(imported?.toIntFlag(), republish?.toIntFlag(), id)
                        )
                    }
                }
            }
        }

        // WP-8.2 (D9): the one Json instance the v6->v7 backfill uses to read `extraJson`/
        // `payloadJson`. `ignoreUnknownKeys` because a PWA original's own unmodeled fields (the
        // whole point of the extraJson passthrough) must never fail a parse here.
        private val migrationJson = Json { ignoreUnknownKeys = true }

        private fun parseJsonObjectOrNull(raw: String): JsonObject? = try {
            migrationJson.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        }

        private fun Boolean.toIntFlag(): Int = if (this) 1 else 0

        // CR-KT-015 (v7 -> v8): task images. Additive only: `tasks.attachments` (JSON TEXT,
        // default '[]' so every existing row reads as "no images") and the local pending-upload
        // table `attachment_uploads`. No row is rewritten.
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachment_uploads (" +
                        "sha TEXT NOT NULL PRIMARY KEY, queuedAt INTEGER NOT NULL)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "questa_database"
                )
                .withQuestaMigrationPolicy(*QUESTA_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }

        /** The production migration chain. */
        internal val QUESTA_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

        /**
         * The production migration + fallback policy, shared with AppDatabaseNoWipeTest.
         * KT-01: destructive fallback ONLY from the pre-repo versions 1..3 (no repo build ever
         * shipped them). Any other missing path (upgrade or downgrade) throws at open and keeps
         * the file. This supersedes the "safety net" note on MIGRATION_4_5 above.
         */
        internal fun <T : RoomDatabase> RoomDatabase.Builder<T>.withQuestaMigrationPolicy(
            vararg migrations: Migration
        ): RoomDatabase.Builder<T> = addMigrations(*migrations)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
    }
}
