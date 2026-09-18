package com.williamguillon.cortana.db

import android.database.sqlite.SQLiteDatabase
import com.williamguillon.cortana.runtime.RuntimePaths
import android.content.Context
import java.io.File

data class WalCheckResult(
    val journalMode: String,
    val walFilePresent: Boolean,
    val shmFilePresent: Boolean,
)

/**
 * Direct check against Hermes's own `state.db` using Android's native SQLite driver (spec §4.4
 * point 11) — no external `sqlite3` command, no assumption baked into the runtime layer about
 * what mode it's running in.
 */
object WalChecker {
    fun check(ctx: Context): WalCheckResult {
        val dbFile = RuntimePaths.stateDb(ctx)
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        val mode = db.use { opened ->
            opened.rawQuery("PRAGMA journal_mode;", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
        }
        return WalCheckResult(
            journalMode = mode,
            walFilePresent = File(dbFile.parentFile, "${dbFile.name}-wal").exists(),
            shmFilePresent = File(dbFile.parentFile, "${dbFile.name}-shm").exists(),
        )
    }
}
