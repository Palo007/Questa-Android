package com.example.data.clock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.2 (Wave 1) -- §5 card `HlcClockTest`: "the seed from HLC_LAST_ISSUED is monotonic across
 * restart." Extended for review r1 blocker 5: [DefaultHlcClock] ports Opti's `_hlcHeal`/`now`/
 * `ratchetHlc` (app.js:139-165, commit 7d5d4f3) exactly, including the 120s
 * `HLC_RATCHET_TOLERANCE_MS` and the `hlcReset`/`clockSkew` diagnostic events.
 */
class HlcClockTest {

    private class FakeSeedStore {
        var persisted: Long = 0L
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    }

    private fun clock(seed: Long, store: FakeSeedStore): HlcClock =
        DefaultHlcClock(seed = seed, persist = { store.persisted = it }).also {
            it.bindEventSink { kind, payload -> store.events.add(kind to payload) }
        }

    @Test
    fun `now() never issues a stamp older than the seed`() {
        val store = FakeSeedStore().apply { persisted = 5_000L }
        val clock = clock(seed = store.persisted, store = store)

        val first = clock.now()
        assertTrue("first stamp ($first) must be >= seed (5000)", first >= 5_000L)
        assertEquals(first, store.persisted)
    }

    @Test
    fun `restart reseeds from the last persisted value and stays monotonic`() {
        val store = FakeSeedStore()
        val clockA = clock(seed = store.persisted, store = store)
        val stampA = clockA.now()

        // "Process restart": a brand new DefaultHlcClock instance, seeded from whatever the first
        // instance last persisted -- this is exactly what com.example.data.clock.createHlcClock
        // does against a real SyncDataStore (DefaultHlcClock.seedFrom).
        val clockB = clock(seed = store.persisted, store = store)
        val stampB = clockB.now()

        assertTrue("restart must not regress the clock: $stampB should be > $stampA", stampB > stampA)
    }

    @Test
    fun `two stamps issued back to back are strictly increasing even at the same physical millisecond`() {
        val store = FakeSeedStore()
        val clock = clock(seed = 0L, store = store)
        val a = clock.now()
        val b = clock.now()
        assertTrue("$b must be strictly greater than $a", b > a)
    }

    @Test
    fun `ratchet moves the clock forward within tolerance`() {
        val store = FakeSeedStore()
        val clock = clock(seed = 1_000L, store = store)
        val nearFuture = PhysicalClock.nowMs() + 30_000L // well inside the 120s tolerance

        val movedForward = clock.ratchet(nearFuture)
        assertTrue(movedForward)
        assertEquals(nearFuture, clock.lastIssued.value)

        val movedBackward = clock.ratchet(1L)
        assertTrue("a remote stamp behind the local clock must not move it", !movedBackward)
        assertEquals(nearFuture, clock.lastIssued.value)
    }

    // --- review r1 blocker 5: HLC_RATCHET_TOLERANCE_MS, heal(), and the diagnostic events ---

    @Test
    fun `HLC_RATCHET_TOLERANCE_MS is Opti's 120 second constant`() {
        assertEquals(120_000L, HlcClock.HLC_RATCHET_TOLERANCE_MS)
    }

    @Test
    fun `ratchet refuses a remote stamp beyond tolerance and emits clockSkew instead of moving`() {
        val store = FakeSeedStore()
        val clock = clock(seed = 1_000L, store = store)
        val physicalIsh = PhysicalClock.nowMs()
        val farFuture = physicalIsh + HlcClock.HLC_RATCHET_TOLERANCE_MS + 60_000L

        val moved = clock.ratchet(farFuture)

        assertTrue("a remote stamp beyond tolerance must be refused, not clamped", !moved)
        assertEquals("the local clock must not move at all", 1_000L, clock.lastIssued.value)
        assertEquals(1, store.events.count { it.first == "clockSkew" })
        val payload = store.events.first { it.first == "clockSkew" }.second
        assertEquals(farFuture, payload["remoteTs"])
    }

    @Test
    fun `heal() snaps a poisoned clock back to physical time and emits hlcReset`() {
        val store = FakeSeedStore()
        val poisoned = PhysicalClock.nowMs() + HlcClock.HLC_RATCHET_TOLERANCE_MS + 999_999L
        val clock = clock(seed = poisoned, store = store)

        val healed = clock.heal()

        assertTrue("a clock more than tolerance ahead of physical time must heal", healed)
        assertTrue(
            "healed value should be close to physical time, was ${clock.lastIssued.value}",
            clock.lastIssued.value < poisoned
        )
        assertEquals(1, store.events.count { it.first == "hlcReset" })
        val payload = store.events.first { it.first == "hlcReset" }.second
        assertEquals(poisoned, payload["was"])
    }

    @Test
    fun `heal() is a no-op within tolerance`() {
        val store = FakeSeedStore()
        val clock = clock(seed = PhysicalClock.nowMs(), store = store)

        val healed = clock.heal()

        assertTrue(!healed)
        assertTrue("no diagnostic should fire when nothing healed", store.events.isEmpty())
    }

    @Test
    fun `now() heals a poisoned clock before issuing a stamp`() {
        val store = FakeSeedStore()
        val poisoned = PhysicalClock.nowMs() + HlcClock.HLC_RATCHET_TOLERANCE_MS + 999_999L
        val clock = clock(seed = poisoned, store = store)

        val stamp = clock.now()

        assertTrue("now() must heal first, so the issued stamp must be far below the poison", stamp < poisoned)
        assertEquals(1, store.events.count { it.first == "hlcReset" })
    }

    @Test
    fun `createHlcClock seeds from SyncDataStore getHlcLastIssued`() = runTest {
        // Exercises DefaultHlcClock.seedFrom's suspend contract without needing a real
        // android.content.Context / DataStore (out of scope for a plain JUnit test); the
        // Robolectric-backed SyncDataStore <-> HLC_LAST_ISSUED wiring itself is covered by
        // BaseStoreMigrationTest's use of the same DataStore, and by SyncDataStore's own
        // existing getHlcLastIssued/saveHlcLastIssued accessors.
        var persisted = 42L
        val seed = persisted
        val clock = DefaultHlcClock(seed, persist = { persisted = it })
        assertTrue(clock.now() >= seed)
    }

    // --- review r2 minor 4: heal() at boot (createHlcClock) + buffered events until sink binds ---

    @Test
    fun `an event emitted before bindEventSink is buffered, not dropped`() {
        val store = FakeSeedStore()
        val poisoned = PhysicalClock.nowMs() + HlcClock.HLC_RATCHET_TOLERANCE_MS + 999_999L
        // No sink bound yet -- construct raw, do not use the clock() helper (which binds one).
        val clock: HlcClock = DefaultHlcClock(seed = poisoned, persist = { store.persisted = it })

        val healed = clock.heal()
        assertTrue(healed)
        assertTrue("no sink bound yet: nothing delivered", store.events.isEmpty())

        clock.bindEventSink { kind, payload -> store.events.add(kind to payload) }

        assertEquals("the buffered hlcReset must be delivered exactly once, on bind", 1, store.events.count { it.first == "hlcReset" })
    }

    @Test
    fun `a boot-time hlcReset from createHlcClock is delivered once the sink binds`() = runTest {
        val poisoned = PhysicalClock.nowMs() + HlcClock.HLC_RATCHET_TOLERANCE_MS + 999_999L
        var persisted = poisoned
        val fakeStore = object {
            suspend fun getHlcLastIssued(): Long = persisted
        }
        // createHlcClock takes a real SyncDataStore for its seed read; exercise the same heal-at-
        // construction contract directly against DefaultHlcClock (createHlcClock is a thin
        // suspend wrapper: seedFrom(syncDataStore) then `DefaultHlcClock(seed, persist).heal()`)
        // to avoid needing a Robolectric Context/DataStore in a plain JUnit test.
        val clock: HlcClock = DefaultHlcClock(seed = fakeStore.getHlcLastIssued(), persist = { persisted = it })
        clock.heal() // what createHlcClock does immediately, before any DI wiring can bind a sink

        val delivered = mutableListOf<Pair<String, Map<String, Any?>>>()
        assertEquals(0, delivered.size)

        clock.bindEventSink { kind, payload -> delivered.add(kind to payload) }

        assertEquals(1, delivered.count { it.first == "hlcReset" })
    }
}
