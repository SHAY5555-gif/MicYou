package com.lanrhyme.micyou.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneMixerTest {
    @Test fun zeroEchoIsDryAndGainCanMute() {
        val mixer = MicrophoneMixer()
        val samples = shortArrayOf(1234, -2345, 8000)
        mixer.process(samples, samples.size, 48000, 1)
        assertArrayEquals(shortArrayOf(1234, -2345, 8000), samples)
        mixer.gain = 0f
        mixer.process(samples, samples.size, 48000, 1)
        assertArrayEquals(shortArrayOf(0, 0, 0), samples)
    }

    @Test fun echoUsesTheRequestedDelayAndResetRemovesItsTail() {
        val mixer = MicrophoneMixer().apply { echoLevel = 0.5f; echoDelayMs = 50f }
        val samples = ShortArray(101).apply { this[0] = 10000 }
        mixer.process(samples, samples.size, 1000, 1)
        assertEquals(10000, samples[0].toInt())
        assertEquals(5000, samples[50].toInt())
        assertEquals(1250, samples[100].toInt())
        mixer.reset()
        val silence = ShortArray(200)
        mixer.process(silence, silence.size, 1000, 1)
        assertTrue(silence.all { it == 0.toShort() })
    }

    @Test fun loudInputIsLimitedWithoutWrappingPolarity() {
        val mixer = MicrophoneMixer().apply { gain = 2f }
        val samples = shortArrayOf(30000, -30000)
        mixer.process(samples, samples.size, 48000, 1)
        assertTrue(samples[0] > 0 && samples[0] <= MicrophoneMixer.LIMITER_CEILING)
        assertTrue(samples[1] < 0 && -samples[1].toInt() <= MicrophoneMixer.LIMITER_CEILING)
    }

    @Test fun quietVoiceCanBeBoostedSixfold() {
        val mixer = MicrophoneMixer().apply { gain = 6f }
        val samples = shortArrayOf(1000, -1000)
        mixer.process(samples, samples.size, 48000, 1)
        assertArrayEquals(shortArrayOf(6000, -6000), samples)
    }

    @Test fun stereoLimiterPreservesChannelBalance() {
        val mixer = MicrophoneMixer().apply { gain = 6f }
        val samples = shortArrayOf(20000, 10000)
        mixer.process(samples, samples.size, 48000, 2)
        assertTrue(kotlin.math.abs(samples[0].toInt() - samples[1].toInt() * 2) <= 1)
        assertTrue(samples[0] <= MicrophoneMixer.LIMITER_CEILING)
    }

    @Test fun zeroVolumeAlsoMutesAnExistingEchoTail() {
        val mixer = MicrophoneMixer().apply { echoLevel = 0.5f; echoDelayMs = 50f }
        val impulse = shortArrayOf(10000)
        mixer.process(impulse, 1, 1000, 1)
        mixer.gain = 0f
        val tail = ShortArray(100)
        mixer.process(tail, tail.size, 1000, 1)
        assertTrue(tail.all { it == 0.toShort() })
    }
}
