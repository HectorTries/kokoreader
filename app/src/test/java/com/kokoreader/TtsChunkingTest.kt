package com.kokoreader

import org.junit.Assert.*
import org.junit.Test

/** JVM unit tests for chunking (120-200 chars), token cap (400), empty-G2P policy. */
class TtsChunkingTest {

    private fun clause(len: Int, end: String = "."): String =
        "w".repeat(maxOf(1, len - end.length)) + end

    @Test fun `short text yields single chunk`() {
        val chunks = TtsLogic.splitSentences("Hello world.")
        assertEquals(1, chunks.size)
        assertEquals("Hello world.", chunks[0])
    }

    @Test fun `long multi-clause text stays within 120-200 except final`() {
        val text = (1..12).joinToString(" ") { i -> "Clause number $i carries enough words to reach a decent length." }
        assertTrue(text.length > 600)
        val chunks = TtsLogic.splitSentences(text)
        assertTrue(chunks.size >= 2)
        for (c in chunks.dropLast(1)) {
            assertTrue("chunk len ${c.length} in [${TtsLogic.MIN_CHUNK_CHARS},${TtsLogic.MAX_CHUNK_CHARS}]: $c",
                c.length in TtsLogic.MIN_CHUNK_CHARS..TtsLogic.MAX_CHUNK_CHARS)
        }
        // Reassembly preserves all words.
        assertEquals(text.split(Regex("\\s+")), chunks.joinToString(" ").split(Regex("\\s+")))
    }

    @Test fun `no chunk exceeds max chars`() {
        val text = (1..20).joinToString(" ") { "Sentence $it with padding words to grow the total size a bit." }
        for (c in TtsLogic.splitSentences(text)) {
            assertTrue("len ${c.length} <= ${TtsLogic.MAX_CHUNK_CHARS}", c.length <= TtsLogic.MAX_CHUNK_CHARS)
        }
    }

    @Test fun `single giant clause hard-splits on word boundary`() {
        val text = ("word ".repeat(120)).trim() + "."
        val chunks = TtsLogic.splitSentences(text)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(c.length <= TtsLogic.MAX_CHUNK_CHARS)
        // No word torn apart.
        for (c in chunks) assertTrue(c.split(" ").all { it.isNotEmpty() })
    }

    @Test fun `empty text yields no chunks`() {
        assertTrue(TtsLogic.splitSentences("").isEmpty())
    }

    @Test fun `token cap passes through under-limit lists`() {
        val ids = LongArray(400) { it.toLong() }
        val out = TtsLogic.applyTokenCap(ids)
        assertEquals(1, out.size)
        assertArrayEquals(ids, out[0])
    }

    @Test fun `token cap halves over-limit lists recursively`() {
        val ids = LongArray(1000) { it.toLong() }
        val out = TtsLogic.applyTokenCap(ids)
        assertTrue(out.all { it.size <= TtsLogic.MAX_TOKENS_PER_CHUNK })
        assertEquals(ids.toList(), out.flatMap { it.toList() })
        assertTrue(out.size >= 3)
    }

    @Test fun `empty G2P on non-blank text is failure (BUG-3)`() {
        assertFalse(TtsLogic.emptyG2pIsSuccess(textIsBlank = false, idListCount = 0))
    }

    @Test fun `empty G2P on blank text is trivial success`() {
        assertTrue(TtsLogic.emptyG2pIsSuccess(textIsBlank = true, idListCount = 0))
    }

    @Test fun `non-empty G2P is success`() {
        assertTrue(TtsLogic.emptyG2pIsSuccess(textIsBlank = false, idListCount = 2))
    }
}
