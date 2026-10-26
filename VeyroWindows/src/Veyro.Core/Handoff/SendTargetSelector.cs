namespace Veyro.Core.Handoff;

/// <summary>A paired phone that shared or right-clicked files can go to. From the trust store only.</summary>
public sealed record SendCandidate(string DeviceId, string DisplayName, bool IsConnected);

/// <summary>What to do with a hand-off (ADR-039).</summary>
public abstract record SendPlan
{
    private SendPlan() { }

    /// <summary>Send to these phones, in order, without asking.</summary>
    public sealed record SendNow(IReadOnlyList<string> DeviceIds) : SendPlan;

    /// <summary>Show the "Send to" chooser with this phone selected (null: nothing selectable).</summary>
    public sealed record Choose(string? PreselectedDeviceId) : SendPlan;

    /// <summary>Nothing is paired: offer pairing instead.</summary>
    public sealed record NoPhonePaired : SendPlan;

    /// <summary>The request cannot be served; <see cref="Message"/> is shown to the user.</summary>
    public sealed record Unavailable(string Message) : SendPlan;
}

/// <summary>
/// Target rules for the Share sheet and the Explorer menu (ADR-039). Pure, so they are tested
/// without a window. Device ids are compared case-insensitively, as everywhere else.
/// </summary>
public static class SendTargetSelector
{
    public const string NotPairedMessage = "This phone is not paired with Veyro any more.";
    public const string NothingConnectedMessage = "No phone is connected. Open Veyro on the phone and try again.";

    /// <param name="paired">Paired phones with live connection state.</param>
    /// <param name="activeDeviceId">The phone the main window shows, if any.</param>
    /// <param name="sendStraightToOnlyConnected">Setting "Send shared files straight to the connected phone".</param>
    public static SendPlan Plan(
        IReadOnlyList<SendCandidate> paired,
        string? activeDeviceId,
        HandoffTargetKind target,
        string? deviceId,
        bool sendStraightToOnlyConnected)
    {
        ArgumentNullException.ThrowIfNull(paired);
        switch (target)
        {
            case HandoffTargetKind.Device:
                // A phone that is paired but not connected yet is still a valid target; the
                // sender waits briefly for it to connect.
                return Find(paired, deviceId) is { } device
                    ? new SendPlan.SendNow([device.DeviceId])
                    : new SendPlan.Unavailable(NotPairedMessage);

            case HandoffTargetKind.AllConnected:
                if (paired.Count == 0) return new SendPlan.NoPhonePaired();
                var connected = paired.Where(phone => phone.IsConnected).Select(phone => phone.DeviceId).ToList();
                return connected.Count > 0 ? new SendPlan.SendNow(connected) : new SendPlan.Unavailable(NothingConnectedMessage);

            default:
                if (paired.Count == 0) return new SendPlan.NoPhonePaired();
                var online = paired.Where(phone => phone.IsConnected).ToList();
                if (online.Count == 1 && sendStraightToOnlyConnected) return new SendPlan.SendNow([online[0].DeviceId]);
                return new SendPlan.Choose(Preselect(paired, activeDeviceId));
        }
    }

    /// <summary>
    /// The chooser's initial selection: the phone on screen when it is connected, else the first
    /// connected phone, else the phone on screen, else the first paired phone.
    /// </summary>
    public static string? Preselect(IReadOnlyList<SendCandidate> paired, string? activeDeviceId)
    {
        ArgumentNullException.ThrowIfNull(paired);
        SendCandidate? active = Find(paired, activeDeviceId);
        if (active is { IsConnected: true }) return active.DeviceId;
        return (paired.FirstOrDefault(phone => phone.IsConnected) ?? active ?? paired.FirstOrDefault())?.DeviceId;
    }

    /// <summary>What the chooser's answer means. Empty when nothing sendable was chosen.</summary>
    public static IReadOnlyList<string> ResolveChoice(IReadOnlyList<SendCandidate> paired, string? chosenDeviceId, bool allConnected)
    {
        ArgumentNullException.ThrowIfNull(paired);
        if (allConnected) return paired.Where(phone => phone.IsConnected).Select(phone => phone.DeviceId).ToList();
        return Find(paired, chosenDeviceId) is { } device ? [device.DeviceId] : [];
    }

    private static SendCandidate? Find(IReadOnlyList<SendCandidate> paired, string? id)
        => id is null ? null : paired.FirstOrDefault(phone => string.Equals(phone.DeviceId, id, StringComparison.OrdinalIgnoreCase));
}
