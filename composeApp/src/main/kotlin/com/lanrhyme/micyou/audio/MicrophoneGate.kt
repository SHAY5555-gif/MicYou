package com.lanrhyme.micyou.audio

/** Controls transmission without changing capture, DSP, or connection settings. */
class MicrophoneGate {
    @Volatile var pushToTalkEnabled = true
        private set
    @Volatile var isMuted = true
        private set
    @Volatile private var pressed = false

    val isTransmitting: Boolean
        get() = !isMuted && (!pushToTalkEnabled || pressed)

    @Synchronized
    fun setMode(enabled: Boolean): Boolean {
        if (pushToTalkEnabled == enabled) return false
        reset()
        pushToTalkEnabled = enabled
        return true
    }

    @Synchronized
    fun setPressed(down: Boolean, connected: Boolean): Boolean {
        if (!pushToTalkEnabled) return false
        pressed = down && connected
        isMuted = !pressed
        return true
    }

    @Synchronized
    fun setMuted(muted: Boolean, connected: Boolean) {
        isMuted = muted || !connected || (pushToTalkEnabled && !pressed)
    }

    @Synchronized
    fun reset() {
        isMuted = true
        pressed = false
    }
}
