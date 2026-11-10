using NAudio.Wave;

namespace MicYou.Studio;

// The live queue can discard stale monitoring frames after a scheduling delay.
// Recording is fed separately with the original input samples.
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
    private readonly int maximumQueued;
    private readonly int fadeFrames;
    private readonly float[] fadeStart;
    private readonly float[] lastRendered;
    private int fadeRemaining;
    public WaveFormat WaveFormat { get; }
    public float Gain = 1;
    public float Volume = 0.5f;
    public float Echo;
    public int EchoDelayMs = 160;
    public bool Listening = true;
    public float OutputPeak;
    public int Underruns;
    public long SkippedFrames;

    public LiveMonitor(int sampleRate, int channels, int prebufferMs = 20, int maximumQueuedMs = 0)
    {
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(sampleRate, channels);
        samples = new float[sampleRate * channels / 4];
        delay = new float[sampleRate * channels];
        prebuffer = Math.Max(channels, sampleRate * channels * prebufferMs / 1000);
        maximumQueued = maximumQueuedMs == 0 ? samples.Length : Math.Clamp(
            sampleRate * maximumQueuedMs / 1000 * channels, channels, samples.Length);
        fadeFrames = Math.Max(1, sampleRate / 1000);
        fadeStart = new float[channels];
        lastRendered = new float[channels];
    }

    public double QueuedMilliseconds
    {
        get { lock (sync) return 1000.0 * queued / (WaveFormat.SampleRate * WaveFormat.Channels); }
    }

    public bool Push(ReadOnlySpan<float> input)
    {
        lock (sync)
        {
            if (maximumQueued < samples.Length && queued + input.Length > maximumQueued)
            {
                var discard = queued + input.Length - maximumQueued;
                var discardQueued = Math.Min(queued, discard);
                head = (head + discardQueued) % samples.Length;
                queued -= discardQueued;
                input = input[(discard - discardQueued)..];
                SkippedFrames += discard / WaveFormat.Channels;
                lastRendered.CopyTo(fadeStart, 0);
                fadeRemaining = fadeFrames * WaveFormat.Channels;
            }
            if (input.Length > samples.Length - queued) return false;
            var writeAt = (head + queued) % samples.Length;
            var firstLength = Math.Min(input.Length, samples.Length - writeAt);
            input[..firstLength].CopyTo(samples.AsSpan(writeAt));
            input[firstLength..].CopyTo(samples);
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
            var listening = Volatile.Read(ref Listening);
            if (!listening) fadeRemaining = 0;
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
                var value = listening ? Math.Clamp((dry + delayed * echo) * gain, -0.98f, 0.98f) : 0;
                var channel = i % WaveFormat.Channels;
                if (fadeRemaining > 0)
                {
                    var frame = fadeFrames - (fadeRemaining + WaveFormat.Channels - 1) / WaveFormat.Channels;
                    var blend = (frame + 1f) / fadeFrames;
                    value = fadeStart[channel] * (1 - blend) + value * blend;
                    fadeRemaining--;
                }
                buffer[i] = value;
                lastRendered[channel] = value;
                peak = Math.Max(peak, Math.Abs(value));
            }
            queued -= available;
            if (primed && available < count)
            {
                primed = false;
                fadeRemaining = 0;
                Array.Clear(lastRendered);
                Interlocked.Increment(ref Underruns);
            }
            Volatile.Write(ref OutputPeak, Math.Max(Volatile.Read(ref OutputPeak), peak));
            return count;
        }
    }
}
