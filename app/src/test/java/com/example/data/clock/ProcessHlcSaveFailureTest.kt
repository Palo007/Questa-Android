package com.example.data.clock

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * WP-8.I (W7, orchestrator-approved guess): one failed HLC save is logged and skipped. The persist
 * loop keeps running (no uncaught exception, so the app is not killed) and the next save succeeds.
 */
class ProcessHlcSaveFailureTest {

    @Test fun aSaveThatThrowsOnce_neverCrashes_andTheNextSaveSucceeds() = runBlocking {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e })
        val attempts = Collections.synchronizedList(mutableListOf<Long>())
        val saved = Collections.synchronizedList(mutableListOf<Long>())
        val clock = ProcessHlc.build(seed = 0L, scope = scope) { v ->
            attempts += v
            if (attempts.size == 1) throw IOException("Unable to rename (test)")
            saved += v
        }
        try {
            val first = clock.now()
            withTimeout(5000) { while (attempts.isEmpty()) delay(10) }
            assertTrue("the failed value is not saved", first !in saved)

            val second = clock.now()
            withTimeout(5000) { while (second !in saved) delay(10) }
            assertEquals(second, saved.last())
            assertTrue("the persist loop is still running", scope.isActive)
            assertTrue("no uncaught exception: $uncaught", uncaught.isEmpty())
            // The in-memory clock is unchanged by the failure.
            assertEquals(second, clock.lastIssued.value)
        } finally {
            scope.cancel()
        }
    }
}
