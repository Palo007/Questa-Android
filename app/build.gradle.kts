import java.util.Properties

// Reads a key from local.properties (P-1/P-2). Not committed; absent locally -> null.
fun localProp(key: String): String? {
  val props = Properties()
  val f = rootProject.file("local.properties")
  if (f.exists()) f.inputStream().use { props.load(it) }
  return props.getProperty(key)
}

// KT-13 / DEC-7: one release-signing value: env var first, then keystore.properties (not committed).
fun releaseSigningProp(envName: String, propKey: String): String? {
  System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
  val f = rootProject.file("keystore.properties")
  if (!f.exists()) return null
  val props = Properties()
  f.inputStream().use { props.load(it) }
  return props.getProperty(propKey)?.takeIf { it.isNotBlank() }
}

// A build-time string literal: the DROPBOX_APP_KEY env var first, then local.properties, else empty.
// Returns the QUOTED literal, for use as buildConfigField's value inside android { defaultConfig }.
// Used for the Dropbox app key seed so no live credential is ever committed.
// Build with -PnoKeySeed (build.bat "nokey") to leave the seed empty whatever is configured.
fun dropboxKeyLiteral(localKey: String): String {
  val v = if (project.hasProperty("noKeySeed")) "" else
    (System.getenv("DROPBOX_APP_KEY")?.takeIf { it.isNotBlank() } ?: localProp(localKey))
      ?.trim().orEmpty()
  return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

val releaseStorePath = releaseSigningProp("KEYSTORE_PATH", "storeFile")
val releaseStorePassword = releaseSigningProp("STORE_PASSWORD", "storePassword")
val releaseKeyPassword = releaseSigningProp("KEY_PASSWORD", "keyPassword")
val releaseKeyAlias = releaseSigningProp("KEY_ALIAS", "keyAlias") ?: "upload"
val hasReleaseKey = releaseStorePath?.let { rootProject.file(it).exists() } == true &&
  releaseStorePassword != null && releaseKeyPassword != null
if (!hasReleaseKey) {
  logger.warn(
    "WARNING (KT-13/DEC-7): no release key (set KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS " +
      "or create keystore.properties). Release builds are signed with the DEBUG key: not for real installs."
  )
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "io.github.palo007.questa" // DEC-6; namespace stays com.example
    minSdk = 24
    targetSdk = 36
    // Set by hand in gradle.properties (appVersionName, appVersionCode); raise both for each
    // release. Shown at the bottom of Settings as "v<appVersionName>"; build.bat uses the same
    // name for the APK files.
    versionCode = providers.gradleProperty("appVersionCode").get().trim().toInt()
    versionName = providers.gradleProperty("appVersionName").get().trim()

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("boolean", "REAL_ACCOUNT_UNLOCKED", localProp("realAccountUnlocked") ?: "false")
    buildConfigField("String", "SCRATCH_ACCOUNT_IDS", "\"${localProp("scratchAccountIds") ?: ""}\"")
    // Optional Dropbox app key seed. On the builder's own install it is stored once at first
    // start, when no key is stored yet; after that the key saved in the app is the only source.
    // Read from local.properties (dropboxAppKey) or the DROPBOX_APP_KEY env var, never committed.
    // Empty in a public build: the app then asks the user for a key.
    buildConfigField("String", "DROPBOX_APP_KEY_SEED", dropboxKeyLiteral("dropboxAppKey"))
  }

  // KT-13 / DEC-7: release key from env vars, else an uncommitted keystore.properties
  // (storeFile, storePassword, keyAlias, keyPassword). Neither -> the debug key + a warning.
  // Never throws at configuration time, so unit tests build on any machine.
  signingConfigs {
    if (hasReleaseKey) {
      create("release") {
        storeFile = rootProject.file(releaseStorePath!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
      manifestPlaceholders["oauthScheme"] = "questa"
      buildConfigField("String", "OAUTH_REDIRECT_URI", "\"questa://oauth\"")
      // WP-8.12-T1: the debug app clock offset is inert in release.
      buildConfigField("boolean", "DEBUG_CLOCK_ENABLED", "false")
    }
    debug {
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
      resValue("string", "app_name", "Questa dev")
      manifestPlaceholders["oauthScheme"] = "questa-dev"
      buildConfigField("String", "OAUTH_REDIRECT_URI", "\"questa-dev://oauth\"")
      // WP-8.12-T1: adb-settable app clock offset (src/debug DebugClockReceiver).
      buildConfigField("boolean", "DEBUG_CLOCK_ENABLED", "true")
    }
  }
  // The androidTest APK (G10 capture) pulls several libraries that each ship these license files.
  packaging {
    resources {
      excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md", "META-INF/NOTICE.md")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
    resValues = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // 8.B CR-1 follow-up: Robolectric's MigrationTestHelper reads assets via
  // com.android.tools.test_config.properties' `android_merged_assets`, which AGP always points at
  // the DEBUG variant's merged assets (build/intermediates/assets/debug/...), not a test-specific
  // merge -- `sourceSets["test"].assets` alone is never read by a Robolectric unit test. The
  // schemas must be on the `debug` source set's assets for MigrationTestHelper to find them.
  sourceSets["debug"].assets.directories.add("$projectDir/schemas")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
  implementation(libs.reorderable) // KT-85: archived lib, pinned; replace with the Compose BOM bump (KT-16)
  // implementation(libs.coil.compose)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.browser)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  testImplementation(libs.mockk)
  // 8.B CR-1 (D20 mid-wave path, filed by WP-8.2/Wave 1): MigrationTestHelper for
  // Migration6to7Test, a plain Robolectric JVM test, not an instrumented one -- this artifact
  // was previously wired only into androidTestImplementation below.
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.mockk.android)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.core.ktx)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
