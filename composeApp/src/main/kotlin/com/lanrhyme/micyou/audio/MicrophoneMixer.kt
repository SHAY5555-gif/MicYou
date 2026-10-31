package com.lanrhyme.micyou.audio

/** Local microphone gain and a bounded echo. A zero echo level is completely dry. */
class MicrophoneMixer {
    @Volatile var gain: Float = 1f
    @Volatile var echoLevel: Float = 0f
    @Volatile var echoDelayMs: Float = 120f
    private var delay = FloatArray(0)
    private var cursor = 0

    @Synchronized
    fun reset() {
        delay.fill(0f)
        cursor = 0
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
        val volume = gain.coerceIn(0f, 2f)
        for (index in 0 until count) {
            val dry = samples[index].toFloat()
            val previous = delay[cursor]
            val mixed = (dry + previous * level) * volume
            samples[index] = mixed.coerceIn(-32768f, 32767f).toInt().toShort()
            delay[cursor] = (dry + previous * if (level > 0f) 0.25f else 0f)
                .coerceIn(-32768f, 32767f)
            cursor = (cursor + 1) % delay.size
        }
    }
}
