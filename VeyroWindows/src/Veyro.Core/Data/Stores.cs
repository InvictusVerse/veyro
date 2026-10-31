using System.Text.Json;
using Veyro.Core.Models;
using Veyro.Core.Notifications;

namespace Veyro.Core.Data;

public sealed record AppSettings(
    string DeviceName,
    bool IsDiscoverable,
    string DownloadDirectory,
    bool AutoStartOnLogin = false,
    // ADR-047: closing the window keeps Veyro running in the tray, on by default; turned off in
    // Settings or from the tray icon's menu.
    bool MinimizeToTrayOnClose = true,
    // ADR-029: the Bluetooth phone chosen for call audio and the Veyro peer it belongs to.
    string? BluetoothCallTransportId = null,
    string? BluetoothCallPeerId = null,
    // ADR-030: first-run onboarding finished. Missing in older settings files, so it defaults to
    // false; startup marks installs that already trust a peer as complete.
    bool OnboardingCompleted = false,
    // ADR-036/047: set once the user changed the tray switch. Without it the stored value was
    // never chosen, so loading takes the current default (on).
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
    // Veyro asked for the firewall rule at startup once (whatever the answer): it never asks on
    // its own again. Only the pairing network check's "Add rule", pressed by the user, asks then.
    bool FirewallPromptAsked = false,
    // ADR-047: "Start with Windows" is turned on once, on the first start of a build that has
    // it; after that only the user changes it (Settings, the tray menu, or Windows' Startup apps).
    bool AutoStartDefaultApplied = false,
    // ADR-047: the one-time "Veyro is still running in the tray" note was shown.
    bool TrayHintShown = false,
    // ADR-063: phone apps that sent a notification and what each does on this PC (Show, Feed
    // only, Hide), for every paired phone. Package names and labels only, never contents.
    IReadOnlyList<NotificationAppRule>? NotificationApps = null,
    // ADR-066: screen mirroring quality, chosen on this PC only (the phone only allows capture).
    // "battery", "balanced" (default), "high" or "4k".
    string MirrorQualityPreset = "balanced",
    // ADR-066: ask for the phone's internal sound (never its microphone).
    bool MirrorAudio = true,
    // ADR-066: ask the phone for a hardware encoder (on by default; software only as a fallback).
    bool MirrorHardwareEncode = true,
    // Do not disturb: phone notifications and messages still arrive in Veyro but pop up no toast.
    // Calls and file requests still show. Off by default; the tray, the Notifications page and the
    // mirror window turn it on.
    bool NotificationPopupsPaused = false,
    // The same, automatically while a phone's screen is mirrored (a film in full screen). On.
    bool QuietWhileMirroring = true,
    // ADR-067: phone as webcam, chosen on this PC: camera id (null: the back camera), size from
    // that camera's list (1280 x 720 by default), frame rate, and a mirror image (off).
    string? WebcamCamera = null,
    int WebcamWidth = 1280,
    int WebcamHeight = 720,
    int WebcamFps = 30,
    bool WebcamMirror = false,
    // ADR-074: "modern" (every phone action visible on Home, floating controls in the mirror and
    // camera windows) or "classic" (the earlier layout with the More menu and a toolbar).
    string UiStyle = AppUiStyle.Modern
);

/// <summary>ADR-074: the two PC layouts, switched in Settings &gt; General.</summary>
public static class AppUiStyle
{
    public const string Modern = "modern";
    public const string Classic = "classic";

    public static bool IsClassic(AppSettings settings) => settings.UiStyle == Classic;
}

/// <summary>Whether a phone notification or message may pop up a toast now (Do not disturb).</summary>
public static class NotificationQuiet
{
    public static bool IsQuiet(AppSettings settings, bool mirroring) =>
        settings.NotificationPopupsPaused || (settings.QuietWhileMirroring && mirroring);
}

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

    /// <summary>
    /// The user removed the phone here and it could not be told (ADR-057). Removes it like
    /// <see cref="RemoveAsync"/>, but keeps its pairing secret for a while so the phone can be
    /// told with an authenticated <c>unpair</c> when it next dials in. Used for nothing else.
    /// </summary>
    Task RemoveAndNotifyAsync(string deviceId) => RemoveAsync(deviceId);

    /// <summary>
    /// The kept pairing secret of a phone removed here and not yet told, or null (ADR-057). A fresh
    /// array the caller owns: LinkServer zeroes it after use (review issue 12).
    /// </summary>
    byte[]? GetPendingUnpairSecret(string deviceId) => null;

    /// <summary>The phone was told, or no longer holds the old pairing: drop its kept secret.</summary>
    Task ClearPendingUnpairAsync(string deviceId) => Task.CompletedTask;

    /// <summary>
    /// ADR-059: the phone announced <paramref name="name"/> for itself (already validated).
    /// Replaces the stored name and keeps a nickname. False for an unknown phone or the same name.
    /// </summary>
    async Task<bool> SetAnnouncedNameAsync(string deviceId, string name)
    {
        if (GetPeer(deviceId) is not { } peer || peer.DisplayName == name) return false;
        await UpsertAsync(peer with { DisplayName = name }).ConfigureAwait(false);
        return true;
    }
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
                // ADR-047: tray mode is the default now; a value nobody chose takes the default.
                if (loaded is not null) return loaded.TrayModeChosen ? loaded : loaded with { MinimizeToTrayOnClose = true };
            }
        }
        catch
        {
            // Fall back to defaults
        }

        return new AppSettings(
            DeviceName: Environment.MachineName,
            IsDiscoverable: true,
            DownloadDirectory: DefaultDownloadDirectory);
    }

    /// <summary>Where received files go until the user picks another folder: Downloads\Veyro.</summary>
    public static string DefaultDownloadDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        "Downloads",
        "Veyro");

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
