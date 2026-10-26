// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Converters/Converters.cs (BatteryStatusToIconConverter, RingerModeToIconConverter), GPL-3.0.
// Modified for Veyro.

using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// The active phone's battery, ringer and DND for the Control Center (ADR-026), plus the
/// ring / ringer / DND requests. Shown state is only ever what the phone reported; a request
/// changes nothing locally until the phone's next <c>devStatus</c> confirms it.
/// </summary>
public sealed class DeviceStatusViewModel : BaseViewModel
{
    /// <summary>The phone stops ringing on its own after 30 s (04-PROTOCOL.md 6.2).</summary>
    private static readonly TimeSpan RingTimeout = TimeSpan.FromSeconds(30);
    /// <summary>Same order as Sefirah's ringer selector: Silent, Vibrate, Ring.</summary>
    private static readonly string[] RingerModes =
        [DeviceStatusService.RingerSilent, DeviceStatusService.RingerVibrate, DeviceStatusService.RingerNormal];

    private readonly IDeviceStatusService _status;
    private readonly DispatcherQueue? _dispatcher;
    private readonly DispatcherQueueTimer? _ringTimer;
    private string? _peerId;
    private LinkMessage.DeviceStatus? _reported;
    private bool _isRinging;
    private string _statusText = string.Empty;

    public DeviceStatusViewModel(IDeviceStatusService status, IDeviceManager deviceManager)
    {
        _status = status;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;
        if (_dispatcher is not null)
        {
            _ringTimer = _dispatcher.CreateTimer();
            _ringTimer.Interval = RingTimeout;
            _ringTimer.IsRepeating = false;
            _ringTimer.Tick += (_, _) => SetRinging(false);
        }

        _status.Changed += peerId => Post(() =>
        {
            if (string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase)) Sync();
        });
        deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            _peerId = device?.DeviceId;
            SetRinging(false);
            Sync();
        });
        Sync();
    }

    public bool CanControl => _peerId is not null && _status.CanControl(_peerId);

    /// <summary>True only once the connected phone has reported a battery level.</summary>
    public bool HasBattery => _reported?.Battery is not null;
    public string BatteryText => _reported?.Battery is { } battery ? $"{battery}%" : string.Empty;
    public bool IsCharging => _reported?.Charging == true;

    /// <summary>Battery glyph for the reported level, same table as Sefirah's BatteryStatusToIconConverter.</summary>
    public string BatteryGlyph => _reported?.Battery is { } level ? BatteryGlyphFor(level, IsCharging) : string.Empty;

    private static string BatteryGlyphFor(int level, bool charging)
    {
        int tens = Math.Clamp(level / 10, 0, 10);
        if (charging)
            return tens switch { 10 => "\uEA93", 9 => "\uE83E", _ => ((char)(0xE85A + tens)).ToString() };
        return tens == 10 ? "\uE83F" : ((char)(0xE850 + tens)).ToString();
    }
    public string BatteryLabel => _reported?.Battery is { } battery
        ? $"Phone battery {battery} percent{(IsCharging ? ", charging" : string.Empty)}"
        : "Phone battery not reported";

    /// <summary>Index into Silent / Vibrate / Ring, or -1 when the phone has not reported it.</summary>
    public int RingerIndex => Array.IndexOf(RingerModes, _reported?.Ringer ?? string.Empty);
    public bool HasRinger => RingerIndex >= 0;
    public bool HasStatusRow => HasBattery || HasRinger || IsDndOn;

    /// <summary>Connected and status-capable, but no devStatus yet: say so instead of showing a value.</summary>
    public bool IsWaitingForStatus => CanControl && !HasStatusRow;
    public string RingerGlyph => _reported?.Ringer switch
    {
        DeviceStatusService.RingerNormal => "\uEA8F",
        DeviceStatusService.RingerVibrate => "\uE877",
        DeviceStatusService.RingerSilent => "\uE7ED",
        _ => string.Empty
    };
    public bool IsRingerSilent => _reported?.Ringer == DeviceStatusService.RingerSilent;
    public bool IsRingerVibrate => _reported?.Ringer == DeviceStatusService.RingerVibrate;
    public bool IsRingerNormal => _reported?.Ringer == DeviceStatusService.RingerNormal;
    public string RingerLabel => _reported?.Ringer switch
    {
        DeviceStatusService.RingerNormal => "Ringer on",
        DeviceStatusService.RingerVibrate => "Vibrate",
        DeviceStatusService.RingerSilent => "Silent",
        _ => "Ringer unknown"
    };

    public bool IsDndOn => _reported?.Dnd == true;
    public string DndLabel => _reported?.Dnd switch
    {
        true => "Do not disturb is on",
        false => "Do not disturb is off",
        null => "Do not disturb unknown"
    };

    public bool IsRinging => _isRinging;
    public string RingButtonText => _isRinging ? "Stop ringing" : "Ring phone";

    public string StatusText
    {
        get => _statusText;
        private set
        {
            if (SetProperty(ref _statusText, value)) OnPropertyChanged(nameof(HasStatus));
        }
    }

    public bool HasStatus => !string.IsNullOrEmpty(_statusText);

    public async Task ToggleRingAsync()
    {
        bool ring = !_isRinging;
        if (await SendAsync(() => _status.SendControlAsync(_peerId!, ring: ring), "ring")) SetRinging(ring);
    }

    public Task RequestRingerAsync(int index)
    {
        if (index < 0 || index >= RingerModes.Length || index == RingerIndex) return Task.CompletedTask;
        string mode = RingerModes[index];
        return SendAsync(() => _status.SendControlAsync(_peerId!, ringer: mode), "ringer");
    }

    public Task RequestDndAsync(bool enabled)
        => SendAsync(() => _status.SendControlAsync(_peerId!, dnd: enabled), "dnd");

    private async Task<bool> SendAsync(Func<Task> request, string name)
    {
        StatusText = string.Empty;
        if (!CanControl)
        {
            StatusText = "The phone is not connected.";
            return false;
        }
        try
        {
            await request();
            return true;
        }
        catch (Exception error)
        {
            VeyroTrace.Error($"Device control '{name}' failed", error);
            StatusText = "Could not reach the phone.";
            return false;
        }
    }

    private void SetRinging(bool ringing)
    {
        if (_isRinging == ringing) return;
        _isRinging = ringing;
        if (_ringTimer is not null)
        {
            _ringTimer.Stop();
            if (ringing) _ringTimer.Start();
        }
        OnPropertyChanged(nameof(IsRinging));
        OnPropertyChanged(nameof(RingButtonText));
    }

    private void Sync()
    {
        _reported = _peerId is null ? null : _status.GetPhoneStatus(_peerId);
        if (!CanControl)
        {
            SetRinging(false);
            StatusText = string.Empty;
        }
        foreach (string name in new[]
                 {
                     nameof(CanControl), nameof(HasBattery), nameof(BatteryText), nameof(IsCharging), nameof(BatteryGlyph),
                     nameof(BatteryLabel), nameof(RingerIndex), nameof(HasRinger), nameof(HasStatusRow), nameof(IsWaitingForStatus), nameof(RingerGlyph),
                     nameof(RingerLabel), nameof(IsRingerSilent), nameof(IsRingerVibrate), nameof(IsRingerNormal),
                     nameof(IsDndOn), nameof(DndLabel)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}
