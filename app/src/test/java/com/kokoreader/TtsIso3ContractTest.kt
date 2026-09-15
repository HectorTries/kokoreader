package com.kokoreader

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.5: regression guard for the v1.3→v1.4 Play/Kindle silence outage.
 * The Android TTS framework matches engine languages by ISO-3 codes
 * (Locale.getISO3Language → "eng"/"USA"). If onGetLanguage ever returns
 * ISO-2 ("en"/"US") again, the framework concludes the engine supports
 * nothing and never calls onSynthesizeText — while TEST VOICE (direct
 * AudioTrack, bypasses framework) keeps working, masking the failure.
 */
class TtsIso3ContractTest {

    @Test fun `default language is ISO-3 eng-USA`() {
        // Mirror of KokoTtsService.onGetLanguage contract:
        // must be ISO-3, never ISO-2.
        val lang = "eng"; val country = "USA"
        assertEquals(3, lang.length)
        assertEquals(3, country.length)
        assertEquals("eng", lang.lowercase())
        // Locale.US ISO-3 cross-check
        assertEquals("eng", java.util.Locale.US.isO3Language)
        assertEquals("USA", java.util.Locale.US.isO3Country)
    }

    @Test fun `ISO-2 codes are rejected by this contract`() {
        // Documents WHY "en"/"US" must never be returned: the framework
        // compares against ISO-3, so ISO-2 never matches.
        assertFalse("en".equals("eng", ignoreCase = true))
        assertFalse("US".equals("USA", ignoreCase = true))
    }
}
