package com.example.data.sync.auth

import androidx.test.core.app.ApplicationProvider
import com.example.QuestaApplication
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * DropboxConfig is process-wide and Robolectric runs QuestaApplication.onCreate (and its startup
 * refresh) for every test. This rule waits for that job, then pins the cache to [key], and resets
 * both cache and store afterwards, so no test sees another test's key.
 */
class DropboxConfigRule(val key: String = TEST_APP_KEY) : TestWatcher() {
    override fun starting(d: Description) {
        joinStartupIfAny()
        DropboxConfig.store = null
        DropboxConfig.setCacheForTests(key)
    }

    override fun finished(d: Description) {
        DropboxConfig.store = null
        DropboxConfig.setCacheForTests("")
    }

    /** Waits for the app's startup job when the application under test is a QuestaApplication. */
    private fun joinStartupIfAny() = runBlocking {
        val app = runCatching { ApplicationProvider.getApplicationContext<android.content.Context>() }.getOrNull()
        (app as? QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    companion object { const val TEST_APP_KEY = "test-app-key" }
}
