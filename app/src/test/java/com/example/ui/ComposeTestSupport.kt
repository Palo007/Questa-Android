package com.example.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain

/**
 * WP-8.14: call before `createComposeRule()`. A Compose test rule takes its clock's scheduler
 * from `Dispatchers.Main` when that is a test dispatcher; a Main left set by an earlier test class
 * in the same JVM would freeze the clock (AppNotIdleException). This puts the real Main back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun freshMainDispatcher() {
    try { Dispatchers.resetMain() } catch (e: Exception) { }
}
