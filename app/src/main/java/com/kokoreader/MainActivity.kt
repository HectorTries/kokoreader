package com.kokoreader

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_OVERLAY = 1002
        private const val REQ_NOTIFY = 1003
        private const val TAG = "KokoReader"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            Log.e(TAG, "setContentView failed", t)
            Toast.makeText(this, "UI failed to load: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Direct route: use KokoReader voice in ANY app (Kindle Read Aloud etc.)
        // with no overlay. Opens system TTS settings to pick KokoReader.
        try {
            findViewById<Button>(R.id.ttsSettingsButton)?.setOnClickListener {
                try {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Open Settings → System → Text-to-speech output → KokoReader: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tts settings button wiring failed", t)
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            startFlow()
        }
        wireVoiceSettings()
        wireEpSettings()
        refreshEngineStatus()
        wireDiagnostics()
        findViewById<Button>(R.id.stopButton).setOnClickListener {            val stop = Intent(this, ReaderService::class.java).apply {
                action = ReaderService.ACTION_STOP
            }
            // Deliver explicit stop even if the service is running foreground.
            try {
                startService(stop)
            } catch (_: Exception) {}
            stopService(Intent(this, ReaderService::class.java))
            findViewById<TextView>(R.id.statusText).text = "Stopped."
        }
    }

    /** v0.6: voice + speed settings (persisted; KokoroEngine picks them up). */
    private fun wireVoiceSettings() {
        try {
            val prefs = getSharedPreferences("kokoreader", MODE_PRIVATE)
            val voiceGroup = findViewById<android.widget.RadioGroup>(R.id.voiceGroup)
            val sky = findViewById<android.widget.RadioButton>(R.id.voiceSky)
            val nicole = findViewById<android.widget.RadioButton>(R.id.voiceNicole)
            val savedVoice = prefs.getString("voice_asset", "models/af_sky.bin")
            if (savedVoice?.contains("nicole", ignoreCase = true) == true) nicole.isChecked = true
            else sky.isChecked = true
            voiceGroup.setOnCheckedChangeListener { _, checkedId ->
                val asset = if (checkedId == R.id.voiceNicole) "models/af_nicole.bin" else "models/af_sky.bin"
                Thread {
                    try {
                        KokoroEngine.ensureInit(applicationContext)
                        val ok = KokoroEngine.setVoiceAsset(applicationContext, asset)
                        runOnUiThread {
                            Toast.makeText(this, if (ok) "Voice: ${if (asset.contains("nicole")) "Nicole" else "Sky"}" else "Voice switch failed", Toast.LENGTH_SHORT).show()
                            refreshEngineStatus()
                        }
                    } catch (t: Throwable) {
                        runOnUiThread { Toast.makeText(this, "Voice switch failed: ${t.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            val seek = findViewById<android.widget.SeekBar>(R.id.speedSeek)
            val label = findViewById<android.widget.TextView>(R.id.engineStatus)
            val speedLabel = findViewById<android.widget.TextView>(R.id.speedLabel)
            val savedSpeed = prefs.getFloat("speech_speed", 1.25f)
            seek.progress = ((savedSpeed - 0.5f) / 1.5f * 150).toInt().coerceIn(0, 150)
            speedLabel.text = "Speed: ${"%.2f".format(savedSpeed)}x"
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val speed = 0.5f + p / 150f * 1.5f
                    speedLabel.text = "Speed: ${"%.2f".format(speed)}x"
                    if (fromUser) KokoroEngine.setSpeechSpeed(applicationContext, speed)
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) { refreshEngineStatus() }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "voice settings wiring failed", t)
        }
    }

    /** v0.7: EP selector (Auto/NNAPI/GPU/CPU), persisted; rebuilds session. */
    private fun wireEpSettings() {
        try {
            val prefs = getSharedPreferences("kokoreader", MODE_PRIVATE)
            val group = findViewById<android.widget.RadioGroup>(R.id.epGroup)
            val saved = prefs.getString("ep_pref", "AUTO") ?: "AUTO"
            when (saved) {
                "NNAPI" -> findViewById<android.widget.RadioButton>(R.id.epNnapi).isChecked = true
                "GPU" -> findViewById<android.widget.RadioButton>(R.id.epGpu).isChecked = true
                "CPU" -> findViewById<android.widget.RadioButton>(R.id.epCpu).isChecked = true
                else -> findViewById<android.widget.RadioButton>(R.id.epAuto).isChecked = true
            }
            group.setOnCheckedChangeListener { _, checkedId ->
                val pref = when (checkedId) {
                    R.id.epNnapi -> KokoroEngine.EpPref.NNAPI
                    R.id.epGpu -> KokoroEngine.EpPref.GPU
                    R.id.epCpu -> KokoroEngine.EpPref.CPU
                    else -> KokoroEngine.EpPref.AUTO
                }
                Toast.makeText(this, "Switching to ${pref.name}…", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        KokoroEngine.ensureInit(applicationContext)
                        val active = KokoroEngine.setEpPref(applicationContext, pref)
                        runOnUiThread {
                            Toast.makeText(this, "Active EP: $active", Toast.LENGTH_LONG).show()
                            refreshEngineStatus()
                        }
                    } catch (t: Throwable) {
                        runOnUiThread { Toast.makeText(this, "EP switch failed: ${t.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ep settings wiring failed", t)
        }
    }

    private fun refreshEngineStatus() {
        try {
            val tv = findViewById<android.widget.TextView>(R.id.engineStatus)
            Thread {
                try { KokoroEngine.ensureInit(applicationContext) } catch (_: Exception) {}
                val s = "${KokoroEngine.status} | voice=${KokoroEngine.currentVoice()} speed=${"%.2f".format(KokoroEngine.speechSpeed)}x"
                val phases = KokoroEngine.phaseTimings
                val lastMs = KokoroEngine.lastInferMs
                val avgMs = KokoroEngine.avgInferMs
                val inferLine = if (lastMs >= 0) "Inference [${KokoroEngine.activeEp}]: last=${lastMs}ms avg=${"%.0f".format(avgMs)}ms" else "Inference [${KokoroEngine.activeEp}]: no chunks yet"
                runOnUiThread {
                    tv.text = "Engine: $s" + if (phases.isNotEmpty()) "\nInit phases: $phases" else ""
                    try { findViewById<android.widget.TextView>(R.id.inferStats).text = inferLine } catch (_: Exception) {}
                    try {
                        val f = java.io.File(filesDir, "kokoro-82m-int8.onnx")
                        val sizeMb = if (f.exists()) "%.1fMB".format(f.length() / 1048576.0) else "missing"
                        val probe = try { EspeakBridge.textToPhonemeIds("hello").size } catch (_: Exception) { -1 }
                        val espeakReady = java.io.File(java.io.File(filesDir, "espeak-data"), ".ready").exists()
                        val persisted = KokoroEngine.wasReadyBefore(applicationContext)
                        findViewById<android.widget.TextView>(R.id.diagText).text =
                            "model: exists=${f.exists()} size=$sizeMb\nORT ready=${KokoroEngine.isReady()} (persisted=$persisted)\nG2P 'hello' phonemes=$probe (espeak-data ready=$espeakReady)\nsampleRate=${KokoroEngine.SAMPLE_RATE}Hz mono PCM16\nlast synth=${KokoroEngine.lastInferMs}ms\nstatus=${KokoroEngine.status}"
                    } catch (_: Exception) {}
                }
            }.start()
        } catch (_: Exception) {}
    }

    /** v0.10: diagnostics card + Test voice button. Scope: MainActivity only. */
    private fun wireDiagnostics() {
        try {
            findViewById<Button>(R.id.testVoiceButton)?.setOnClickListener { v ->
                v.isEnabled = false
                findViewById<TextView>(R.id.testVoiceResult).text = "Test: synthesising…"
                Thread {
                    var reason = ""
                    var ok = false
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    try {
                        KokoroEngine.ensureInit(applicationContext)
                        if (!KokoroEngine.isReady()) { reason = "engine not ready: ${KokoroEngine.status}" }
                        else {
                            val chunks = ArrayList<FloatArray>()
                            ok = KokoroEngine.synthesizeChunked("Hello from KokoReader", { false }, { pcm ->
                                chunks.add(pcm); true
                            })
                            if (!ok) { reason = "synth returned false: ${KokoroEngine.status}" }
                            else {
                                val total = chunks.sumOf { it.size }
                                if (total == 0) { ok = false; reason = "empty PCM" }
                                else {
                                    val shorts = ShortArray(total)
                                    var o = 0
                                    for (c in chunks) for (f in c) shorts[o++] = (f.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                    // v1.1 FIX-5/6: STREAM mode at exactly 24000Hz mono PCM16
                                    // (matches engine + TTS callback contract). Old STATIC mode
                                    // released the track immediately after play() — cutting
                                    // audio to silence. Now block until playback completes.
                                    val track = android.media.AudioTrack.Builder()
                                        .setAudioAttributes(android.media.AudioAttributes.Builder()
                                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                        .setAudioFormat(android.media.AudioFormat.Builder()
                                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                            .setSampleRate(KokoroEngine.SAMPLE_RATE).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                                        .setBufferSizeInBytes(maxOf(shorts.size * 2, android.media.AudioTrack.getMinBufferSize(KokoroEngine.SAMPLE_RATE, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)))
                                        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                                        .build()
                                    try {
                                        track.play()
                                        var written = 0
                                        while (written < shorts.size) {
                                            val n = track.write(shorts, written, shorts.size - written)
                                            if (n <= 0) break
                                            written += n
                                        }
                                        // Wait for the tail to actually play out.
                                        var waited = 0
                                        val expectMs = (shorts.size * 1000L / KokoroEngine.SAMPLE_RATE).toInt() + 1500
                                        while (track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING && waited < expectMs) {
                                            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                                            waited += 100
                                        }
                                        try { track.stop() } catch (_: Exception) {}
                                        val ms = android.os.SystemClock.elapsedRealtime() - t0
                                        reason = "played $written/${shorts.size} samples @${KokoroEngine.SAMPLE_RATE}Hz in ${ms}ms"
                                    } finally { track.release() }
                                }
                            }
                        }
                    } catch (t: Throwable) { ok = false; reason = t.message ?: t.javaClass.simpleName }
                    val line = if (ok) "Test: SUCCESS — $reason" else "Test: FAIL — $reason"
                    runOnUiThread {
                        findViewById<TextView>(R.id.testVoiceResult).text = line
                        v.isEnabled = true
                        refreshEngineStatus()
                    }
                }.start()
            }
        } catch (t: Throwable) { Log.w(TAG, "diagnostics wiring failed", t) }
    }

    /** Ordered consent chain: notifications → overlay → capture. Never strands the user. */
    private fun startFlow() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY
            )
        }
        if (!Settings.canDrawOverlays(this)) {
            findViewById<TextView>(R.id.statusText).setText(R.string.overlay_prompt)
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        requestCaptureConsent()
    }

    private fun requestCaptureConsent() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "capture intent failed", e)
            Toast.makeText(this, "Screen capture unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("legacy")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val status = findViewById<TextView>(R.id.statusText)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Log.i(TAG, "overlay granted, proceeding to capture consent")
                    requestCaptureConsent()
                } else {
                    Log.w(TAG, "overlay denied")
                    status.text = "Overlay permission is required for the floating button. Tap Start reading to try again."
                    Toast.makeText(this, "Overlay permission denied — floating button can't appear", Toast.LENGTH_LONG).show()
                }
            }
            REQ_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val svc = Intent(this, ReaderService::class.java).apply {
                        putExtra(ReaderService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ReaderService.EXTRA_RESULT_DATA, data)
                    }
                    try {
                        startForegroundService(svc)
                        status.text =
                            "Service started. Open any app with English text, tap 🔊 on the floating button to read the page, ⏭ for the next page. Drag the button to move it."
                    } catch (e: Exception) {
                        Log.e(TAG, "startForegroundService failed", e)
                        status.text = "Could not start service: ${e.message}"
                    }
                } else {
                    // User cancelled the consent dialog — say so instead of going silent.
                    Log.w(TAG, "capture consent denied/cancelled (result=$resultCode)")
                    status.text = "Screen-capture permission was not granted. Tap Start reading and accept the dialog to enable reading."
                    Toast.makeText(this, "Screen capture declined — tap Start reading to try again", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
