package com.example.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.datastore.preferences.core.edit
import com.example.MainActivity
import com.example.R
import com.example.data.inbox.QuickLogHabit
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.AuthState
import com.example.data.sync.syncDataStore
import com.example.ui.settings.EXTRA_OPEN_SETTINGS
import com.example.ui.settings.OPEN_SETTINGS_DROPBOX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WP-8.13 (PX-77, D6): the launcher long-press menu, ported from Bubblewrap
 * `quicklog/ShortcutPublisher.kt:16-94`.
 *
 * Owns only the dynamic shortcuts whose id starts with [PREFIX]; others are never touched.
 * - [AuthState.NOT_LINKED]: only "Connect Dropbox".
 * - [AuthState.RECONNECT_REQUIRED]: "Reconnect Dropbox" at rank 0, plus `MAX_ITEMS - 1` habit items.
 * - [AuthState.LINKED]: up to [MAX_ITEMS] habit items.
 *
 * The signature (`id=shortLabel|...`, stored in [SyncKeys.SHORTCUT_SIG]) skips the update when
 * nothing changed, because launchers rate-limit background updates. A pinned habit item whose
 * habit is gone is disabled with [ORPHAN_MESSAGE] (only while linked).
 *
 * Kotlin differences (O4): the habit list comes from Room (not `/inbox-meta/habits.json`), so it is
 * never null in practice; the connect item opens MainActivity -> Settings -> Dropbox
 * ([EXTRA_OPEN_SETTINGS]), not Bubblewrap's `SettingsActivity`.
 */
object ShortcutPublisher {
    const val PREFIX = "ql-"
    const val CONNECT_ID = "ql-connect"
    const val MAX_ITEMS = 4
    const val LABEL_MAX = 25
    const val ORPHAN_MESSAGE = "Habit no longer in Questa"

    /** Where the signature is kept. Default: the app DataStore key [SyncKeys.SHORTCUT_SIG]. */
    interface SigStore {
        suspend fun get(): String?
        suspend fun set(value: String)
    }

    class DataStoreSigStore(private val ctx: Context) : SigStore {
        override suspend fun get(): String? = ctx.applicationContext.syncDataStore.data.first()[SyncKeys.SHORTCUT_SIG]
        override suspend fun set(value: String) {
            ctx.applicationContext.syncDataStore.edit { it[SyncKeys.SHORTCUT_SIG] = value }
        }
    }

    /** One shortcut to publish (the pure part of Bubblewrap `habitShortcut` / `connectShortcut`). */
    data class Spec(
        val id: String,
        val shortLabel: String,
        val longLabel: String,
        val rank: Int,
        /** null for the connect item. */
        val habitId: String? = null,
        val title: String? = null,
        val dir: Int = 0,
    )

    /** The Bubblewrap `desired` list (ShortcutPublisher.kt:33-40). */
    fun desired(auth: AuthState, habits: List<QuickLogHabit>?): List<Spec> {
        if (auth == AuthState.NOT_LINKED) return listOf(connectSpec(false))
        val broken = auth == AuthState.RECONNECT_REQUIRED
        val named = (habits ?: emptyList()).map { it.copy(title = shortcutTitle(it)) }
        val items = pickShortcutItems(named, if (broken) MAX_ITEMS - 1 else MAX_ITEMS)
            .mapIndexed { i, item -> habitSpec(item, i + if (broken) 1 else 0) }
        return if (broken) listOf(connectSpec(true)) + items else items
    }

    /** Bubblewrap ShortcutPublisher.kt:43. */
    fun signature(desired: List<Spec>): String = desired.joinToString("|") { it.id + "=" + it.shortLabel }

    fun habitShortcutId(habitId: String, dir: Int): String =
        if (dir == -1) PREFIX + "h-" + habitId + "-down" else PREFIX + "h-" + habitId

    private fun habitSpec(item: ShortcutItem, rank: Int): Spec {
        val h = item.habit
        val label = (if (item.dir == -1) "−" + "1 " + h.title else "+1 " + h.title).take(LABEL_MAX)
        return Spec(habitShortcutId(h.id, item.dir), label, label, rank, h.id, h.title, item.dir)
    }

    private fun connectSpec(reconnect: Boolean): Spec {
        val label = if (reconnect) "Reconnect Dropbox" else "Connect Dropbox"
        return Spec(CONNECT_ID, label, "$label for quick log", 0)
    }

    private val lock = Mutex()

    /**
     * Bubblewrap `publish` (ShortcutPublisher.kt:24-61), with the auth state and the habit list passed
     * in. Safe on API 24 (ShortcutManagerCompat is a no-op there). Never throws.
     */
    suspend fun publish(
        ctx: Context,
        auth: AuthState,
        habits: List<QuickLogHabit>?,
        sigStore: SigStore = DataStoreSigStore(ctx),
    ) = lock.withLock {
        try {
            publishLocked(ctx.applicationContext, auth, habits, sigStore)
        } catch (e: Exception) {
            android.util.Log.w("QuestaQuickLog", "shortcut publish failed", e)
        }
    }

    private suspend fun publishLocked(app: Context, auth: AuthState, all: List<QuickLogHabit>?, sigStore: SigStore) {
        val specs = desired(auth, all)
        val desiredIds = specs.map { it.id }.toSet()
        val ours = ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id }.filter { it.startsWith(PREFIX) }
        val sig = signature(specs)
        // Launchers rate-limit background updates; skip when nothing changed.
        if (sig == sigStore.get() && ours.toSet() == desiredIds) return

        val stale = ours - desiredIds
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(app, stale)
        if (specs.isNotEmpty() && !ShortcutManagerCompat.addDynamicShortcuts(app, specs.map { build(app, it) })) return

        // A habit pinned to the home screen but deleted in Questa: grey it out instead of letting it
        // log to an id that would be rejected.
        if (auth == AuthState.LINKED && all != null) {
            val known = all.flatMap { listOf(habitShortcutId(it.id, 1), habitShortcutId(it.id, -1)) }.toSet()
            val gone = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }
                .filter { it.startsWith(PREFIX + "h-") && it !in known }
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(app, gone, ORPHAN_MESSAGE)
        }
        sigStore.set(sig)
    }

    private fun icon(ctx: Context) = IconCompat.createWithResource(ctx, R.drawable.ql_shortcut)

    internal fun build(ctx: Context, s: Spec): ShortcutInfoCompat {
        val intent = if (s.habitId == null) {
            Intent(Intent.ACTION_VIEW)
                .setClass(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_SETTINGS, OPEN_SETTINGS_DROPBOX)
        } else {
            Intent(QuickLogActivity.ACTION)
                .setClass(ctx, QuickLogActivity::class.java)
                .putExtra(QuickLogActivity.EXTRA_HABIT_ID, s.habitId)
                .putExtra(QuickLogActivity.EXTRA_TITLE, s.title)
                .putExtra(QuickLogActivity.EXTRA_DIR, s.dir)
        }
        return ShortcutInfoCompat.Builder(ctx, s.id)
            .setShortLabel(s.shortLabel)
            .setLongLabel(s.longLabel)
            .setIcon(icon(ctx))
            .setIntent(intent)
            .setRank(s.rank)
            .build()
    }
}
