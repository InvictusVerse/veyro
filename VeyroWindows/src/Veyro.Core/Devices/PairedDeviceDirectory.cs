using Veyro.Core.Models;

namespace Veyro.Core.Devices;

/// <summary>
/// One paired phone in the device switcher: identity from the trust store, connection state from
/// the live session registry. Never built from discovery records, which are untrusted.
/// </summary>
public sealed record PairedDeviceEntry(
    string DeviceId,
    string DisplayName,
    PlatformType Platform,
    string PublicKeyBase64,
    bool IsConnected);

/// <summary>
/// Rules for the sidebar device switcher (multi-device). Pure so they are testable without WinUI:
/// the list comes from trusted peers only, and the selection follows a phone that connects when
/// the phone on screen is not connected.
/// </summary>
public static class PairedDeviceDirectory
{
    public const string UnnamedDevice = "Unnamed device";

    /// <summary>Trusted peers in pairing order, de-duplicated by device id, with live connection state.</summary>
    public static IReadOnlyList<PairedDeviceEntry> Build(IEnumerable<TrustedPeer> trusted, Func<string, bool> isConnected)
    {
        ArgumentNullException.ThrowIfNull(trusted);
        ArgumentNullException.ThrowIfNull(isConnected);
        return trusted
            .Where(peer => !string.IsNullOrWhiteSpace(peer.DeviceId))
            .GroupBy(peer => peer.DeviceId, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.Last())
            .OrderBy(peer => peer.PairedAt)
            .Select(peer => new PairedDeviceEntry(
                peer.DeviceId,
                // ADR-059: a nickname given on this PC wins over the name the phone gives itself.
                string.IsNullOrWhiteSpace(peer.ShownName) ? UnnamedDevice : peer.ShownName.Trim(),
                peer.Platform,
                peer.PublicKeyBase64,
                isConnected(peer.DeviceId)))
            .ToList();
    }

    /// <summary>
    /// After the list is rebuilt: keep the shown phone while it is still paired; otherwise prefer a
    /// connected phone, then the first one. Null when nothing is paired.
    /// </summary>
    public static string? SelectAfterReload(IReadOnlyList<PairedDeviceEntry> devices, string? currentId)
    {
        ArgumentNullException.ThrowIfNull(devices);
        if (Find(devices, currentId) is { } current) return current.DeviceId;
        return (devices.FirstOrDefault(device => device.IsConnected) ?? devices.FirstOrDefault())?.DeviceId;
    }

    /// <summary>
    /// A phone's session opened: show it when nothing is shown or the shown phone is not connected.
    /// A connected phone on screen is never replaced, so the user's choice is kept.
    /// </summary>
    public static string? SelectAfterSessionOpened(IReadOnlyList<PairedDeviceEntry> devices, string? currentId, string openedId)
    {
        ArgumentNullException.ThrowIfNull(devices);
        PairedDeviceEntry? current = Find(devices, currentId);
        PairedDeviceEntry? opened = Find(devices, openedId);
        if (opened is null) return current?.DeviceId ?? SelectAfterReload(devices, null);
        if (current is null || !current.IsConnected) return opened.DeviceId;
        return current.DeviceId;
    }

    /// <summary>Cycles through the list (mouse wheel over the phone). Delta sign gives the direction.</summary>
    public static string? Step(IReadOnlyList<PairedDeviceEntry> devices, string? currentId, int delta)
    {
        ArgumentNullException.ThrowIfNull(devices);
        if (devices.Count == 0) return null;
        int index = -1;
        for (int i = 0; i < devices.Count; i++)
        {
            if (string.Equals(devices[i].DeviceId, currentId, StringComparison.OrdinalIgnoreCase))
            {
                index = i;
                break;
            }
        }
        if (index < 0) return devices[0].DeviceId;
        if (delta == 0) return devices[index].DeviceId;
        int step = delta > 0 ? -1 : 1; // wheel up moves to the previous phone
        return devices[(index + step + devices.Count) % devices.Count].DeviceId;
    }

    private static PairedDeviceEntry? Find(IReadOnlyList<PairedDeviceEntry> devices, string? id)
        => id is null ? null : devices.FirstOrDefault(device => string.Equals(device.DeviceId, id, StringComparison.OrdinalIgnoreCase));
}
