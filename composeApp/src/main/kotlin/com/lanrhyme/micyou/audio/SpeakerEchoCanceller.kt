package com.lanrhyme.micyou.audio

import cn.enaium.webrtc.aec3.*

/** AEC fed with the exact post-mixer PCM queued to the Bluetooth output. */
class SpeakerEchoCanceller(private val sampleRate: Int = 16000, delayMs: Int = 240) : AutoCloseable {
    // The published 1.0.3 native binary passes cancellation QA at 16 kHz.
    // Its 48 kHz path leaves synthetic echo untouched; do not silently use it.
    init { require(sampleRate == 16000) }
    private val residualDelayMs = 80
    private val renderDelayFrames = ((delayMs - residualDelayMs).coerceAtLeast(0) / 10)
    private val config = createAec3Config().apply {
        // WebRTC's default delay is in 64-sample blocks at 16 kHz (4 ms each).
        setDelayDefaultDelay(residualDelayMs / 4)
        setFilterInitialStateSeconds(0.5f)
        setFilterConservativeInitialPhase(false)
        check(validate()) { "Invalid speaker echo configuration" }
    }
    private val environment = createAec3Environment()
    private val factory = createAec3FactoryWithConfig(config)
    private val control = createAec3EchoControl(factory, environment, sampleRate, 1, 1)
        .apply { setAudioBufferDelay(residualDelayMs) }
    private val render = createAec3AudioBuffer(sampleRate, 1)
    private val capture = createAec3AudioBuffer(sampleRate, 1)
    private val frameSize = sampleRate / 100
    private val floats = FloatArray(frameSize)
    private val renderHistory = Array(renderDelayFrames + 1) { FloatArray(frameSize) }
    private var renderIndex = 0
    private var closed = false

    fun processCapture(samples: ShortArray, enabled: Boolean = true) {
        require(samples.size == frameSize)
        // Reference the older frame now approaching the speaker, not the new
        // frame still queued behind Bluetooth. This avoids matching near voice
        // against its own not-yet-played copy in a microphone loopback.
        val referenceIndex = (renderIndex - renderDelayFrames + renderHistory.size) % renderHistory.size
        render.writeChannel(0, renderHistory[referenceIndex])
        control.analyzeRender(render)
        for (i in floats.indices) floats[i] = samples[i] * 0.25f
        capture.writeChannel(0, floats)
        control.analyzeCapture(capture)
        control.processCapture(capture, false)
        if (enabled) {
            val cleaned = capture.readChannel(0)
            for (i in samples.indices) samples[i] = (cleaned[i] * 4f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    fun analyzeRender(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..frameSize)
        val reference = renderHistory[renderIndex]
        for (i in reference.indices) reference[i] = if (i < count) samples[i] * 0.25f else 0f
        renderIndex = (renderIndex + 1) % renderHistory.size
    }

    fun metrics(): Aec3Metrics = control.getMetrics()

    override fun close() {
        if (closed) return
        closed = true
        capture.close()
        render.close()
        control.close()
        factory.close()
        environment.close()
        config.close()
    }
}
