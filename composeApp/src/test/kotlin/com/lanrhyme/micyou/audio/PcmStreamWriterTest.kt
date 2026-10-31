package com.lanrhyme.micyou.audio

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class PcmStreamWriterTest {
    @Test fun partialAndEmptyWritesPreserveEverySampleInOrder() = runBlocking {
        val source = ShortArray(480) { (it - 240).toShort() }
        val received = mutableListOf<Short>()
        var calls = 0
        val written = writePcmFully(source, source.size) { data, offset, size ->
            val accepted = if (calls++ % 4 == 0) 0 else minOf(37, size)
            received.addAll(data.slice(offset until offset + accepted))
            accepted
        }
        assertEquals(source.size, written)
        assertArrayEquals(source, received.toShortArray())
    }

    @Test fun explicitMuteStopsTheUnwrittenTail() = runBlocking {
        var live = true
        val written = writePcmFully(ShortArray(480), 480, keepWriting = { live }) { _, _, _ ->
            live = false
            120
        }
        assertEquals(120, written)
    }

    @Test fun stalledOutputRemainsCancellable() = runBlocking {
        val result = withTimeoutOrNull(50) {
            writePcmFully(ShortArray(480), 480) { _, _, _ -> 0 }
        }
        assertNull(result)
    }

    @Test fun audioErrorStopsInsteadOfSkippingSamples() = runBlocking {
        try {
            writePcmFully(ShortArray(480), 480) { _, _, _ -> -6 }
            fail("An audio error must stop the stream")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("-6"))
        }
    }
}
