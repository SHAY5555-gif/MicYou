package com.lanrhyme.micyou.audio

import android.util.Log
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class SpeakerEchoCancellerTest {
    @Test fun cancelsKnownDelayedSpeakerSignal() {
        val rate = 16000
        val size = rate / 100
        val delay = 24
        val frames = Array(delay + 1) { ShortArray(size) }
        val random = Random(714)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        SpeakerEchoCanceller(rate, 240).use { aec ->
            repeat(1000) { frame ->
                val render = ShortArray(size) { random.nextInt(-8000, 8001).toShort() }
                val old = frames[frame % frames.size]
                val capture = ShortArray(size) { (old[it] * 0.55f).toInt().toShort() }
                if (frame >= 800) inputEnergy += capture.sumOf { it.toDouble() * it }
                aec.processCapture(capture)
                if (frame >= 800) outputEnergy += capture.sumOf { it.toDouble() * it }
                aec.analyzeRender(render)
                frames[frame % frames.size] = render
            }
            val reductionDb = 10 * log10(inputEnergy / outputEnergy.coerceAtLeast(1.0))
            Log.i("SpeakerEchoTest", "Synthetic delayed echo reduction=$reductionDb dB metrics=${aec.metrics()}")
            assertTrue("Expected useful echo reduction, got $reductionDb dB", reductionDb > 12.0)
        }
    }

    @Test fun preservesVoiceWithoutSpeakerEcho() {
        val size = 160
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        SpeakerEchoCanceller().use { aec ->
            repeat(500) { frame ->
                val voice = ShortArray(size) { i ->
                    val t = (frame * size + i) / 16000.0
                    (3000 * sin(t * 2 * Math.PI * 397) + 1800 * sin(t * 2 * Math.PI * 853)).toInt().toShort()
                }
                if (frame >= 400) inputEnergy += voice.sumOf { it.toDouble() * it }
                aec.processCapture(voice)
                if (frame >= 400) outputEnergy += voice.sumOf { it.toDouble() * it }
                aec.analyzeRender(ShortArray(size))
            }
        }
        assertTrue("Voice must not be muted without echo", outputEnergy > inputEnergy * 0.25)
    }
}
