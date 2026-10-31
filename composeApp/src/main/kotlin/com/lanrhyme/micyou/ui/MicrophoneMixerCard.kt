package com.lanrhyme.micyou.ui

import androidx.compose.foundation.background
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 400.dp
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Color(0xFF252D27), RoundedCornerShape(24.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.mixerConsoleTitle), color = Color(0xFFE2EBDC),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { viewModel.toggleStream() },
                    enabled = state.streamState != StreamState.Connecting) {
                    Text(stringResource(if (connected) R.string.pttDisconnect else R.string.pttConnect))
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MixerFader(
                    stringResource(R.string.mixerVolume), "${(state.microphoneGain * 100).roundToInt()}%",
                    state.microphoneGain, 0f..2f, "0", "200%", Color(0xFFBAE28B),
                    Modifier.weight(1f).fillMaxHeight(), compact, viewModel::setMicrophoneGain
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
            Text(stringResource(R.string.mixerFaderHint), color = Color(0xFFB6C3B5),
                style = MaterialTheme.typography.bodySmall)
            Surface(
                modifier = Modifier.fillMaxWidth().height(if (compact) 64.dp else 76.dp)
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
                stringResource(if (connected) R.string.pttReady else R.string.pttConnectFirst),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB6C3B5)
            )
        }
    }
}
