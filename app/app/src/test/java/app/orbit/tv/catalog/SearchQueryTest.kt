package app.orbit.tv.catalog

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun sanitizeLowercasesAndStripsPunctuation() {
        assertEquals("bbc news hd", SearchQuery.sanitize("  BBC-News, HD!  "))
    }

    @Test
    fun sanitizeKeepsUnicodeLetters() {
        assertEquals("മലയാളം ടിവി", SearchQuery.sanitize("മലയാളം  ടിവി"))
    }

    @Test
    fun tokensKeepShortInputAndCapAtFour() {
        assertEquals(listOf("a", "bbc", "news"), SearchQuery.tokens("a bbc news"))
        assertEquals(4, SearchQuery.tokens("one two three four five").size)
    }

    @Test
    fun variantsRepairExtraLettersByDeletion() {
        assertTrue(SearchQuery.variants("malayalamm").contains("malayalam"))
        assertTrue(SearchQuery.variants("malayalamm").contains("malayalamm"))
    }

    @Test
    fun variantsRepairMissingLetterWithShortStem() {
        assertTrue(SearchQuery.variants("malayalm").contains("malaya"))
    }

    @Test
    fun shortTokensHaveNoTypoVariants() {
        assertEquals(listOf("tv"), SearchQuery.variants("tv"))
        assertEquals(listOf("a"), SearchQuery.variants("a"))
    }

    @Test
    fun digitKeysMapToChannelNumbers() {
        assertEquals(0, SearchQuery.digitsFromKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(4, SearchQuery.digitsFromKeyCode(KeyEvent.KEYCODE_4))
        assertEquals(9, SearchQuery.digitsFromKeyCode(KeyEvent.KEYCODE_9))
        assertNull(SearchQuery.digitsFromKeyCode(KeyEvent.KEYCODE_A))
        assertNull(SearchQuery.digitsFromKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
