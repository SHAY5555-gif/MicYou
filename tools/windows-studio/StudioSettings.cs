using System.Text.Json;
using NAudio.CoreAudioApi;

namespace MicYou.Studio;

public sealed record AudioEndpoint(string Id, string Name)
{
    public override string ToString() => Name;
}

public sealed class StudioSettings
{
    public string? InputId { get; set; }
    public string? OutputId { get; set; }
    public string RecordingFolder { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.MyMusic), "MicYou Recordings");
    public float MicrophoneGain { get; set; } = 1;
    public float ListeningVolume { get; set; } = 0.5f;
    public float EchoLevel { get; set; }
    public int EchoDelayMs { get; set; } = 160;

    private static string ConfigPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "micyou", "studio.json");

    public static StudioSettings Load()
    {
        if (!File.Exists(ConfigPath)) return new();
        return JsonSerializer.Deserialize<StudioSettings>(File.ReadAllText(ConfigPath)) ?? new();
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        var temporary = ConfigPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temporary, ConfigPath, true);
    }

    public static List<AudioEndpoint> Endpoints(DataFlow flow)
    {
        using var enumerator = new MMDeviceEnumerator();
        var endpoints = new List<AudioEndpoint>();
        foreach (var device in enumerator.EnumerateAudioEndPoints(flow, DeviceState.Active))
        {
            using (device) endpoints.Add(new(device.ID, device.FriendlyName));
        }
        return endpoints;
    }
}
