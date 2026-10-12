package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    // WP-8.B (plans/PARITY_FIX_PLAN.html row "8.B", decision D15) gives the debug variant its own
    // app_name via app/build.gradle.kts `resValue("string", "app_name", "Questa dev")`, so unit
    // tests -- which always run against the debug variant -- see "Questa dev", not the base
    // "Questa" from res/values/strings.xml (still what release builds show).
    assertEquals("Questa dev", appName)
  }
}
