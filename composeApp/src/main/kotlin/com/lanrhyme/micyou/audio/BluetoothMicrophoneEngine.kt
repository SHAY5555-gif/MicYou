package com.lanrhyme.micyou.audio

import android.content.Context
import android.content.Intent
import android.media.*
import android.media.AudioFormat as AndroidAudioFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.lanrhyme.micyou.R
import com.lanrhyme.micyou.service.AudioService
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.viewmodel.StreamState
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BluetoothMicrophoneState(
    val streamState: StreamState = StreamState.Idle,
    val isMuted: Boolean = true,
    val deviceName: String = "",
    val error: String? = null
)

/** On-device PCM monitoring. No network, desktop server, or speech codec. */
class BluetoothMicrophoneEngine {
    companion object {
        @Volatile private var active = WeakReference<BluetoothMicrophoneEngine>(null)
        fun requestStop() { active.get()?.stop() }
        fun isActive() = active.get()?.state?.value?.streamState == StreamState.Streaming
    }

    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MicYou-Bluetooth")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(BluetoothMicrophoneState())
    val state = _state.asStateFlow()
    private val _levels = MutableStateFlow(0f)
    val levels = _levels.asStateFlow()
    val mixer = MicrophoneMixer()
    private val gate = MicrophoneGate()
    private val flushRequested = AtomicBoolean(false)
    private var job: Job? = null
    private var closeJob: Job? = null

    fun setPushToTalkEnabled(enabled: Boolean) {
        if (gate.setMode(enabled)) publishMute()
    }

    fun setPushToTalkPressed(pressed: Boolean) {
        if (gate.setPressed(pressed, _state.value.streamState == StreamState.Streaming)) publishMute()
    }

    fun setMute(muted: Boolean) {
        gate.setMuted(muted, _state.value.streamState == StreamState.Streaming)
        publishMute()
    }

    private fun publishMute() {
        if (gate.isMuted) {
            mixer.reset()
            flushRequested.set(true)
        }
        _state.update { it.copy(isMuted = gate.isMuted) }
    }

    suspend fun start() {
        stopAndWait()
        val context = requireNotNull(ContextHelper.getContext())
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val output = outputs.firstOrNull { it.productName.toString().contains("Soundbar", true) }
            ?: outputs.singleOrNull()
            ?: run {
                val message = getString(R.string.bluetoothConnectFirst)
                _state.value = BluetoothMicrophoneState(StreamState.Error, error = message)
                throw IllegalStateException(message)
            }
        gate.reset()
        flushRequested.set(false)
        _state.value = BluetoothMicrophoneState(StreamState.Connecting, deviceName = output.productName.toString())
        val ready = CompletableDeferred<Unit>()
        active = WeakReference(this)
        job = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            var noise: NoiseSuppressor? = null
            var echo: AcousticEchoCanceler? = null
            try {
                val rate = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                    ?.toIntOrNull()?.takeIf { it in listOf(44100, 48000) } ?: 48000
                val blockFrames = (rate / 200).coerceAtLeast(1)
                val recordMin = AudioRecord.getMinBufferSize(rate, AndroidAudioFormat.CHANNEL_IN_MONO,
                    AndroidAudioFormat.ENCODING_PCM_16BIT)
                val playMin = AudioTrack.getMinBufferSize(rate, AndroidAudioFormat.CHANNEL_OUT_MONO,
                    AndroidAudioFormat.ENCODING_PCM_16BIT)
                check(recordMin > 0 && playMin > 0) { "Unsupported Bluetooth PCM configuration" }
                val inputFormat = AndroidAudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT).setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO).build()
                val outputFormat = AndroidAudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT).setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO).build()
                recorder = AudioRecord.Builder()
                    .setAudioSource(if (Build.VERSION.SDK_INT >= 29) MediaRecorder.AudioSource.VOICE_PERFORMANCE
                        else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(inputFormat).setBufferSizeInBytes(maxOf(recordMin, blockFrames * 4)).build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?.let { recorder.setPreferredDevice(it) }
                runCatching {
                    if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(recorder.audioSessionId)
                    noise?.enabled = true
                    if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(recorder.audioSessionId)
                    echo?.enabled = true
                }
                val builder = AudioTrack.Builder().setAudioFormat(outputFormat)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(playMin, blockFrames * 4))
                if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                player = builder.build()
                check(player.state == AudioTrack.STATE_INITIALIZED) { "Bluetooth output initialization failed" }
                check(player.setPreferredDevice(output)) { getString(R.string.bluetoothRouteFailed) }
                player.setBufferSizeInFrames(blockFrames * 2)
                val serviceIntent = Intent(context, AudioService::class.java).apply {
                    action = AudioService.ACTION_START
                    putExtra(AudioService.EXTRA_USE_WIFI_LOCK, false)
                }
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
                else context.startService(serviceIntent)
                recorder.startRecording()
                player.play()
                val samples = ShortArray(blockFrames)
                val started = SystemClock.elapsedRealtime()
                var routeVerified = false
                Logger.i("BluetoothMic", "Direct PCM rate=$rate block=$blockFrames outputBuffer=${player.bufferSizeInFrames} ns=${noise?.enabled} aec=${echo?.enabled}")
                while (isActive) {
                    val routed = player.routedDevice
                    if (!routeVerified && routed?.id == output.id) {
                        routeVerified = true
                        _state.update { it.copy(streamState = StreamState.Streaming) }
                        ready.complete(Unit)
                        Logger.i("BluetoothMic", "Output route verified: ${output.productName}")
                    } else if (routeVerified && routed?.id != output.id) {
                        throw IllegalStateException(getString(R.string.bluetoothRouteLost))
                    } else if (!routeVerified && SystemClock.elapsedRealtime() - started > 3000) {
                        throw IllegalStateException(getString(R.string.bluetoothRouteFailed))
                    }
                    if (flushRequested.getAndSet(false)) {
                        player.pause()
                        player.flush()
                        player.play()
                    }
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) throw IllegalStateException("Microphone read failed: $count")
                    if (count == 0) { delay(1); continue }
                    var peak = 0
                    for (index in 0 until count) peak = maxOf(peak, kotlin.math.abs(samples[index].toInt()))
                    _levels.value = peak / 32768f
                    if (routeVerified && gate.isTransmitting) mixer.process(samples, count, rate, 1)
                    else samples.fill(0, 0, count)
                    val written = player.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0) throw IllegalStateException("Bluetooth write failed: $written")
                    // A full output queue drops the current tail instead of accumulating stale voice.
                    if (written < count) delay(1)
                }
            } catch (cancelled: CancellationException) {
                ready.completeExceptionally(cancelled)
                throw cancelled
            } catch (error: Exception) {
                Logger.e("BluetoothMic", "Direct audio stopped", error)
                _state.update { it.copy(streamState = StreamState.Error, error = error.message) }
                ready.completeExceptionally(error)
            } finally {
                gate.reset()
                mixer.reset()
                runCatching { player?.pause(); player?.flush(); player?.release() }
                runCatching { recorder?.stop(); recorder?.release() }
                runCatching { noise?.release(); echo?.release() }
                _levels.value = 0f
                _state.update { it.copy(isMuted = true,
                    streamState = if (it.streamState == StreamState.Error) StreamState.Error else StreamState.Idle) }
                if (active.get() === this@BluetoothMicrophoneEngine) active.clear()
                runCatching { context.startService(Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }) }
            }
        }
        try {
            withTimeout(5000) { ready.await() }
        } catch (error: Exception) {
            job?.cancelAndJoin()
            throw error
        }
    }

    fun stop() { scope.launch { stopAndWait() } }

    suspend fun stopAndWait() {
        gate.reset()
        publishMute()
        job?.cancelAndJoin()
        job = null
        _state.update { it.copy(streamState = StreamState.Idle, error = null) }
    }

    @Synchronized
    fun close(): Job {
        closeJob?.let { return it }
        return CoroutineScope(Dispatchers.IO).launch {
            stopAndWait()
            scope.cancel()
            dispatcher.close()
        }.also { closeJob = it }
    }
}
