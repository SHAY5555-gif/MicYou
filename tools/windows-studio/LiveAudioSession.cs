using System.Buffers;
using System.Buffers.Binary;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace MicYou.Studio;

public sealed class LiveAudioSession : IAsyncDisposable
{
    private readonly object recordingLock = new();
    private readonly ManualResetEventSlim captureStopped = new(false);
    private readonly ManualResetEventSlim captureReady = new(false);
    private WasapiRecorder? recorder;
    private ExclusiveCapture? exclusiveCapture;
    private WasapiPlayer? player;
    private MMDevice? input;
    private MMDevice? output;
    private LosslessRecording? recording;
    private bool stopping;
    private bool captureStarted;
    private bool isFloat;
    private int bytesPerSample;
    private double startupPrefillMs;
    public LiveMonitor? Monitor { get; private set; }
    public WaveFormat? CaptureFormat { get; private set; }
    public bool CaptureIsFloat => isFloat;
    public float InputPeak;
    public float MaximumInputPeak;
    public long CapturedSamples;
    public bool IsRunning { get; private set; }
    public string? LastRecording { get; private set; }
    public LosslessRecording? Recording { get { lock (recordingLock) return recording; } }
    public double CapturePeriodMs => exclusiveCapture?.PeriodMilliseconds ?? recorder?.LatencyMilliseconds ?? 0;
    public double OutputPeriodMs => player?.LatencyMilliseconds ?? 0;
    public bool ExclusiveMode => exclusiveCapture != null;
    public bool LowLatency => ExclusiveMode || recorder?.LowLatencyActive == true && player?.LowLatencyActive == true;
    public event Action<string>? Failed;

    public void Start(string inputId, string outputId, bool echoCancellation, StudioSettings settings)
    {
        using var enumerator = new MMDeviceEnumerator();
        input = enumerator.GetDevice(inputId);
        output = enumerator.GetDevice(outputId);
        if (input.State != DeviceState.Active || output.State != DeviceState.Active)
            throw new InvalidOperationException("המיקרופון או הרמקול שנבחרו אינם מחוברים");

        using var inputClient = input.CreateAudioClient();
        CaptureFormat = inputClient.MixFormat;
        var minimum = settings.MinimumLatency;
        var exclusive = minimum && settings.ExclusiveMode;
        if (minimum) echoCancellation = false;
        if (exclusive)
        {
            exclusiveCapture = new ExclusiveCapture(input);
            CaptureFormat = exclusiveCapture.WaveFormat;
            exclusiveCapture.DataAvailable += OnData;
            exclusiveCapture.Stopped += error =>
            {
                if (!stopping && error != null) Failed?.Invoke(error.Message);
            };
        }
        else
        {
            var builder = new WasapiRecorderBuilder().WithDevice(input).WithFormat(CaptureFormat)
                .WithBufferLength(minimum ? 10 : 20).WithLowLatency(required: minimum).WithMmcssThreadPriority("Pro Audio");
            if (echoCancellation) builder.WithCommunicationsMode().WithEchoCancellationReferenceEndpoint(output);
            else builder.WithRawMode();
            recorder = builder.Build();
            recorder.DataAvailable += OnData;
            recorder.RecordingStopped += (_, args) =>
            {
                captureStopped.Set();
                if (!stopping && args.Exception != null) Failed?.Invoke(args.Exception.Message);
            };
        }
        bytesPerSample = CaptureFormat.BitsPerSample / 8;
        isFloat = CaptureFormat.Encoding == WaveFormatEncoding.IeeeFloat ||
            CaptureFormat is WaveFormatExtensible extended &&
            extended.SubFormat == new Guid("00000003-0000-0010-8000-00aa00389b71");
        if (bytesPerSample is < 1 or > 8 || (isFloat && bytesPerSample is not (4 or 8)))
            throw new NotSupportedException("פורמט המיקרופון אינו נתמך");
        var maximumQueuedMs = minimum ? 30 : 0;
        var outputPeriodMs = minimum ? 10 : 20;
        if (exclusive)
        {
            using var outputClient = output.CreateAudioClient();
            outputPeriodMs = (int)Math.Ceiling(outputClient.MinimumDevicePeriod / 10000.0);
            var driverPeriodMs = Math.Max(exclusiveCapture!.PeriodMilliseconds, outputPeriodMs);
            maximumQueuedMs = (int)Math.Ceiling(driverPeriodMs * 4);
            startupPrefillMs = driverPeriodMs * 2;
        }
        Monitor = new LiveMonitor(CaptureFormat.SampleRate, CaptureFormat.Channels,
            minimum ? 0 : 20, maximumQueuedMs)
        {
            Gain = settings.MicrophoneGain,
            Volume = settings.ListeningVolume,
            Echo = minimum ? 0 : settings.EchoLevel,
            EchoDelayMs = settings.EchoDelayMs
        };
        var playback = new WasapiPlayerBuilder().WithDevice(output).WithMmcssThreadPriority("Pro Audio");
        if (exclusive)
        {
            playback.WithExclusiveMode().WithLatency(outputPeriodMs);
        }
        else playback.WithLowLatency(required: minimum).WithLatency(minimum ? 10 : 20);
        if (minimum) playback.WithRawMode();
        player = playback.Build();
        player.Init(new SampleToWaveProvider(Monitor));
        player.PlaybackStopped += (_, args) =>
        {
            if (!stopping && args.Exception != null) Failed?.Invoke(args.Exception.Message);
        };
        if (exclusiveCapture != null) exclusiveCapture.Start();
        else recorder!.StartRecording();
        captureStarted = true;
        if (exclusive && !captureReady.Wait(TimeSpan.FromSeconds(1)))
            throw new IOException("המיקרופון לא העביר שמע בזמן ההפעלה. בדוק את החיבור ונסה שוב");
        player.Play();
        IsRunning = true;
    }

    private void OnData(ReadOnlySpan<byte> buffer, AudioClientBufferFlags flags, long position, long qpc)
    {
        if (stopping || Monitor == null) return;
        var silent = (flags & AudioClientBufferFlags.Silent) != 0;
        var count = buffer.Length / bytesPerSample;
        var decoded = ArrayPool<float>.Shared.Rent(count);
        try
        {
            var peak = 0f;
            for (var index = 0; index < count; index++)
            {
                var value = silent ? 0 : Decode(buffer.Slice(index * bytesPerSample, bytesPerSample));
                if (!float.IsFinite(value)) value = 0;
                decoded[index] = value;
                peak = Math.Max(peak, Math.Abs(value));
            }
            Volatile.Write(ref InputPeak, Math.Max(Volatile.Read(ref InputPeak), peak));
            Volatile.Write(ref MaximumInputPeak, Math.Max(MaximumInputPeak, peak));
            Interlocked.Add(ref CapturedSamples, count);
            if (!Monitor.Push(decoded.AsSpan(0, count)))
                Failed?.Invoke("באפר השמע התמלא. עצור והפעל שוב את המיקרופון");
            if (!captureReady.IsSet && Monitor.QueuedMilliseconds >= startupPrefillMs) captureReady.Set();
        }
        finally { ArrayPool<float>.Shared.Return(decoded); }
        lock (recordingLock) recording?.Append(buffer, silent);
    }

    private float Decode(ReadOnlySpan<byte> value)
    {
        if (isFloat) return bytesPerSample == 4
            ? BitConverter.Int32BitsToSingle(BinaryPrimitives.ReadInt32LittleEndian(value))
            : (float)BitConverter.Int64BitsToDouble(BinaryPrimitives.ReadInt64LittleEndian(value));
        return bytesPerSample switch
        {
            1 => (value[0] - 128) / 128f,
            2 => BinaryPrimitives.ReadInt16LittleEndian(value) / 32768f,
            3 => ((value[0] | value[1] << 8 | value[2] << 16) << 8 >> 8) / 8388608f,
            4 => BinaryPrimitives.ReadInt32LittleEndian(value) / 2147483648f,
            _ => throw new NotSupportedException("פורמט PCM אינו נתמך")
        };
    }

    public void StartRecording(string folder)
    {
        if (!IsRunning || CaptureFormat == null) throw new InvalidOperationException("הפעל תחילה את המיקרופון");
        lock (recordingLock)
        {
            if (recording != null) return;
            recording = new LosslessRecording(folder, CaptureFormat);
            LastRecording = recording.Path;
        }
    }

    public async Task StopRecordingAsync()
    {
        LosslessRecording? current;
        lock (recordingLock) { current = recording; recording = null; }
        if (current == null) return;
        await current.FinishAsync();
        if (current.Error != null) throw new IOException(current.Error);
    }

    public async ValueTask DisposeAsync()
    {
        stopping = true;
        IsRunning = false;
        try
        {
            exclusiveCapture?.Dispose();
            if (recorder != null && captureStarted)
            {
                recorder.StopRecording();
                await Task.Run(() => captureStopped.Wait(3000));
            }
            player?.Stop();
        }
        finally
        {
            try { await StopRecordingAsync(); }
            finally
            {
                try { recorder?.Dispose(); }
                finally
                {
                    try { player?.Dispose(); }
                    finally
                    {
                        input?.Dispose();
                        output?.Dispose();
                        captureReady.Dispose();
                        captureStopped.Dispose();
                    }
                }
            }
        }
    }
}
