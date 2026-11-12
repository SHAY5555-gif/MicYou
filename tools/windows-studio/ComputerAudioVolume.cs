using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace MicYou.Studio;

public sealed class ComputerAudioVolume : IDisposable
{
    private sealed record SessionLevel(float Original, float Applied);

    private readonly Dictionary<string, SessionLevel> levels = new();
    private MMDevice? output;
    private float volume = 1;

    public void SetOutput(string? endpointId)
    {
        if (output?.ID == endpointId) return;
        Restore();
        output?.Dispose();
        output = null;
        levels.Clear();
        if (endpointId == null) return;
        using var devices = new MMDeviceEnumerator();
        output = devices.GetDevice(endpointId);
    }

    public void SetVolume(float value)
    {
        volume = Math.Clamp(value, 0, 1);
        Refresh();
    }

    public void Refresh()
    {
        if (output == null) return;
        var manager = output.AudioSessionManager;
        manager.RefreshSessions();
        var sessions = manager.Sessions;
        var seen = new HashSet<string>();
        for (var index = 0; index < sessions.Count; index++)
        {
            using var session = sessions[index];
            try
            {
                if (session.GetProcessID == Environment.ProcessId || session.State == AudioSessionState.AudioSessionStateExpired)
                    continue;
                var id = session.GetSessionInstanceIdentifier;
                var control = session.SimpleAudioVolume;
                var current = control.Volume;
                seen.Add(id);
                var original = current;
                if (levels.TryGetValue(id, out var previous))
                {
                    original = previous.Original;
                    if (Math.Abs(current - previous.Applied) > 0.001f)
                        original = previous.Applied > 0 ? Math.Clamp(original * current / previous.Applied, 0, 1) : current;
                }
                var adjusted = original * volume;
                if (Math.Abs(current - adjusted) > 0.0001f) control.Volume = adjusted;
                levels[id] = new SessionLevel(original, adjusted);
            }
            catch (COMException) { } // A session may disappear while Windows enumerates it.
            catch (NotSupportedException) { }
        }
        foreach (var id in levels.Keys.Where(id => !seen.Contains(id)).ToArray()) levels.Remove(id);
    }

    private void Restore()
    {
        if (output == null || levels.Count == 0) return;
        try
        {
            var manager = output.AudioSessionManager;
            manager.RefreshSessions();
            var sessions = manager.Sessions;
            for (var index = 0; index < sessions.Count; index++)
            {
                using var session = sessions[index];
                try
                {
                    if (!levels.TryGetValue(session.GetSessionInstanceIdentifier, out var previous)) continue;
                    var control = session.SimpleAudioVolume;
                    if (Math.Abs(control.Volume - previous.Applied) <= 0.001f) control.Volume = previous.Original;
                }
                catch (COMException) { }
                catch (NotSupportedException) { }
            }
        }
        catch (COMException) { } // The selected output can be disconnected before the window closes.
    }

    public void Dispose()
    {
        Restore();
        levels.Clear();
        output?.Dispose();
        output = null;
    }
}
