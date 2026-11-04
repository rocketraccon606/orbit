package app.orbit.tv.catalog

object SearchQuery {

    // \p{M} keeps Indic vowel signs; without it Malayalam names lose every
    // combining mark and the index can no longer match them.
    fun sanitize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(raw: String): List<String> =
        sanitize(raw).split(' ').filter { it.isNotEmpty() }.take(4)

    /**
     * Match variants for one token: the token itself, one-letter deletions
     * (extra-character typos) and a shortened stem (missing-character typos).
     * Android's platform SQLite has no FTS5, so these are used as LIKE
     * substrings against name, alt names, language, category and country.
     */
    fun variants(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        if (token.length == 1) return listOf(token)
        val deletions = if (token.length >= 5) {
            token.indices.map { token.removeRange(it, it + 1) }
        } else {
            emptyList()
        }
        val base = (listOf(token) + deletions).distinct().take(12)
        val stem = if (token.length >= 6) listOf(token.take(token.length - 2)) else emptyList()
        return (base + stem).distinct().filter { it.length >= 2 }
    }

    fun digitsFromKeyCode(keyCode: Int): Int? {
        val n = keyCode - android.view.KeyEvent.KEYCODE_0
        return if (n in 0..9) n else null
    }
}
