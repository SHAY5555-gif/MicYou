package com.lanrhyme.micyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.R
import com.lanrhyme.micyou.viewmodel.AppUiState
import com.lanrhyme.micyou.viewmodel.MainViewModel
import com.lanrhyme.micyou.viewmodel.StreamState
import kotlin.math.roundToInt

@Composable
fun MicrophoneMixerCard(state: AppUiState, viewModel: MainViewModel) {
    val connected = state.streamState == StreamState.Streaming
    val holding = connected && !state.isMuted
    val talkLabel = stringResource(if (holding) R.string.pttTalking else R.string.pttHold)
    val latestViewModel by rememberUpdatedState(viewModel)
    DisposableEffect(Unit) {
        onDispose { latestViewModel.setPushToTalkPressed(false) }
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { viewModel.toggleStream() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.streamState != StreamState.Connecting
        ) {
            Text(stringResource(if (connected) R.string.pttDisconnect else R.string.pttConnect))
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(96.dp)
                .semantics { contentDescription = talkLabel }
                .pointerInput(connected) {
                    if (connected) {
                        detectTapGestures(onPress = {
                            latestViewModel.setPushToTalkPressed(true)
                            try { tryAwaitRelease() }
                            finally { latestViewModel.setPushToTalkPressed(false) }
                        })
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = if (holding) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondaryContainer
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
            stringResource(if (connected) R.string.pttReady else R.string.pttConnectFirst),
            style = MaterialTheme.typography.bodySmall
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            MixerSlider(
                stringResource(R.string.mixerVolume), "${(state.microphoneGain * 100).roundToInt()}%",
                state.microphoneGain, 0f..2f, viewModel::setMicrophoneGain
            )
            MixerSlider(
                stringResource(R.string.mixerEcho), "${(state.microphoneEcho * 100).roundToInt()}%",
                state.microphoneEcho, 0f..0.5f, viewModel::setMicrophoneEcho
            )
            MixerSlider(
                stringResource(R.string.mixerDelay), "${state.microphoneEchoDelay.roundToInt()} ms",
                state.microphoneEchoDelay, 50f..450f, viewModel::setMicrophoneEchoDelay
            )
            Text(stringResource(R.string.mixerEchoHint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MixerSlider(label: String, display: String, value: Float,
                        range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(display)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range,
        modifier = Modifier.semantics { contentDescription = label })
}
