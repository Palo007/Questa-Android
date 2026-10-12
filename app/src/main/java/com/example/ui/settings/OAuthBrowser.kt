package com.example.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * WP-8.12-U1: the one place that opens the Dropbox authorize URL.
 *
 * From an Activity the Custom Tab is started WITHOUT FLAG_ACTIVITY_NEW_TASK, so it lives in the
 * app's task. The questa(-dev)://oauth redirect then reaches the singleTask MainActivity, which is
 * brought to the front and clears the tab above it (no stray browser task that comes back later).
 * No Custom Tabs browser (or ActivityNotFoundException) -> the legacy ACTION_VIEW + NEW_TASK path.
 * The URL (PKCE challenge, state) is passed through untouched.
 */
object OAuthBrowser {

    /** Returns true when a Custom Tab was started, false when the plain browser fallback was used. */
    fun open(
        context: Context,
        url: String,
        customTabsPackage: (Context) -> String? = { CustomTabsClient.getPackageName(it, null) },
        start: (Context, Intent) -> Unit = { c, i -> c.startActivity(i) },
    ): Boolean {
        val uri = Uri.parse(url)
        val activity = findActivity(context)
        val launchContext: Context = activity ?: context
        val pkg = customTabsPackage(launchContext)
        if (pkg != null) {
            val intent = customTabIntent(uri, pkg, fromActivity = activity != null)
            try {
                start(launchContext, intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // fall through to the plain browser
            }
        }
        start(launchContext, fallbackIntent(uri))
        return false
    }

    internal fun customTabIntent(uri: Uri, pkg: String, fromActivity: Boolean): Intent {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build().intent
        intent.data = uri
        intent.setPackage(pkg)
        if (!fromActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    internal fun fallbackIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
