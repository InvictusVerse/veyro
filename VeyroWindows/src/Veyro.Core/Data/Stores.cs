using System.Text.Json;
using Veyro.Core.Models;

namespace Veyro.Core.Data;

public sealed record AppSettings(
    string DeviceName,
    bool IsDiscoverable,
    string DownloadDirectory,
    bool AutoStartOnLogin = false,
    // Tray mode is off until the user turns it on (ADR-036). Older builds wrote true by default.
    bool MinimizeToTrayOnClose = false,
    // ADR-029: the Bluetooth phone chosen for call audio and the Veyro peer it belongs to.
    string? BluetoothCallTransportId = null,
    string? BluetoothCallPeerId = null,
    // ADR-030: first-run onboarding finished. Missing in older settings files, so it defaults to
    // false; startup marks installs that already trust a peer as complete.
    bool OnboardingCompleted = false,
    // ADR-036: set once the user changed the tray switch. Files without it carry the old default
    // (true), which nobody chose, so loading them turns tray mode off.
    bool TrayModeChosen = false,
    // ADR-039: a Share sheet hand-off goes straight to the phone when exactly one is connected.
    bool SendSharedFilesStraightToConnectedPhone = true,
    // ADR-039: "Send with Veyro" in the Explorer right-click menu. Off until the user turns it on;
    // the menu server reads this property from settings.json by name.
    bool ShowInExplorerContextMenu = false,
    // ADR-040: dial a paired phone that is on the network but did not reconnect by itself.
    bool ReconnectAutomatically = true,
    // ADR-043: what this PC copies goes to the phones' virtual clipboard (on by default).
    bool SendClipboardToPhones = true,
    // ADR-043: include copied images too (off by default: they are large).
    bool SyncClipboardImages = false,
    // The user said no to the firewall prompt at startup: Veyro never asks on its own again. The
    // pairing network check's "Add rule" still asks, and clears this when it succeeds.
    bool FirewallPromptDeclined = false
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
                if (loaded is not null) return loaded.TrayModeChosen ? loaded : loaded with { MinimizeToTrayOnClose = false };
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
