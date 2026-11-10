using System.Text.Json;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace MicYou.Studio;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--inventory"))
        {
            Console.WriteLine(JsonSerializer.Serialize(new
            {
                inputs = StudioSettings.Endpoints(DataFlow.Capture),
                outputs = StudioSettings.Endpoints(DataFlow.Render)
            }));
            return 0;
        }
        if (args.Contains("--self-test")) return SelfTest.Run(args.Last());
        if (args.Contains("--audio-check")) return AudioCheck.Run(args.Last(), args.Contains("--minimum")).GetAwaiter().GetResult();
        if (args.Contains("--render"))
        {
            Application.SetHighDpiMode(HighDpiMode.DpiUnaware);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using var preview = new StudioForm(false);
            preview.Render(args.Last());
            return 0;
        }
        ApplicationConfiguration.Initialize();
        using var mutex = new Mutex(true, "Local\\MicYouStudioWindow", out var created);
        if (!created) return 0;
        Application.Run(new StudioForm(args.Contains("--start")));
        return 0;
    }
}

internal static class AudioCheck
{
    public static async Task<int> Run(string folder, bool minimum)
    {
        try
        {
            var saved = StudioSettings.Load();
            var inputs = StudioSettings.Endpoints(DataFlow.Capture);
            var outputs = StudioSettings.Endpoints(DataFlow.Render);
            var input = inputs.FirstOrDefault(x => x.Id == saved.InputId) ?? inputs.First(x => x.Name.Contains("Wireless Mic Rx"));
            var output = outputs.FirstOrDefault(x => x.Id == saved.OutputId) ?? outputs.First(x => x.Name.Contains("EP-HDMI-RX"));
            await using var session = new LiveAudioSession();
            var errors = new List<string>();
            session.Failed += message => { lock (errors) errors.Add(message); };
            session.Start(input.Id, output.Id, false, new StudioSettings { ListeningVolume = 0, MinimumLatency = minimum });
            session.StartRecording(folder);
            var peak = 0f;
            for (var count = 0; count < 50; count++)
            {
                await Task.Delay(100);
                peak = Math.Max(peak, Volatile.Read(ref session.InputPeak));
            }
            await session.StopRecordingAsync();
            using var recording = new WaveFileReader(session.LastRecording!);
            Console.WriteLine(JsonSerializer.Serialize(new
            {
                input = input.Name,
                output = output.Name,
                session.LowLatency,
                session.CapturePeriodMs,
                session.OutputPeriodMs,
                inputPeak = peak,
                session.MaximumInputPeak,
                session.CapturedSamples,
                minimum,
                bufferedMs = session.Monitor!.QueuedMilliseconds,
                underruns = session.Monitor.Underruns,
                recording = session.LastRecording,
                recordingBytes = recording.Length,
                format = recording.WaveFormat.ToString(),
                encoding = recording.WaveFormat.Encoding.ToString(),
                subFormat = (recording.WaveFormat as WaveFormatExtensible)?.SubFormat.ToString(),
                errors
            }));
            return errors.Count == 0 && recording.Length > 0 ? 0 : 1;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }
}

internal static class SelfTest
{
    public static int Run(string folder)
    {
        try
        {
            var format = WaveFormat.CreateIeeeFloatWaveFormat(48000, 2);
            var original = Enumerable.Range(0, 9600).Select(i => MathF.Sin(i * 0.023f) * 0.2f).ToArray();
            var bytes = new byte[original.Length * sizeof(float)];
            Buffer.BlockCopy(original, 0, bytes, 0, bytes.Length);
            var file = new LosslessRecording(folder, format);
            file.Append(bytes, false);
            file.FinishAsync().GetAwaiter().GetResult();
            using var reader = new WaveFileReader(file.Path);
            var restored = new byte[bytes.Length];
            var count = reader.Read(restored, 0, restored.Length);
            if (count != bytes.Length || !bytes.SequenceEqual(restored))
                throw new Exception("The recorded WAV did not preserve the input bytes");
            var monitor = new LiveMonitor(48000, 2) { Volume = 1 };
            monitor.Push(original);
            var received = new float[original.Length];
            monitor.Read(received, 0, received.Length);
            if (!original.SequenceEqual(received)) throw new Exception("Dry monitoring changed the sample sequence");
            monitor.Listening = false;
            monitor.Push(original);
            monitor.Read(received, 0, received.Length);
            if (received.Any(value => value != 0)) throw new Exception("Mute did not silence monitoring");
            if (monitor.Push(new float[48000])) throw new Exception("The bounded queue accepted an overflow");
            var fast = new LiveMonitor(48000, 2, 0) { Volume = 1 };
            var smallPacket = original.AsSpan(0, 96).ToArray();
            fast.Push(smallPacket);
            var immediate = new float[96];
            fast.Read(immediate, 0, immediate.Length);
            if (!smallPacket.SequenceEqual(immediate)) throw new Exception("Minimum latency waited for extra queued audio");
            Console.WriteLine(JsonSerializer.Serialize(new { passed = true, wav = file.Path, bytes = count }));
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }
}
