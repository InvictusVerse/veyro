// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Converters/Converters.cs (BatteryStatusToIconConverter, RingerModeToIconConverter), GPL-3.0.
// Modified for Veyro.

using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
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
    private readonly IWallpaperService _wallpapers;
    private readonly DispatcherQueue? _dispatcher;
    private readonly DispatcherQueueTimer? _ringTimer;
    private string? _peerId;
    private PeerMessage.DeviceStatus? _reported;
    private bool _isRinging;
    private string _statusText = string.Empty;
    private byte[]? _wallpaperBytes;
    private ImageSource? _wallpaper;
    // ADR-060: sliders show the user's value until the phone confirms it; sends are coalesced.
    private readonly HeldLevel _volumeHold = new();
    private readonly HeldLevel _brightnessHold = new();
    private readonly LatestValueSender<int> _volumeSender;
    private readonly LatestValueSender<int> _brightnessSender;

    public DeviceStatusViewModel(IDeviceStatusService status, IWallpaperService wallpapers, IDeviceManager deviceManager)
    {
        _status = status;
        _wallpapers = wallpapers;
        _volumeSender = new LatestValueSender<int>(value =>
            SendAsync(() => _status.SendPhoneControlAsync(_peerId!, mediaVolume: value), "volume", () => CanVolume));
        _brightnessSender = new LatestValueSender<int>(value =>
            SendAsync(() => _status.SendPhoneControlAsync(_peerId!, brightness: value), "brightness", () => CanBrightness));
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
        _wallpapers.Changed += peerId => Post(() =>
        {
            if (string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase)) _ = SyncWallpaperAsync();
        });
        deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            _peerId = device?.DeviceId;
            SetRinging(false);
            Sync();
            _ = SyncWallpaperAsync();
        });
        Sync();
        _ = SyncWallpaperAsync();
    }

    // ── Wallpaper (ADR-038): the phone's own, or the fallback gradient ───────────────────

    /// <summary>The phone's wallpaper from its latest <c>wallpaper</c> message, or null.</summary>
    public ImageSource? Wallpaper => _wallpaper;
    public bool HasWallpaper => _wallpaper is not null;
    public bool HasNoWallpaper => _wallpaper is null;

    private async Task SyncWallpaperAsync()
    {
        byte[]? bytes = _peerId is null ? null : _wallpapers.GetPhoneWallpaper(_peerId);
        if (ReferenceEquals(bytes, _wallpaperBytes)) return;
        _wallpaperBytes = bytes;
        ImageSource? image = null;
        if (bytes is not null)
        {
            try
            {
                var bitmap = new BitmapImage { DecodePixelHeight = 360 };
                using var stream = new MemoryStream(bytes);
                await bitmap.SetSourceAsync(stream.AsRandomAccessStream());
                image = bitmap;
            }
            catch (Exception)
            {
                // Untrusted bytes: a JPEG that does not decode shows the fallback.
                image = null;
            }
        }
        if (!ReferenceEquals(bytes, _wallpaperBytes)) return;
        _wallpaper = image;
        OnPropertyChanged(nameof(Wallpaper));
        OnPropertyChanged(nameof(HasWallpaper));
        OnPropertyChanged(nameof(HasNoWallpaper));
    }

    // ── Radios (ADR-038). Each glyph exists only once the phone reported it. ─────────────

    public bool HasBluetooth => _reported?.Bluetooth is not null;
    public double BluetoothOpacity => _reported?.Bluetooth == true ? 1.0 : 0.45;
    public string BluetoothLabel => _reported?.Bluetooth == true ? "Bluetooth on" : "Bluetooth off";

    public bool HasWifi => _reported?.Wifi is not null;
    public string WifiGlyph => _reported?.Wifi switch
    {
        null => string.Empty,
        <= 1 => "\uE872",
        2 => "\uE873",
        3 => "\uE874",
        _ => "\uE701"
    };
    public double WifiOpacity => _reported?.Wifi is >= 1 ? 1.0 : 0.45;
    public string WifiLabel => _reported?.Wifi switch
    {
        null => string.Empty,
        < 0 => "Wi-Fi off",
        { } level => $"Wi-Fi signal {level} of 4"
    };

    public bool HasCell => _reported?.Cell is not null;
    public string CellGlyph => _reported?.Cell switch
    {
        null => string.Empty,
        < 0 => "\uE871",
        { } level => ((char)(0xE86C + Math.Clamp(level, 0, 4))).ToString()
    };
    public double CellOpacity => _reported?.Cell is >= 0 ? 1.0 : 0.45;

    /// <summary>Carrier, signal and mobile data, for the tooltip. Shown, never logged.</summary>
    public string CellLabel
    {
        get
        {
            if (_reported?.Cell is not { } level) return string.Empty;
            var parts = new List<string>();
            if (!string.IsNullOrEmpty(_reported.Carrier)) parts.Add(_reported.Carrier);
            parts.Add(level < 0 ? "No service" : $"Signal {level} of 4");
            if (_reported.MobileData is { } data) parts.Add(data ? "Mobile data on" : "Mobile data off");
            return string.Join(", ", parts);
        }
    }

    /// <summary>Only phones that report radios get the radio part of the status row.</summary>
    public bool HasRadios => HasBluetooth || HasWifi || HasCell;

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
    public bool HasStatusRow => HasBattery || HasRinger || IsDndOn || HasRadios;

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

    /// <summary>Quick-action Sound tile: the reported mode, or a neutral speaker when unknown.</summary>
    public string SoundGlyph => HasRinger ? RingerGlyph : "\uE767";
    public string SoundLabel => HasRinger ? $"Sound: {RingerLabel}" : "Sound mode not reported";

    public string DndButtonLabel => _reported?.Dnd switch
    {
        true => "Do not disturb: on. Select to turn off.",
        false => "Do not disturb: off. Select to turn on.",
        null => "Do not disturb"
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

    // ── Flashlight, media volume, brightness (ADR-060). Shown state is what the phone reports. ──

    private bool IsConnected => _peerId is not null && _status.CanControl(_peerId);
    private bool HasPhoneControls => _peerId is not null && _status.CanUsePhoneControls(_peerId);

    private string? Reason(PhoneControlKind kind) => PhoneControlsView.Unavailable(IsConnected, HasPhoneControls, _reported, kind);

    public bool CanTorch => Reason(PhoneControlKind.Torch) is null;
    public bool IsTorchOn => _reported?.Torch == true;
    public string TorchTip => Reason(PhoneControlKind.Torch) ?? (IsTorchOn
        ? "Flashlight: on. Select to turn it off."
        : "Flashlight: off. Select to turn it on.");

    public bool CanVolume => Reason(PhoneControlKind.Volume) is null;

    /// <summary>The slider's value: the user's pick while it is on its way, else the phone's.</summary>
    public double ShownMediaVolume => _volumeHold.Shown(_reported?.MediaVolume) ?? 0;
    public string VolumeTip => Reason(PhoneControlKind.Volume) ??
        (_reported?.MediaVolume is { } volume ? $"Media volume on the phone: {volume}%" : "Media volume on the phone");

    public bool CanBrightness => Reason(PhoneControlKind.Brightness) is null;
    public double ShownBrightness => _brightnessHold.Shown(_reported?.Brightness) ?? 0;
    public bool IsAutoBrightness => _reported?.AutoBrightness == true;
    public string BrightnessTip => Reason(PhoneControlKind.Brightness) ??
        (_reported?.Brightness is { } level
            ? $"Screen brightness on the phone: {level}%{(IsAutoBrightness ? ", automatic" : string.Empty)}"
            : "Screen brightness on the phone");

    public Task RequestTorchAsync(bool on)
        => SendAsync(() => _status.SendPhoneControlAsync(_peerId!, torch: on), "torch", () => CanTorch);

    /// <summary>From the slider. Coalesced: one send in flight, the newest value follows.</summary>
    public void RequestMediaVolume(int percent)
    {
        if (!CanVolume || percent is < 0 or > 100) return;
        _volumeHold.Request(percent);
        _ = _volumeSender.Post(percent);
    }

    public void RequestBrightness(int percent)
    {
        if (!CanBrightness || percent is < 0 or > 100) return;
        _brightnessHold.Request(percent);
        _ = _brightnessSender.Post(percent);
    }

    public Task RequestAutoBrightnessAsync(bool on)
        => SendAsync(() => _status.SendPhoneControlAsync(_peerId!, autoBrightness: on), "auto brightness", () => CanBrightness);

    private async Task<bool> SendAsync(Func<Task> request, string name, Func<bool>? allowed = null)
    {
        StatusText = string.Empty;
        if (!CanControl)
        {
            StatusText = "The phone is not connected.";
            return false;
        }
        if (allowed is not null && !allowed()) return false;
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
                     nameof(IsDndOn), nameof(DndLabel), nameof(DndButtonLabel),
                     nameof(HasBluetooth), nameof(BluetoothOpacity), nameof(BluetoothLabel),
                     nameof(HasWifi), nameof(WifiGlyph), nameof(WifiOpacity), nameof(WifiLabel),
                     nameof(HasCell), nameof(CellGlyph), nameof(CellOpacity), nameof(CellLabel), nameof(HasRadios),
                     nameof(SoundGlyph), nameof(SoundLabel),
                     nameof(CanTorch), nameof(IsTorchOn), nameof(TorchTip),
                     nameof(CanVolume), nameof(ShownMediaVolume), nameof(VolumeTip),
                     nameof(CanBrightness), nameof(ShownBrightness), nameof(IsAutoBrightness), nameof(BrightnessTip)
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
