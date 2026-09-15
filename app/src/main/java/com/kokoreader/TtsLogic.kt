package com.kokoreader

/**
 * Pure-Kotlin TTS logic — zero Android/ORT imports so it compiles and runs
 * on a plain JVM (unit-testable without a device or Robolectric).
 *
 * KokoroEngine delegates to these helpers; behaviour is defined HERE and
 * verified by app/src/test unit tests. Do not add android.* imports.
 */
object TtsLogic {

    const val MIN_CHUNK_CHARS = 120
    const val MAX_CHUNK_CHARS = 200
    const val MAX_TOKENS_PER_CHUNK = 400

    /** Clause-aware split: clauses on .!?; + newline, merged into [MIN,MAX] range. */
    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val clauses = text.split(Regex("(?<=[.!?;\n])\\s+"))
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
        }
        for (c in clauses) {
            if (c.isEmpty()) continue
            if (cur.isEmpty()) {
                var rest = c
                while (rest.length > MAX_CHUNK_CHARS) {
                    var cut = rest.lastIndexOf(' ', MAX_CHUNK_CHARS)
                    if (cut < 20) cut = MAX_CHUNK_CHARS
                    out.add(rest.substring(0, cut))
                    rest = rest.substring(cut).trimStart()
                }
                cur.append(rest)
            } else if (cur.length + 1 + c.length <= MAX_CHUNK_CHARS) {
                cur.append(' ').append(c)
            } else {
                if (cur.length < MIN_CHUNK_CHARS && out.isNotEmpty()) {
                    cur.append(' ').append(c)
                    if (cur.length > MAX_CHUNK_CHARS) {
                        var cut = cur.lastIndexOf(' ', MAX_CHUNK_CHARS)
                        if (cut < 20) cut = MAX_CHUNK_CHARS
                        out.add(cur.substring(0, cut))
                        cur.delete(0, cut)
                        while (cur.isNotEmpty() && cur[0] == ' ') cur.deleteCharAt(0)
                    }
                } else {
                    flush()
                    var rest = c
                    while (rest.length > MAX_CHUNK_CHARS) {
                        var cut = rest.lastIndexOf(' ', MAX_CHUNK_CHARS)
                        if (cut < 20) cut = MAX_CHUNK_CHARS
                        out.add(rest.substring(0, cut))
                        rest = rest.substring(cut).trimStart()
                    }
                    cur.append(rest)
                }
            }
        }
        flush()
        return out
    }

    /** Token-cap split: halve id lists recursively until each <= MAX_TOKENS. */
    fun applyTokenCap(ids: LongArray): List<LongArray> {
        val out = ArrayList<LongArray>()
        val queue = ArrayDeque<LongArray>()
        queue.add(ids)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.size <= MAX_TOKENS_PER_CHUNK) out.add(cur)
            else {
                val mid = cur.size / 2
                queue.add(cur.copyOfRange(0, mid))
                queue.add(cur.copyOfRange(mid, cur.size))
            }
        }
        return out
    }

    /** G2P probe verdict: probe failed (threw) or empty ids => NOT ready. */
    enum class ProbeVerdict { READY, FAILED_EMPTY, FAILED_CRASH }
    fun g2pProbeVerdict(probe: Result<LongArray>): ProbeVerdict {
        val ids = probe.getOrNull() ?: return ProbeVerdict.FAILED_CRASH
        return if (ids.isEmpty()) ProbeVerdict.FAILED_EMPTY else ProbeVerdict.READY
    }

    /**
     * All-empty G2P policy (BUG-3): non-blank text yielding zero id lists
     * is a FAILURE (false), never silent success. Blank text is trivially true.
     */
    fun emptyG2pIsSuccess(textIsBlank: Boolean, idListCount: Int): Boolean {
        if (idListCount > 0) return true
        return textIsBlank
    }

    /** Voice-name gate for the framework contract (v1.5 fix): accepts our
     *  voice id, BCP-47 "en" variants, and the legacy Sky/Nicole aliases.
     *  Pure JVM — unit-tested. */
    fun isKokoVoice(voiceName: String?): Boolean {
        if (voiceName.isNullOrEmpty()) return false
        val n = voiceName.lowercase()
        return n == "en-us-kokoro-af-sky" || n.contains("koko") ||
            n.contains("sky") || n.contains("nicole") ||
            n == "en-us" || n == "en_us" || n == "en" || n.startsWith("en-")
    }

    /** Completion action for the TTS callback state machine. */
    enum class Completion { DONE, ERROR, NONE_STOPPED }
    fun completionAction(ok: Boolean, started: Boolean, stopped: Boolean): Completion {
        if (stopped) return Completion.NONE_STOPPED
        return if (ok && started) Completion.DONE else Completion.ERROR
    }

    /** Float [-1,1] -> 16-bit LE PCM bytes. */
    fun floatToPcm16(pcm: FloatArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            var s = (pcm[i] * 32767f).toInt()
            if (s > 32767) s = 32767
            if (s < -32768) s = -32768
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
