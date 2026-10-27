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

/// <summary>
/// The PC's own Wi-Fi signal for the phone's PC card (ADR-038), implemented per platform. Declared
/// here because <see cref="DeviceStatusService"/> consumes it.
/// </summary>
public interface IPcNetworkSource
{
    /// <summary>Raised on any thread when the reading may have changed.</summary>
    event Action? Changed;

    /// <summary>Wi-Fi signal 0..4 while on Wi-Fi, null when not on Wi-Fi or unknown.</summary>
    int? WifiLevel { get; }
}

public interface IDeviceStatusService
{
    /// <summary>
    /// The peer's reported status or controllability changed. Raised on the session thread;
    /// UI subscribers marshal to their dispatcher.
    /// </summary>
    event Action<string>? Changed;

    /// <summary>The phone's latest <c>devStatus</c>, or null when none since its session opened.</summary>
    PeerMessage.DeviceStatus? GetPhoneStatus(string peerId);

    /// <summary>Connected and advertising <see cref="PeerCapabilities.DeviceStatus"/>.</summary>
    bool CanControl(string peerId);

    /// <summary>Sends <c>devControl</c>. The phone answers with the resulting <c>devStatus</c>.</summary>
    Task SendControlAsync(string peerId, bool? ring = null, string? ringer = null, bool? dnd = null, CancellationToken ct = default);
}

/// <summary>
/// Device status in both directions (04-PROTOCOL.md 6.2 and 6.2.1, ADR-026, ADR-038): keeps each
/// phone's latest <c>devStatus</c> including radio fields (cleared when its session closes), sends
/// <c>devControl</c>, and publishes the PC's own battery and Wi-Fi <c>devStatus</c> to capable
/// peers on connect and on change.
/// </summary>
public sealed class DeviceStatusService : IDeviceStatusService, IDisposable
{
    public const string RingerNormal = "normal";
    public const string RingerVibrate = "vibrate";
    public const string RingerSilent = "silent";
    /// <summary>Battery changes smaller than this are not sent (protocol: 1 point).</summary>
    public const int BatteryThreshold = 1;
    /// <summary>Longest carrier name kept from the phone; longer is cut (untrusted input).</summary>
    public const int MaxCarrierLength = 64;

    private readonly IPeerSessionRegistry _sessions;
    private readonly IPcBatterySource? _battery;
    private readonly IPcNetworkSource? _network;
    private readonly object _gate = new();
    private readonly Dictionary<string, PeerMessage.DeviceStatus> _phoneStatus = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, PeerMessage.DeviceStatus> _sentToPeer = new(StringComparer.OrdinalIgnoreCase);

    public event Action<string>? Changed;

    public DeviceStatusService(IPeerSessionRegistry sessions, IPcBatterySource? battery, IPcNetworkSource? network = null)
    {
        _sessions = sessions;
        _battery = battery;
        _network = network;
        _sessions.DeviceStatusReceived += OnDeviceStatus;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
        if (_battery is not null) _battery.Changed += OnBatteryChanged;
        if (_network is not null) _network.Changed += OnBatteryChanged;
    }

    public static bool IsRingerMode(string? value)
        => value is RingerNormal or RingerVibrate or RingerSilent;

    public PeerMessage.DeviceStatus? GetPhoneStatus(string peerId)
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
        await _sessions.SendAsync(peerId, new PeerMessage.DeviceControl(ring, ringer, dnd), ct).ConfigureAwait(false);
    }

    public void Dispose()
    {
        _sessions.DeviceStatusReceived -= OnDeviceStatus;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
        if (_battery is not null) _battery.Changed -= OnBatteryChanged;
        if (_network is not null) _network.Changed -= OnBatteryChanged;
    }

    private bool IsCapable(string peerId)
        => (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.DeviceStatus) != 0;

    private void OnDeviceStatus(string peerId, PeerMessage.DeviceStatus status)
    {
        // Untrusted input: out-of-contract fields become "unknown" rather than wrong.
        // The carrier is user data: kept for the UI, never logged.
        string? carrier = string.IsNullOrWhiteSpace(status.Carrier) ? null : status.Carrier.Trim();
        if (carrier is { Length: > MaxCarrierLength }) carrier = carrier[..MaxCarrierLength];
        var sanitized = new PeerMessage.DeviceStatus(
            status.Battery is >= 0 and <= 100 ? status.Battery : null,
            status.Charging,
            IsRingerMode(status.Ringer) ? status.Ringer : null,
            status.Dnd,
            Wifi: status.Wifi is >= -1 and <= 4 ? status.Wifi : null,
            Cell: status.Cell is >= -1 and <= 4 ? status.Cell : null,
            Carrier: carrier,
            Bluetooth: status.Bluetooth,
            MobileData: status.MobileData);
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

    /// <summary>
    /// Sends the PC battery and Wi-Fi (ADR-038) to one peer unless it already has an equivalent
    /// reading. Nothing is sent when neither is known.
    /// </summary>
    private void Publish(string peerId)
    {
        PcBatteryReading? reading;
        int? wifi;
        try
        {
            reading = _battery?.Current;
            wifi = _network?.WifiLevel;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading PC status failed", error);
            return;
        }
        if (wifi is not (>= 0 and <= 4)) wifi = null;
        if (reading is null && wifi is null) return; // nothing known: send nothing

        var status = new PeerMessage.DeviceStatus(
            reading is null ? null : Math.Clamp(reading.Percent, 0, 100),
            reading?.Charging,
            Wifi: wifi);
        lock (_gate)
        {
            if (_sentToPeer.TryGetValue(peerId, out var last) &&
                last.Charging == status.Charging &&
                last.Wifi == status.Wifi &&
                (last.Battery is null) == (status.Battery is null) &&
                Math.Abs((last.Battery ?? 0) - (status.Battery ?? 0)) < BatteryThreshold)
                return;
            _sentToPeer[peerId] = status;
        }
        _ = SendAsync(peerId, status);
    }

    private async Task SendAsync(string peerId, PeerMessage.DeviceStatus status)
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
