package com.example.startup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-8.21 (r11; r12) BootWiringOrderTest, §5.0 rule W1. Authored by 8.21; 8.I applies the wiring
 * in `QuestaApplication.onCreate` and removes the matching @Ignore:
 *  - row (1) at the W3 close;
 *  - row (2) at the W4 close.
 *
 * It checks the source order of `onCreate`, because the steps are plain calls on the main thread
 * and BootGate (WP-8.6) is not in this branch. The anchors 8.I must use are the strings below;
 * the step-1 configured read must be assigned to `val configured`.
 */
class BootWiringOrderTest {
    private fun onCreateSource(): String {
        val candidates = listOf(
            File("src/main/java/com/example/QuestaApplication.kt"),
            File("app/src/main/java/com/example/QuestaApplication.kt")
        )
        val text = candidates.first { it.exists() }.readText()
        val start = text.indexOf("override fun onCreate()")
        assertTrue("onCreate not found", start >= 0)
        return text.substring(start)
    }

    private fun String.at(anchor: String): Int = indexOf(anchor).also {
        assertTrue("missing anchor `$anchor` in QuestaApplication.onCreate", it >= 0)
    }

    private fun assertBefore(src: String, a: String, b: String) {
        assertTrue("`$a` must come before `$b`", src.at(a) < src.at(b))
    }

    // Enabled by 8.I at the W3 close (§5.0 W1 wiring applied).
    @Test fun row1_w1Steps_0_1_2_4_5_6_7_inOrder_andLogSinkBound() {
        val src = onCreateSource()
        val step0 = "StartupMigrations.captureStep0("
        // step 0 comes before ANY database or DataStore access
        assertBefore(src, step0, "AppDatabase.getDatabase(")
        assertBefore(src, step0, "SyncDataStore(")
        val order = listOf(
            step0,                              // 0
            "val configured",                   // 1
            "BootGate.configuredProvider",      // 2
            "BootGate.bootStartDay(",           // 4
            "StartupMigrations.run(",           // 5
            "BootGate.onForeground(",           // 6
            "syncScheduler.start()"             // 7
        )
        order.zipWithNext().forEach { (a, b) -> assertBefore(src, a, b) }
        src.at("BootGate.lifecycleLog")
    }

    // Enabled at the W4 close (step 3, bindStartDay; WP-8.10 B10 body).
    @Test fun row2_bindStartDay_afterStep2_beforeSteps4And7() {
        val src = onCreateSource()
        assertBefore(src, "BootGate.configuredProvider", "BootGate.bindStartDay")
        assertBefore(src, "BootGate.bindStartDay", "BootGate.bootStartDay(")
        assertBefore(src, "BootGate.bindStartDay", "syncScheduler.start()")
    }
}
