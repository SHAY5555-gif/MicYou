package com.lanrhyme.micyou.audio

import kotlin.math.pow
import kotlin.math.exp

/** Level-based pickup control, applied before output gain. It does not measure distance. */
class CloseVoiceGate {
    @Volatile var sensitivity: Float = 0.3f
    @Volatile var isOpen: Boolean = false
        private set
    private var quietSamples = 0
    private var sinceStrongSamples = 0
    private var envelope = 0f

    @Synchronized fun reset() {
        isOpen = false
        quietSamples = 0
        sinceStrongSamples = 0
        envelope = 0f
    }

    @Synchronized fun process(samples: ShortArray, count: Int, sampleRate: Int, inputRms: Float) {
        require(count in 0..samples.size && sampleRate > 0)
        val setting = sensitivity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.3f
        if (setting >= 0.995f) {
            isOpen = true
            envelope = 1f
            quietSamples = 0
            sinceStrongSamples = 0
            return
        }
        val openThreshold = 10.0.pow((-18.0 - 45.0 * setting) / 20.0).toFloat()
        if (inputRms >= openThreshold) {
            isOpen = true
            quietSamples = 0
            sinceStrongSamples = 0
        } else if (inputRms < openThreshold * 0.67f) {
            quietSamples += count
            sinceStrongSamples += count
            if (quietSamples >= sampleRate * 0.06f) isOpen = false
        } else {
            quietSamples = 0
            sinceStrongSamples += count
        }
        if (sinceStrongSamples >= sampleRate * 0.12f) isOpen = false

        val coefficient = (1.0 - exp(-1.0 / (sampleRate * if (isOpen) 0.006 else 0.020))).toFloat()
        val target = if (isOpen) 1f else 0f
        for (i in 0 until count) {
            envelope += (target - envelope) * coefficient
            if (!isOpen && envelope < 0.0001f) envelope = 0f
            samples[i] = (samples[i] * envelope).toInt().toShort()
        }
    }
}
