package com.lanrhyme.micyou.audio

import kotlin.math.exp

/** Removes DC and low-frequency handling noise before pickup detection and gain. */
class MicrophoneConditioner {
    private var previousInput = 0.0
    private var previousOutput = 0.0
    private var sampleRate = 0
    private var coefficient = 0.0

    fun process(samples: ShortArray, count: Int, rate: Int) {
        require(count in 0..samples.size && rate > 0)
        if (sampleRate != rate) {
            sampleRate = rate
            coefficient = exp(-2.0 * Math.PI * 85.0 / rate)
            previousInput = 0.0
            previousOutput = 0.0
        }
        for (i in 0 until count) {
            val input = samples[i].toDouble()
            val output = coefficient * (previousOutput + input - previousInput)
            previousInput = input
            previousOutput = output
            samples[i] = output.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}
