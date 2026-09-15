package com.kokoreader

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared Kokoro-82M inference engine (singleton, thread-safe).
 *
 * Crash-hardening vs v0.3 (which OOM-killed the process on Play):
 *  - v0.3 did `assets.open(...).readBytes()` on the 134MB model -> ~134MB heap
 *    allocation PLUS ORT's internal copy from byte[] -> transient ~270MB+ -> OOM.
 *  - v0.4 streams the asset to filesDir ONCE (8KB buffer, no giant allocation)
 *    and lets ORT memory-map the file via createSession(path).
 *  - Every entry point catches Throwable (incl. OutOfMemoryError) and reports
 *    via status listener. Nothing here ever crashes the host process silently.
 */
object KokoroEngine {

    private const val TAG = "KokoroEngine"
    private const val MODEL_ASSET = "models/kokoro-82m-int8.onnx"
    /** Default voice asset (v0.6: af_sky — brighter/faster than af_sarah-style af_nicole). */
    private const val VOICE_ASSET = "models/af_sky.bin"
    private const val LEGACY_VOICE_ASSET = "models/af_nicole.bin"
    @Volatile private var currentVoiceAsset: String = VOICE_ASSET
    private const val MODEL_FILE = "kokoro-82m-int8.onnx"
    const val SAMPLE_RATE = 24000

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var voice: FloatArray? = null
    @Volatile private var voiceRows = 0
    /** Playback speed multiplier sent as the Kokoro `speed` input.
     *  Default 1.25x (v0.6): user report — 1.0x default voice sounds too
     *  slow/seductive. Persisted in prefs; host TTS rate multiplies it. */
    @Volatile var speechSpeed: Float = 1.25f
    /** Per-phase init timings (ms) for latency diagnosis. */
    @Volatile var phaseTimings: String = ""
        private set
    @Volatile var status: String = "not initialised"
        private set
    private val initialising = AtomicBoolean(false)
    @Volatile private var initDone = false

    /** v0.7: execution-provider preference. NNAPI first, GPU second
     *  (GPU = NNAPI runtime with USE_FP16 — the standard ORT Android AAR
     *  ships no standalone GPU EP, so GPU placement goes via NNAPI FP16
     *  which the NNAPI runtime may place on GPU/TPU), CPU fallback always.
     *  Persisted in prefs under "ep_pref" (AUTO/NNAPI/GPU/CPU). */
    enum class EpPref { AUTO, NNAPI, GPU, CPU }
    @Volatile var epPref: EpPref = EpPref.AUTO
        private set
    /** Which EP the live session was actually built with. NNAPI in ORT
     *  does PARTIAL delegation: supported nodes run on the accelerator,
     *  the rest (e.g. custom/layer-norm variants) stay on CPU — never
     *  all-or-nothing, and session creation surviving == verified. */
    @Volatile var activeEp: String = "cpu"
        private set
    /** Rolling per-chunk inference stats (ms) for EP comparison. */
    @Volatile var lastInferMs: Long = -1
        private set
    @Volatile var avgInferMs: Double = -1.0
        private set
    @Volatile private var inferCount: Long = 0
    @Volatile private var inferTotalMs: Long = 0
    @Volatile private var modelPath: String = ""

    /** Idempotent. Runs load on the calling thread — callers must use a worker thread. */
    @Synchronized
    fun ensureInit(appContext: Context) {
        if (initDone) return
        if (!initialising.compareAndSet(false, true)) {
            // Another thread is initialising — wait briefly so callers that
            // raced the service-onCreate prewarm don't synthesise on a
            // half-loaded session (v0.5 first-speech race).
            var waited = 0
            while (!initDone && waited < 120_000) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                waited += 200
            }
            return
        }
        val t = HashMap<String, Long>()
        fun mark(k: String) { t[k] = android.os.SystemClock.elapsedRealtime() }
        mark("start")
        try {
            // 0. Restore persisted speech prefs (speed + voice).
            try {
                val prefs = appContext.getSharedPreferences("kokoreader", Context.MODE_PRIVATE)
                speechSpeed = prefs.getFloat("speech_speed", 1.25f).coerceIn(0.5f, 2.0f)
                val savedVoice = prefs.getString("voice_asset", VOICE_ASSET) ?: VOICE_ASSET
                if (savedVoice != VOICE_ASSET) currentVoiceAsset = savedVoice
                epPref = try { EpPref.valueOf(prefs.getString("ep_pref", "AUTO") ?: "AUTO") } catch (_: Exception) { EpPref.AUTO }
            } catch (_: Exception) {}
            // 1. espeak-ng G2P: extract data (assets can't be mmap'd by path)
            //    then init native lib with the extracted dir.
            try {
                val espeakDir = File(appContext.filesDir, "espeak-data")
                extractEspeakData(appContext, espeakDir)
                if (!EspeakBridge.init(espeakDir.absolutePath)) {
                    Log.w(TAG, "espeak init returned false; G2P may fall back")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "espeak-ng init failed", t)
            }
            // 2. Voice style vectors (small, heap-safe). Fall back to the
            //    legacy bundled voice if the new default asset is missing.
            mark("voice_start")
            loadVoice(appContext, currentVoiceAsset)
            if (voice == null && currentVoiceAsset != LEGACY_VOICE_ASSET) {
                loadVoice(appContext, LEGACY_VOICE_ASSET)
            }
            mark("voice_done")
            // 3. Model: stream asset -> file, then mmap via path. Never readBytes().
            val modelFile = File(appContext.filesDir, MODEL_FILE)
            val assetLen = try {
                appContext.assets.openFd(MODEL_ASSET).use { it.length }
            } catch (_: Exception) { -1L }
            if (assetLen in 1..1023) {
                status = "model asset is a stub (${assetLen}b); voice disabled"
                Log.w(TAG, status)
                return
            }
            if (!modelFile.exists() || modelFile.length() < 1024) {
                status = "copying model to storage (one-time, ~134MB)…"
                Log.i(TAG, status)
                copyAssetToFile(appContext, MODEL_ASSET, modelFile)
            }
            if (modelFile.length() < 1024) {
                status = "model file too small (${modelFile.length()}b); voice disabled"
                Log.w(TAG, status)
                return
            }
            mark("copy_done")
            env = OrtEnvironment.getEnvironment()
            modelPath = modelFile.absolutePath
            mark("session_start")
            session = createAcceleratedSession(modelPath)
            mark("session_done")
            // v0.9: G2P probe — refuse "ready" with zero audio. If espeak
            // init failed, every chunk G2Ps to empty ids and hosts hear
            // silence reported as success. Surface it here instead.
            try {
                val probe = EspeakBridge.textToPhonemeIds("hello")
                if (probe.isEmpty()) {
                    status = "G2P probe failed (espeak-data missing/broken); voice disabled"
                    try { persistReady(appContext, false) } catch (_: Exception) {}
                    Log.e(TAG, status)
                    try { session?.close() } catch (_: Exception) {}
                    session = null
                    return
                }
            } catch (t: Throwable) {
                status = "G2P probe crashed: ${t.message}; voice disabled"
                try { persistReady(appContext, false) } catch (_: Exception) {}
                Log.e(TAG, status, t)
                try { session?.close() } catch (_: Exception) {}
                session = null
                return
            }
            // Warmup: one short silent synthesis so the FIRST real utterance
            // doesn't pay graph-arena + kernel autotune cost (v0.5 complaint).
            try {
                val warmIds = longArrayOf(16, 16, 16)
                val warm = runKokoro(session!!, voice!!, warmIds)
                Log.i(TAG, "warmup ok (${warm.size} samples)")
            } catch (t: Throwable) {
                Log.w(TAG, "warmup inference failed (non-fatal)", t)
            }
            mark("warmup_done")
            val sb = StringBuilder()
            var prev = t["start"]!!
            for (k in listOf("voice_start", "voice_done", "copy_done", "session_start", "session_done", "warmup_done")) {
                val cur = t[k] ?: continue
                sb.append("$k=+${cur - prev}ms ")
                prev = cur
            }
            phaseTimings = sb.toString()
            status = "ready (model=${modelFile.length()}b, inputs=${session!!.inputNames}, ep=$activeEp, speed=${speechSpeed}x, voice=$currentVoiceAsset)"
            persistReady(appContext, true)
            Log.i(TAG, "KokoroEngine $status | phases: $phaseTimings")
        } catch (t: Throwable) {
            status = "init failed: ${t.javaClass.simpleName}: ${t.message}"
            try { persistReady(appContext, false) } catch (_: Exception) {}
            Log.e(TAG, status, t)
            try { session?.close() } catch (_: Exception) {}
            session = null
        } finally {
            initDone = true
            initialising.set(false)
        }
    }

    fun isReady(): Boolean = initDone && session != null && voice != null && voiceRows > 0

    /** v1.1 FIX-3b: persistent ready flag — survives process restarts so the
     *  second Play (and every later one) skips straight to synthesis when a
     *  previous run completed staging successfully. Cleared on any init failure. */
    fun wasReadyBefore(appContext: Context): Boolean {
        return try {
            val prefs = appContext.getSharedPreferences("kokoreader", Context.MODE_PRIVATE)
            prefs.getBoolean("engine_ready_v1", false) &&
                File(appContext.filesDir, MODEL_FILE).let { it.exists() && it.length() > 1024 } &&
                File(File(appContext.filesDir, "espeak-data"), ".ready").exists()
        } catch (_: Exception) { false }
    }
    private fun persistReady(appContext: Context, ready: Boolean) {
        try {
            appContext.getSharedPreferences("kokoreader", Context.MODE_PRIVATE)
                .edit().putBoolean("engine_ready_v1", ready).apply()
        } catch (_: Exception) {}
    }

    /** Persisted voice/speed setters (called from app settings). */
    fun setVoiceAsset(appContext: Context, asset: String): Boolean {
        return try {
            loadVoice(appContext, asset)
            if (voice != null && voiceRows > 0) {
                currentVoiceAsset = asset
                appContext.getSharedPreferences("kokoreader", Context.MODE_PRIVATE)
                    .edit().putString("voice_asset", asset).apply()
                Log.i(TAG, "voice switched to $asset ($voiceRows rows)")
                true
            } else false
        } catch (t: Throwable) {
            Log.e(TAG, "voice switch failed", t)
            false
        }
    }

    fun setSpeechSpeed(appContext: Context, speed: Float) {
        speechSpeed = speed.coerceIn(0.5f, 2.0f)
        try {
            appContext.getSharedPreferences("kokoreader", Context.MODE_PRIVATE)
                .edit().putFloat("speech_speed", speechSpeed).apply()
        } catch (_: Exception) {}
    }

    fun currentVoice(): String = currentVoiceAsset

    private fun loadVoice(context: Context, asset: String = currentVoiceAsset) {
        try {
            context.assets.open(asset).use { stream ->
                // Voice file is ~512KB; bounded read with size guard.
                val out = java.io.ByteArrayOutputStream(600 * 1024)
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 8 * 1024 * 1024) throw IllegalStateException("voice asset implausibly large")
                    out.write(buf, 0, n)
                }
                val raw = out.toByteArray()
                val fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val arr = FloatArray(raw.size / 4)
                fb.get(arr)
                voice = arr
                voiceRows = arr.size / 256
                Log.i(TAG, "voice loaded ($voiceRows rows)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "no voice asset; engine will report not-ready", t)
        }
    }

    /** Extract espeak-data assets to filesDir once (marker file guards re-copy). */
    private fun extractEspeakData(context: Context, destDir: File) {
        val marker = File(destDir, ".ready")
        if (marker.exists()) return
        destDir.mkdirs()
        val am = context.assets
        fun copyDir(assetDir: String, outDir: File) {
            outDir.mkdirs()
            for (name in am.list(assetDir) ?: emptyArray()) {
                val aPath = "$assetDir/$name"
                val kids = try { am.list(aPath) } catch (_: Exception) { null }
                if (kids != null && kids.isNotEmpty()) {
                    copyDir(aPath, File(outDir, name))
                } else {
                    try {
                        am.open(aPath).use { input ->
                            File(outDir, name).outputStream().use { input.copyTo(it) }
                        }
                    } catch (_: Exception) { /* empty dir pseudo-entry */ }
                }
            }
        }
        // Wipe stale partial extraction, then copy fresh.
        try { destDir.deleteRecursively() } catch (_: Exception) {}
        destDir.mkdirs()
        copyDir("espeak-data", destDir)
        try { marker.createNewFile() } catch (_: Exception) {}
        Log.i(TAG, "espeak-data extracted to ${destDir.absolutePath}")
    }

    private fun copyAssetToFile(context: Context, asset: String, dest: File) {
        val tmp = File(context.filesDir, "$MODEL_FILE.tmp")
        try {
            context.assets.open(asset).use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        if (total > 1024L * 1024 * 1024) throw IllegalStateException("model implausibly large")
                    }
                    output.flush()
                }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("rename failed")
            Log.i(TAG, "model staged at ${dest.absolutePath} (${dest.length()}b)")
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Exception) {}
            throw t
        }
    }

    /**
     * Synthesise text, yielding one PCM float chunk at a time.
     * Returns true if all chunks were produced, false on any failure/stop.
     * Never throws.
     */
    /** v0.8 speed brief: 120-200 char clause chunks (split on .!?;,
     *  keep delimiter), ~400 phoneme token cap per chunk with split
     *  fallback. Larger chunks = fewer ORT dispatches = higher throughput;
     *  120-200 chars keeps longest-phoneme languages (~2x phonemes/char)
     *  under the token cap. */
    const val MIN_CHUNK_CHARS = 120
    const val MAX_CHUNK_CHARS = 200
    /** Max phoneme tokens per single inference call (~400). Chunks whose
     *  G2P id list exceeds this are halved recursively (split fallback). */
    const val MAX_TOKENS_PER_CHUNK = 400

    /** Split text into clause-aware chunks in [MIN,MAX] char range.
     *  Short trailing text merges with the previous chunk.
     *  Delegates to TtsLogic (pure JVM, unit-tested) — single source of truth. */
    fun splitSentences(text: String): List<String> = TtsLogic.splitSentences(text)

    fun synthesizeChunked(text: String, shouldStop: () -> Boolean, yield: (FloatArray) -> Boolean): Boolean {
        val s = session
        val v = voice
        if (s == null || v == null || voiceRows <= 0) {
            Log.w(TAG, "synthesize with engine not ready ($status)")
            return false
        }
        return try {
            // v0.8 double-buffer: pre-G2P all chunks up front (cheap), then
            // infer chunk N+1 on a worker while chunk N's PCM is yielded
            // (playback blocks in yield). Gapless: no gaps inserted anywhere.
            val idLists = ArrayList<LongArray>()
            for (chunk in splitSentences(text)) {
                if (shouldStop()) break
                val ids = try {
                    EspeakBridge.textToPhonemeIds(chunk)
                } catch (t: Throwable) {
                    Log.e(TAG, "G2P failed on chunk", t)
                    return false
                }
                if (ids.isEmpty()) continue
                // Token cap ~400 phonemes: split fallback by halving.
                val queue = ArrayDeque<LongArray>()
                queue.add(ids)
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    if (cur.size <= MAX_TOKENS_PER_CHUNK) idLists.add(cur)
                    else {
                        val mid = cur.size / 2
                        queue.add(cur.copyOfRange(0, mid))
                        queue.add(cur.copyOfRange(mid, cur.size))
                        Log.i(TAG, "token-cap split: ${cur.size} -> $mid + ${cur.size - mid}")
                    }
                }
            }
            // v0.9: all-empty G2P on non-blank text is a failure, not
            // success — return false so fallbacks / error callbacks trigger.
            if (idLists.isEmpty()) return text.isBlank()
            val inferPool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                var ok = true
                var pending: java.util.concurrent.Future<FloatArray>? = null
                for (i in idLists.indices) {
                    if (shouldStop()) { ok = false; break }
                    val ids = idLists[i]
                    // Prefetch next chunk while current is inferred/yielded.
                    val nextFuture: java.util.concurrent.Future<FloatArray>? =
                        if (i + 1 < idLists.size) inferPool.submit<FloatArray> {
                            inferOne(s, v, idLists[i + 1])
                        } else null
                    val pcm: FloatArray? = try {
                        pending?.get() ?: inferOne(s, v, ids)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ONNX inference failed", t)
                        nextFuture?.cancel(true)
                        ok = false
                        break
                    }
                    pending = nextFuture
                    if (pcm == null) { ok = false; break }
                    try {
                        if (!yield(pcm)) {
                            pending?.cancel(true)
                            ok = false
                            break
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "yield failed", t)
                        pending?.cancel(true)
                        ok = false
                        break
                    }
                }
                try { pending?.cancel(true) } catch (_: Exception) {}
                ok
            } finally {
                try { inferPool.shutdownNow() } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesizeChunked failed", t)
            false
        }
    }

    /** Switch EP preference (persists; takes effect on next session build).
     *  Returns the new active EP if the session was rebuilt, else the pref.
     *  Rebuild is synchronous — callers must use a worker thread. */
    fun setEpPref(appContext: Context, pref: EpPref): String {
        try {
            appContext.getSharedPreferences("kokoreader", Context.MODE_PRIVATE)
                .edit().putString("ep_pref", pref.name).apply()
        } catch (_: Exception) {}
        epPref = pref
        if (!initDone || modelPath.isEmpty()) return pref.name.lowercase()
        return try {
            val e = env ?: return pref.name.lowercase()
            val old = session
            val fresh = createAcceleratedSession(modelPath)
            session = fresh
            try { old?.close() } catch (_: Exception) {}
            // Smoke-test: one tiny inference so a broken EP surfaces here,
            // not mid-utterance. On failure, rebuild CPU and keep going.
            try {
                runKokoro(fresh, voice ?: throw IllegalStateException("no voice"), longArrayOf(16, 16, 16))
            } catch (t: Throwable) {
                Log.w(TAG, "EP smoke test failed on $activeEp, falling back to CPU", t)
                val cpu = createAcceleratedSession(modelPath, forceCpu = true)
                session = cpu
                try { fresh.close() } catch (_: Exception) {}
            }
            status = "ready (ep=$activeEp, speed=${speechSpeed}x, voice=$currentVoiceAsset)"
            activeEp
        } catch (t: Throwable) {
            Log.e(TAG, "EP switch failed", t)
            activeEp
        }
    }

    /** v0.7: build the ORT session with NNAPI (+FP16 for GPU placement)
     *  first, CPU fallback always. NNAPI delegates per-op — unsupported
     *  ops (custom/layer-norm variants) transparently stay on CPU.
     *  Never throws: worst case returns a plain CPU session. */
    private fun createAcceleratedSession(path: String, forceCpu: Boolean = false): OrtSession {
        val e = env ?: throw IllegalStateException("no ORT env")
        /** v0.8 speed brief: intraOp=big-core count, interOp=1,
         *  graphOpt=ALL, CPU arena ON (addCPU(true)) + mem-pattern opt. */
        fun baseOpts(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            val cores = try { Runtime.getRuntime().availableProcessors() } catch (_: Exception) { 4 }
            try { setIntraOpNumThreads(cores.coerceIn(4, 8)) } catch (_: Exception) {}
            try { setInterOpNumThreads(1) } catch (_: Exception) {}
            try { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) } catch (_: Exception) {}
            try { setMemoryPatternOptimization(true) } catch (_: Exception) {}
            try { addCPU(true) } catch (_: Exception) {}
        }
        fun cpuSession(): OrtSession {
            activeEp = "cpu"
            return e.createSession(path, baseOpts())
        }
        if (forceCpu || epPref == EpPref.CPU) return cpuSession()
        val wantFp16 = (epPref == EpPref.GPU || epPref == EpPref.AUTO)
        // Attempt order: [FP16?] NNAPI -> plain NNAPI -> CPU.
        // Explicit NNAPI pref skips the FP16 variant (max compat).
        val attempts = ArrayList<Pair<String, java.util.EnumSet<ai.onnxruntime.providers.NNAPIFlags>>>()
        if (epPref == EpPref.NNAPI) {
            attempts.add("nnapi" to java.util.EnumSet.noneOf(ai.onnxruntime.providers.NNAPIFlags::class.java))
        } else {
            if (wantFp16) attempts.add("gpu(nnapi-fp16)" to java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
            attempts.add("nnapi" to java.util.EnumSet.noneOf(ai.onnxruntime.providers.NNAPIFlags::class.java))
        }
        for ((label, flags) in attempts) {
            try {
                val opts = baseOpts()
                opts.addNnapi(flags)
                val s = e.createSession(path, opts)
                activeEp = label
                Log.i(TAG, "session created with EP: $label (flags=$flags)")
                return s
            } catch (t: Throwable) {
                Log.w(TAG, "EP $label unavailable, trying next: ${t.message}")
            }
        }
        Log.w(TAG, "all accelerated EPs failed; using CPU")
        return cpuSession()
    }

    /** v0.8: single inference call with rolling timing stats (shared by
     *  foreground and double-buffer prefetch paths). Never swallows. */
    private fun inferOne(s: OrtSession, v: FloatArray, ids: LongArray): FloatArray {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val out = runKokoro(s, v, ids)
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        lastInferMs = dt
        inferCount++
        inferTotalMs += dt
        avgInferMs = inferTotalMs.toDouble() / inferCount
        Log.i(TAG, "infer ep=$activeEp chunk=${ids.size}tok ${dt}ms avg=${"%.0f".format(avgInferMs)}ms")
        return out
    }

    private fun runKokoro(s: OrtSession, v: FloatArray, phonemeIds: LongArray): FloatArray {
        val e = env ?: throw IllegalStateException("no ORT env")
        val names = s.inputNames.toList()
        val ids = OnnxTensor.createTensor(e, LongBuffer.wrap(phonemeIds), longArrayOf(1, phonemeIds.size.toLong()))
        var style: OnnxTensor? = null
        var speed: OnnxTensor? = null
        try {
            val row = (minOf(phonemeIds.size, voiceRows) - 1).coerceAtLeast(0)
            val styleVec = v.copyOfRange(row * 256, row * 256 + 256)
            style = OnnxTensor.createTensor(e, arrayOf(styleVec))
            speed = OnnxTensor.createTensor(e, floatArrayOf(speechSpeed))
            // Model contract (verified): tokens [1,N] int64, style [1,256] fp32,
            // speed [1] fp32. Match by exact name, fall back to position.
            val inputs = HashMap<String, OnnxTensor>()
            if (names.contains("tokens")) inputs["tokens"] = ids
            if (names.contains("style")) inputs["style"] = style!!
            if (names.contains("speed")) inputs["speed"] = speed!!
            if (inputs.size < 3 && names.size == 3) {
                // Same 3-tensor contract, different names: assign by position.
                val ordered = names.filter { it !in inputs }
                val byPos = listOf(ids, style!!, speed!!).filter { t -> t !in inputs.values }
                ordered.zip(byPos).forEach { (n, t) -> inputs[n] = t }
            }
            if (inputs.size != names.size) {
                throw IllegalStateException("input mismatch (model wants $names, have ${inputs.keys})")
            }
            val out = s.run(inputs)
            try {
                val raw = out[0].value
                @Suppress("UNCHECKED_CAST")
                return when (raw) {
                    is FloatArray -> raw.clone()
                    is Array<*> -> (raw[0] as FloatArray).clone()
                    else -> throw IllegalStateException("unexpected audio type ${raw?.javaClass}")
                }
            } finally {
                try { out.close() } catch (_: Exception) {}
            }
        } finally {
            try { ids.close() } catch (_: Exception) {}
            try { style?.close() } catch (_: Exception) {}
            try { speed?.close() } catch (_: Exception) {}
        }
    }

    /** Float [-1,1] -> 16-bit LE PCM bytes (delegates to unit-tested TtsLogic). */
    fun floatToPcm16(pcm: FloatArray): ByteArray = TtsLogic.floatToPcm16(pcm)
}
