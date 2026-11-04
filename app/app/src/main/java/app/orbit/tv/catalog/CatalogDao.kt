package app.orbit.tv.catalog

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

class CatalogDao private constructor(
    private val db: SQLiteDatabase,
    val path: String,
) {

    companion object {
        fun open(file: File): CatalogDao? {
            if (!file.isFile || file.length() == 0L) return null
            return try {
                var db = SQLiteDatabase.openDatabase(
                    file.path, null, SQLiteDatabase.OPEN_READONLY,
                )
                var version = schemaVersion(db)
                if (version == 1) {
                    // Keep a previously installed catalog usable offline after the
                    // app upgrade; the next update replaces it with schema 2.
                    db.close()
                    if (!upgradeOneToTwo(file)) return null
                    db = SQLiteDatabase.openDatabase(
                        file.path, null, SQLiteDatabase.OPEN_READONLY,
                    )
                    version = schemaVersion(db)
                }
                if (version != 2) {
                    db.close()
                    null
                } else {
                    CatalogDao(db, file.path)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun schemaVersion(db: SQLiteDatabase): Int =
            db.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }

        private fun upgradeOneToTwo(file: File): Boolean = try {
            SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.beginTransaction()
                try {
                    db.execSQL("ALTER TABLE channel ADD COLUMN website TEXT")
                    db.execSQL("ALTER TABLE channel ADD COLUMN network TEXT")
                    db.execSQL("ALTER TABLE channel ADD COLUMN launched TEXT")
                    db.execSQL("ALTER TABLE channel ADD COLUMN owners TEXT")
                    db.version = 2
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        runCatching { db.close() }
    }

    fun collections(limit: Int = 20): List<CollectionSummary> {
        val sql = """
            SELECT c.id, c.title, c.kind, c.position, COUNT(i.channel_id) AS n
            FROM collection c
            JOIN collection_item i ON i.collection_id = c.id
            GROUP BY c.id
            HAVING n > 0
            ORDER BY c.position
            LIMIT ?
        """.trimIndent()
        return query(sql, arrayOf(limit.toString())) { c ->
            CollectionSummary(
                id = c.getString(0),
                title = c.getString(1),
                kind = c.getString(2),
                position = c.getInt(3),
                count = c.getInt(4),
            )
        }
    }

    fun collectionItems(collectionId: String, limit: Int = 60): List<ChannelCard> {
        val sql = """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM collection_item i
            JOIN channel ch ON ch.id = i.channel_id
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE i.collection_id = ?
            ORDER BY i.position
            LIMIT ?
        """.trimIndent()
        return query(sql, arrayOf(collectionId, limit.toString())) { it.toCard() }
    }

    fun channelsInOrder(ids: List<String>): List<ChannelCard> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.id IN ($placeholders)
        """.trimIndent()
        val byId = query(sql, ids.toTypedArray()) { it.toCard() }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun channel(id: String): ChannelDetail? {
        val row = query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched, ch.website, ch.owners
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.id = ?
            """.trimIndent(),
            arrayOf(id),
        ) {
            it.toCard() to (it.stringOrNull(10).orEmpty() to it.stringOrNull(11).orEmpty())
        }.firstOrNull() ?: return null
        val card = row.first

        val streams = query(
            """
            SELECT id, url, kind, state, user_agent, referrer, position
            FROM stream WHERE channel_id = ? ORDER BY position
            """.trimIndent(),
            arrayOf(id),
        ) { c ->
            StreamRef(
                id = c.getString(0),
                url = c.getString(1),
                kind = c.getString(2),
                state = c.getString(3),
                userAgent = c.stringOrNull(4),
                referrer = c.stringOrNull(5),
                position = c.getInt(6),
            )
        }
        return ChannelDetail(card, streams, row.second.first, row.second.second)
    }

    fun search(raw: String, limit: Int = 60): List<ChannelCard> {
        val tokens = SearchQuery.tokens(raw)
        if (tokens.isEmpty()) return emptyList()

        // Platform SQLite on Android TV has no FTS5, so query the small catalog
        // (about 1k rows) with LIKE instead. Each token must match somewhere;
        // its typo variants are OR'ed within the token group.
        val conditions = tokens.joinToString(" AND ") {
            val variants = SearchQuery.variants(it)
            "(" + variants.joinToString(" OR ") {
                "(ch.name LIKE ? OR ch.alt_names LIKE ? OR lang.name LIKE ?" +
                    " OR cat.title LIKE ? OR co.name LIKE ?)"
            } + ")"
        }
        val args = tokens.flatMap { token ->
            SearchQuery.variants(token).flatMap { variant ->
                // A single letter is a prefix search so typing "a" surfaces
                // Asianet and ABP instead of every channel containing an A.
                val pattern = if (variant.length == 1) "$variant%" else "%$variant%"
                List(5) { pattern }
            }
        }.toMutableList<String>().apply { add(limit.toString()) }

        return query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE $conditions
            ORDER BY ch.best_score DESC, ch.name
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray(),
        ) { it.toCard() }
    }

    fun info(): CatalogInfo {
        val meta = mutableMapOf<String, String>()
        db.rawQuery("SELECT key, value FROM meta", null).use { c ->
            while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
        }
        val channels = scalar("SELECT COUNT(*) FROM channel")
        val playable = scalar("SELECT COUNT(*) FROM channel WHERE state != 'dead'")
        val collections = scalar("SELECT COUNT(*) FROM collection")
        val malayalam = scalar(
            "SELECT COUNT(*) FROM collection_item WHERE collection_id = 'malayalam'",
        )
        return CatalogInfo(
            version = meta["catalog_version"].orEmpty(),
            builtAt = meta["built_at"].orEmpty(),
            schema = meta["schema"]?.toIntOrNull() ?: 0,
            source = meta["source"].orEmpty(),
            counts = CatalogCounts(channels, playable, collections, malayalam),
        )
    }

    fun channelCount(): Int = scalar("SELECT COUNT(*) FROM channel")

    fun allPlayable(): List<ChannelCard> =
        query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.state != 'dead'
            ORDER BY ch.number, ch.name
            """.trimIndent(),
            emptyArray(),
        ) { it.toCard() }

    fun channelsByCategory(category: String, limit: Int = 300): List<ChannelCard> =
        query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.state != 'dead' AND cat.title = ?
            ORDER BY ch.best_score DESC, ch.number
            LIMIT ?
            """.trimIndent(),
            arrayOf(category, limit.toString()),
        ) { it.toCard() }

    fun channelsByLanguage(language: String, limit: Int = 300): List<ChannelCard> =
        query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.state != 'dead' AND lang.name = ?
            ORDER BY ch.best_score DESC, ch.number
            LIMIT ?
            """.trimIndent(),
            arrayOf(language, limit.toString()),
        ) { it.toCard() }

    fun similar(card: ChannelCard, limit: Int = 8): List<ChannelCard> =
        query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.state != 'dead' AND ch.id != ?
              AND (lang.name = ? OR cat.title = ?)
            ORDER BY (lang.name = ?) DESC, ch.best_score DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(card.id, card.language, card.category, card.language, limit.toString()),
        ) { it.toCard() }

    fun channelByNumber(number: Int): ChannelCard? =
        query(
            """
            SELECT ch.id, ch.name, ch.logo_url, cat.title, lang.name, co.name,
                   ch.state, ch.number, ch.network, ch.launched
            FROM channel ch
            LEFT JOIN category cat ON cat.id = ch.category_id
            LEFT JOIN language lang ON lang.code = ch.language_id
            LEFT JOIN country co ON co.code = ch.country_id
            WHERE ch.number = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(number.toString()),
        ) { it.toCard() }.firstOrNull()

    private fun scalar(sql: String): Int =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun <T> query(
        sql: String,
        args: Array<String>,
        map: (Cursor) -> T,
    ): List<T> = db.rawQuery(sql, args).use { c ->
        buildList {
            while (c.moveToNext()) add(map(c))
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.toCard(): ChannelCard = ChannelCard(
        id = getString(0),
        name = getString(1),
        logoUrl = stringOrNull(2),
        category = stringOrNull(3) ?: "Other",
        language = stringOrNull(4) ?: "Unknown",
        country = stringOrNull(5) ?: "",
        state = getString(6),
        number = getInt(7),
        network = stringOrNull(8).orEmpty(),
        launched = stringOrNull(9).orEmpty(),
    )
}
