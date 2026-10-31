package com.lanrhyme.micyou.audio

/** Local microphone gain and a bounded echo. A zero echo level is completely dry. */
class MicrophoneMixer {
    companion object {
        const val MAX_GAIN = 6f
        const val LIMITER_CEILING = 31128f
    }
    @Volatile var gain: Float = 1f
    @Volatile var echoLevel: Float = 0f
    @Volatile var echoDelayMs: Float = 120f
    private var delay = FloatArray(0)
    private var cursor = 0
    private var limiterGain = 1f
    private var frame = FloatArray(0)

    @Synchronized
    fun reset() {
        delay.fill(0f)
        cursor = 0
        limiterGain = 1f
    }

    @Synchronized
    fun process(samples: ShortArray, count: Int, sampleRate: Int, channels: Int) {
        require(count in 0..samples.size)
        require(sampleRate > 0 && channels > 0)
        val size = ((sampleRate * echoDelayMs.coerceIn(50f, 450f) / 1000f).toInt()
            .coerceAtLeast(1) * channels)
        if (delay.size != size) {
            delay = FloatArray(size)
            cursor = 0
        }
        val level = echoLevel.coerceIn(0f, 0.5f)
        val volume = gain.coerceIn(0f, MAX_GAIN)
        if (frame.size != channels) frame = FloatArray(channels)
        val recovery = (1.0 - kotlin.math.exp(-1.0 / (sampleRate * 0.08))).toFloat()
        var index = 0
        while (index < count) {
            val frameCount = minOf(channels, count - index)
            var peak = 0f
            for (channel in 0 until frameCount) {
                val dry = samples[index + channel].toFloat()
                val previous = delay[cursor]
                frame[channel] = (dry + previous * level) * volume
                peak = maxOf(peak, kotlin.math.abs(frame[channel]))
                delay[cursor] = (dry + previous * if (level > 0f) 0.25f else 0f)
                    .coerceIn(-32768f, 32767f)
                cursor = (cursor + 1) % delay.size
            }
            val target = if (peak > LIMITER_CEILING) LIMITER_CEILING / peak else 1f
            limiterGain = if (target < limiterGain) target else limiterGain + (target - limiterGain) * recovery
            for (channel in 0 until frameCount) {
                samples[index + channel] = (frame[channel] * limiterGain)
                    .coerceIn(-LIMITER_CEILING, LIMITER_CEILING).toInt().toShort()
            }
            index += frameCount
        }
    }
}
