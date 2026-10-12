package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.boot.BootGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * WP-8.10: since the W4 bind, Robolectric's QuestaApplication.onCreate runs the real startDay
 * (runCron writes localSavedAt to the app's DataStore) in the background. Tests that write the
 * app's DataStore themselves call this in setUp and tearDown so no boot write overlaps theirs:
 * join the app's StartupMigrations launch, reset BootGate to S0 (cancels its rollover Job), then
 * wait for any dayMutex holder to finish.
 */
object AppBootQuiesce {
    fun quiesce() = runBlocking {
        (ApplicationProvider.getApplicationContext<android.content.Context>() as? QuestaApplication)?.startupMigrationsJob?.join()
        BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { System.currentTimeMillis() })
        BootGate.dayMutex.lock()
        BootGate.dayMutex.unlock()
    }
}
