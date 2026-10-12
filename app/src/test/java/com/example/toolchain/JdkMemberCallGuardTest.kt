package com.example.toolchain

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * KT-87 / KT-07 / DEC-11 lasting guard (source scan, runs on any JDK, also JDK 25).
 *
 * With compileSdk 36, android.jar has the Java 21 `SequencedCollection` members on
 * `java.util.List` (reversed, removeFirst, removeLast, getFirst, getLast). A Kotlin call with
 * that exact name binds to the Java member, not to the Kotlin stdlib extension. minSdk is 24, so
 * on an API 24-34 phone (and on a JDK 17 test JVM) the call throws NoSuchMethodError.
 *
 * Use the Kotlin-safe forms instead: `asReversed()` (Kotlin stdlib view; any chain that still
 * calls the member, like `reversed().toList()`, is NOT safe); `removeAt(0)` /
 * `removeAt(lastIndex)`; `first()` / `last()`.
 *
 * Allowed: receivers declared in the same file as a Kotlin `ArrayDeque` (its members are Kotlin
 * code compiled into the app, e.g. `DiagRing.entries`), when the file does not import
 * `java.util.ArrayDeque`.
 */
class JdkMemberCallGuardTest {

    private val names = listOf("reversed", "removeFirst", "removeLast", "getFirst", "getLast")
    private val call = Regex("""\??\.\s*(${names.joinToString("|")})\s*\(\s*\)""")
    private val receiverBefore = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""")
    private val selfName = "JdkMemberCallGuardTest.kt"

    private fun root(rel: String): File {
        val candidates = listOf(File(rel), File("app/$rel"))
        val dir = candidates.firstOrNull { it.isDirectory }
        if (dir == null) fail("source root not found: $rel (cwd=${File(".").absolutePath})")
        return dir!!
    }

    private fun kotlinDequeNames(text: String): Set<String> {
        if (Regex("""import\s+java\.util\.ArrayDeque\b""").containsMatchIn(text)) return emptySet()
        return Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*ArrayDeque\b[^=\n]*)?=\s*ArrayDeque\b""")
            .findAll(text).map { it.groupValues[1] }.toSet() +
            Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*ArrayDeque\b""")
                .findAll(text).map { it.groupValues[1] }.toSet()
    }

    /** Returns "path:line: code" for every call that is not on a Kotlin ArrayDeque receiver. */
    private fun scan(vararg rels: String): List<String> {
        val hits = ArrayList<String>()
        var files = 0
        for (rel in rels) {
            val dir = root(rel)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != selfName }.forEach { f ->
                files++
                val text = f.readText()
                val deques = kotlinDequeNames(text)
                text.lines().forEachIndexed { i, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                    call.findAll(line).forEach { m ->
                        val recv = receiverBefore.find(line.substring(0, m.range.first))?.groupValues?.get(1)
                        if (recv == null || recv !in deques) {
                            hits += "${f.relativeTo(dir.parentFile ?: dir).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
                        }
                    }
                }
            }
        }
        assertTrue("scan found no Kotlin files under ${rels.toList()}", files > 0)
        return hits
    }

    @Test
    fun mainSources_haveNoSequencedCollectionMemberCalls() {
        val hits = scan("src/main")
        assertTrue(
            "Java 21 List member calls in app code (NoSuchMethodError on API < 35):\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /** Unit tests run on the JVM that launches Gradle (JDK 17 fails, KT-07); androidTest runs on the phone. */
    @Test
    fun testSources_haveNoSequencedCollectionMemberCalls() {
        val hits = scan("src/test", "src/androidTest")
        assertTrue(
            "Java 21 List member calls in test code (NoSuchMethodError on JDK 17 / API < 35):\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /** Positive control: the scanner itself must see a bad call and must allow a Kotlin ArrayDeque. */
    @Test
    fun scanner_flagsListCall_andAllowsKotlinArrayDeque() {
        val bad = "val x = rows.map { it }" + ".rev" + "ersed()"
        assertTrue(call.containsMatchIn(bad))
        val dequeSrc = "private val entries = ArrayDeque<Int>()\nentries.removeFirst()"
        assertTrue("entries" in kotlinDequeNames(dequeSrc))
        val javaDeque = "import java.util.ArrayDeque\nprivate val entries = ArrayDeque<Int>()"
        assertTrue(kotlinDequeNames(javaDeque).isEmpty())
    }
}
