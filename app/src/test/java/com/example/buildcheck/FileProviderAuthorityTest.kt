package com.example.buildcheck

import android.content.ComponentName
import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * KT-81 (fix plan P6a): the FileProvider authority must follow the applicationId, so a release
 * build and the debug build (`.dev` suffix) can live on one phone without
 * INSTALL_FAILED_CONFLICTING_PROVIDER. Unit tests run the debug variant (`com.example.dev`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileProviderAuthorityTest {

  private val expected = "${BuildConfig.APPLICATION_ID}.fileprovider"

  @Test
  fun authority_followsApplicationId() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val info = context.packageManager.getProviderInfo(ComponentName(context, FileProvider::class.java), 0)
    assertEquals(expected, info.authority)
  }

  /** Code side of KT-81: the share call must not hard-code the authority. */
  @Test
  fun settingsHost_hasNoHardCodedAuthority() {
    val src = SourceFiles.read("src/main/java/com/example/ui/settings/SettingsHost.kt")
    assertFalse("SettingsHost.kt hard-codes com.example.fileprovider", src.contains("\"com.example.fileprovider\""))
    assertTrue(src.contains("\${BuildConfig.APPLICATION_ID}.fileprovider"))
  }
}

/** Reads project files in unit tests. Gradle runs tests in the module dir; IDEs may use the root. */
internal object SourceFiles {
  /** The `app` module dir: cwd under Gradle, `app/` under an IDE that runs from the root. */
  private val moduleDir: File =
    if (File("src/main/AndroidManifest.xml").exists()) File(".") else File("app")

  private val rootDir: File = File(moduleDir, "..")

  private fun existing(f: File): File = f.takeIf { it.exists() } ?: error("not found: ${f.canonicalPath}")

  /** Module file, e.g. `src/main/...` or `build.gradle.kts` (the app one). */
  fun read(modulePath: String): String = existing(File(moduleDir, modulePath)).readText()

  /** Root-project file, e.g. `gradle/libs.versions.toml` or the root `build.gradle.kts`. */
  fun readRoot(rootPath: String): String = existing(File(rootDir, rootPath)).readText()

  /** Absolute path of the repository root (for tools like git that need a working directory). */
  val repoRoot: File get() = rootDir
}
