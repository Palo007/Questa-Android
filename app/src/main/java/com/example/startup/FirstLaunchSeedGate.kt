package com.example.startup

import java.io.File

/** R-4: may `seedFirstLaunch` write the starter tasks / rewards on this launch? */
interface SeedGate {
    /** true only on a proven fresh install that has not been seeded yet. */
    fun shouldSeed(): Boolean

    /** Closes the gate for good (called once the seed decision was made). */
    fun markSeeded()
}

/**
 * R-4 (8.12 s10, rewards 0 -> 2 on an upgrade). Opti seeds nothing outside `freshState()` and
 * never re-adds what the user deleted, so the Kotlin seed runs ONLY when:
 *  - the 8.21 install stamp is [InstallStamp.FRESH] (step 0b: no DB, no DataStore keys, no
 *    restored marker) -- never LEGACY, RESTORE_PENDING or RESTORED, never with no stamp; and
 *  - the seed marker [markerFile] is absent. It lives in `noBackupFilesDir` next to the stamp,
 *    so a Reset (Room wipe) or an emptied list never re-opens the gate.
 */
class FirstLaunchSeedGate(private val stamp: InstallStamp, private val markerFile: File) : SeedGate {
    override fun shouldSeed(): Boolean = stamp.read() == InstallStamp.FRESH && !markerFile.exists()

    override fun markSeeded() {
        markerFile.parentFile?.mkdirs()
        markerFile.writeText("seeded")
    }

    companion object {
        const val FILE_NAME = "questa_seed_done"

        fun forDir(noBackupDir: File) = FirstLaunchSeedGate(
            InstallStamp(File(noBackupDir, InstallStamp.FILE_NAME)),
            File(noBackupDir, FILE_NAME),
        )
    }
}
