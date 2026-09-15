package com.kokoreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 regression guard (the REAL silence root cause).
 *
 * AOSP TextToSpeechService binder requires SUCCESS(0)/ERROR(-1) from
 * onIsValidVoiceName/onLoadVoice:
 *   int retVal = onIsValidVoiceName(voiceName);
 *   if (retVal == TextToSpeech.SUCCESS) { enqueue LoadVoiceItem }
 * v1.1–v1.4 returned LANG_COUNTRY_AVAILABLE(2), so voice loading ALWAYS
 * failed -> setLanguage/setVoice failed -> hosts sent zero utterances.
 * TEST VOICE bypassed the framework, hiding the bug.
 */
class TtsVoiceContractTest {

    @Test fun ownVoiceAccepted() {
        assertTrue(TtsLogic.isKokoVoice("en-US-kokoro-af-sky"))
    }

    @Test fun bcp47EnVariantsAccepted() {
        assertTrue(TtsLogic.isKokoVoice("en-US"))
        assertTrue(TtsLogic.isKokoVoice("en_US"))
        assertTrue(TtsLogic.isKokoVoice("en"))
        assertTrue(TtsLogic.isKokoVoice("en-GB"))
    }

    @Test fun legacyAliasesAccepted() {
        assertTrue(TtsLogic.isKokoVoice("af_sky"))
        assertTrue(TtsLogic.isKokoVoice("af_nicole"))
        assertTrue(TtsLogic.isKokoVoice("kokoro"))
    }

    @Test fun junkRejected() {
        assertFalse(TtsLogic.isKokoVoice(null))
        assertFalse(TtsLogic.isKokoVoice(""))
        assertFalse(TtsLogic.isKokoVoice("de-DE"))
        assertFalse(TtsLogic.isKokoVoice("fr-FR-pico"))
    }
}
