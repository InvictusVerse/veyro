using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Status;

/// <summary>The PC's own battery, when it has one.</summary>
public sealed record PcBatteryReading(int Percent, bool Charging);

/// <summary>
/// The PC battery, implemented per platform (Windows: PowerManager). Declared here because
/// <see cref="DeviceStatusService"/> consumes it.
/// </summary>
public interface IPcBatterySource
{
    /// <summary>Raised on any thread when the reading may have changed.</summary>
    event Action? Changed;

    /// <summary>Null when the PC has no battery (desktop) or it cannot be read.</summary>
    PcBatteryReading? Current { get; }
}

public interface IDeviceStatusService
{
    /// <summary>
    /// The peer's reported status or controllability changed. Raised on the session thread;
    /// UI subscribers marshal to their dispatcher.
    /// </summary>
    event Action<string>? Changed;

    /// <summary>The phone's latest <c>devStatus</c>, or null when none since its session opened.</summary>
    LinkMessage.DeviceStatus? GetPhoneStatus(string peerId);

    /// <summary>Connected and advertising <see cref="LinkCapabilities.DeviceStatus"/>.</summary>
    bool CanControl(string peerId);

    /// <summary>Sends <c>devControl</c>. The phone answers with the resulting <c>devStatus</c>.</summary>
    Task SendControlAsync(string peerId, bool? ring = null, string? ringer = null, bool? dnd = null, CancellationToken ct = default);
}

/// <summary>
/// Device status in both directions (04-PROTOCOL.md 6.2, ADR-026): keeps each phone's latest
/// <c>devStatus</c> (cleared when its session closes), sends <c>devControl</c>, and publishes the
/// PC's own battery-only <c>devStatus</c> to capable peers on connect and on change.
/// </summary>
public sealed class DeviceStatusService : IDeviceStatusService, IDisposable
{
    public const string RingerNormal = "normal";
    public const string RingerVibrate = "vibrate";
    public const string RingerSilent = "silent";
    /// <summary>Battery changes smaller than this are not sent (protocol: 1 point).</summary>
    public const int BatteryThreshold = 1;

    private readonly IPeerSessionRegistry _sessions;
    private readonly IPcBatterySource? _battery;
    private readonly object _gate = new();
    private readonly Dictionary<string, LinkMessage.DeviceStatus> _phoneStatus = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, LinkMessage.DeviceStatus> _sentToPeer = new(StringComparer.OrdinalIgnoreCase);

    public event Action<string>? Changed;

    public DeviceStatusService(IPeerSessionRegistry sessions, IPcBatterySource? battery)
    {
        _sessions = sessions;
        _battery = battery;
        _sessions.DeviceStatusReceived += OnDeviceStatus;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
        if (_battery is not null) _battery.Changed += OnBatteryChanged;
    }

    public static bool IsRingerMode(string? value)
        => value is RingerNormal or RingerVibrate or RingerSilent;

    public LinkMessage.DeviceStatus? GetPhoneStatus(string peerId)
    {
        lock (_gate) return _phoneStatus.GetValueOrDefault(peerId);
    }

    public bool CanControl(string peerId)
        => _sessions.IsConnected(peerId) && IsCapable(peerId);

    public async Task SendControlAsync(string peerId, bool? ring = null, string? ringer = null, bool? dnd = null, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        if (ring is null && ringer is null && dnd is null)
            throw new ArgumentException("Nothing to control.");
        if (ringer is not null && !IsRingerMode(ringer))
            throw new ArgumentException("Unknown ringer mode.", nameof(ringer));
        if (!_sessions.IsConnected(peerId))
            throw new InvalidOperationException("The phone is not connected.");
        if (!IsCapable(peerId))
            throw new InvalidOperationException("The phone does not support device status.");
        await _sessions.SendAsync(peerId, new LinkMessage.DeviceControl(ring, ringer, dnd), ct).ConfigureAwait(false);
    }

    public void Dispose()
    {
        _sessions.DeviceStatusReceived -= OnDeviceStatus;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
        if (_battery is not null) _battery.Changed -= OnBatteryChanged;
    }

    private bool IsCapable(string peerId)
        => (_sessions.GetPeerCapabilities(peerId) & LinkCapabilities.DeviceStatus) != 0;

    private void OnDeviceStatus(string peerId, LinkMessage.DeviceStatus status)
    {
        // Untrusted input: out-of-contract fields become "unknown" rather than wrong.
        var sanitized = new LinkMessage.DeviceStatus(
            status.Battery is >= 0 and <= 100 ? status.Battery : null,
            status.Charging,
            IsRingerMode(status.Ringer) ? status.Ringer : null,
            status.Dnd);
        lock (_gate) _phoneStatus[peerId] = sanitized;
        Changed?.Invoke(peerId);
    }

    private void OnSessionClosed(string peerId)
    {
        lock (_gate)
        {
            _phoneStatus.Remove(peerId);
            _sentToPeer.Remove(peerId);
        }
        Changed?.Invoke(peerId);
    }

    private void OnCapabilitiesChanged(string peerId)
    {
        Changed?.Invoke(peerId);
        if (IsCapable(peerId)) Publish(peerId);
    }

    private void OnBatteryChanged()
    {
        foreach (string peerId in _sessions.GetConnectedPeers())
        {
            if (IsCapable(peerId)) Publish(peerId);
        }
    }

    /// <summary>Sends the PC battery to one peer unless it already has an equivalent reading.</summary>
    private void Publish(string peerId)
    {
        PcBatteryReading? reading;
        try { reading = _battery?.Current; }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading PC battery failed", error);
            return;
        }
        if (reading is null) return; // no battery: send nothing

        var status = new LinkMessage.DeviceStatus(Math.Clamp(reading.Percent, 0, 100), reading.Charging);
        lock (_gate)
        {
            if (_sentToPeer.TryGetValue(peerId, out var last) &&
                last.Charging == status.Charging &&
                Math.Abs((last.Battery ?? -100) - status.Battery!.Value) < BatteryThreshold)
                return;
            _sentToPeer[peerId] = status;
        }
        _ = SendAsync(peerId, status);
    }

    private async Task SendAsync(string peerId, LinkMessage.DeviceStatus status)
    {
        try { await _sessions.SendAsync(peerId, status).ConfigureAwait(false); }
        catch (Exception error)
        {
            lock (_gate)
            {
                if (_sentToPeer.TryGetValue(peerId, out var last) && ReferenceEquals(last, status)) _sentToPeer.Remove(peerId);
            }
            VeyroTrace.Error("Sending PC device status failed", error);
        }
    }
}
