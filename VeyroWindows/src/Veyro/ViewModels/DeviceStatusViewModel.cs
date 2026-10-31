using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// What the phone panel shows about the active phone (ADR-026, ADR-038, ADR-060): battery,
/// radios, ringer, do-not-disturb, flashlight, media volume and brightness, plus the requests
/// that change them. Every shown value is what the phone last reported. A request changes
/// nothing locally; the phone's next <c>devStatus</c> confirms it (sliders keep the user's
/// pick visible until then).
/// </summary>
public sealed class DeviceStatusViewModel : BaseViewModel
{
    // ---- Static tables -------------------------------------------------------------------

    /// <summary>The phone stops a find-my-phone ring by itself after 30 s (04-PROTOCOL.md 6.2).</summary>
    private static readonly TimeSpan RingLimit = TimeSpan.FromSeconds(30);

    /// <summary>Ringer modes in selector order; the position is the index the UI passes in.</summary>
    private static readonly string[] RingerOrder =
    [
        DeviceStatusService.RingerSilent,
        DeviceStatusService.RingerVibrate,
        DeviceStatusService.RingerNormal
    ];

    /// <summary>Battery glyphs by tens of percent (0-10), discharging.</summary>
    private static readonly string[] BatteryGlyphs =
        ["\uE850", "\uE851", "\uE852", "\uE853", "\uE854", "\uE855", "\uE856", "\uE857", "\uE858", "\uE859", "\uE83F"];

    /// <summary>Battery glyphs by tens of percent (0-10), charging.</summary>
    private static readonly string[] ChargingBatteryGlyphs =
        ["\uE85A", "\uE85B", "\uE85C", "\uE85D", "\uE85E", "\uE85F", "\uE860", "\uE861", "\uE862", "\uE83E", "\uEA93"];

    /// <summary>Cellular signal glyphs for 0-4 bars.</summary>
    private static readonly string[] CellBars = ["\uE86C", "\uE86D", "\uE86E", "\uE86F", "\uE870"];

    private const string CellNoService = "\uE871";

    /// <summary>
    /// Property names re-raised whenever a new phone snapshot arrives, grouped by topic so a
    /// property added to a topic only needs listing once.
    /// </summary>
    private static readonly string[] SnapshotProperties =
    [
        // availability and battery
        nameof(CanControl), nameof(HasBattery), nameof(BatteryText), nameof(IsCharging), nameof(BatteryGlyph),
        nameof(BatteryLabel),
        // ringer, sound tile and do-not-disturb
        nameof(RingerIndex), nameof(HasRinger), nameof(RingerGlyph), nameof(RingerLabel), nameof(IsRingerSilent),
        nameof(IsRingerVibrate), nameof(IsRingerNormal), nameof(SoundGlyph), nameof(SoundLabel),
        nameof(IsDndOn), nameof(DndLabel), nameof(DndButtonLabel),
        // radios
        nameof(HasBluetooth), nameof(BluetoothOpacity), nameof(BluetoothLabel),
        nameof(HasWifi), nameof(WifiGlyph), nameof(WifiOpacity), nameof(WifiLabel),
        nameof(HasCell), nameof(CellGlyph), nameof(CellOpacity), nameof(CellLabel), nameof(HasRadios),
        // summary row
        nameof(HasStatusRow), nameof(IsWaitingForStatus),
        // phone controls (ADR-060)
        nameof(CanTorch), nameof(IsTorchOn), nameof(TorchTip),
        nameof(CanVolume), nameof(ShownMediaVolume), nameof(VolumeTip),
        nameof(CanBrightness), nameof(ShownBrightness), nameof(IsAutoBrightness), nameof(BrightnessTip)
    ];

    // ---- State ---------------------------------------------------------------------------

    private readonly IDeviceStatusService _statusService;
    private readonly IWallpaperService _wallpaperService;
    private readonly DispatcherQueue? _ui;
    private readonly DispatcherQueueTimer? _ringExpiry;

    // ADR-060: a slider shows the user's pick until the phone confirms it; sends are coalesced.
    private readonly HeldLevel _volumeHeld = new();
    private readonly HeldLevel _brightnessHeld = new();
    private readonly LatestValueSender<int> _volumeSender;
    private readonly LatestValueSender<int> _brightnessSender;

    private string? _activePeer;
    private PeerMessage.DeviceStatus? _snapshot;
    private bool _ringing;
    private string _errorText = string.Empty;
    private byte[]? _wallpaperSource;
    private ImageSource? _wallpaperImage;

    public DeviceStatusViewModel(IDeviceStatusService status, IWallpaperService wallpapers, IDeviceManager deviceManager)
    {
        _statusService = status;
        _wallpaperService = wallpapers;
        _ui = DispatcherQueue.GetForCurrentThread();
        _activePeer = deviceManager.ActiveDevice?.DeviceId;

        _volumeSender = new LatestValueSender<int>(value =>
            Dispatch(() => _statusService.SendPhoneControlAsync(_activePeer!, mediaVolume: value), "volume", () => CanVolume));
        _brightnessSender = new LatestValueSender<int>(value =>
            Dispatch(() => _statusService.SendPhoneControlAsync(_activePeer!, brightness: value), "brightness", () => CanBrightness));

        if (_ui is not null)
        {
            _ringExpiry = _ui.CreateTimer();
            _ringExpiry.Interval = RingLimit;
            _ringExpiry.IsRepeating = false;
            _ringExpiry.Tick += (_, _) => SetRinging(false);
        }

        _statusService.Changed += peerId => OnUi(() =>
        {
            if (IsActive(peerId)) RefreshSnapshot();
        });
        _wallpaperService.Changed += peerId => OnUi(() =>
        {
            if (IsActive(peerId)) _ = RefreshWallpaperAsync();
        });
        deviceManager.ActiveDeviceChanged += device => OnUi(() =>
        {
            _activePeer = device?.DeviceId;
            SetRinging(false);
            RefreshSnapshot();
            _ = RefreshWallpaperAsync();
        });

        RefreshSnapshot();
        _ = RefreshWallpaperAsync();
    }

    // ---- Wallpaper (ADR-038) -------------------------------------------------------------

    /// <summary>The phone's wallpaper from its latest <c>wallpaper</c> message, or null.</summary>
    public ImageSource? Wallpaper => _wallpaperImage;
    public bool HasWallpaper => _wallpaperImage is not null;
    public bool HasNoWallpaper => _wallpaperImage is null;

    private async Task RefreshWallpaperAsync()
    {
        byte[]? bytes = _activePeer is null ? null : _wallpaperService.GetPhoneWallpaper(_activePeer);
        if (ReferenceEquals(bytes, _wallpaperSource)) return;
        _wallpaperSource = bytes;

        ImageSource? decoded = null;
        if (bytes is not null)
        {
            try
            {
                var bitmap = new BitmapImage { DecodePixelHeight = 360 };
                using var stream = new MemoryStream(bytes);
                await bitmap.SetSourceAsync(stream.AsRandomAccessStream());
                decoded = bitmap;
            }
            catch (Exception)
            {
                // The bytes come from the phone and are untrusted: one that does not decode
                // simply leaves the fallback gradient in place.
                decoded = null;
            }
        }

        // A newer wallpaper arrived while this one was decoding: drop this result.
        if (!ReferenceEquals(bytes, _wallpaperSource)) return;
        _wallpaperImage = decoded;
        OnPropertyChanged(nameof(Wallpaper));
        OnPropertyChanged(nameof(HasWallpaper));
        OnPropertyChanged(nameof(HasNoWallpaper));
    }

    // ---- Connection and summary row ------------------------------------------------------

    public bool CanControl => _activePeer is not null && _statusService.CanControl(_activePeer);

    /// <summary>True when something in the summary row (battery, radios, ringer, DND) can be drawn.</summary>
    public bool HasStatusRow => HasBattery || HasRinger || IsDndOn || HasRadios;

    /// <summary>Connected and able to report, but no snapshot has arrived yet.</summary>
    public bool IsWaitingForStatus => CanControl && !HasStatusRow;

    // ---- Battery -------------------------------------------------------------------------

    public bool HasBattery => _snapshot?.Battery is not null;
    public string BatteryText => _snapshot?.Battery is { } percent ? $"{percent}%" : string.Empty;
    public bool IsCharging => _snapshot?.Charging == true;

    public string BatteryGlyph
    {
        get
        {
            if (_snapshot?.Battery is not { } percent) return string.Empty;
            int tens = Math.Clamp(percent / 10, 0, 10);
            return (IsCharging ? ChargingBatteryGlyphs : BatteryGlyphs)[tens];
        }
    }

    public string BatteryLabel => _snapshot?.Battery is { } percent
        ? $"Phone battery {percent} percent{(IsCharging ? ", charging" : string.Empty)}"
        : "Phone battery not reported";

    // ---- Radios: a glyph exists only once the phone reported that radio ------------------

    public bool HasBluetooth => _snapshot?.Bluetooth is not null;
    public double BluetoothOpacity => _snapshot?.Bluetooth == true ? 1.0 : 0.45;
    public string BluetoothLabel => _snapshot?.Bluetooth == true ? "Bluetooth on" : "Bluetooth off";

    public bool HasWifi => _snapshot?.Wifi is not null;

    public string WifiGlyph => _snapshot?.Wifi switch
    {
        null => string.Empty,
        <= 1 => "\uE872",
        2 => "\uE873",
        3 => "\uE874",
        _ => "\uE701"
    };

    public double WifiOpacity => _snapshot?.Wifi is >= 1 ? 1.0 : 0.45;

    public string WifiLabel => _snapshot?.Wifi switch
    {
        null => string.Empty,
        < 0 => "Wi-Fi off",
        { } bars => $"Wi-Fi signal {bars} of 4"
    };

    public bool HasCell => _snapshot?.Cell is not null;

    public string CellGlyph => _snapshot?.Cell switch
    {
        null => string.Empty,
        < 0 => CellNoService,
        { } bars => CellBars[Math.Clamp(bars, 0, CellBars.Length - 1)]
    };

    public double CellOpacity => _snapshot?.Cell is >= 0 ? 1.0 : 0.45;

    /// <summary>Carrier, signal and mobile data, for the tooltip. Displayed only, never logged.</summary>
    public string CellLabel
    {
        get
        {
            if (_snapshot?.Cell is not { } bars) return string.Empty;
            var parts = new List<string>(3);
            if (!string.IsNullOrEmpty(_snapshot.Carrier)) parts.Add(_snapshot.Carrier);
            parts.Add(bars < 0 ? "No service" : $"Signal {bars} of 4");
            if (_snapshot.MobileData is { } mobileData) parts.Add(mobileData ? "Mobile data on" : "Mobile data off");
            return string.Join(", ", parts);
        }
    }

    public bool HasRadios => HasBluetooth || HasWifi || HasCell;

    // ---- Ringer, sound tile, do-not-disturb ----------------------------------------------

    /// <summary>Position in Silent / Vibrate / Ring, or -1 until the phone reports a mode.</summary>
    public int RingerIndex => Array.IndexOf(RingerOrder, _snapshot?.Ringer ?? string.Empty);
    public bool HasRinger => RingerIndex >= 0;

    public bool IsRingerSilent => _snapshot?.Ringer == DeviceStatusService.RingerSilent;
    public bool IsRingerVibrate => _snapshot?.Ringer == DeviceStatusService.RingerVibrate;
    public bool IsRingerNormal => _snapshot?.Ringer == DeviceStatusService.RingerNormal;

    public string RingerGlyph => _snapshot?.Ringer switch
    {
        DeviceStatusService.RingerNormal => "\uEA8F",
        DeviceStatusService.RingerVibrate => "\uE877",
        DeviceStatusService.RingerSilent => "\uE7ED",
        _ => string.Empty
    };

    public string RingerLabel => _snapshot?.Ringer switch
    {
        DeviceStatusService.RingerNormal => "Ringer on",
        DeviceStatusService.RingerVibrate => "Vibrate",
        DeviceStatusService.RingerSilent => "Silent",
        _ => "Ringer unknown"
    };

    /// <summary>Sound tile: the reported mode, or a plain speaker while the mode is unknown.</summary>
    public string SoundGlyph => HasRinger ? RingerGlyph : "\uE767";
    public string SoundLabel => HasRinger ? $"Sound: {RingerLabel}" : "Sound mode not reported";

    public bool IsDndOn => _snapshot?.Dnd == true;

    public string DndLabel => _snapshot?.Dnd switch
    {
        true => "Do not disturb is on",
        false => "Do not disturb is off",
        null => "Do not disturb unknown"
    };

    public string DndButtonLabel => _snapshot?.Dnd switch
    {
        true => "Do not disturb: on. Select to turn off.",
        false => "Do not disturb: off. Select to turn on.",
        null => "Do not disturb"
    };

    // ---- Find my phone, request errors ---------------------------------------------------

    public bool IsRinging => _ringing;
    public string RingButtonText => _ringing ? "Stop ringing" : "Ring phone";

    /// <summary>The last request failure, shown under the quick controls; empty when none.</summary>
    public string StatusText
    {
        get => _errorText;
        private set
        {
            if (SetProperty(ref _errorText, value)) OnPropertyChanged(nameof(HasStatus));
        }
    }

    public bool HasStatus => _errorText.Length > 0;

    public async Task ToggleRingAsync()
    {
        bool wantRing = !_ringing;
        if (await Dispatch(() => _statusService.SendControlAsync(_activePeer!, ring: wantRing), "ring"))
            SetRinging(wantRing);
    }

    public Task RequestRingerAsync(int index)
    {
        if (index < 0 || index >= RingerOrder.Length || index == RingerIndex) return Task.CompletedTask;
        string mode = RingerOrder[index];
        return Dispatch(() => _statusService.SendControlAsync(_activePeer!, ringer: mode), "ringer");
    }

    public Task RequestDndAsync(bool enabled)
        => Dispatch(() => _statusService.SendControlAsync(_activePeer!, dnd: enabled), "dnd");

    // ---- Flashlight, media volume, brightness (ADR-060) ----------------------------------

    private bool HasPhoneControls => _activePeer is not null && _statusService.CanUsePhoneControls(_activePeer);

    /// <summary>Why a phone control is unavailable right now, or null when it can be used.</summary>
    private string? WhyUnavailable(PhoneControlKind kind)
        => PhoneControlsView.Unavailable(CanControl, HasPhoneControls, _snapshot, kind);

    public bool CanTorch => WhyUnavailable(PhoneControlKind.Torch) is null;
    public bool IsTorchOn => _snapshot?.Torch == true;

    public string TorchTip => WhyUnavailable(PhoneControlKind.Torch)
        ?? (IsTorchOn ? "Flashlight: on. Select to turn it off." : "Flashlight: off. Select to turn it on.");

    public bool CanVolume => WhyUnavailable(PhoneControlKind.Volume) is null;

    /// <summary>The slider value: the user's pick while it is in flight, else the phone's.</summary>
    public double ShownMediaVolume => _volumeHeld.Shown(_snapshot?.MediaVolume) ?? 0;

    public string VolumeTip => WhyUnavailable(PhoneControlKind.Volume)
        ?? (_snapshot?.MediaVolume is { } volume ? $"Media volume on the phone: {volume}%" : "Media volume on the phone");

    public bool CanBrightness => WhyUnavailable(PhoneControlKind.Brightness) is null;
    public double ShownBrightness => _brightnessHeld.Shown(_snapshot?.Brightness) ?? 0;
    public bool IsAutoBrightness => _snapshot?.AutoBrightness == true;

    public string BrightnessTip => WhyUnavailable(PhoneControlKind.Brightness)
        ?? (_snapshot?.Brightness is { } level
            ? $"Screen brightness on the phone: {level}%{(IsAutoBrightness ? ", automatic" : string.Empty)}"
            : "Screen brightness on the phone");

    public Task RequestTorchAsync(bool on)
        => Dispatch(() => _statusService.SendPhoneControlAsync(_activePeer!, torch: on), "torch", () => CanTorch);

    /// <summary>From the slider. Coalesced: one send in flight, the newest value follows it.</summary>
    public void RequestMediaVolume(int percent)
    {
        if (!CanVolume || percent is < 0 or > 100) return;
        _volumeHeld.Request(percent);
        _ = _volumeSender.Post(percent);
    }

    public void RequestBrightness(int percent)
    {
        if (!CanBrightness || percent is < 0 or > 100) return;
        _brightnessHeld.Request(percent);
        _ = _brightnessSender.Post(percent);
    }

    public Task RequestAutoBrightnessAsync(bool on)
        => Dispatch(() => _statusService.SendPhoneControlAsync(_activePeer!, autoBrightness: on), "auto brightness", () => CanBrightness);

    // ---- Internals -----------------------------------------------------------------------

    private bool IsActive(string? peerId) => string.Equals(peerId, _activePeer, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Runs one request against the phone. Returns false (and sets <see cref="StatusText"/>
    /// where it is the user's concern) when it was refused or failed.
    /// </summary>
    private async Task<bool> Dispatch(Func<Task> request, string what, Func<bool>? precondition = null)
    {
        StatusText = string.Empty;
        if (!CanControl)
        {
            StatusText = "The phone is not connected.";
            return false;
        }
        if (precondition is not null && !precondition()) return false;

        try
        {
            await request();
            return true;
        }
        catch (Exception error)
        {
            VeyroTrace.Error($"Device control '{what}' failed", error);
            StatusText = "Could not reach the phone.";
            return false;
        }
    }

    private void SetRinging(bool ringing)
    {
        if (_ringing == ringing) return;
        _ringing = ringing;
        if (_ringExpiry is not null)
        {
            _ringExpiry.Stop();
            if (ringing) _ringExpiry.Start();
        }
        OnPropertyChanged(nameof(IsRinging));
        OnPropertyChanged(nameof(RingButtonText));
    }

    private void RefreshSnapshot()
    {
        _snapshot = _activePeer is null ? null : _statusService.GetPhoneStatus(_activePeer);
        if (!CanControl)
        {
            SetRinging(false);
            StatusText = string.Empty;
        }
        foreach (string property in SnapshotProperties) OnPropertyChanged(property);
    }

    private void OnUi(Action action)
    {
        if (_ui is null || _ui.HasThreadAccess) action();
        else _ui.TryEnqueue(() => action());
    }
}
