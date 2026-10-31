package com.lanrhyme.micyou.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneGateTest {
    @Test fun connectionStartsMutedAndRequiresAnExplicitAction() {
        val gate = MicrophoneGate()
        assertTrue(gate.isMuted)
        assertFalse(gate.isTransmitting)
        gate.setMode(false)
        assertFalse(gate.isTransmitting)
    }

    @Test fun pushToTalkRequiresAConnectedPressAndStopsOnRelease() {
        val gate = MicrophoneGate()
        gate.setPressed(true, connected = false)
        assertFalse(gate.isTransmitting)
        gate.setPressed(true, connected = true)
        assertTrue(gate.isTransmitting)
        gate.setPressed(false, connected = true)
        gate.setMuted(false, connected = true)
        assertFalse(gate.isTransmitting)
    }

    @Test fun normalModeStaysOnAfterReleaseUntilExplicitMute() {
        val gate = MicrophoneGate()
        gate.setMode(false)
        gate.setMuted(false, connected = true)
        assertTrue(gate.isTransmitting)
        assertFalse(gate.setPressed(false, connected = true))
        assertTrue(gate.isTransmitting)
        gate.setMuted(true, connected = true)
        assertFalse(gate.isTransmitting)
    }

    @Test fun changingModesClosesEitherTypeOfOpenMicrophone() {
        val gate = MicrophoneGate()
        gate.setPressed(true, connected = true)
        gate.setMode(false)
        assertFalse(gate.isTransmitting)
        gate.setMuted(false, connected = true)
        gate.setMode(true)
        assertFalse(gate.isTransmitting)
    }

    @Test fun disconnectCannotLeaveOrReopenTheMicrophone() {
        val gate = MicrophoneGate()
        gate.setMode(false)
        gate.setMuted(false, connected = true)
        gate.reset()
        assertFalse(gate.isTransmitting)
        gate.setMuted(false, connected = false)
        assertFalse(gate.isTransmitting)
    }

    @Test fun selectingTheCurrentModeDoesNotInterruptNormalTransmission() {
        val gate = MicrophoneGate()
        gate.setMode(false)
        gate.setMuted(false, connected = true)
        assertFalse(gate.setMode(false))
        assertTrue(gate.isTransmitting)
    }
}
