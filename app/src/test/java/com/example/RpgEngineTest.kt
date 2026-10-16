package com.example

import com.example.data.RpgEngine
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

// WP-8.10: RpgEngine reads no clock; every stamp is a parameter.
private const val NOW = 1_784_000_000_000L
private val ZONE: ZoneId = ZoneId.of("UTC")

class RpgEngineTest {

    @Test
    fun testXpToLevelFormula() {
        // xpToLevel(lvl) = Math.round(0.25 * lvl * lvl + 10 * lvl + 139.75)
        // lvl = 1 -> 0.25 + 10 + 139.75 = 150
        assertEquals(150, RpgEngine.xpToLevel(1))
        // lvl = 2 -> 0.25*4 + 20 + 139.75 = 1 + 20 + 139.75 = 160.75 -> 161
        assertEquals(161, RpgEngine.xpToLevel(2))
    }

    @Test
    fun testXpGainsAndLevelUp() {
        var char = CharacterEntity(lvl = 1, xp = 0, hp = 30.0, maxHp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Gain 100 XP (no level up)
        char = RpgEngine.gainXp(char, 100, now = NOW)
        assertEquals(1, char.lvl)
        assertEquals(100, char.xp)
        assertEquals(30.0, char.hp, 0.001)

        // Gain 60 more XP (levels up to 2, 10 XP carried over, HP fully restored to maxHp)
        char = RpgEngine.gainXp(char, 60, now = NOW)
        assertEquals(2, char.lvl)
        assertEquals(10, char.xp)
        assertEquals(50.0, char.hp, 0.001)
    }

    @Test
    fun testMultipleLevelsUp() {
        var char = CharacterEntity(lvl = 1, xp = 0, hp = 10.0, maxHp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        // Level 1 needs 150 XP. Level 2 needs 161 XP. Total = 311 XP.
        // Giving 320 XP should land character on Level 3 with remaining 9 XP.
        char = RpgEngine.gainXp(char, 320, now = NOW)
        assertEquals(3, char.lvl)
        assertEquals(9, char.xp)
        assertEquals(50.0, char.hp, 0.001)
    }

    @Test
    fun testDamageDepletionAndSurvival() {
        val char = CharacterEntity(hp = 50.0, lvl = 2, gold = 10.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val damagedChar = RpgEngine.takeDamage(char, 20.0, now = NOW)
        assertEquals(30.0, damagedChar.hp, 0.001)
        assertEquals(2, damagedChar.lvl)
        assertEquals(10.0, damagedChar.gold, 0.001)
    }

    @Test
    fun testDamageDepletionAndDeath() {
        // Death reduces level by 1, gold by 25%, restores HP to max, resets XP to 0
        val char = CharacterEntity(lvl = 3, xp = 50, hp = 10.0, maxHp = 50.0, gold = 100.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val deadChar = RpgEngine.takeDamage(char, 15.0, now = NOW)
        
        assertEquals(2, deadChar.lvl)
        assertEquals(0, deadChar.xp)
        assertEquals(50.0, deadChar.hp, 0.001)
        assertEquals(75.0, deadChar.gold, 0.001)
    }

    @Test
    fun testDeathAtLevelOne() {
        // Level cannot fall below 1
        val char = CharacterEntity(lvl = 1, xp = 50, hp = 5.0, maxHp = 50.0, gold = 40.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val deadChar = RpgEngine.takeDamage(char, 10.0, now = NOW)
        
        assertEquals(1, deadChar.lvl)
        assertEquals(0, deadChar.xp)
        assertEquals(50.0, deadChar.hp, 0.001)
        assertEquals(30.0, deadChar.gold, 0.001)
    }

    @Test
    fun testValueDeltaBounds() {
        // Value delta clamped in [-47.27, 21.27]
        val deltaMin = RpgEngine.valueDelta(-50.0)
        val deltaBoundMin = RpgEngine.valueDelta(-47.27)
        assertEquals(deltaBoundMin, deltaMin, 0.000001)

        val deltaMax = RpgEngine.valueDelta(30.0)
        val deltaBoundMax = RpgEngine.valueDelta(21.27)
        assertEquals(deltaBoundMax, deltaMax, 0.000001)
    }

    @Test
    fun testTaskCompletionAndUncompletion() {
        val char = CharacterEntity(lvl = 1, xp = 0, gold = 0.0, mp = 0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val task = TaskEntity(id = "task1", type = "todo", title = "Check engine", difficulty = "easy", value = 0.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        val (completedChar, completedTask) = RpgEngine.completeTask(char, task, nowMs = NOW, now = NOW, zone = ZONE)
        assertTrue(completedTask.done)
        assertTrue(completedTask.value > task.value)
        assertTrue(completedChar.gold > char.gold)
        assertTrue(completedChar.xp > char.xp)
        assertNotNull(completedTask.gr)

        // Uncomplete task -> should perfectly reverse everything
        val (undoneChar, undoneTask) = RpgEngine.uncompleteTask(completedChar, completedTask, nowMs = NOW, now = NOW)
        assertEquals(char.lvl, undoneChar.lvl)
        assertEquals(char.xp, undoneChar.xp)
        assertEquals(char.gold, undoneChar.gold, 0.001)
        assertEquals(char.mp, undoneChar.mp)
        assertEquals(task.value, undoneTask.value, 0.001)
        assertEquals(false, undoneTask.done)
        assertNull(undoneTask.gr)
    }

    @Test
    fun testLogHabitProgression() {
        val char = CharacterEntity(lvl = 1, xp = 0, hp = 50.0, gold = 10.0, mp = 0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val logHabit = TaskEntity(id = "log1", type = "habit", title = "Drink water", difficulty = "log", value = 0.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        // Up tap should increment cUp but give no rewards
        val (scoredCharUp, scoredHabitUp) = RpgEngine.scoreHabitUp(char, logHabit, now = NOW, atMs = NOW)
        assertEquals(1, scoredHabitUp.cUp)
        assertEquals(0.0, scoredHabitUp.value, 0.001)
        assertEquals(0, scoredCharUp.xp)
        assertEquals(10.0, scoredCharUp.gold, 0.001)

        // Down tap should increment cDown but give no damage
        val (scoredCharDown, scoredHabitDown) = RpgEngine.scoreHabitDown(char, logHabit, now = NOW, atMs = NOW)
        assertEquals(1, scoredHabitDown.cDown)
        assertEquals(0.0, scoredHabitDown.value, 0.001)
        assertEquals(50.0, scoredCharDown.hp, 0.001)
    }

    @Test
    fun testHabitUpProgression() {
        val char = CharacterEntity(lvl = 1, xp = 0, gold = 10.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val habit = TaskEntity(id = "h1", type = "habit", title = "Drink water", difficulty = "trivial", value = 0.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        val (scoredChar, scoredHabit) = RpgEngine.scoreHabitUp(char, habit, now = NOW, atMs = NOW)
        assertEquals(1, scoredHabit.cUp)
        assertEquals(0, scoredHabit.cDown)
        assertTrue(scoredHabit.value > habit.value)
        assertTrue(scoredChar.gold > char.gold)
        assertTrue(scoredChar.xp > char.xp)
    }

    @Test
    fun testHabitDownProgression() {
        val char = CharacterEntity(lvl = 1, xp = 0, hp = 50.0, gold = 10.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val habit = TaskEntity(id = "h1", type = "habit", title = "Sweets", difficulty = "medium", value = 0.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        val (scoredChar, scoredHabit) = RpgEngine.scoreHabitDown(char, habit, now = NOW, atMs = NOW)
        assertEquals(0, scoredHabit.cUp)
        assertEquals(1, scoredHabit.cDown)
        assertTrue(scoredHabit.value < habit.value)
        assertTrue(scoredChar.hp < char.hp)
    }

    @Test
    fun testRewardPurchases() {
        val char = CharacterEntity(gold = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Buy affordable reward
        val purchasedChar = RpgEngine.buyReward(char, 20.0, now = NOW)
        assertNotNull(purchasedChar)
        assertEquals(30.0, purchasedChar!!.gold, 0.001)

        // Attempt buying unaffordable reward
        val failedChar = RpgEngine.buyReward(char, 100.0, now = NOW)
        assertNull(failedChar)
    }

    @Test
    fun testBuyPotion() {
        val char = CharacterEntity(hp = 20.0, maxHp = 50.0, gold = 100.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Buy regular potion (25 gold, 15 heal)
        val charAfterPotion = RpgEngine.buyPotion(char, "potion", now = NOW)
        assertNotNull(charAfterPotion)
        assertEquals(35.0, charAfterPotion!!.hp, 0.001)
        assertEquals(75.0, charAfterPotion.gold, 0.001)

        // Buy big potion (60 gold, full heal)
        val charAfterBigPotion = RpgEngine.buyPotion(charAfterPotion, "bigpotion", now = NOW)
        assertNotNull(charAfterBigPotion)
        assertEquals(50.0, charAfterBigPotion!!.hp, 0.001)
        assertEquals(15.0, charAfterBigPotion.gold, 0.001)

        // Try to buy when HP is full
        val failedChar = RpgEngine.buyPotion(charAfterBigPotion, "potion", now = NOW)
        assertNull(failedChar)
    }

    @Test
    fun testCronDailiesRolloverAndDamage() {
        val char = CharacterEntity(hp = 50.0, gold = 10.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val dailyCompleted = TaskEntity(id = "d1", type = "daily", title = "Gym", done = true, value = 0.0, streak = 2, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val dailyMissed = TaskEntity(id = "d2", type = "daily", title = "Read", done = false, value = 0.0, streak = 5, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Cron runs on Thu 2026-07-16 (UTC) for yesterday, Wed 2026-07-15.
        val nowMs = java.time.LocalDateTime.of(2026, 7, 16, 9, 0).atZone(ZONE).toInstant().toEpochMilli()
        val cronResult = RpgEngine.runCron(
            RpgEngine.CronInput(char.copy(lastCron = "20260715"), listOf(dailyCompleted, dailyMissed), 20260715, nowMs, ZONE, NOW),
            20260716, com.example.data.PauseState.NONE
        )
        val cronChar = cronResult.character
        val cronTasks = cronResult.tasks
        val missLogs = cronResult.missLogs
        
        // Missed daily causes damage and resets streak and resets done=false
        assertTrue(cronChar.hp < char.hp)
        
        val updatedD1 = cronTasks.find { it.id == "d1" }!!
        assertEquals(false, updatedD1.done)
        assertEquals(2, updatedD1.streak) // kept streak because it was done

        val updatedD2 = cronTasks.find { it.id == "d2" }!!
        assertEquals(false, updatedD2.done)
        assertEquals(0, updatedD2.streak) // streak reset to 0
        assertEquals(20260715, updatedD2.missedOn)
        assertTrue(updatedD2.value < dailyMissed.value)
    }

    @Test
    fun testCompleteTaskDueTodayGate() {
        val char = CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        // Daily scheduled for Monday (dow=1)
        val daily = TaskEntity(id = "d1", type = "daily", title = "Monday Task", repeat = listOf(false, true, false, false, false, false, false), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        // Complete on Monday (dow=1) -> Success
        val (charMon, taskMon) = RpgEngine.completeTask(char, daily, todayDow = 1, nowMs = NOW, now = NOW, zone = ZONE)
        assertTrue(taskMon.done)
        assertTrue(charMon.xp > char.xp)

        // Complete on Tuesday (dow=2) -> Blocked
        val (charTue, taskTue) = RpgEngine.completeTask(char, daily, todayDow = 2, nowMs = NOW, now = NOW, zone = ZONE)
        assertEquals(false, taskTue.done)
        assertEquals(char.xp, charTue.xp)

        // Complete with todayDow = -1 (RYA/Legacy) -> Success always
        val (charLegacy, taskLegacy) = RpgEngine.completeTask(char, daily, todayDow = -1, nowMs = NOW, now = NOW, zone = ZONE)
        assertTrue(taskLegacy.done)
    }

    @Test
    fun testAdjustHabitCountersCompoundingUp() {
        val char = CharacterEntity(lvl = 1, xp = 0, gold = 0.0, hp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        // High value task near boundary to test compounding
        val habit = TaskEntity(id = "h1", type = "habit", title = "Habit 1", difficulty = "hard", value = 20.0, cUp = 5, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        // Adjust cUp by +2 (5 -> 7)
        val (newChar, newTask, result) = RpgEngine.adjustHabitCounters(char, habit, newCUp = 7, newCDown = 0, now = NOW)
        
        assertEquals(7, newTask.cUp)
        
        // Manual verification of compounding value
        var expectedValue = 20.0
        expectedValue = RpgEngine.clamp(expectedValue + RpgEngine.valueDelta(expectedValue), -47.27, 99.0)
        expectedValue = RpgEngine.clamp(expectedValue + RpgEngine.valueDelta(expectedValue), -47.27, 99.0)
        assertEquals(expectedValue, newTask.value, 0.001)
        
        assertTrue(result.xp > 0)
        assertEquals(result.xp, newChar.xp)
    }

    @Test
    fun testAdjustHabitCountersCompoundingDown() {
        val char = CharacterEntity(hp = 50.0, maxHp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val habit = TaskEntity(id = "h1", type = "habit", title = "Habit 1", difficulty = "medium", value = 0.0, cDown = 0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        // Adjust cDown by +2
        val (newChar, newTask, result) = RpgEngine.adjustHabitCounters(char, habit, newCUp = 0, newCDown = 2, now = NOW)

        assertEquals(2, newTask.cDown)
        
        var expectedValue = 0.0
        expectedValue = RpgEngine.clamp(expectedValue - RpgEngine.valueDelta(expectedValue), -47.27, 99.0)
        expectedValue = RpgEngine.clamp(expectedValue - RpgEngine.valueDelta(expectedValue), -47.27, 99.0)
        assertEquals(expectedValue, newTask.value, 0.001)

        assertTrue(result.hp < 0) 
        assertEquals(50.0 + result.hp, newChar.hp, 0.01)
    }

    @Test
    fun testAdjustHabitCountersFloorAndCeiling() {
        // Up-negative: reverse reward, floor XP/Gold/MP at 0
        val char = CharacterEntity(xp = 5, gold = 0.5, mp = 1, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val habit = TaskEntity(id = "h1", type = "habit", title = "Habit 1", difficulty = "hard", value = 10.0, cUp = 10, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Adjust cUp down by 5 (reversing 5 rewards)
        val (newChar, newTask, result) = RpgEngine.adjustHabitCounters(char, habit, newCUp = 5, newCDown = 0, now = NOW)
        
        assertEquals(5, newTask.cUp)
        assertEquals(0, newChar.xp)
        assertEquals(0.0, newChar.gold, 0.001)
        assertEquals(0, newChar.mp)
        
        // Down-negative: heal, capped at maxHp
        val charInjured = CharacterEntity(hp = 10.0, maxHp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val habitBad = TaskEntity(id = "h1", type = "habit", title = "Habit Bad", difficulty = "medium", value = -10.0, cDown = 10, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Adjust cDown down by 10 (reversing 10 misses)
        val (newCharHealed, _, resultHeal) = RpgEngine.adjustHabitCounters(charInjured, habitBad, newCUp = 0, newCDown = 0, now = NOW)
        
        // Should heal significantly
        assertTrue(newCharHealed.hp > 10.0)
        assertTrue(newCharHealed.hp <= 50.0)
        assertTrue(resultHeal.hp > 0)
    }

    @Test
    fun testAdjustHabitCountersNoOp() {
        val char = CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val habit = TaskEntity(id = "h1", type = "habit", title = "Habit 1", cUp = 5, cDown = 5, value = 1.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val (newChar, newTask, result) = RpgEngine.adjustHabitCounters(char, habit, newCUp = 5, newCDown = 5, now = habit.updatedAt)
        
        assertEquals(char, newChar)
        assertEquals(habit, newTask)
        assertEquals(0, result.xp)
        assertEquals(0.0, result.hp, 0.0)
    }

    @Test
    fun testCreditYesterdayBackdatesToLocalNoon() {
        // WP-8.10 (PX-68): replaced the flat -24 h backdate; CreditYesterdayTest has the full rows.
        val char = CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val daily = TaskEntity(id = "d1", type = "daily", title = "Yesterday Task", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val credit = RpgEngine.creditYesterday(char, daily, paused = false, nowMs = NOW, now = NOW, zone = ZONE)!!
        val taskRya = credit.task
        assertTrue(taskRya.done)
        assertNotNull(taskRya.doneAt)
        assertEquals(com.example.data.LocalDay.localDayDateAtOffset(NOW, -1, ZONE), taskRya.doneAt)
    }

    @Test
    fun testCompleteTaskRyaRewardsAndNoHpChange() {
        val char = CharacterEntity(hp = 40.0, maxHp = 50.0, xp = 0, gold = 0.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val daily = TaskEntity(id = "d1", type = "daily", title = "Yesterday Task", difficulty = "medium", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

        val credit = RpgEngine.creditYesterday(char, daily, paused = false, nowMs = NOW, now = NOW, zone = ZONE)!!
        val charRya = credit.character
        val taskRya = credit.task

        assertTrue(taskRya.done)
        // Rewards granted
        assertTrue(charRya.xp > 0)
        assertTrue(charRya.gold > 0.0)
        // No HP change (RYA runs before cron damage)
        assertEquals(40.0, charRya.hp, 0.001)
    }
    // WP-8.10 review: calculateCronDates (and its dayStart offset) is removed; cron uses LocalDay.
}
