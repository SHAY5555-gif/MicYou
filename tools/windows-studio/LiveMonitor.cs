using NAudio.Wave;

namespace MicYou.Studio;

// A bounded frame queue preserves captured samples and reports a clock or
// scheduling overrun instead of silently dropping microphone audio.
public sealed class LiveMonitor : ISampleProvider
{
    private readonly object sync = new();
    private readonly float[] samples;
    private readonly float[] delay;
    private int head;
    private int queued;
    private int delayPosition;
    private bool primed;
    private readonly int prebuffer;
    public WaveFormat WaveFormat { get; }
    public float Gain = 1;
    public float Volume = 0.5f;
    public float Echo;
    public int EchoDelayMs = 160;
    public bool Listening = true;
    public float OutputPeak;
    public int Underruns;

    public LiveMonitor(int sampleRate, int channels, int prebufferMs = 20)
    {
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(sampleRate, channels);
        samples = new float[sampleRate * channels / 4];
        delay = new float[sampleRate * channels];
        prebuffer = Math.Max(channels, sampleRate * channels * prebufferMs / 1000);
    }

    public double QueuedMilliseconds
    {
        get { lock (sync) return 1000.0 * queued / (WaveFormat.SampleRate * WaveFormat.Channels); }
    }

    public bool Push(ReadOnlySpan<float> input)
    {
        lock (sync)
        {
            if (input.Length > samples.Length - queued) return false;
            for (var i = 0; i < input.Length; i++) samples[(head + queued + i) % samples.Length] = input[i];
            queued += input.Length;
            return true;
        }
    }

    public int Read(float[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public int Read(Span<float> buffer)
    {
        lock (sync)
        {
            buffer.Clear();
            var count = buffer.Length;
            if (!primed && queued >= prebuffer) primed = true;
            var available = primed ? Math.Min(count, queued) : 0;
            available -= available % WaveFormat.Channels;
            var echo = Math.Clamp(Volatile.Read(ref Echo), 0, 0.4f);
            var gain = Math.Clamp(Volatile.Read(ref Gain), 0, 3) * Math.Clamp(Volatile.Read(ref Volume), 0, 1);
            var delaySamples = Math.Clamp(Volatile.Read(ref EchoDelayMs), 50, 450)
                * WaveFormat.SampleRate / 1000 * WaveFormat.Channels;
            var peak = 0f;
            for (var i = 0; i < available; i++)
            {
                var dry = samples[head];
                head = (head + 1) % samples.Length;
                var delayed = delay[(delayPosition + delay.Length - delaySamples) % delay.Length];
                delay[delayPosition] = dry + delayed * echo * 0.25f;
                delayPosition = (delayPosition + 1) % delay.Length;
                var value = Listening ? Math.Clamp((dry + delayed * echo) * gain, -0.98f, 0.98f) : 0;
                buffer[i] = value;
                peak = Math.Max(peak, Math.Abs(value));
            }
            queued -= available;
            if (primed && available < count)
            {
                primed = false;
                Interlocked.Increment(ref Underruns);
            }
            Volatile.Write(ref OutputPeak, Math.Max(Volatile.Read(ref OutputPeak), peak));
            return count;
        }
    }
}
