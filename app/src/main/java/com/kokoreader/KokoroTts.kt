package com.kokoreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Overlay-path TTS (v0.4): KokoroEngine (shared singleton, mmap'd model —
 * no more 134MB readBytes() OOM) with Android system-TTS fallback.
 * All callbacks and toasts are main-thread safe; nothing here throws
 * out to the caller.
 */
class KokoroTts(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioTrack: AudioTrack? = null
    @Volatile private var stopRequested = false

    @Volatile private var systemTts: TextToSpeech? = null
    @Volatile private var systemTtsReady = false

    companion object {
        private const val TAG = "KokoroTts"
    }

    init {
        initSystemTtsFallback()
        // Pre-warm engine off the main thread (file staging + ORT load).
        executor.execute {
            try {
                KokoroEngine.ensureInit(context.applicationContext)
                Log.i(TAG, "engine: ${KokoroEngine.status}")
            } catch (t: Throwable) {
                Log.e(TAG, "engine pre-warm failed", t)
            }
        }
    }

    private fun initSystemTtsFallback() {
        mainHandler.post {
            try {
                systemTts = TextToSpeech(context.applicationContext) { status ->
                    systemTtsReady = status == TextToSpeech.SUCCESS
                    if (systemTtsReady) {
                        try {
                            systemTts?.language = Locale.US
                            Log.i(TAG, "system TTS fallback ready")
                        } catch (e: Exception) {
                            Log.w(TAG, "system TTS locale failed", e)
                        }
                    } else {
                        Log.w(TAG, "system TTS init failed (status=$status)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "system TTS bind failed", e)
            }
        }
    }

    /** Always audible: Kokoro ONNX voice when ready, else Android system TTS. */
    fun speak(text: String, onDone: () -> Unit) {
        stopRequested = false
        executor.execute {
            var finished = false
            try {
                try {
                    KokoroEngine.ensureInit(context.applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "engine init failed", t)
                }
                if (KokoroEngine.isReady()) {
                    try {
                        finished = KokoroEngine.synthesizeChunked(
                            text,
                            shouldStop = { stopRequested },
                            yield = { pcm -> streamAudio(pcm) }
                        )
                        if (finished) Log.i(TAG, "spoken via Kokoro ONNX (${text.length} chars)")
                        else Log.w(TAG, "Kokoro synthesis incomplete; falling back")
                    } catch (t: Throwable) {
                        Log.e(TAG, "ONNX speak failed, falling back to system TTS", t)
                        finished = false
                    }
                } else {
                    Log.i(TAG, "engine not ready (${KokoroEngine.status}); using system TTS")
                }
                if (!finished && !stopRequested) {
                    speakViaSystemTts(text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed", t)
            } finally {
                try { onDone() } catch (_: Exception) {}
            }
        }
    }

    private fun speakViaSystemTts(text: String) {
        var tts = systemTts
        if (tts == null || !systemTtsReady) {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    tts = TextToSpeech(context.applicationContext) { status ->
                        systemTts = tts
                        systemTtsReady = status == TextToSpeech.SUCCESS
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "system TTS rebind failed", e)
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            tts = systemTts
        }
        val engine = tts
        if (engine == null || !systemTtsReady) {
            Log.e(TAG, "no TTS engine available at all")
            return
        }
        try {
            engine.language = Locale.US
        } catch (_: Exception) {}
        for (chunk in text.chunked(3900)) {
            if (stopRequested) break
            val id = UUID.randomUUID().toString()
            val done = CountDownLatch(1)
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.countDown() }
                @Deprecated("legacy")
                override fun onError(utteranceId: String?) { done.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { done.countDown() }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { done.countDown() }
            }
            mainHandler.post {
                try {
                    engine.setOnUtteranceProgressListener(listener)
                    engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
                } catch (e: Exception) {
                    Log.e(TAG, "system speak failed", e)
                    done.countDown()
                }
            }
            done.await(60, TimeUnit.SECONDS)
        }
        Log.i(TAG, "spoken via system TTS (${text.length} chars)")
    }

    /** Returns true unless stop was requested mid-chunk. Never throws. */
    private fun streamAudio(pcm: FloatArray): Boolean {
        try {
            var track = audioTrack
            if (track == null || track.sampleRate != KokoroEngine.SAMPLE_RATE) {
                try { track?.release() } catch (_: Exception) {}
                val bufSize = AudioTrack.getMinBufferSize(
                    KokoroEngine.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                ).coerceAtLeast(pcm.size * 4)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(KokoroEngine.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack = track
            }
            track!!.play()
            var off = 0
            while (off < pcm.size && !stopRequested) {
                val n = minOf(4096, pcm.size - off)
                track.write(pcm, off, n, AudioTrack.WRITE_BLOCKING)
                off += n
            }
            if (stopRequested) {
                try { track.pause() } catch (_: Exception) {}
                try { track.flush() } catch (_: Exception) {}
                return false
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "streamAudio failed", t)
            return false
        }
    }

    fun stop() {
        stopRequested = true
        mainHandler.post {
            try { systemTts?.stop() } catch (_: Exception) {}
        }
    }

    fun isReady(): Boolean = KokoroEngine.isReady() || systemTtsReady

    fun close() {
        stopRequested = true
        mainHandler.post {
            try { systemTts?.stop() } catch (_: Exception) {}
            try { systemTts?.shutdown() } catch (_: Exception) {}
            systemTts = null
        }
        executor.execute {
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
        }
        executor.shutdown()
    }
}
