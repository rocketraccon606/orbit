package app.orbit.tv.userdata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class UserDatabase(context: Context) : SQLiteOpenHelper(
    context, "orbit-user.db", null, 1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE favorite(channel_id TEXT PRIMARY KEY, added_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE recent(channel_id TEXT PRIMARY KEY, last_watched_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE watch_count(channel_id TEXT PRIMARY KEY, count INTEGER NOT NULL, last_watched_at INTEGER)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun isFavorite(id: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM favorite WHERE channel_id = ? LIMIT 1", arrayOf(id),
        ).use { it.moveToFirst() }

    fun toggleFavorite(id: String): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            val exists = db.rawQuery(
                "SELECT 1 FROM favorite WHERE channel_id = ? LIMIT 1", arrayOf(id),
            ).use { it.moveToFirst() }
            if (exists) {
                db.delete("favorite", "channel_id = ?", arrayOf(id))
            } else {
                db.insertWithOnConflict(
                    "favorite", null,
                    ContentValues().apply {
                        put("channel_id", id)
                        put("added_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
            !exists
        } finally {
            db.endTransaction()
        }
    }

    fun favorites(limit: Int = 30): List<String> =
        readableDatabase.rawQuery(
            "SELECT channel_id FROM favorite ORDER BY added_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun favoriteIds(): Set<String> =
        readableDatabase.rawQuery("SELECT channel_id FROM favorite", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun touchRecent(id: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "recent", null,
                ContentValues().apply {
                    put("channel_id", id)
                    put("last_watched_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.execSQL(
                """
                DELETE FROM recent WHERE channel_id NOT IN (
                    SELECT channel_id FROM recent ORDER BY last_watched_at DESC LIMIT 20
                )
                """.trimIndent(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recents(limit: Int = 20): List<String> =
        readableDatabase.rawQuery(
            "SELECT channel_id FROM recent ORDER BY last_watched_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun incrementWatchCount(id: String) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            """
            INSERT INTO watch_count(channel_id, count, last_watched_at) VALUES (?, 1, ?)
            ON CONFLICT(channel_id) DO UPDATE SET count = count + 1, last_watched_at = excluded.last_watched_at
            """.trimIndent(),
            arrayOf(id, now),
        )
    }

    fun clearFavorites() {
        writableDatabase.delete("favorite", null, null)
    }

    fun clearRecents() {
        writableDatabase.delete("recent", null, null)
    }
}
