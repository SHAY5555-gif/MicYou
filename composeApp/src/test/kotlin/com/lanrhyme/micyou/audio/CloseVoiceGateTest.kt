package com.lanrhyme.micyou.audio

import org.junit.Assert.*
import org.junit.Test

class CloseVoiceGateTest {
    private fun process(gate: CloseVoiceGate, amplitude: Int): ShortArray {
        val pcm = ShortArray(480) { amplitude.toShort() }
        gate.process(pcm, pcm.size, 48000, amplitude / 32768f)
        return pcm
    }

    @Test fun quietPickupStaysSilentBeforeOutputGain() {
        val gate = CloseVoiceGate()
        val mixer = MicrophoneMixer().apply { gain = 6f }
        repeat(20) {
            val pcm = process(gate, 150)
            mixer.process(pcm, pcm.size, 48000, 1)
            assertTrue(pcm.all { it == 0.toShort() })
        }
        assertFalse(gate.isOpen)
    }

    @Test fun strongCloseVoicePassesWithAFastSmoothAttack() {
        val gate = CloseVoiceGate()
        val pcm = process(gate, 5000)
        assertTrue(gate.isOpen)
        assertTrue(pcm.first() in 1..4999)
        assertEquals(5000, pcm.last().toInt())
    }

    @Test fun gateClosesBeforeADelayedQuietRepeat() {
        val gate = CloseVoiceGate()
        process(gate, 5000)
        repeat(15) { process(gate, 0) }
        assertFalse(gate.isOpen)
        assertTrue(process(gate, 300).all { it == 0.toShort() })
    }

    @Test fun higherSensitivityCanAdmitAQuieterVoice() {
        val gate = CloseVoiceGate()
        assertTrue(process(gate, 300).all { it == 0.toShort() })
        gate.sensitivity = 0.7f
        assertTrue(process(gate, 300).any { it > 0 })
        assertTrue(gate.isOpen)
    }

    @Test fun maximumSensitivityBypassesTheGateAndResetClosesIt() {
        val gate = CloseVoiceGate().apply { sensitivity = 1f }
        assertTrue(process(gate, 50).all { it == 50.toShort() })
        gate.reset()
        assertFalse(gate.isOpen)
    }
}
