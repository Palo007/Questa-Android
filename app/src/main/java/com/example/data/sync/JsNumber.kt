package com.example.data.sync

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * WP-8.3 (PX-22, SY-40, R9): ECMA-262 `Number::toString(x)` for a finite double -- the text
 * `JSON.stringify` writes for a number.
 *
 * The digit string does NOT come from `Double.toString()`. On desktop JDK >= 19 that is shortest
 * round-trip, but older JDKs and Android's ART are not guaranteed to be, and SY-40 names that as a
 * hash-preimage risk. Instead the shortest digits are searched directly:
 *
 *  - `exact` = the exact binary value of [d] as a [BigDecimal] (lossless).
 *  - For precision p = 1..17: take the p-digit decimal nearest to `exact` (HALF_EVEN, so an exact
 *    tie picks the even digit, as ECMA-262 step 5 requires); if it parses back to [d] it is the
 *    answer. Otherwise try the p-digit neighbour on the other side of `exact` (needed only at a
 *    power-of-two boundary, where the round-trip interval is asymmetric). The round-trip interval
 *    is contiguous and contains `exact`, so if ANY p-digit decimal round-trips, one of these two
 *    neighbours does. 17 digits always round-trip.
 *
 * Integers with |d| < 2^53 take a fast path: their decimal digits are exact and already minimal,
 * which is also exactly what JS prints.
 *
 * Layout (n = decimal exponent, k = digit count), ECMA-262 Number::toString steps 6-10:
 * `k <= n <= 21` digits + zeros; `0 < n <= 21` digits with a point; `-6 < n <= 0` "0." + zeros +
 * digits; otherwise exponent form `d[.ddd]e(+|-)x`.
 */
object JsNumber {

    private const val TWO_POW_53 = 9007199254740992.0

    fun format(d: Double): String {
        require(!d.isNaN() && !d.isInfinite()) {
            "JsNumber.format: non-finite value ($d). JSON.stringify writes null for NaN/Infinity; " +
                "the caller must emit JsonNull instead."
        }
        if (d == 0.0) return "0" // covers -0.0: JSON.stringify(-0) === "0"
        if (d == Math.floor(d) && Math.abs(d) < TWO_POW_53) return d.toLong().toString()

        val negative = d < 0.0
        val absD = Math.abs(d)
        val shortest = shortestRoundTrip(absD).stripTrailingZeros()
        val digits = shortest.unscaledValue().toString()
        val k = digits.length
        val n = k - shortest.scale()

        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exp = n - 1
                val sign = if (exp >= 0) "+" else "-"
                val mantissa = if (k == 1) digits else "${digits[0]}.${digits.substring(1)}"
                "${mantissa}e$sign${Math.abs(exp)}"
            }
        }
        return if (negative) "-$body" else body
    }

    private fun roundTrips(candidate: BigDecimal, target: Double): Boolean =
        java.lang.Double.parseDouble(candidate.toString()) == target

    private fun shortestRoundTrip(absD: Double): BigDecimal {
        val exact = BigDecimal(absD)
        for (p in 1..17) {
            val nearest = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (roundTrips(nearest, absD)) return nearest
            val cmp = nearest.compareTo(exact)
            if (cmp != 0) {
                val other = exact.round(MathContext(p, if (cmp < 0) RoundingMode.CEILING else RoundingMode.FLOOR))
                if (roundTrips(other, absD)) return other
            }
        }
        return exact.round(MathContext(17, RoundingMode.HALF_EVEN))
    }
}
