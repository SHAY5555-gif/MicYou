using System.Threading.Channels;
using NAudio.Wave;

namespace MicYou.Studio;

// The recording receives the original WASAPI bytes, before monitoring gain or
// echo. Disk work runs separately from the capture callback.
public sealed class LosslessRecording
{
    private readonly Channel<byte[]> chunks = Channel.CreateBounded<byte[]>(256);
    private readonly Task completion;
    private int finishing;
    public string Path { get; }
    public DateTime StartedAt { get; } = DateTime.Now;
    public string? Error { get; private set; }

    public LosslessRecording(string folder, WaveFormat format)
    {
        Directory.CreateDirectory(folder);
        Path = System.IO.Path.Combine(folder, $"MicYou-{DateTime.Now:yyyyMMdd-HHmmss}-{Guid.NewGuid():N}.wav");
        var file = new FileStream(Path, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.Read);
        WaveFileWriter writer;
        try { writer = new WaveFileWriter(file, format); }
        catch { file.Dispose(); throw; }
        completion = Task.Run(async () =>
        {
            try
            {
                var lastFlush = Environment.TickCount64;
                await foreach (var chunk in chunks.Reader.ReadAllAsync())
                {
                    writer.Write(chunk);
                    if (Environment.TickCount64 - lastFlush >= 1000)
                    {
                        writer.Flush();
                        lastFlush = Environment.TickCount64;
                    }
                }
            }
            catch (Exception exception) { Error = exception.Message; }
            finally
            {
                try { writer.Dispose(); }
                catch (Exception exception) { Error ??= exception.Message; }
                finally { file.Dispose(); }
            }
        });
    }

    public void Append(ReadOnlySpan<byte> source, bool silent)
    {
        if (Volatile.Read(ref finishing) != 0) return;
        var copy = new byte[source.Length];
        if (!silent) source.CopyTo(copy);
        if (!chunks.Writer.TryWrite(copy))
        {
            Error = "ההקלטה נעצרה כי הדיסק לא עמד בקצב הכתיבה";
            FinishWriting();
        }
    }

    private void FinishWriting()
    {
        Interlocked.Exchange(ref finishing, 1);
        chunks.Writer.TryComplete();
    }

    public async Task FinishAsync()
    {
        FinishWriting();
        await completion;
    }
}
