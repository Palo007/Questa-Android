package com.example.data.sync

import java.io.File

/**
 * Guards for the tests that shell out to `tools/verify_with_pwa.mjs`.
 *
 * That script checks a backup against the ORIGINAL progressive-web implementation of Questa
 * (https://github.com/Palo007/Questa), which is a separate checkout and is not vendored here.
 * Without it the script exits non-zero, which would fail the calling test for a reason that has
 * nothing to do with the code under test.
 *
 * `OptiFixtureManifestTest` already guards its own generator calls this way; these two call
 * sites were the exceptions, and on a fresh clone they failed. They are now skipped instead,
 * so `./gradlew testDebugUnitTest` is green on any machine that has only a JDK and the Android
 * SDK, and still does the real cross-implementation check wherever the PWA checkout is present.
 */
internal object PwaToolchain {

    /** Env override, matching tools/verify_with_pwa.mjs's own default. */
    const val OPTI_ROOT_ENV = "OPTI_ROOT"

    /** The pinned PWA commit these fixtures were generated against (tools/verify_with_pwa.mjs). */
    const val TARGET_OPTI_GIT = "7d5d4f3"

    /**
     * Where to look for the PWA checkout: `$OPTI_ROOT` if it names an existing directory, else a
     * sibling of this repo named `Opti`. Returns null when neither exists -- that null is what
     * the guards key off. Deliberately *not* an absolute path under one maintainer's home
     * directory -- a hardcoded `C:\Users\<someone>\...` default worked only on that machine.
     *
     * The env var is honoured only when it RESOLVES. `tools/verify_with_pwa.mjs` reads the same
     * variable, so if it points at a stale or missing checkout the script exits non-zero and the
     * test fails; detecting that here lets it skip instead. If it is set but wrong we also do not
     * fall back to the sibling -- an explicit override that does not resolve should not silently
     * be papered over with a different path.
     */
    fun optiRoot(repoRoot: File? = null): File? {
        System.getenv(OPTI_ROOT_ENV)?.takeIf { it.isNotBlank() }?.let { raw ->
            return File(raw).takeIf { it.isDirectory }
        }
        val base = repoRoot ?: repoRootFromCwd() ?: return null
        return sequenceOf(base.parentFile?.resolve("Opti"), base.resolve("Opti"))
            .filterNotNull()
            .firstOrNull { it.isDirectory }
    }

    /**
     * Resolves the PWA checkout to a specific commit the tools can actually run against, or null
     * if there is no usable checkout. Used by the `assumeTrue` guards.
     *
     * Checks more than the directory: the fixture generators and `verify_with_pwa.mjs` read a
     * *pinned commit* out of that repository with `git show <rev>:<path>`. A checkout at the
     * wrong commit, or one without git metadata at all (a downloaded zip, say), makes the tools
     * exit non-zero. Detecting that here turns a hard failure into a self-explaining skip.
     */
    fun pinnedRevOrNull(repoRoot: File?): String? {
        val root = optiRoot(repoRoot) ?: return null
        val rev = try {
            val p = ProcessBuilder("git", "-C", root.path, "rev-parse", "--verify", "$TARGET_OPTI_GIT^{commit}")
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        } catch (e: Exception) {
            return null
        }
        return TARGET_OPTI_GIT.takeIf { rev.isNotEmpty() }
    }

    /** True when `cmd` can be started. A missing interpreter must skip, never fail. */
    fun isOnPath(cmd: String): Boolean = try {
        val proc = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
        proc.waitFor()
        true
    } catch (e: Exception) {
        false
    }

    /** Message used by the `assumeTrue` calls, so a skip explains itself in the test report. */
    fun skipReason(repoRoot: File?): String =
        "needs Node.js on PATH and a checkout of the original PWA " +
            "(https://github.com/Palo007/Questa) at $OPTI_ROOT_ENV or ../Opti, pinned to " +
            "$TARGET_OPTI_GIT. Both are optional: without them this cross-check is skipped. " +
            "(repo root: ${repoRoot?.absolutePath ?: "unknown"})"

    private fun repoRootFromCwd(): File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "tools/verify_with_pwa.mjs").isFile }
}
