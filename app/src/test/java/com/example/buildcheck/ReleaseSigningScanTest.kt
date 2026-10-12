package com.example.buildcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KT-13 (fix plan P6b, DEC-7) + KT-60 (DEC-6): build-file scan. Plain JVM test, runs in every
 * unit-test variant. Only active (non-comment) lines count.
 *
 * Fails before P6b: release signing needs `my-upload-key.jks` + env passwords (no fallback, so
 * `assembleRelease` fails at validateSigningRelease), there is no `keystore.properties` path, the
 * id is `com.example`, and the live tools still target `com.example.dev`.
 */
class ReleaseSigningScanTest {

  private fun activeLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }

  private val appGradle by lazy { activeLines(SourceFiles.read("build.gradle.kts")) }

  private fun has(token: String) = appGradle.any { it.contains(token) }

  @Test
  fun applicationId_isFinal_namespaceKept() {
    assertTrue("applicationId not final", has("applicationId = \"io.github.palo007.questa\""))
    assertTrue("debug .dev suffix lost", has("applicationIdSuffix = \".dev\""))
    assertTrue("namespace must stay com.example (DEC-6)", has("namespace = \"com.example\""))
  }

  @Test
  fun releaseSigning_readsEnvThenKeystoreProperties() {
    for (t in listOf("KEYSTORE_PATH", "STORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS", "keystore.properties")) {
      assertTrue("release signing does not read $t", has(t))
    }
    assertFalse("hard-coded upload keystore is still the default", has("my-upload-key.jks"))
  }

  @Test
  fun releaseSigning_fallsBackToDebugKey_withWarning() {
    assertTrue("no debug-key fallback for release", has("signingConfigs.getByName(\"debug\")"))
    assertTrue("fallback prints no Gradle warning", has("logger.warn("))
    // Lazy: no throw at configuration time, so testDebugUnitTest works without a key (Momus 18).
    assertFalse("build file throws at configuration time", appGradle.any { it.startsWith("throw ") || it.contains("error(\"") })
  }

  @Test
  fun keystoreProperties_isGitIgnored() {
    assertTrue(".gitignore lacks keystore.properties", activeLines(SourceFiles.readRoot(".gitignore")).contains("keystore.properties"))
  }

  @Test
  fun liveTools_targetNewDevId() {
    val phone = SourceFiles.readRoot("tools/live/lib/phone.mjs")
    assertTrue(phone.contains("export const PKG = 'io.github.palo007.questa.dev';"))
    assertTrue(phone.contains("export const RUNNER = 'io.github.palo007.questa.dev.test/"))
    val capture = SourceFiles.readRoot("tools/capture_dropbox_bodies.mjs")
    assertEquals(0, Regex("\\|\\| \"com\\.example\\.dev\"").findAll(capture).count())
  }
}
