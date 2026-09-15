package com.kokoreader

import org.junit.Assert.*
import org.junit.Test

/**
 * Synth probe + TTS contract tests.
 * - G2P probe verdict: stub espeak (empty ids / crash) must NEVER read READY.
 * - Completion policy: callback.error() when zero chunks (BUG-3), start()
 *   always precedes done() on success, stopped utterances report nothing.
 * - Threading (BUG-2) + retry (BUG-4) + callback-ordering (silent-success
 *   grep) are verified structurally against KokoTtsService source in
 *   ServiceContractTest (runs in the same JVM suite).
 */
class TtsContractTest {

    // ---- G2P probe (fail-fast, never silent success) ----

    @Test fun `empty probe ids means voice disabled`() {
        assertEquals(
            TtsLogic.ProbeVerdict.FAILED_EMPTY,
            TtsLogic.g2pProbeVerdict(Result.success(longArrayOf()))
        )
    }

    @Test fun `crashed probe means voice disabled`() {
        assertEquals(
            TtsLogic.ProbeVerdict.FAILED_CRASH,
            TtsLogic.g2pProbeVerdict(Result.failure(RuntimeException("espeak missing")))
        )
    }

    @Test fun `healthy probe means ready`() {
        assertEquals(
            TtsLogic.ProbeVerdict.READY,
            TtsLogic.g2pProbeVerdict(Result.success(longArrayOf(1, 2, 3)))
        )
    }

    @Test fun `probe verdict never reports READY for failure inputs`() {
        assertNotEquals(TtsLogic.ProbeVerdict.READY, TtsLogic.g2pProbeVerdict(Result.success(longArrayOf())))
        assertNotEquals(TtsLogic.ProbeVerdict.READY, TtsLogic.g2pProbeVerdict(Result.failure(Exception())))
    }

    // ---- Completion state machine (mock-callback semantics) ----

    /** Minimal fake for SynthesisCallback ordering: start/done/error. */
    class FakeCallback {
        val events = ArrayList<String>()
        var throwOnStart = false
        fun start() { if (throwOnStart) throw RuntimeException("binder dead"); events.add("start") }
        fun done() { events.add("done") }
        fun error() { events.add("error") }
        fun complete(action: TtsLogic.Completion) = when (action) {
            TtsLogic.Completion.DONE -> { start(); done() }
            TtsLogic.Completion.ERROR -> error()
            TtsLogic.Completion.NONE_STOPPED -> Unit
        }
    }

    @Test fun `zero chunks started means error not done (BUG-3)`() {
        // ok=true but nothing started (all G2P empty) -> ERROR.
        assertEquals(TtsLogic.Completion.ERROR, TtsLogic.completionAction(ok = true, started = false, stopped = false))
        val cb = FakeCallback()
        cb.complete(TtsLogic.completionAction(ok = true, started = false, stopped = false))
        assertEquals(listOf("error"), cb.events)
    }

    @Test fun `ok and started means start precedes done`() {
        val cb = FakeCallback()
        cb.complete(TtsLogic.completionAction(ok = true, started = true, stopped = false))
        assertEquals(listOf("start", "done"), cb.events)
    }

    @Test fun `failed synthesis means error`() {
        assertEquals(TtsLogic.Completion.ERROR, TtsLogic.completionAction(ok = false, started = true, stopped = false))
        assertEquals(TtsLogic.Completion.ERROR, TtsLogic.completionAction(ok = false, started = false, stopped = false))
    }

    @Test fun `stopped utterance reports nothing`() {
        assertEquals(TtsLogic.Completion.NONE_STOPPED, TtsLogic.completionAction(ok = true, started = true, stopped = true))
        val cb = FakeCallback()
        cb.complete(TtsLogic.completionAction(ok = true, started = true, stopped = true))
        assertTrue(cb.events.isEmpty())
    }

    @Test fun `start is called exactly once before done`() {
        val cb = FakeCallback()
        cb.complete(TtsLogic.Completion.DONE)
        assertEquals(1, cb.events.count { it == "start" })
        assertTrue(cb.events.indexOf("start") < cb.events.indexOf("done"))
    }

    // ---- PCM contract ----

    @Test fun `floatToPcm16 silence maps to zeros`() {
        assertArrayEquals(ByteArray(8), TtsLogic.floatToPcm16(floatArrayOf(0f, 0f, 0f, 0f)))
    }

    @Test fun `floatToPcm16 clamps out-of-range`() {
        val out = TtsLogic.floatToPcm16(floatArrayOf(2f, -2f))
        // 32767 -> FF 7F ; -32768 -> 00 80 (LE)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), out)
    }
}
