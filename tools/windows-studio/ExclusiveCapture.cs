using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using NAudio.Wave;

namespace MicYou.Studio;

public sealed class ExclusiveCapture : IDisposable
{
    public delegate void PacketHandler(ReadOnlySpan<byte> data, AudioClientBufferFlags flags, long position, long qpc);

    private AudioClient client;
    private readonly AudioCaptureClient capture;
    private readonly EventWaitHandle packetReady = new(false, EventResetMode.AutoReset);
    private readonly ManualResetEvent stop = new(false);
    private readonly byte[] silence;
    private Thread? worker;
    private int disposed;

    public WaveFormat WaveFormat { get; }
    public double PeriodMilliseconds { get; }
    public event PacketHandler? DataAvailable;
    public event Action<Exception?>? Stopped;

    public ExclusiveCapture(MMDevice device)
    {
        client = device.CreateAudioClient();
        try
        {
            WaveFormat = SelectFormat(client);
            var period = client.MinimumDevicePeriod;
            client.SetClientProperties(AudioStreamCategory.Other, AudioClientStreamOptions.Raw);
            try
            {
                // Exclusive event streams require the same duration and periodicity.
                // NAudio 3.3's recorder passes zero periodicity, so initialize capture directly.
                client.Initialize(AudioClientShareMode.Exclusive, AudioClientStreamFlags.EventCallback,
                    period, period, WaveFormat, Guid.Empty);
            }
            catch (COMException error) when (error.ErrorCode == AudioClientErrorCode.BufferSizeNotAligned)
            {
                period = (long)Math.Ceiling(10000000.0 * client.BufferSize / WaveFormat.SampleRate);
                client.Dispose();
                client = device.CreateAudioClient();
                client.SetClientProperties(AudioStreamCategory.Other, AudioClientStreamOptions.Raw);
                client.Initialize(AudioClientShareMode.Exclusive, AudioClientStreamFlags.EventCallback,
                    period, period, WaveFormat, Guid.Empty);
            }
            client.SetEventHandle(packetReady.SafeWaitHandle.DangerousGetHandle());
            capture = client.AudioCaptureClient;
            PeriodMilliseconds = 1000.0 * client.BufferSize / WaveFormat.SampleRate;
            silence = new byte[client.BufferSize * WaveFormat.BlockAlign];
        }
        catch
        {
            client.Dispose();
            packetReady.Dispose();
            stop.Dispose();
            throw;
        }
    }

    private static WaveFormat SelectFormat(AudioClient client)
    {
        var mix = client.MixFormat;
        if (client.IsFormatSupported(AudioClientShareMode.Exclusive, mix)) return mix;
        foreach (var channels in new[] { mix.Channels, 1, 2 }.Distinct())
        {
            foreach (var bits in new[] { 32, 24, 16 })
            {
                var format = new WaveFormatExtensible(mix.SampleRate, bits, channels);
                if (client.IsFormatSupported(AudioClientShareMode.Exclusive, format)) return format;
            }
        }
        throw new NotSupportedException("המיקרופון אינו תומך במצב בלעדי בקצב הדגימה הנוכחי");
    }

    public void Start()
    {
        client.Start();
        worker = new Thread(ReadPackets) { IsBackground = true, Name = "MicYou low latency capture" };
        try { worker.Start(); }
        catch
        {
            worker = null;
            client.Stop();
            throw;
        }
    }

    private void ReadPackets()
    {
        Exception? failure = null;
        var registration = AvSetMmThreadCharacteristics("Pro Audio", out _);
        try
        {
            var handles = new WaitHandle[] { stop, packetReady };
            while (WaitHandle.WaitAny(handles) == 1)
            {
                while (!stop.WaitOne(0) && capture.GetNextPacketSize() > 0) DispatchPacket();
            }
        }
        catch (Exception error) { failure = error; }
        finally
        {
            try { client.Stop(); }
            catch (Exception error) { failure ??= error; }
            if (registration != IntPtr.Zero) AvRevertMmThreadCharacteristics(registration);
            Stopped?.Invoke(failure);
        }
    }

    private unsafe void DispatchPacket()
    {
        var pointer = capture.GetBuffer(out var frames, out var flags, out var position, out var qpc);
        try
        {
            var bytes = frames * WaveFormat.BlockAlign;
            var data = (flags & AudioClientBufferFlags.Silent) != 0
                ? silence.AsSpan(0, bytes)
                : new ReadOnlySpan<byte>(pointer.ToPointer(), bytes);
            DataAvailable?.Invoke(data, flags, position, qpc);
        }
        finally { capture.ReleaseBuffer(frames); }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        stop.Set();
        worker?.Join();
        capture.Dispose();
        client.Dispose();
        packetReady.Dispose();
        stop.Dispose();
    }

    [DllImport("avrt.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr AvSetMmThreadCharacteristics(string taskName, out uint taskIndex);

    [DllImport("avrt.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AvRevertMmThreadCharacteristics(IntPtr handle);
}
