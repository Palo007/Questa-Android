package com.example.data

import com.example.data.clock.DayStamp
import com.example.data.local.entities.CharHistoryEntity
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.GrantedReward
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import com.example.data.sync.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.ZoneId

/**
 * Game rules, ported from Opti app.js (7d5d4f3). Pure: no function reads a clock.
 *
 * WP-8.10 (§4.4, WP-8.6 hand-off): every `updatedAt` stamp is a parameter. RpgRepository passes
 * its HLC `now()` (Opti `t.updatedAt = now()`, and `save()` stamping `char.updatedAt = now()`,
 * app.js:233-243). Physical instants (`doneAt`, history dates, day stamps) are separate
 * parameters, as Opti reads `Date.now()` / `new Date()` for those.
 */
object RpgEngine {
    // app.js:2209 `DIFF = { trivial:0.1, easy:1, medium:1.5, hard:2, log:0 }`.
    private val DIFF_COEFFICIENTS = mapOf(
        "trivial" to 0.1,
        "easy" to 1.0,
        "medium" to 1.5,
        "hard" to 2.0,
        "log" to 0.0
    )

    fun clamp(value: Double, min: Double, max: Double): Double {
        return Math.max(min, Math.min(max, value))
    }

    fun xpToLevel(lvl: Int): Int {
        return Math.round(0.25 * lvl * lvl + 10.0 * lvl + 139.75).toInt()
    }

    fun valueDelta(value: Double): Double {
        return Math.pow(0.9747, clamp(value, -47.27, 21.27))
    }

    data class RewardResult(
        val xp: Int,
        val gold: Double,
        val mp: Int
    )

    data class CounterAdjustResult(
        val xp: Int,
        val gold: Double,
        val mp: Int,
        val hp: Double
    )

    /**
     * app.js:2221-2225. `DIFF[task.difficulty] || 1`: a missing key AND the falsy `log: 0` both
     * fall back to 1 (PX-69, G3).
     */
    fun coefficient(difficulty: String): Double =
        DIFF_COEFFICIENTS[difficulty.lowercase()]?.takeIf { it != 0.0 } ?: 1.0

    fun completionReward(difficulty: String, value: Double): RewardResult {
        val d = valueDelta(value)
        val m = coefficient(difficulty)
        val xp = Math.max(1, Math.round(d * m * 7.0).toInt())
        val gold = Math.max(0.0, Math.round(d * m * 1.2 * 100.0) / 100.0)
        val mp = Math.round(m).toInt()
        return RewardResult(xp, gold, mp)
    }

    fun missDamage(difficulty: String, value: Double): Double {
        val base = when (difficulty.lowercase()) {
            "hard" -> 4.0
            "medium" -> 3.0
            "trivial" -> 1.0
            else -> 2.0
        }
        return Math.round(base * valueDelta(value) * 0.9 * 100.0) / 100.0
    }

    // Stage 3 / T3.3 (spec §7 D5): char.updatedAt is stamped at each content mutation's .copy()
    // chokepoint. WP-8.10: the stamp is [now], the caller's HLC value (no wall clock here).
    fun gainXp(character: CharacterEntity, xp: Int, now: Long): CharacterEntity {
        var currentXp = character.xp + xp
        var currentLvl = character.lvl
        var currentHp = character.hp
        var currentMaxHp = character.maxHp

        var need = xpToLevel(currentLvl)
        while (currentXp >= need) {
            currentXp -= need
            currentLvl++
            currentMaxHp = 50.0 // HP limit is always 50 in standard config
            currentHp = currentMaxHp
            need = xpToLevel(currentLvl)
        }
        return character.copy(
            lvl = currentLvl,
            xp = currentXp,
            hp = currentHp,
            maxHp = currentMaxHp,
            updatedAt = now
        )
    }

    fun takeDamage(character: CharacterEntity, amount: Double, now: Long): CharacterEntity {
        val newHp = Math.round((character.hp - amount) * 100.0) / 100.0
        return if (newHp <= 0.0) {
            val newLvl = Math.max(1, character.lvl - 1)
            val newGold = Math.round((character.gold * 0.75) * 100.0) / 100.0
            character.copy(
                lvl = newLvl,
                gold = newGold,
                hp = character.maxHp,
                xp = 0,
                needsDeathAcknowledge = true,
                updatedAt = now
            )
        } else {
            character.copy(hp = newHp, updatedAt = now)
        }
    }

    fun buyReward(character: CharacterEntity, cost: Double, now: Long): CharacterEntity? {
        if (character.gold < cost) return null
        val newGold = Math.round((character.gold - cost) * 100.0) / 100.0
        return character.copy(gold = newGold, updatedAt = now)
    }

    /** PX-70 (G14): Opti SHOP_ITEMS, app.js:6466-6471. */
    data class ShopItem(val id: String, val title: String, val cost: Double, val desc: String)

    val SHOP_ITEMS: List<ShopItem> = listOf(
        ShopItem("potion", "Health Potion", 25.0, "Restore 15 HP."),
        ShopItem("bigpotion", "Greater Potion", 60.0, "Fully restore HP."),
    )

    /** app.js:6472-6480 `buyShopItem`: full HP, then gold, then `use()`. */
    fun buyPotion(character: CharacterEntity, type: String, now: Long): CharacterEntity? {
        val item = SHOP_ITEMS.firstOrNull { it.id == type } ?: return null
        if (character.hp >= character.maxHp) return null
        if (character.gold < item.cost) return null
        val newGold = Math.round((character.gold - item.cost) * 100.0) / 100.0
        val newHp = if (type == "potion") clamp(character.hp + 15.0, 0.0, character.maxHp) else character.maxHp
        return character.copy(hp = newHp, gold = newGold, updatedAt = now)
    }

    /**
     * app.js:2481-2526 `completeTask`. WP-8.10: G7 (the daily streak rises only when not
     * [paused]); SY-18 (`doneDay` = the local day of [nowMs]; `missedOn` cleared). [nowMs] is the
     * physical instant (`doneAt = Date.now()`), [now] the HLC stamp.
     *
     * CR-KT-020: writes the per-task `history` point exactly as the PWA -- daily: `value`,
     * `completed:true`, `isDue:true`, a copy of `_gr`, a copy of `repeat` and a `{text,done}`
     * checklist snapshot when the checklist is non-empty (:2514); todo: `value`, `completed:true`,
     * a copy of `_gr` (:2521). Records the REALIZED `_gr` delta (`newValue - task.value` after
     * the clamp, :2491-2493, so a complete/un-complete round trip at the 99 ceiling no longer
     * walks the value down), stamps the todo `completedAt` (:2520), and stamps `_histCreated` for
     * dailies only (:2515): `true` = this completion PUSHED the day's point, `false` = it merged
     * into an existing same-UTC-day one. This resolves the Stage 6 / T6.7
     * [DEFERRED_OPEN_QUESTION] about `completeTask`'s history point and `_gr`.
     */
    fun completeTask(
        character: CharacterEntity,
        task: TaskEntity,
        todayDow: Int = -1,
        paused: Boolean = false,
        nowMs: Long,
        now: Long,
        zone: ZoneId
    ): Pair<CharacterEntity, TaskEntity> {
        if (task.done) return Pair(character, task)

        // Safety gate: Dailies not scheduled for today cannot be completed.
        if (task.type == "daily" && todayDow != -1 && !isDailyDueOn(task, todayDow)) {
            return Pair(character, task)
        }

        val r = completionReward(task.difficulty, task.value)
        val delta = valueDelta(task.value)

        val updatedChar = gainXp(character, r.xp, now).let {
            it.copy(
                gold = Math.round((it.gold + r.gold) * 100.0) / 100.0,
                mp = it.mp + r.mp
            )
        }

        val newValue = clamp(task.value + delta, -47.27, 99.0)
        // app.js:2491-2493: `_gr.delta` is the REALIZED delta, not the requested one, so
        // `reverseGrant`'s `value - gr.delta` restores exactly what the clamp granted.
        val realizedDelta = newValue - task.value
        val gr = GrantedReward(r.xp, r.gold, r.mp, realizedDelta)

        // app.js:2508-2523: the history point; `_histCreated` is a daily-only stamp.
        val newHistory: List<TaskHistoryPoint>
        val histCreated: Boolean?
        when (task.type) {
            "daily" -> {
                val cl = task.checklist
                newHistory = logHistory(
                    task.history,
                    HistoryPatch(
                        value = newValue,
                        completed = true,
                        isDue = true, // safe: a non-due daily is gated above
                        reward = gr,
                        repeat = task.repeat ?: emptyList(),
                        checklist = if (cl.isNotEmpty()) cl.map { snapItem(it, it.done) } else null,
                    ),
                    nowMs,
                )
                // app.js:2513-2515 `t._histCreated = ((t.history||[]).length > _hLen)`: true when
                // the point was PUSHED, false when it merged into an existing one (a cron miss,
                // an imported row). `unlogToday` may only pop a point it created.
                histCreated = newHistory.size > task.history.size
            }
            "todo" -> {
                newHistory = logHistory(task.history, HistoryPatch(value = newValue, completed = true, reward = gr), nowMs)
                // The PWA touches `_histCreated` only in the daily branch: a todo is never stamped.
                histCreated = task.histCreated
            }
            else -> {
                // Not reachable from the app's tick paths; a completion of any other type
                // writes no point (PWA completeTask has no branch for it either).
                newHistory = task.history
                histCreated = task.histCreated
            }
        }

        val updatedTask = task.copy(
            value = newValue,
            done = true,
            doneAt = nowMs,
            doneDay = LocalDay.dayStamp(nowMs, zone),
            // WP-8.2 collateral fix: TaskEntity.streak is Int? (schema v7, D9 "null = absent").
            streak = if (task.type == "daily" && !paused) (task.streak ?: 0) + 1 else task.streak,
            updatedAt = now,
            missedOn = null,
            gr = gr,
            history = newHistory,
            histCreated = histCreated,
            // app.js:2520: `t.completedAt = Date.now()` -- todos only.
            completedAt = if (task.type == "todo") nowMs else task.completedAt,
        )

        return Pair(updatedChar, updatedTask)
    }

    /**
     * app.js:2554-2580 `uncompleteDaily`/`uncompleteTodo`: `reverseGrant` (the xp/gold/mp
     * clawback and the clamped value restore from `_gr.delta`), then [unlogToday], then
     * `done = false`, `doneAt`/`doneDay` retracted, streak -1 only when not [paused], `updatedAt`
     * bumped. [nowMs] is the physical instant `unlogToday`'s day test reads (`Date.now()`).
     *
     * [DEFERRED_OPEN_QUESTION] CR-PWA-004 (RE-PWA-021 quirk a): the todo un-tick KEEPS
     * `completedAt`, as today's PWA `uncompleteTodo` does (app.js:2569-2580); the fixed PWA will
     * delete it (the analytics todo fallback `!any && t.completedAt`, app.js:4428, still counts a
     * reverted todo). Held as-is until CR-PWA-004 settles and the new fixture is checked.
     */
    fun uncompleteTask(character: CharacterEntity, task: TaskEntity, paused: Boolean = false, nowMs: Long, now: Long): Pair<CharacterEntity, TaskEntity> {
        if (!task.done) return Pair(character, task)
        val gr = task.gr ?: GrantedReward(0, 0.0, 0, valueDelta(task.value))

        val newXp = Math.max(0, character.xp - gr.xp)
        val newGold = Math.max(0.0, Math.round((character.gold - gr.gold) * 100.0) / 100.0)
        val newMp = Math.max(0, character.mp - gr.mp)
        val updatedChar = character.copy(xp = newXp, gold = newGold, mp = newMp, updatedAt = now)

        val newValue = clamp(task.value - gr.delta, -47.27, 99.0)
        // app.js:2557: `unlogToday` runs on the RESTORED value (reverseGrant ran first) and owns
        // `_histCreated`'s deletion.
        val (newHistory, newHistCreated) = unlogToday(task.history, newValue, task.histCreated, nowMs)
        val updatedTask = task.copy(
            value = newValue,
            done = false,
            doneAt = null,
            doneDay = null,
            streak = if (task.type == "daily" && (task.streak ?: 0) > 0 && !paused) (task.streak ?: 0) - 1 else task.streak,
            updatedAt = now,
            gr = null,
            history = newHistory,
            histCreated = newHistCreated,
            // `completedAt` is deliberately NOT touched here -- [DEFERRED_OPEN_QUESTION]
            // CR-PWA-004 (quirk a): the PWA un-tick keeps it; the fixed one deletes it.
        )

        return Pair(updatedChar, updatedTask)
    }

    /**
     * app.js:2536-2553 `unlogToday(t)`. Only when the LAST point is dated the current UTC day
     * (`floor(ms/86400000)`, as Opti): set it `completed = false`; pop it when this completion
     * created it ([histCreated] `true` and the point carries no scoredUp/scoredDown); otherwise
     * keep it with `value` = the restored [restoredValue] and no `reward`. `_histCreated` is
     * deleted exactly when the day test passes (both branches) -- never when it does not, and
     * never when the history is empty.
     *
     * [DEFERRED_OPEN_QUESTION] CR-PWA-004 (RE-PWA-021 quirk b): the UTC day test, and the fact
     * that only the LAST point is considered, are today's accepted behavior; the fixed PWA uses a
     * device-local day test and also un-marks points dated earlier days. Held until CR-PWA-004
     * settles and the new fixture is checked.
     */
    private fun unlogToday(
        history: List<TaskHistoryPoint>,
        restoredValue: Double,
        histCreated: Boolean?,
        nowMs: Long,
    ): Pair<List<TaskHistoryPoint>, Boolean?> {
        val last = history.lastOrNull() ?: return Pair(history, histCreated)
        fun dayOf(ms: Long) = Math.floorDiv(ms, 86_400_000L)
        if (dayOf(last.date) != dayOf(nowMs)) return Pair(history, histCreated)

        // `if(last.completed){ last.completed=false; }` -- a falsy/absent flag is left as it is.
        val updated = if (last.completed == true) last.copy(completed = false) else last
        // `if(t._histCreated && !last.scoredUp && !last.scoredDown && !last.completed) pop`
        // (`last` here is the point AFTER the un-mark above).
        val pop = histCreated == true &&
            (last.scoredUp ?: 0) == 0 && (last.scoredDown ?: 0) == 0 &&
            updated.completed != true
        return if (pop) {
            Pair(history.dropLast(1), null)
        } else {
            Pair(history.dropLast(1) + updated.copy(value = restoredValue, reward = null), null)
        }
    }

    /** app.js:3573 `repsPerTap(title)`: the first run of digits in the title, else 1. */
    fun repsPerTap(title: String?): Int {
        val m = Regex("\\d+").find(title ?: "") ?: return 1
        // [DEFERRED_OPEN_QUESTION] JS parseInt of a digit run past Int range is a large float; clamped here.
        return m.value.toIntOrNull() ?: Int.MAX_VALUE
    }

    /** `t.repsPerTap || repsPerTap(t.title)` (app.js:2476). */
    fun tapReps(task: TaskEntity): Int = task.repsPerTap?.takeIf { it != 0 } ?: repsPerTap(task.title)

    /**
     * app.js:2464-2499 `scoreHabit(id, +1)`. PX-69 (G12): a log habit is a pure tally with a
     * reps history point (`scored: false`); a scored tap records `scoredUp`, reps and `scored: true`.
     * [atMs] is the history date (Opti `meta.atMs`, or `Date.now()`).
     */
    fun scoreHabitUp(character: CharacterEntity, task: TaskEntity, now: Long, atMs: Long): Pair<CharacterEntity, TaskEntity> {
        val reps = tapReps(task)
        if (task.difficulty.lowercase() == "log") {
            val updatedTask = task.copy(
                cUp = task.cUp + 1,
                history = logHistory(task.history, HistoryPatch(value = task.value, reps = reps, repCounted = true, scored = false), atMs),
                updatedAt = now
            )
            return Pair(character, updatedTask)
        }
        val r = completionReward(task.difficulty, task.value)
        val delta = valueDelta(task.value)

        val updatedChar = gainXp(character, r.xp, now).let {
            it.copy(
                gold = Math.round((it.gold + r.gold) * 100.0) / 100.0,
                mp = it.mp + r.mp
            )
        }

        val newValue = clamp(task.value + delta, -47.27, 99.0)
        val updatedTask = task.copy(
            value = newValue,
            cUp = task.cUp + 1,
            history = logHistory(task.history, HistoryPatch(value = newValue, scoredUp = 1, reps = reps, repCounted = true, scored = true), atMs),
            updatedAt = now
        )

        return Pair(updatedChar, updatedTask)
    }

    /** app.js:2476-2507 `scoreHabit(id, -1)`. */
    fun scoreHabitDown(character: CharacterEntity, task: TaskEntity, now: Long, atMs: Long): Pair<CharacterEntity, TaskEntity> {
        if (task.difficulty.lowercase() == "log") {
            val updatedTask = task.copy(
                cDown = task.cDown + 1,
                history = logHistory(task.history, HistoryPatch(value = task.value, reps = tapReps(task), repCounted = true, scored = false), atMs),
                updatedAt = now
            )
            return Pair(character, updatedTask)
        }
        val dmg = missDamage(task.difficulty, task.value)
        val delta = valueDelta(task.value)

        val updatedChar = takeDamage(character, dmg, now)
        val newValue = clamp(task.value - delta, -47.27, 99.0)
        val updatedTask = task.copy(
            value = newValue,
            cDown = task.cDown + 1,
            history = logHistory(task.history, HistoryPatch(value = newValue, scoredDown = 1), atMs),
            updatedAt = now
        )

        return Pair(updatedChar, updatedTask)
    }

    /**
     * Opti saveTask counter loops (027e371 app.js:6906-6950): the habit counter edited in the task
     * editor. [upDelta]/[downDelta] are the user's own +/- in the editor (Opti: sheet copy minus the
     * open-time baseline `_base`); the task gets [newCUp]/[newCDown]. The returned
     * [CounterAdjustResult] is the `edit` event's `counter` object (CR-KT-016), Opti `_cXp/_cGold/
     * _cMp/_cHp`: the reward / damage amounts as computed, NOT the character's clamped change
     * (`_cGold`/`_cHp` are `+(x).toFixed(2)` after every step; a level change does not count).
     */
    fun adjustHabitCounters(
        character: CharacterEntity,
        task: TaskEntity,
        newCUp: Int,
        newCDown: Int,
        now: Long,
        upDelta: Int = newCUp - task.cUp,
        downDelta: Int = newCDown - task.cDown,
    ): Triple<CharacterEntity, TaskEntity, CounterAdjustResult> {
        var currentChar = character
        var currentValue = task.value
        var cXp = 0
        var cGold = 0.0
        var cMp = 0
        var cHp = 0.0

        // UP loop
        if (upDelta > 0) {
            for (i in 0 until upDelta) {
                val r = completionReward(task.difficulty, currentValue)
                currentChar = gainXp(currentChar, r.xp, now).let {
                    it.copy(
                        gold = Math.round((it.gold + r.gold) * 100.0) / 100.0,
                        mp = it.mp + r.mp
                    )
                }
                cXp += r.xp
                cGold = jsToFixed2(cGold + r.gold)
                cMp += r.mp
                currentValue = clamp(currentValue + valueDelta(currentValue), -47.27, 99.0)
            }
        } else if (upDelta < 0) {
            for (i in 0 until -upDelta) {
                val r = completionReward(task.difficulty, currentValue)
                val newXp = Math.max(0, currentChar.xp - r.xp)
                val newGold = Math.max(0.0, Math.round((currentChar.gold - r.gold) * 100.0) / 100.0)
                val newMp = Math.max(0, currentChar.mp - r.mp)
                currentChar = currentChar.copy(xp = newXp, gold = newGold, mp = newMp, updatedAt = now)
                cXp -= r.xp
                cGold = jsToFixed2(cGold - r.gold)
                cMp -= r.mp
                currentValue = clamp(currentValue - valueDelta(currentValue), -47.27, 99.0)
            }
        }

        // DOWN loop
        if (downDelta > 0) {
            for (i in 0 until downDelta) {
                val dmg = missDamage(task.difficulty, currentValue)
                currentChar = takeDamage(currentChar, dmg, now)
                cHp = jsToFixed2(cHp - dmg)
                currentValue = clamp(currentValue - valueDelta(currentValue), -47.27, 99.0)
            }
        } else if (downDelta < 0) {
            for (i in 0 until -downDelta) {
                val heal = missDamage(task.difficulty, currentValue)
                val newHp = clamp(currentChar.hp + heal, 0.0, currentChar.maxHp)
                currentChar = currentChar.copy(hp = newHp, updatedAt = now)
                cHp = jsToFixed2(cHp + heal)
                currentValue = clamp(currentValue + valueDelta(currentValue), -47.27, 99.0)
            }
        }

        if (upDelta == 0 && downDelta == 0 && newCUp == task.cUp && newCDown == task.cDown) {
            return Triple(character, task, CounterAdjustResult(0, 0.0, 0, 0.0))
        }
        val updatedTask = task.copy(
            cUp = newCUp,
            cDown = newCDown,
            value = currentValue,
            updatedAt = now
        )

        return Triple(currentChar, updatedTask, CounterAdjustResult(cXp, cGold + 0.0, cMp, cHp + 0.0))
    }

    /** JS `+x.toFixed(2)`: rounds the exact binary value, ties away from zero, `-0` becomes `0`. */
    private fun jsToFixed2(v: Double): Double {
        if (v.isNaN() || v.isInfinite() || kotlin.math.abs(v) >= 1e21) return v
        return java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toDouble() + 0.0
    }

    /** app.js:100 `isDailyDueOn(t, dow) = !t.repeat || !!t.repeat[dow]` (dow: 0 = Sunday). */
    fun isDailyDueOn(task: TaskEntity, dow: Int): Boolean {
        return task.repeat == null || (dow in 0..6 && task.repeat.getOrNull(dow) == true)
    }

    // ---------------------------------------------------------------- history (app.js:2258-2280)

    /** The keys of a `logHistory` patch; null = the key is absent from the JS patch object. */
    data class HistoryPatch(
        val value: Double? = null,
        val scoredUp: Int? = null,
        val scoredDown: Int? = null,
        val completed: Boolean? = null,
        val checklist: List<ChecklistItem>? = null,
        val reward: GrantedReward? = null,
        val reps: Int? = null,
        val repCounted: Boolean? = null,
        val scored: Boolean? = null,
        val isDue: Boolean? = null,
        val repeat: List<Boolean>? = null,
    )

    /**
     * app.js:2258-2280 `logHistory(t, patch, atMs)`. Same UTC day (`floor(ms/86400000)`, as
     * Opti) as the last point: merge the listed keys into it (isDue/repeat are NOT merged, as
     * Opti); else push `{date, ...patch}` and re-sort by date when the push is out of order.
     */
    fun logHistory(history: List<TaskHistoryPoint>, patch: HistoryPatch, atMs: Long): List<TaskHistoryPoint> {
        val now = atMs
        fun dayOf(ms: Long) = Math.floorDiv(ms, 86_400_000L)
        val last = history.lastOrNull()
        if (last != null && dayOf(last.date) == dayOf(now)) {
            val merged = last.copy(
                date = now,
                value = patch.value ?: last.value,
                scoredUp = patch.scoredUp?.let { (last.scoredUp ?: 0) + it } ?: last.scoredUp,
                scoredDown = patch.scoredDown?.let { (last.scoredDown ?: 0) + it } ?: last.scoredDown,
                completed = patch.completed ?: last.completed,
                checklist = patch.checklist ?: last.checklist,
                reward = patch.reward ?: last.reward,
                reps = patch.reps?.let { (last.reps ?: 0) + it } ?: last.reps,
                repCounted = patch.repCounted?.let { (last.repCounted == true) || it } ?: last.repCounted,
                scored = patch.scored?.let { (last.scored == true) || it } ?: last.scored,
            )
            return history.dropLast(1) + merged
        }
        val point = TaskHistoryPoint(
            date = now,
            value = patch.value,
            scoredUp = patch.scoredUp,
            scoredDown = patch.scoredDown,
            completed = patch.completed,
            checklist = patch.checklist,
            reward = patch.reward,
            reps = patch.reps,
            repCounted = patch.repCounted,
            scored = patch.scored,
            isDue = patch.isDue,
            repeat = patch.repeat,
        )
        val out = history + point
        val n = out.size
        return if (n > 1 && out[n - 2].date > now) out.sortedBy { it.date } else out
    }

    /** A `{text, done}` history snapshot item (no id), as Opti `cl.map(c=>({text,done}))`. */
    private fun snapItem(c: ChecklistItem, done: Boolean): ChecklistItem = ChecklistItem(
        id = c.id,
        text = c.text,
        done = done,
        extraJson = buildJsonObject {
            put("text", JsonPrimitive(c.text))
            put("done", JsonPrimitive(done))
        }.toString()
    )

    // ------------------------------------------------------------ event payloads (logEvent extras)

    /** `(t.repeat||[]).slice()` */
    private fun repeatJson(t: TaskEntity) = JsonArray((t.repeat ?: emptyList()).map { JsonPrimitive(it) })

    /** `cl.map(c=>({id:c.id||null, text:c.text, done}))` */
    private fun checklistJson(cl: List<ChecklistItem>, done: (ChecklistItem) -> Boolean) = JsonArray(cl.map { c ->
        buildJsonObject {
            put("id", if (c.id.isEmpty()) JsonNull else JsonPrimitive(c.id))
            put("text", JsonPrimitive(c.text))
            put("done", JsonPrimitive(done(c)))
        }
    })

    /** `Object.assign({}, t._gr)` = {xp, gold, mp, delta} */
    private fun rewardJson(gr: GrantedReward) = buildJsonObject {
        put("xp", JsonPrimitive(gr.xp))
        put("gold", CanonicalJson.jsonNumber(gr.gold))
        put("mp", JsonPrimitive(gr.mp))
        put("delta", CanonicalJson.jsonNumber(gr.delta))
    }

    // ------------------------------------------------------------ RYA (app.js:2636-2691)

    /**
     * app.js:2640-2653 `missedYesterdayDailies()`, with one [nowMs] snapshot: paused → none;
     * `dayStamp(now) <= lastCron` → none (ordering); yesterday recorded in `pausedDays` → none;
     * else the dailies due yesterday (`repeat[(getDay()+6)%7]`) and not done, in task order.
     */
    fun missedYesterdayDailies(tasks: List<TaskEntity>, pause: PauseState, lastCron: DayStamp, nowMs: Long, zone: ZoneId): List<TaskEntity> {
        if (pause.paused) return emptyList()
        if (LocalDay.dayStamp(nowMs, zone) <= lastCron) return emptyList()
        val yStamp = LocalDay.dayStamp(LocalDay.localDayDateAtOffset(nowMs, -1, zone), zone)
        if (pause.pausedDays.contains(yStamp)) return emptyList()
        val dow = LocalDay.getDay(nowMs, zone)
        return tasks.filter { t -> t.type == "daily" && isDailyDueOn(t, (dow + 6) % 7) && !t.done }
    }

    data class CreditResult(val character: CharacterEntity, val task: TaskEntity, val log: EventLogEntity)

    /**
     * app.js:2667-2691 `creditYesterday(t)`: null = not credited (done, or a daily not due
     * yesterday). Reward as completeTask, realized value delta in `_gr`, `doneAt` = LOCAL noon of
     * yesterday, `doneDay` = that day, `missedOn` cleared, streak +1 unless [paused], and a
     * backdated history point (a plain push, as Opti). [nowMs] = the commit-time clock (§5.0 B12).
     */
    fun creditYesterday(character: CharacterEntity, task: TaskEntity, paused: Boolean, nowMs: Long, now: Long, zone: ZoneId): CreditResult? {
        if (task.done) return null
        if (task.type == "daily" && !isDailyDueOn(task, (LocalDay.getDay(nowMs, zone) + 6) % 7)) return null
        val r = completionReward(task.difficulty, task.value)
        val delta = valueDelta(task.value)
        val char = gainXp(character, r.xp, now).let {
            it.copy(gold = Math.round((it.gold + r.gold) * 100.0) / 100.0, mp = it.mp + r.mp)
        }
        val newValue = clamp(task.value + delta, -47.27, 99.0)
        val realized = newValue - task.value
        val yMs = LocalDay.localDayDateAtOffset(nowMs, -1, zone)
        val gr = GrantedReward(r.xp, r.gold, r.mp, realized)
        val streak = if (!paused) (task.streak ?: 0) + 1 else task.streak
        val cl = task.checklist
        val point = TaskHistoryPoint(
            date = yMs,
            value = newValue,
            completed = true,
            isDue = true,
            reward = gr,
            repeat = task.repeat ?: emptyList(),
            checklist = if (cl.isNotEmpty()) cl.map { snapItem(it, true) } else null,
        )
        val updated = task.copy(
            value = newValue,
            done = true,
            updatedAt = now,
            doneAt = yMs,
            doneDay = LocalDay.dayStamp(yMs, zone),
            missedOn = null,
            gr = gr,
            streak = streak,
            history = task.history + point,
        )
        val log = EventLogEntity(
            timestamp = nowMs,
            kind = "complete",
            taskType = "daily",
            taskId = task.id,
            taskTitle = task.title,
            streak = streak,
            // app.js:2686-2688: {kind, taskType, taskId, taskTitle, streak, reward, repeat, late, checklist}
            payloadJson = buildJsonObject {
                put("reward", rewardJson(gr))
                put("repeat", repeatJson(task))
                put("late", JsonPrimitive(true))
                put("checklist", checklistJson(cl) { true })
            }.toString()
        )
        return CreditResult(char, updated, log)
    }

    // ------------------------------------------------------------ cron (app.js:2899-2981)

    data class CronInput(
        val character: CharacterEntity,
        val tasks: List<TaskEntity>,
        val lastCron: DayStamp,
        val nowMs: Long,
        val zone: ZoneId,
        val now: Long,
    )

    data class CronOutput(
        val ran: Boolean,
        val character: CharacterEntity,
        val tasks: List<TaskEntity>,
        val missLogs: List<EventLogEntity>,
        val pause: PauseState,
        val charSnapshot: CharHistoryEntity?,
        val totalDamage: Double,
    )

    /**
     * app.js:2911-2981 `runCron()`. [today] = `dayStamp(_now)`. The ordering guard
     * `today <= lastCron → return` (:2922, PX-66). Paused (:2923-2944, PX-65): `lastCron = today`,
     * `pausedDays += today` (deduped, last 7), habits still reset on their own boundary (against
     * the PREVIOUS lastCron) with `cResetOn = today`, dailies reset, nothing else. Not paused: the
     * habit reset, miss damage for dailies due yesterday and not done unless yesterday is in
     * `pausedDays` (`_cov`), `missedOn` = yesterday, a miss history point backdated to yesterday
     * noon, `_resetDailies()`, one `takeDamage(total)`, and a char snapshot (`logCharSnapshot`).
     * Cron never bumps a task's `updatedAt` (F3) and `_resetDailies` leaves `doneAt`/`doneDay`.
     */
    fun runCron(input: CronInput, today: DayStamp, pause: PauseState): CronOutput {
        val c0 = input.character
        if (today <= input.lastCron) {
            return CronOutput(false, c0, input.tasks, emptyList(), pause, null, 0.0)
        }
        val prevCron = input.lastCron
        fun resetDaily(t: TaskEntity): TaskEntity =
            if (t.type == "daily") t.copy(done = false, checklist = t.checklist.map { it.copy(done = false) }) else t
        fun resetHabit(t: TaskEntity): TaskEntity =
            if (t.type == "habit" && Week.periodBoundaryCrossed(t.resetFreq ?: "daily", prevCron, input.nowMs, input.zone)) {
                t.copy(cUp = 0, cDown = 0, cResetOn = today)
            } else t
        val todayStr = today.toString()

        if (pause.paused) {
            val days = (pause.pausedDays + today).distinct().takeLast(7)
            val tasks = input.tasks.map { resetDaily(resetHabit(it)) }
            return CronOutput(true, c0.copy(lastCron = todayStr), tasks, emptyList(), PauseState(true, days), null, 0.0)
        }

        val dow = LocalDay.getDay(input.nowMs, input.zone)
        val yNoon = LocalDay.localDayDateAtOffset(input.nowMs, -1, input.zone)
        val yesterdayStamp = LocalDay.dayStamp(yNoon, input.zone)
        val cov = pause.pausedDays.contains(yesterdayStamp)
        var totalDmg = 0.0
        val logs = mutableListOf<EventLogEntity>()
        val tasks = input.tasks.map { t0 ->
            when (t0.type) {
                "habit" -> resetHabit(t0)
                "daily" -> {
                    var t = t0
                    if (isDailyDueOn(t, (dow + 6) % 7) && !t.done && !cov) {
                        val dmg = missDamage(t.difficulty, t.value)
                        totalDmg += dmg
                        val newValue = clamp(t.value - valueDelta(t.value), -47.27, 99.0)
                        t = t.copy(
                            value = newValue,
                            streak = 0,
                            missedOn = yesterdayStamp,
                            history = logHistory(t.history, HistoryPatch(value = newValue, completed = false, isDue = true, repeat = t.repeat ?: emptyList()), yNoon)
                        )
                        logs.add(EventLogEntity(
                            timestamp = input.nowMs,
                            kind = "miss",
                            taskType = "daily",
                            taskId = t.id,
                            taskTitle = t.title,
                            damage = dmg,
                            // app.js:2971-2974: {..., dmg, repeat, checklist} (checklist before _resetDailies)
                            payloadJson = buildJsonObject {
                                put("repeat", repeatJson(t))
                                put("checklist", checklistJson(t.checklist) { it.done })
                            }.toString()
                        ))
                    }
                    resetDaily(t)
                }
                else -> t0
            }
        }
        var char = c0.copy(lastCron = todayStr)
        if (totalDmg > 0.0) char = takeDamage(char, totalDmg, input.now)
        val snap = CharHistoryEntity(date = input.nowMs, hp = char.hp, maxHp = char.maxHp, xp = char.xp, mp = char.mp, gold = char.gold, lvl = char.lvl)
        return CronOutput(true, char, tasks, logs, pause, snap, totalDmg)
    }
}
