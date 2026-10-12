package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-8.1 -- PX-12. `dbxArgHeader` must equal Opti's output byte for byte (G7
 * `fixtures/opti/G7/transport_strings.json`, generated from Opti 7d5d4f3 by tools/gen_opti_fixtures.mjs).
 */
class ArgHeaderTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/opti/manifest.json").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root not found")
    }

    private fun g7(key: String): String {
        val obj = Json.parseToJsonElement(File(repoRoot(), "fixtures/opti/G7/transport_strings.json").readText()) as JsonObject
        return (obj[key] as JsonPrimitive).content
    }

    @Test fun asciiHeader_equalsG7() {
        val arg = buildJsonObject {
            put("path", "/plain/ascii.json")
            put("mode", "overwrite")
        }
        assertEquals(g7("dbx_header_ascii"), dbxArgHeader(arg))
    }

    @Test fun nonAsciiHeader_equalsG7() {
        // Same input as gen_opti_fixtures.mjs genG7: "/日本語/ünïcödé café.json"
        val arg = buildJsonObject { put("path", "/日本語/ünïcödé café.json") }
        assertEquals(g7("dbx_header_nonascii"), dbxArgHeader(arg))
    }

    @Test fun delIsEscapedLikeOpti() {
        // Opti's class is [\u007f-￿], so U+007F is escaped too.
        val arg = buildJsonObject { put("path", "/a\u007fb") }
        assertEquals("{\"path\":\"/a\\u007fb\"}", dbxArgHeader(arg))
    }

    @Test fun surrogatePairIsTwoEscapes() {
        val arg = buildJsonObject { put("path", "/😀") }
        assertEquals("{\"path\":\"/\\ud83d\\ude00\"}", dbxArgHeader(arg))
    }

    @Test fun everyCharacterIsAscii() {
        val header = dbxArgHeader(buildJsonObject { put("path", "/日本.json") })
        assertTrue(header.all { it.code < 0x7F })
    }
}
