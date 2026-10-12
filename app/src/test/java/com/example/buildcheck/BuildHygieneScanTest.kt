package com.example.buildcheck

import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KT-82 / KT-83 / versionCode (fix plan P6a): build-file scan. Plain JVM test (no Robolectric).
 * Only active (non-comment) lines count, so commented template lines do not trip it.
 */
class BuildHygieneScanTest {

  private fun activeLines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }

  private val appGradle by lazy { activeLines(SourceFiles.read("build.gradle.kts")) }
  private val rootGradle by lazy { activeLines(SourceFiles.readRoot("build.gradle.kts")) }
  private val catalog by lazy { activeLines(SourceFiles.readRoot("gradle/libs.versions.toml")) }

  private fun assertNone(where: String, lines: List<String>, tokens: List<String>) {
    val hits = lines.filter { l -> tokens.any { l.contains(it) } }
    assertTrue("$where still has: $hits", hits.isEmpty())
  }

  private fun assertHas(where: String, lines: List<String>, token: String) =
    assertTrue("$where lost: $token", lines.any { it.contains(token) })

  /** KT-82: no secrets plugin, so a build needs no `.env` / `.env.example`. */
  @Test
  fun secretsPlugin_removed() {
    assertNone("app/build.gradle.kts", appGradle, listOf("libs.plugins.secrets", "secrets {"))
    assertNone("build.gradle.kts", rootGradle, listOf("libs.plugins.secrets"))
    assertNone("libs.versions.toml", catalog, listOf("secrets-gradle-plugin", "secretsGradlePlugin"))
  }

  /** KT-83: unused Firebase / google-services / Moshi / Retrofit (0 uses in app/src). */
  @Test
  fun unusedDeps_removed() {
    assertNone(
      "app/build.gradle.kts", appGradle,
      listOf(
        "GoogleServicesPlugin", "libs.plugins.google.services", "googleServices {",
        "libs.firebase.bom", "libs.firebase.ai", "libs.firebase.appcheck",
        "libs.moshi", "libs.converter.moshi", "libs.retrofit",
      ),
    )
    assertNone("build.gradle.kts", rootGradle, listOf("libs.plugins.google.services"))
    assertNone(
      "libs.versions.toml", catalog,
      listOf(
        "com.google.gms.google-services", "firebase-bom", "firebase-ai", "firebase-appcheck",
        "com.squareup.moshi", "com.squareup.retrofit2",
      ),
    )
  }

  /** Momus 17: KSP stays, it runs the Room compiler. */
  @Test
  fun kspForRoom_kept() {
    assertHas("app/build.gradle.kts", appGradle, "alias(libs.plugins.google.devtools.ksp)")
    assertHas("app/build.gradle.kts", appGradle, "\"ksp\"(libs.androidx.room.compiler)")
    assertHas("app/build.gradle.kts", appGradle, "room.schemaLocation")
    assertHas("build.gradle.kts", rootGradle, "alias(libs.plugins.google.devtools.ksp) apply false")
    assertHas("libs.versions.toml", catalog, "androidx-room-compiler")
  }

  /**
   * The version is set by hand in gradle.properties (appVersionName / appVersionCode), never
   * read from git. A git-derived code drops to 1 on a fresh clone, and the phone then refuses
   * to install it over a newer build.
   */
  @Test
  fun version_comesFromGradleProperties() {
    val props = java.util.Properties().apply {
      SourceFiles.readRoot("gradle.properties").reader().use { load(it) }
    }
    val name = props.getProperty("appVersionName")
    val code = props.getProperty("appVersionCode")?.trim()?.toIntOrNull()
    assertTrue("gradle.properties must set appVersionName", !name.isNullOrBlank())
    assertTrue("gradle.properties must set a numeric appVersionCode", code != null)
    assertEquals("versionCode must equal appVersionCode", code, BuildConfig.VERSION_CODE)
    assertTrue(
      "versionName ${BuildConfig.VERSION_NAME} must be appVersionName $name (plus an optional suffix)",
      BuildConfig.VERSION_NAME == name!!.trim() || BuildConfig.VERSION_NAME.startsWith("${name.trim()}-"))
    assertNone("app/build.gradle.kts", appGradle, listOf("rev-list"))
  }
}
