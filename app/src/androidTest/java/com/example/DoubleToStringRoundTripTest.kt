package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.sync.CanonicalJson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP-5.5 / Part A Step 4 — Instrumented test (real ART) for Double.toString() round-trip.
 *
 * This test validates the core assumption of CanonicalJson.jsNumberToString:
 * that ART's Double.toLong() / BigDecimal re-parse chain produces the shortest
 * decimal string compatible with JS, matching the literals in our fixtures.
 */
@RunWith(AndroidJUnit4::class)
class DoubleToStringRoundTripTest {

    private fun check(literal: String) {
        val d = literal.toDouble()
        val formatted = CanonicalJson.jsNumberToString(d)
        assertEquals("Literal $literal failed to round-trip identically on ART", literal, formatted)
    }

    @Test
    fun testFixtureLiterals() {
        // From real_backup.json (Character stats)
        check("33.85")
        check("27419.6")
        
        // From real_backup.json (Task values)
        check("1.2")
        check("-1628.8528864975558")
        check("-1623.407709390758")
        check("-1607.6773844420372")
    }

    @Test
    fun testCanonicalJsonDocExamples() {
        // Values verified against Node in CanonicalJson.kt doc
        check("0")
        check("1")
        check("50")
        check("87.5")
        check("1e+21")
        check("999900000000000000000")
        check("0.000001")
        check("1e-7")
        check("-1.1584629606842927")
        check("2.220446049250313e-16")
        check("5e-324")
        check("1.7976931348623157e+308")
    }

    @Test
    fun testSpecialCases() {
        // JS -0 becomes "0"
        assertEquals("0", CanonicalJson.jsNumberToString(-0.0))
        
        // Integer as Double
        assertEquals("100", CanonicalJson.jsNumberToString(100.0))
    }
}
