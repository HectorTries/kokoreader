package com.kokoreader

import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
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
        /** v0.6: host rate baseline — the engine already runs at 1.25x, so a
         *  host rate of 1.0 maps to no extra change; rates multiply. */
        private const val BASE_SPEED = 1.25f
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

    override fun onStop() {
        stopped.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped.set(false)
        // v0.6: snapshot rate/pitch per utterance (SynthesisRequest getters
        // may only be valid on the binder thread during this call).
        val snapRate: Int = try { request.speechRate } catch (_: Exception) { 100 }
        val text0 = try { request.charSequenceText?.toString() ?: "" } catch (_: Exception) { "" }
        val lang0 = try { request.language } catch (_: Exception) { "eng" }
        val country0 = try { request.country } catch (_: Exception) { "USA" }
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
            try {
                callback.start(KokoroEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
            } catch (t: Throwable) {
                Log.w(TAG, "empty-utterance callback failed", t)
            }
            return
        }
        val clipped = if (text.length > MAX_SPEECH_INPUT) text.take(MAX_SPEECH_INPUT) else text

        // Ensure model is staged/loaded (worker thread — safe for file IO + ORT).
        try {
            KokoroEngine.ensureInit(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed during synthesis", t)
        }
        if (!KokoroEngine.isReady()) {
            Log.w(TAG, "engine not ready (${KokoroEngine.status}); reporting error")
            try { callback.error() } catch (_: Exception) {}
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

        var started = false
        val ok = KokoroEngine.synthesizeChunked(
            clipped,
            shouldStop = { stopped.get() },
            yield = { pcm ->
                try {
                    if (!started) {
                        callback.start(KokoroEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        started = true
                    }
                    val bytes = KokoroEngine.floatToPcm16(pcm)
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
        try {
            // v0.9: nothing started = failure, never done(). Silent
            // empty-audio "success" leaves hosts (Kindle) stuck on mute.
            if (ok && started && !stopped.get()) callback.done()
            else if (!stopped.get()) callback.error()
            // else: stopped mid-utterance — just return, framework handles it.
        } catch (t: Throwable) {
            Log.w(TAG, "completion callback failed", t)
        }
        Log.i(TAG, "utterance ${if (ok) "done" else "ended early"} (${clipped.length} chars)")
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
