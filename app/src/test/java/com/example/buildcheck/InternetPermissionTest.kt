package com.example.buildcheck

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dropbox sync needs android.permission.INTERNET. It once came only from the Firebase merged
 * manifest; P6a (b413645) removed Firebase, and every token exchange then failed at once with
 * "network error". Our own manifest must declare it, so no library change can drop it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternetPermissionTest {

  private val internet = "android.permission.INTERNET"

  @Test
  fun sourceManifest_declaresInternet() {
    val manifest = SourceFiles.read("src/main/AndroidManifest.xml")
    assertTrue(
      "app/src/main/AndroidManifest.xml must declare $internet itself",
      manifest.contains("<uses-permission android:name=\"$internet\""),
    )
  }

  @Test
  fun mergedApp_requestsInternet() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    assertTrue("the built app does not request $internet", info.requestedPermissions.orEmpty().contains(internet))
  }
}
