package com.example.launcher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.events.EvtUploadPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.22-B, review 44 item 2: `EventLogDao.countUploadableSince` (one COUNT, no rows loaded)
 * gives the same number as the push's own [EvtUploadPolicy.uploadable] rule over the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadBacklogCountTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun ev(ts: Long, dev: String? = "me", uid: String? = "u$ts", kind: String = "habitTap",
                   synthetic: Boolean = false, imported: Boolean? = null, republish: Boolean? = null) =
        EventLogEntity(timestamp = ts, kind = kind, uid = uid, dev = dev, synthetic = synthetic, imported = imported, republish = republish)

    private val rows = listOf(
        ev(90), ev(100), ev(101), ev(102, dev = "other"), ev(103, uid = null), ev(104, uid = ""),
        ev(105, kind = "lifecycle"), ev(106, synthetic = true), ev(107, imported = true),
        ev(108, imported = true, republish = true), ev(109, imported = false), ev(110, imported = true, republish = false),
        ev(111, dev = null), ev(112),
    )

    @Test
    fun `count query matches EvtUploadPolicy uploadable for every watermark`() = runBlocking {
        rows.forEach { db.eventLogDao().insertLog(it) }
        for (since in listOf(0L, 99L, 100L, 105L, 111L, 200L)) {
            val expected = rows.count { EvtUploadPolicy.uploadable(it, "me", since) }
            assertEquals("since=$since", expected, db.eventLogDao().countUploadableSince(since, "me"))
        }
        assertEquals(4, db.eventLogDao().countUploadableSince(100L, "me"))
    }
}
