using System.Text.Json;
using Veyro.Core.Data;
using Veyro.Core.Devices;
using Veyro.Core.Models;

namespace Veyro.Core.Handoff;

public enum ExplorerMenuEntryKind
{
    Device,
    AllConnected,
    Pair
}

/// <summary>One entry of the "Send with Veyro" submenu.</summary>
public sealed record ExplorerMenuEntry(ExplorerMenuEntryKind Kind, string Title, string? DeviceId, bool IsEnabled);

/// <summary>
/// The optional Explorer right-click menu (ADR-039), off by default. Explorer asks for it on every
/// right-click, so everything here is cheap and never throws.
/// </summary>
public static class ExplorerMenu
{
    public const string RootTitle = "Send with Veyro";
    public const string AllConnectedTitle = "Send to all connected phones";
    public const string PairTitle = "Pair a phone...";
    public const string ConnectedSuffix = " (connected)";

    public static IReadOnlyList<ExplorerMenuEntry> Build(IReadOnlyList<SendCandidate> paired)
    {
        ArgumentNullException.ThrowIfNull(paired);
        if (paired.Count == 0) return [new ExplorerMenuEntry(ExplorerMenuEntryKind.Pair, PairTitle, null, true)];

        var entries = paired
            .Select(phone => new ExplorerMenuEntry(
                ExplorerMenuEntryKind.Device,
                phone.IsConnected ? phone.DisplayName + ConnectedSuffix : phone.DisplayName,
                phone.DeviceId,
                true))
            .ToList();
        entries.Add(new ExplorerMenuEntry(ExplorerMenuEntryKind.AllConnected, AllConnectedTitle, null, paired.Any(phone => phone.IsConnected)));
        return entries;
    }

    /// <summary>The hand-off an invoked entry produces. Null when there is nothing to do.</summary>
    public static HandoffRequest? ToRequest(ExplorerMenuEntry entry, IReadOnlyList<string> paths)
    {
        ArgumentNullException.ThrowIfNull(entry);
        ArgumentNullException.ThrowIfNull(paths);
        if (!entry.IsEnabled) return null;
        if (entry.Kind == ExplorerMenuEntryKind.Pair) return new HandoffRequest(HandoffKind.Pair, []);
        if (paths.Count == 0) return null;
        return entry.Kind == ExplorerMenuEntryKind.AllConnected
            ? new HandoffRequest(HandoffKind.ContextMenu, paths, HandoffTargetKind.AllConnected)
            : new HandoffRequest(HandoffKind.ContextMenu, paths, HandoffTargetKind.Device, entry.DeviceId);
    }

    /// <summary>
    /// Whether the menu is turned on, read straight from settings.json without loading the app.
    /// Hidden when the file is missing, unreadable or malformed.
    /// </summary>
    public static bool ReadEnabled(string settingsFilePath)
    {
        try
        {
            var info = new FileInfo(settingsFilePath);
            if (!info.Exists || info.Length is 0 or > 256 * 1024) return false;
            using JsonDocument document = JsonDocument.Parse(File.ReadAllBytes(settingsFilePath));
            return document.RootElement.ValueKind == JsonValueKind.Object &&
                   document.RootElement.TryGetProperty(nameof(AppSettings.ShowInExplorerContextMenu), out JsonElement value) &&
                   value.ValueKind == JsonValueKind.True;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private sealed record TrustEnvelope(string PayloadJson);

    // Names and ids only: the pairing secret and keys are not read into this process.
    private sealed record TrustEntry(string DeviceId, string DisplayName, DateTimeOffset PairedAt);

    /// <summary>
    /// Paired phones for menu labels when Veyro is not running, read from trust.json without the
    /// HMAC check (the key lives in the identity, which this path does not unseal). Labels only:
    /// the main instance looks every id up in its verified trust store before sending anything.
    /// Nothing is connected while Veyro is not running. Empty on any error.
    /// </summary>
    public static IReadOnlyList<SendCandidate> ReadPairedForLabels(string trustFilePath)
    {
        try
        {
            var info = new FileInfo(trustFilePath);
            if (!info.Exists || info.Length is 0 or > 1024 * 1024) return [];
            var envelope = JsonSerializer.Deserialize<TrustEnvelope>(File.ReadAllBytes(trustFilePath));
            if (envelope?.PayloadJson is not { Length: > 0 } payload) return [];
            var entries = JsonSerializer.Deserialize<List<TrustEntry>>(payload) ?? [];
            var peers = entries
                .Where(entry => entry.DeviceId is not null && HandoffCodec.IsPlausibleDeviceId(entry.DeviceId))
                .Select(entry => new TrustedPeer(entry.DeviceId, entry.DisplayName ?? string.Empty, PlatformType.Unknown,
                    string.Empty, string.Empty, false, entry.PairedAt));
            return PairedDeviceDirectory.Build(peers, _ => false)
                .Select(device => new SendCandidate(device.DeviceId, device.DisplayName, false))
                .ToList();
        }
        catch (Exception)
        {
            return [];
        }
    }
}
