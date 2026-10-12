package com.example.buildcheck

import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KT-60 / CR-TWA-001 (fix plan P6b, DEC-6): the debug app id is the final id plus `.dev`.
 * Runs only in `testDebugUnitTest` (src/testDebug). Fails before P6b: the id is `com.example.dev`.
 */
class DebugBuildConfigTest {
  @Test
  fun applicationId_isFinalDev() {
    assertEquals("io.github.palo007.questa.dev", BuildConfig.APPLICATION_ID)
  }
}
