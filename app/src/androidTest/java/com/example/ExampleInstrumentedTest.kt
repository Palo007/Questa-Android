package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun useAppContext() {
    // Context of the app under test. KT-80: the installed id follows the build type
    // (debug = applicationId + ".dev"), so compare with the variant's own id.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(com.example.BuildConfig.APPLICATION_ID, appContext.packageName)
  }
}
