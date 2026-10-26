using System.Text.Json;
using Veyro.Core.Models;

namespace Veyro.Core.Data;

public sealed record AppSettings(
    string DeviceName,
    bool IsDiscoverable,
    string DownloadDirectory,
    bool AutoStartOnLogin = false,
    bool MinimizeToTrayOnClose = true
);

public interface ISettingsStore
{
    AppSettings Current { get; }
    event Action<AppSettings>? SettingsChanged;
    Task UpdateAsync(Func<AppSettings, AppSettings> transform);
}

public interface ITrustStore
{
    IReadOnlyList<TrustedPeer> GetTrustedPeers();
    TrustedPeer? GetPeer(string deviceId);
    Task UpsertAsync(TrustedPeer peer);
    Task RemoveAsync(string deviceId);
}

public interface ITransferHistoryStore
{
    IReadOnlyList<TransferRecord> GetHistory();
    Task UpsertAsync(TransferRecord record);
    Task ClearAsync();
}

public sealed class FileSettingsStore : ISettingsStore
{
    private readonly string _filePath;
    public AppSettings Current { get; private set; }
    public event Action<AppSettings>? SettingsChanged;

    public FileSettingsStore(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        _filePath = Path.Combine(dataDirectory, "settings.json");
        Current = LoadOrDefault();
    }

    private AppSettings LoadOrDefault()
    {
        try
        {
            if (File.Exists(_filePath))
            {
                string json = File.ReadAllText(_filePath);
                var loaded = JsonSerializer.Deserialize<AppSettings>(json);
                if (loaded is not null) return loaded;
            }
        }
        catch
        {
            // Fall back to defaults
        }

        string defaultDownloads = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads",
            "Veyro");

        return new AppSettings(
            DeviceName: Environment.MachineName,
            IsDiscoverable: true,
            DownloadDirectory: defaultDownloads);
    }

    public async Task UpdateAsync(Func<AppSettings, AppSettings> transform)
    {
        Current = transform(Current);
        string tempPath = _filePath + ".tmp";
        string json = JsonSerializer.Serialize(Current, new JsonSerializerOptions { WriteIndented = true });
        await File.WriteAllTextAsync(tempPath, json).ConfigureAwait(false);
        File.Move(tempPath, _filePath, overwrite: true);
        SettingsChanged?.Invoke(Current);
    }
}
