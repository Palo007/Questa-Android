package com.example.buildcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KT-84 / KT-85 (fix plan P7). Plain JVM test (no Robolectric).
 * - KT-84: okhttp >= 4.12 (4.10 pulls okio 3.0.x, CVE-2023-3635 fixed in okio 3.4).
 *   Checks both the resolved runtime version and the catalog entries.
 * - KT-85: archived reorderable 0.9.6 is pinned in the version catalog (same version, no upgrade),
 *   and no longer hard-coded in app/build.gradle.kts.
 */
class DependencyFloorTest {

  private fun activeLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }

  private val appGradle by lazy { activeLines(SourceFiles.read("build.gradle.kts")) }
  private val catalog by lazy { activeLines(SourceFiles.readRoot("gradle/libs.versions.toml")) }

  private fun parse(v: String): List<Int> =
    v.substringBefore('-').split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

  private fun atLeast(v: String, major: Int, minor: Int): Boolean {
    val p = parse(v)
    val ma = p.getOrElse(0) { 0 }
    val mi = p.getOrElse(1) { 0 }
    return ma > major || (ma == major && mi >= minor)
  }

  private fun catalogVersion(key: String): String {
    val line = catalog.firstOrNull { it.matches(Regex("""^$key\s*=\s*".*"$""")) }
      ?: error("libs.versions.toml has no [versions] key '$key'")
    return line.substringAfter('"').substringBefore('"')
  }

  /** Resolved okhttp on the test classpath (what the app ships). */
  @Test
  fun okhttpAtLeast_4_12() {
    val v = okhttp3.OkHttp.VERSION
    assertTrue("resolved okhttp is $v, need >= 4.12", atLeast(v, 4, 12))
  }

  /** Catalog: okhttp and logging-interceptor (same group, must move together) >= 4.12. */
  @Test
  fun catalog_okhttpAndLoggingInterceptorAtLeast_4_12() {
    val ok = catalogVersion("okhttp")
    val li = catalogVersion("loggingInterceptor")
    assertTrue("catalog okhttp = $ok, need >= 4.12", atLeast(ok, 4, 12))
    assertTrue("catalog loggingInterceptor = $li, need >= 4.12", atLeast(li, 4, 12))
    assertEquals("okhttp and logging-interceptor must match", ok, li)
  }

  /** KT-85: reorderable pinned in the catalog at 0.9.6, used via libs.*, not hard-coded. */
  @Test
  fun reorderable_pinnedInCatalog() {
    assertEquals("reorderable must stay pinned at 0.9.6 (KT-85: pin, no upgrade)", "0.9.6", catalogVersion("reorderable"))
    assertTrue(
      "catalog lacks the reorderable library entry",
      catalog.any { it.contains("org.burnoutcrew.composereorderable") && it.contains("version.ref = \"reorderable\"") },
    )
    assertTrue("app/build.gradle.kts must use libs.reorderable", appGradle.any { it.contains("libs.reorderable") })
    val hard = appGradle.filter { it.contains("org.burnoutcrew.composereorderable") }
    assertTrue("reorderable still hard-coded: $hard", hard.isEmpty())
  }
}
