package com.lanrhyme.micyou.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MicrophoneConditionerTest {
    @Test fun removesDcWithoutAJumpAtEachAudioBlock() {
        val filter = MicrophoneConditioner()
        var output = ShortArray(480)
        repeat(20) {
            output = ShortArray(480) { 10000 }
            filter.process(output, output.size, 48000)
        }
        assertTrue(output.all { kotlin.math.abs(it.toInt()) <= 1 })
    }

    @Test fun preservesSpeechBandWhileReducingLowRumble() {
        fun amplitudeRatio(frequency: Double): Double {
            val filter = MicrophoneConditioner()
            var inputEnergy = 0.0
            var outputEnergy = 0.0
            repeat(40) { block ->
                val samples = ShortArray(480) { i ->
                    (sin(2 * PI * frequency * (block * 480 + i) / 48000) * 10000).toInt().toShort()
                }
                if (block >= 20) inputEnergy += samples.sumOf { it.toDouble() * it }
                filter.process(samples, samples.size, 48000)
                if (block >= 20) outputEnergy += samples.sumOf { it.toDouble() * it }
            }
            return sqrt(outputEnergy / inputEnergy)
        }
        assertTrue(amplitudeRatio(1000.0) in 0.96..1.02)
        assertTrue(amplitudeRatio(20.0) < 0.3)
    }
}
