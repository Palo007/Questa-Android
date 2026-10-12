package com.example.data.boot

import com.example.data.clock.PhysicalClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * KT-97: put [BootGate] back to S0 on the production clock (BootGate.kt default:
 * `PhysicalClock.nowMs()`). [BootGate] is an object, so a fixed clock set by one test class
 * otherwise stays for every later class in the same JVM. Call from @After of any test that
 * pins the clock, and from @Before of any test that needs real time.
 */
fun restoreRealBootClock() =
    BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { PhysicalClock.nowMs() })
