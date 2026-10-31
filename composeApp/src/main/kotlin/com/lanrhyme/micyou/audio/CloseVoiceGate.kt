package com.lanrhyme.micyou.audio

import kotlin.math.pow

/** Level-based pickup control, applied before output gain. It does not measure distance. */
class CloseVoiceGate {
    @Volatile var sensitivity: Float = 0.3f
    @Volatile var isOpen: Boolean = false
        private set
    private var quietSamples = 0
    private var envelope = 0f

    @Synchronized fun reset() {
        isOpen = false
        quietSamples = 0
        envelope = 0f
    }

    @Synchronized fun process(samples: ShortArray, count: Int, sampleRate: Int, inputRms: Float) {
        require(count in 0..samples.size && sampleRate > 0)
        val setting = sensitivity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.3f
        if (setting >= 0.995f) {
            isOpen = true
            envelope = 1f
            quietSamples = 0
            return
        }
        val openThreshold = 10.0.pow((-18.0 - 45.0 * setting) / 20.0).toFloat()
        if (inputRms >= openThreshold) {
            isOpen = true
            quietSamples = 0
        } else if (inputRms < openThreshold * 0.67f) {
            quietSamples += count
            if (quietSamples >= sampleRate * 0.06f) isOpen = false
        } else quietSamples = 0

        val step = 1f / (sampleRate * if (isOpen) 0.004f else 0.015f)
        for (i in 0 until count) {
            envelope = if (isOpen) (envelope + step).coerceAtMost(1f)
                else (envelope - step).coerceAtLeast(0f)
            samples[i] = (samples[i] * envelope).toInt().toShort()
        }
    }
}
