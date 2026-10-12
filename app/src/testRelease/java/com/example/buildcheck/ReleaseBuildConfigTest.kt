package com.example.buildcheck

import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KT-60 / CR-TWA-001 (fix plan P6b, DEC-6): the release app id is the final id, no suffix.
 * Runs only in `testReleaseUnitTest` (src/testRelease). Fails before P6b: the id is `com.example`.
 */
class ReleaseBuildConfigTest {
  @Test
  fun applicationId_isFinal() {
    assertEquals("io.github.palo007.questa", BuildConfig.APPLICATION_ID)
  }
}
