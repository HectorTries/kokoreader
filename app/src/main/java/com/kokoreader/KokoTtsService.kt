package com.kokoreader

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KokoReader as a REAL Android TTS engine (v0.4+).
 *
 * Select in: Settings → System → Languages → Text-to-speech output →
 * Preferred engine → KokoReader. Then Kindle "Read Aloud", Google Play Books
 * "Read aloud", TalkBack custom engine, and any app calling Android TTS
 * speak through our Kokoro ONNX voice with zero overlay needed.
 *
 * Heavy work (model staging + inference) always on a worker thread.
 * Every callback wrapped in try/catch; failures reported via
 * callback.error() so the host app gets a clean error, never a crash.
 */
class KokoTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "KokoTtsService"
        private const val MAX_SPEECH_INPUT = 4000
        /** v1.6: per-pass utterance cap (~1000 chars, word-boundary cut),
         *  sequential passes share one start/done session. */
        private const val MAX_UTTERANCE_CHARS = 1000
        /** v0.6: host rate baseline — the engine already runs at 1.25x, so a
         *  host rate of 1.0 maps to no extra change; rates multiply. */
        private const val BASE_SPEED = 1.25f
        /** v1.5 on-screen diagnostics: last framework synth event, readable
         *  from MainActivity without logcat (screenshot-able). */
        @Volatile var lastSynth: String = "no synth request yet"
            private set
        @Volatile var synthCount: Int = 0
            private set
        fun recordSynth(line: String) {
            synthCount++
            lastSynth = "#$synthCount $line"
        }
    }

    private lateinit var synthExecutor: ExecutorService
    /** v0.9 BUG-2: pre-warm/ensureInit runs here, never on synthExecutor —
     *  the 134MB stage + ORT session build must not serialise behind
     *  (or stall) the first utterance. */
    private lateinit var initExecutor: ExecutorService
    private val stopped = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        synthExecutor = Executors.newSingleThreadExecutor()
        initExecutor = Executors.newSingleThreadExecutor()
        // Pre-warm the engine off the main thread (one-time ~134MB file staging).
        initExecutor.execute {
            try {
                KokoroEngine.ensureInit(applicationContext)
                Log.i(TAG, "engine pre-warm: ${KokoroEngine.status}")
            } catch (t: Throwable) {
                Log.e(TAG, "pre-warm failed", t)
            }
        }
        Log.i(TAG, "TTS engine service created")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return try {
            val l = (lang ?: "").lowercase()
            // English-only engine.
            if (l == "eng" || l == "en") {
                if (country.isNullOrEmpty() || country.equals("USA", ignoreCase = true)
                    || country.equals("US", ignoreCase = true) || country.equals("GBR", ignoreCase = true)
                    || country.equals("GB", ignoreCase = true)
                ) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
            } else TextToSpeech.LANG_NOT_SUPPORTED
        } catch (t: Throwable) {
            Log.w(TAG, "onIsLanguageAvailable failed", t)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    // v1.4 FIX (Play/Kindle silent regression): the framework matches
    // languages by ISO-3 codes ("eng"/"USA", cf. Locale.getISO3Language).
    // v1.3 switched this to ISO-2 ("en"/"US") so the framework concluded
    // the engine supports nothing it asks for and never called
    // onSynthesizeText. TEST VOICE bypasses the framework (direct
    // AudioTrack), which is why it kept working. Voice locale (Locale.US
    // -> eng/USA) and onIsLanguageAvailable already use ISO-3 semantics.
    override fun onGetLanguage(): Array<String> {
        return try {
            arrayOf("eng", "USA", "")
        } catch (t: Throwable) {
            Log.w(TAG, "onGetLanguage failed", t)
            arrayOf("eng", "USA", "")
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return try {
            val l = (lang ?: "").lowercase()
            if (l == "eng" || l == "en") {
                if (country.isNullOrEmpty() || country.equals("USA", ignoreCase = true)
                    || country.equals("US", ignoreCase = true) || country.equals("GBR", ignoreCase = true)
                    || country.equals("GB", ignoreCase = true)
                ) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
            } else TextToSpeech.LANG_NOT_SUPPORTED
        } catch (t: Throwable) {
            Log.w(TAG, "onLoadLanguage failed", t)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    // v1.1 FIX-1 (silent-engine killer): expose a US English voice.
    // Without non-empty getVoices + isValidVoice + loadVoice, the Android
    // TTS framework (and Kindle) treat the engine as voiceless and send
    // nothing to onSynthesizeText. Voice features require API 21+; the
    // whole block degrades gracefully on older runtimes (minSdk 29 anyway).
    private fun kokoVoice(): Voice? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val locale = Locale.US
            Voice(
                "en-US-kokoro-af-sky",
                locale,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                setOf("female", "en-US", "kokoro", "af_sky")
            )
        } catch (t: Throwable) {
            Log.w(TAG, "voice construction failed", t)
            null
        }
    }

    override fun onGetVoices(): List<Voice> {
        return try {
            val v = kokoVoice()
            if (v != null) listOf(v) else emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "onGetVoices failed", t)
            emptyList()
        }
    }

    // NOTE: actual framework signatures are onIsValidVoiceName(String) and
    // onLoadVoice(String). (Earlier draft used Voice-typed overloads that
    // don't exist — fixed in v1.1.)
    // v1.5 ROOT-CAUSE FIX (Play/Kindle silence since v1.1): the framework
    // contract requires SUCCESS/ERROR here, NOT LANG_* codes. AOSP binder:
    //   int retVal = onIsValidVoiceName(voiceName);
    //   if (retVal == TextToSpeech.SUCCESS) { enqueue LoadVoiceItem }
    // LANG_COUNTRY_AVAILABLE (2) != SUCCESS (0), so voice loading ALWAYS
    // failed -> setLanguage/setVoice failed -> hosts sent zero utterances.
    // TEST VOICE bypasses the framework (direct AudioTrack), which is why
    // it always worked. (The v1.3/v1.4 ISO-2/ISO-3 theory was a red herring:
    // onGetLanguage is only called on API <= 17 per AOSP javadoc.)
    override fun onIsValidVoiceName(voiceName: String?): Int {
        return try {
            if (TtsLogic.isKokoVoice(voiceName)) TextToSpeech.SUCCESS
            else TextToSpeech.ERROR
        } catch (t: Throwable) {
            Log.w(TAG, "onIsValidVoiceName failed", t)
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        return try {
            if (!TtsLogic.isKokoVoice(voiceName)) return TextToSpeech.ERROR
            Log.i(TAG, "voice loaded: $voiceName")
            TextToSpeech.SUCCESS
        } catch (t: Throwable) {
            Log.w(TAG, "onLoadVoice failed", t)
            TextToSpeech.ERROR
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        return try {
            val l = (lang ?: "").lowercase()
            if (l == "eng" || l == "en" || l.isEmpty()) "en-US-kokoro-af-sky" else ""
        } catch (t: Throwable) {
            Log.w(TAG, "onGetDefaultVoiceNameFor failed", t)
            ""
        }
    }

    override fun onStop() {
        stopped.set(true)
        lastSynth = "${lastSynth.split("\n").firstOrNull() ?: "-"}\n[stopped]"
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped.set(false)
        // v0.6: snapshot rate/pitch per utterance (SynthesisRequest getters
        // may only be valid on the binder thread during this call).
        val snapRate: Int = try { request.speechRate } catch (_: Exception) { 100 }
        val text0 = try { request.charSequenceText?.toString() ?: "" } catch (_: Exception) { "" }
        // v1.1 FIX-2: accept ALL en variants, never reject. Log what the host asked for.
        val lang0 = try { request.language } catch (_: Exception) { "eng" }
        val country0 = try { request.country } catch (_: Exception) { "USA" }
        val variant0 = try { request.variant } catch (_: Exception) { "" }
        val voiceName0 = try { request.voiceName } catch (_: Exception) { "" }
        Log.i(TAG, "synth req lang=$lang0 country=$country0 variant=$variant0 voice=$voiceName0 rate=$snapRate chars=${text0.length} head=[${text0.take(60).replace('\n', ' ')}]")
        recordSynth("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK).format(java.util.Date())} lang=$lang0/$country0 voice=$voiceName0 chars=${text0.length} head=[${text0.take(50).replace('\n', ' ')}]")
        synthExecutor.execute {
            try {
                doSynthesize(text0, snapRate, callback)
            } catch (t: Throwable) {
                Log.e(TAG, "synthesis crashed — reporting error, never propagating", t)
                try {
                    callback.error()
                } catch (_: Exception) {}
            }
        }
    }

    private fun doSynthesize(text0: String, hostRatePct: Int, callback: SynthesisCallback) {
        val text = text0
        if (text.isBlank()) {
            var started = false
            try {
                callback.start(KokoroEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                started = true
                if (started) callback.done()
            } catch (t: Throwable) {
                Log.w(TAG, "empty-utterance callback failed", t)
            }
            return
        }
        val clipped = if (text.length > MAX_SPEECH_INPUT) text.take(MAX_SPEECH_INPUT) else text

        // v1.6: ensureInit + isReady BEFORE the first callback.start(). If
        // init fails, report error() with no start — a start with no audio
        // leaves hosts waiting on a stream that never delivers.
        var hasStarted = false
        var hasFinished = false
        try {
            KokoroEngine.ensureInit(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed during synthesis", t)
        }
        if (!KokoroEngine.isReady()) {
            Log.w(TAG, "engine not ready (${KokoroEngine.status}); reporting error")
            recordSynth("error init-failed chars=${clipped.length}")
            try { if (!hasFinished) { callback.error(); hasFinished = true } } catch (_: Exception) {}
            return
        }
        // v0.6: honour host speech-rate (Kindle/Play Books speed slider).
        // Effective speed = persisted engine speed scaled by host rate.
        try {
            val prefs = applicationContext.getSharedPreferences("kokoreader", MODE_PRIVATE)
            val base = prefs.getFloat("speech_speed", 1.25f).coerceIn(0.5f, 2.0f)
            val hostRate = try { hostRatePct.toFloat() / 100f } catch (_: Exception) { 1f }
            val safeHost = if (hostRate in 0.25f..4.0f) hostRate else 1f
            KokoroEngine.speechSpeed = (base * safeHost).coerceIn(0.5f, 2.0f)
            if (safeHost != 1f) Log.i(TAG, "host rate ${safeHost}x (base ${base}x) -> engine ${KokoroEngine.speechSpeed}x")
        } catch (t: Throwable) {
            Log.w(TAG, "speech-rate mapping failed", t)
        }

        // v1.6: cap each synthesize pass at ~1000 chars (boundary-aligned),
        // processed sequentially in ONE start/done session. Long hosts
        // utterances no longer risk G2P/ORT stalls mid-stream.
        val segments = ArrayList<String>()
        var rem = clipped
        while (rem.length > MAX_UTTERANCE_CHARS) {
            var cut = rem.lastIndexOf(' ', MAX_UTTERANCE_CHARS)
            if (cut < MAX_UTTERANCE_CHARS / 2) cut = MAX_UTTERANCE_CHARS
            segments.add(rem.substring(0, cut))
            rem = rem.substring(cut).trimStart()
        }
        if (rem.isNotEmpty()) segments.add(rem)

        var bytesDelivered = 0
        var ok = true
        for (seg in segments) {
            if (stopped.get()) { ok = false; break }
            ok = KokoroEngine.synthesizeChunked(
                seg,
            shouldStop = { stopped.get() },
            yield = { pcm ->
                try {
                    // v1.6: late start on first audio, guarded — start at most once.
                    if (!hasStarted && !hasFinished) {
                        callback.start(KokoroEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        hasStarted = true
                    }
                    val bytes = KokoroEngine.floatToPcm16(pcm)
                    bytesDelivered += bytes.size
                    var off = 0
                    while (off < bytes.size) {
                        if (stopped.get()) return@synthesizeChunked false
                        val n = minOf(8192, bytes.size - off)
                        val written = callback.audioAvailable(bytes, off, n)
                        // v0.9 BUG-4: a 0-write is backpressure, not progress —
                        // re-offer the same slice (bounded), then fail loudly.
                        if (written > 0) { off += written; continue }
                        var retries = 0
                        var w2 = 0
                        while (retries < 10 && w2 <= 0 && !stopped.get()) {
                            try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                            w2 = try { callback.audioAvailable(bytes, off, n) } catch (_: Exception) { break }
                            retries++
                        }
                        if (w2 > 0) { off += w2; continue }
                        Log.e(TAG, "audioAvailable 0-write x$retries — reporting error")
                        return@synthesizeChunked false
                    }
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "audio callback failed", t)
                    false
                }
            }
            )
            if (!ok) break
        }
        try {
            // v0.9: nothing started = failure, never done(). Silent
            // empty-audio "success" leaves hosts (Kindle) stuck on mute.
            // v1.6: never leave the callback dangling — if stopped after
            // start, done()/error (guarded by hasFinished).
            if (hasFinished) { /* already terminated */ }
            else if (ok && hasStarted && !stopped.get()) { callback.done(); hasFinished = true }
            else if (stopped.get()) {
                // Stopped mid-utterance after start: terminate gracefully
                // with done() (SynthesisCallback has no stop()); fall back
                // to error() if done() throws. No start yet: error().
                if (hasStarted) { try { callback.done() } catch (_: Exception) { try { callback.error() } catch (_: Exception) {} } }
                else { try { callback.error() } catch (_: Exception) {} }
                hasFinished = true
            }
            else { try { callback.error() } catch (_: Exception) {}; hasFinished = true }
        } catch (t: Throwable) {
            Log.w(TAG, "completion callback failed", t)
        }
        // v1.6 diagnostics: distinguish request vs audio actually delivered.
        recordSynth("done bytes=$bytesDelivered ok=$ok started=$hasStarted stopped=${stopped.get()} chars=${clipped.length}")
        Log.i(TAG, "utterance ${if (ok) "done" else "ended early"} (${clipped.length} chars, bytes=$bytesDelivered)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            stopped.set(true)
        } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            stopped.set(true)
            synthExecutor.shutdownNow()
            initExecutor.shutdownNow()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    @Suppress("unused")
    private fun localeOf(lang: String, country: String, variant: String): Locale = try {
        Locale(lang, country, variant)
    } catch (_: Exception) {
        Locale.US
    }
}
