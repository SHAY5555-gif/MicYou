package com.lanrhyme.micyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.R
import com.lanrhyme.micyou.audio.MicrophoneMixer
import com.lanrhyme.micyou.viewmodel.AppUiState
import com.lanrhyme.micyou.viewmodel.MainViewModel
import com.lanrhyme.micyou.viewmodel.StreamState
import com.lanrhyme.micyou.viewmodel.ConnectionMode
import kotlin.math.roundToInt

@Composable
fun MicrophoneMixerCard(state: AppUiState, viewModel: MainViewModel) {
    val connected = state.streamState == StreamState.Streaming
    val direct = state.mode == ConnectionMode.Bluetooth
    val holding = connected && !state.isMuted
    val talkLabel = stringResource(when {
        !state.pushToTalkEnabled && holding -> R.string.normalMute
        !state.pushToTalkEnabled -> R.string.normalStart
        holding -> R.string.pttTalking
        else -> R.string.pttHold
    })
    val latestViewModel by rememberUpdatedState(viewModel)
    DisposableEffect(Unit) {
        onDispose { latestViewModel.setPushToTalkPressed(false) }
    }
    val talkInteraction = if (state.pushToTalkEnabled) {
        Modifier.pointerInput(connected) {
            if (connected) {
                detectTapGestures(onPress = {
                    latestViewModel.setPushToTalkPressed(true)
                    try { tryAwaitRelease() }
                    finally { latestViewModel.setPushToTalkPressed(false) }
                })
            }
        }
    } else {
        Modifier.clickable(enabled = connected) { latestViewModel.toggleMute() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 400.dp
        val contentHeight = maxOf(maxHeight, 360.dp)
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Color(0xFF252D27), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .height(contentHeight)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.mixerConsoleTitle), color = Color(0xFFE2EBDC),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { viewModel.toggleStream() },
                    enabled = state.streamState != StreamState.Connecting) {
                    Text(stringResource(if (connected) R.string.pttDisconnect
                        else if (direct) R.string.bluetoothStart else R.string.pttConnect))
                }
            }
            if (direct && connected) Text(state.bluetoothDeviceName,
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB6C3B5))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MixerFader(
                    stringResource(R.string.mixerVolume), "${(state.microphoneGain * 100).roundToInt()}%",
                    state.microphoneGain, 0f..MicrophoneMixer.MAX_GAIN, "0", "600%", Color(0xFFBAE28B),
                    Modifier.weight(1f).fillMaxHeight(), compact, viewModel::setMicrophoneGain
                )
                MixerFader(
                    stringResource(if (direct) R.string.bluetoothSpeakerVolume else R.string.mixerMusic),
                    "${((if (direct) state.speakerGain else state.musicGain) * 100).roundToInt()}%",
                    if (direct) state.speakerGain else state.musicGain, 0f..1f, "0", "100%", Color(0xFFEBA5B3),
                    Modifier.weight(1f).fillMaxHeight(), compact,
                    onChange = if (direct) viewModel::setSpeakerGain else viewModel::setMusicGain,
                    enabled = direct || state.pcMusicAvailable
                )
                MixerFader(
                    stringResource(R.string.mixerEcho), "${(state.microphoneEcho * 100).roundToInt()}%",
                    state.microphoneEcho, 0f..0.5f, "0", "50%", Color(0xFFFFC16E),
                    Modifier.weight(1f).fillMaxHeight(), compact, viewModel::setMicrophoneEcho
                )
                MixerFader(
                    stringResource(R.string.mixerDelay), "${state.microphoneEchoDelay.roundToInt()} ms",
                    state.microphoneEchoDelay, 50f..450f, "50", "450 ms", Color(0xFF88C8DB),
                    Modifier.weight(1f).fillMaxHeight(), compact, viewModel::setMicrophoneEchoDelay
                )
            }
            Text(stringResource(if (direct) R.string.bluetoothLocalHint else if (state.pcMusicAvailable) R.string.mixerFaderHint else R.string.mixerMusicHint), color = Color(0xFFB6C3B5),
                style = MaterialTheme.typography.bodySmall)
            Surface(
                modifier = Modifier.fillMaxWidth().height(if (compact) 64.dp else 76.dp)
                    .semantics { contentDescription = talkLabel }
                    .then(talkInteraction),
                shape = RoundedCornerShape(24.dp),
                color = if (holding) Color(0xFFBAE28B) else Color(0xFF364530),
                contentColor = if (holding) Color(0xFF203019) else Color(0xFFDCE8D6)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (holding) Icons.Rounded.Mic else Icons.Rounded.MicOff, null)
                    Spacer(Modifier.width(10.dp))
                    Text(talkLabel, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                stringResource(when {
                    !connected && state.pushToTalkEnabled -> R.string.pttConnectFirst
                    !connected -> R.string.normalConnectFirst
                    state.pushToTalkEnabled -> R.string.pttReady
                    holding -> R.string.normalLive
                    else -> R.string.normalReady
                }),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB6C3B5)
            )
        }
    }
}
