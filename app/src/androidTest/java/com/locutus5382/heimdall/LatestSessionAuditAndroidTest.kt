package com.locutus5382.heimdall

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.locutus5382.heimdall.recording.latestSavedSessionAudit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated in-memory SQLite acceptance fixtures. Never opens, deletes or
 * modifies the app's real recordings or its default database.
 */
@RunWith(AndroidJUnit4::class)
class LatestSessionAuditAndroidTest {
    private fun fixture(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("""CREATE TABLE sessions (
                id INTEGER PRIMARY KEY, sample_count INTEGER NOT NULL,
                status TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE samples (
                id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL)""")
            db.execSQL("CREATE INDEX samples_session_idx ON samples(session_id, id)")
            block(db)
        }
    }

    @Test fun matchingLatestCompletedSessionIsReadOnly() = fixture { db ->
        db.execSQL("INSERT INTO sessions VALUES (41, 2, 'COMPLETED')")
        db.execSQL("INSERT INTO sessions VALUES (42, 5, 'RECORDING')")
        db.execSQL("INSERT INTO samples VALUES (1, 41)")
        db.execSQL("INSERT INTO samples VALUES (2, 41)")
        val audit = requireNotNull(latestSavedSessionAudit(db))
        assertEquals(41L, audit.sessionId)
        assertEquals(2L, audit.declaredRows)
        assertEquals(2L, audit.actualRows)
        assertTrue(audit.consistent)
        db.rawQuery("SELECT COUNT(*) FROM samples WHERE session_id=41", null)
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0))
            }
    }

    @Test fun mismatchIsReportedWithoutRepairOrDeletingSourceRows() = fixture { db ->
        db.execSQL("INSERT INTO sessions VALUES (61, 9, 'COMPLETED')")
        db.execSQL("INSERT INTO samples VALUES (1, 61)")
        val audit = requireNotNull(latestSavedSessionAudit(db))
        assertFalse(audit.consistent)
        assertEquals(9L, audit.declaredRows)
        assertEquals(1L, audit.actualRows)
        db.rawQuery("SELECT sample_count FROM sessions WHERE id=61", null)
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(9L, cursor.getLong(0))
            }
    }

    @Test fun noCompletedSessionMustRemainUnknown() = fixture { db ->
        db.execSQL("INSERT INTO sessions VALUES (3, 7, 'RECORDING')")
        assertNull(latestSavedSessionAudit(db))
    }

    @Test fun zeroSampleInterruptedCompletionIsNotFabricatedMismatch() = fixture { db ->
        db.execSQL("INSERT INTO sessions VALUES (8, 0, 'COMPLETED')")
        val audit = requireNotNull(latestSavedSessionAudit(db))
        assertEquals(0L, audit.actualRows)
        assertTrue(audit.consistent)
    }
}
