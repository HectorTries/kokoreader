package com.kokoreader

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Structural contract tests over service source + manifest.
 * These guard the v0.9 fixes against regression without a device:
 *  BUG-2: init runs on initExecutor, never the synth thread/binder thread.
 *  BUG-3: no silent success — done() is always gated on started.
 *  BUG-4: audioAvailable 0-write retries before failing loudly.
 *  Manifest: exported TTS service with BIND_TTS_SERVICE + TTS_SERVICE action.
 */
class ServiceContractTest {

    private fun src(name: String): String {
        // Resolved from the working tree at test runtime (gradle projectDir).
        val prop = System.getProperty("kokoreader.srcMain")
            ?: throw IllegalStateException("kokoreader.srcMain not set (see app/build.gradle.kts test config)")
        return File(prop, name).readText()
    }

    private fun service(): String = src("com/kokoreader/KokoTtsService.kt")
    private fun engine(): String = src("com/kokoreader/KokoroEngine.kt")

    // ---- BUG-2: init off the synth thread ----

    @Test fun `prewarm uses initExecutor not synthExecutor`() {
        val s = service()
        assertTrue("initExecutor field must exist", s.contains("initExecutor"))
        assertTrue("pre-warm must run on initExecutor", s.contains("initExecutor.execute"))
        // ensureInit must never be submitted to the synth executor.
        assertFalse("ensureInit must not run on synthExecutor",
            Regex("synthExecutor\\.execute\\s*\\{[^}]*ensureInit", RegexOption.DOT_MATCHES_ALL).containsMatchIn(s))
    }

    @Test fun `doSynthesize documents worker-thread init`() {
        val s = service()
        assertTrue(s.contains("fun doSynthesize"))
        assertTrue(s.contains("ensureInit"))
    }

    // ---- BUG-3: no silent success ----

    @Test fun `every callback done is gated on started`() {
        val s = service()
        val doneCalls = Regex("callback\\.done\\(\\)").findAll(s).toList()
        assertTrue("expected at least one done() call", doneCalls.isNotEmpty())
        for (m in doneCalls) {
            val before = s.substring(0, m.range.first).takeLast(600)
            assertTrue("callback.done() must be gated on started: ...${before.takeLast(120)}",
                before.contains("started"))
        }
    }

    @Test fun `zero-chunk success maps to error`() {
        val s = service()
        assertTrue("ok && started gate required",
            s.contains("if (ok && started") || s.contains("ok && started &&"))
        assertTrue("error() fallback required", s.contains("callback.error()"))
    }

    @Test fun `engine all-empty G2P returns failure for non-blank text`() {
        val e = engine()
        assertTrue(e.contains("if (idLists.isEmpty()) return text.isBlank()"))
    }

    @Test fun `engine G2P probe refuses ready on empty ids`() {
        val e = engine()
        assertTrue("probe empty check", e.contains("probe.isEmpty()"))
        assertTrue("probe failure disables session", e.contains("session = null"))
    }

    // ---- BUG-4: audioAvailable retry ----

    @Test fun `zero-write audioAvailable retries before failing`() {
        val s = service()
        assertTrue("retry loop required", s.contains("retries < 10"))
        assertTrue("backpressure sleep required", s.contains("Thread.sleep(5)"))
        assertTrue("0-write must log loudly", s.contains("0-write"))
    }

    // ---- Manifest ----

    @Test fun `manifest declares exported TTS service`() {
        val prop = System.getProperty("kokoreader.srcMain")
            ?: throw IllegalStateException("kokoreader.srcMain not set")
        val manifest = File(File(prop).parent, "AndroidManifest.xml").readText()
        assertTrue("KokoTtsService declared", manifest.contains(".KokoTtsService"))
        assertTrue("exported=true", manifest.contains("android:exported=\"true\""))
        assertTrue("BIND_TTS_SERVICE", manifest.contains("android.permission.BIND_TTS_SERVICE"))
        assertTrue("TTS_SERVICE action", manifest.contains("android.intent.action.TTS_SERVICE"))
        assertTrue("tts meta-data", manifest.contains("android.speech.tts"))
    }
}
