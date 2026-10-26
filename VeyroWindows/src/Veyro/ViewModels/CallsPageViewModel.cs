using System.Globalization;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// Call log, current call and dialing for the active phone (ADR-027). Talks only to
/// <see cref="ICallService"/> and <see cref="CallRouter"/>. Audio stays on the phone unless the
/// Bluetooth hands-free link to the same phone is connected (ADR-029). Numbers and names are never logged.
/// </summary>
public sealed class CallsPageViewModel : BaseViewModel
{
    private readonly ICallService _calls;
    private readonly CallRouter _router;
    private readonly IDeviceManager _deviceManager;
    private readonly DispatcherQueue? _dispatcher;
    private string? _peerId;
    private PhoneCall? _call;
    private string _dialNumber = string.Empty;
    private string _statusText = string.Empty;
    private bool _isActive;
    private bool _isLoading;
    private bool _requested;

    public CallsPageViewModel(ICallService calls, CallRouter router, IDeviceManager deviceManager)
    {
        _calls = calls;
        _router = router;
        _deviceManager = deviceManager;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;

        Entries.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoEntries));
        _calls.Changed += change => Post(() => OnChanged(change));
        _router.RouteChanged += () => Post(() =>
        {
            foreach (string name in new[] { nameof(IsBluetoothReady), nameof(HeaderText), nameof(AnswerText), nameof(CurrentCallText) })
                OnPropertyChanged(name);
        });
        _deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            _peerId = device?.DeviceId;
            _requested = false;
            Sync();
            EnsureLoaded();
        });
        Sync();
    }

    public ObservableCollection<CallLogItemViewModel> Entries { get; } = [];

    public bool IsAvailable => _peerId is not null && _calls.IsAvailable(_peerId);
    public bool IsUnavailable => !IsAvailable;

    public string UnavailableText => _peerId is null
        ? "Pair a phone to see its calls here."
        : "Connect the phone to see its calls. " + MessagesViewModel.EnableHint;

    public bool IsLoading => _isLoading;
    public bool HasNoEntries => IsAvailable && !_isLoading && Entries.Count == 0;
    public string EmptyText => "No calls to show. " + MessagesViewModel.EnableHint;

    public bool HasCurrentCall => _call is not null;
    public bool IsRinging => _call?.IsRinging == true;
    public bool IsInCall => _call is not null && !_call.IsRinging;
    public string CurrentCallTitle => _call is null ? string.Empty : _call.Name ?? _call.Number ?? "Unknown caller";
    /// <summary>ADR-029: Bluetooth hands-free is connected to this phone, so answering plays audio here.</summary>
    public bool IsBluetoothReady => _router.IsBluetoothReadyFor(_peerId);

    public string HeaderText => IsBluetoothReady
        ? "Connected over Bluetooth: calls answered here play on this PC."
        : "Calls are placed and answered on the phone; audio stays on the phone.";

    public string AnswerText => IsBluetoothReady ? "Answer on PC" : "Answer on phone";

    public string CurrentCallText => _call switch
    {
        null => string.Empty,
        { IsRinging: true, Direction: CallService.DirectionOut } => "Calling... (audio on the phone)",
        { IsRinging: true } => "Incoming call",
        _ when IsBluetoothReady => "On a call (see the call window for audio on this PC)",
        _ => "On a call (audio on the phone)"
    };

    public string DialNumber
    {
        get => _dialNumber;
        set
        {
            if (SetProperty(ref _dialNumber, value ?? string.Empty)) OnPropertyChanged(nameof(CanDial));
        }
    }

    public bool CanDial => IsAvailable && PhoneAddress.Normalize(_dialNumber) is not null;

    public string StatusText
    {
        get => _statusText;
        private set
        {
            if (SetProperty(ref _statusText, value)) OnPropertyChanged(nameof(HasStatus));
        }
    }

    public bool HasStatus => !string.IsNullOrEmpty(_statusText);

    public void Activate()
    {
        _isActive = true;
        Sync();
        EnsureLoaded();
    }

    public void Deactivate() => _isActive = false;

    public async Task RefreshAsync()
    {
        if (_peerId is not { } peer || !IsAvailable) return;
        _requested = true;
        SetLoading(true);
        StatusText = string.Empty;
        try { await _calls.RequestCallLogAsync(peer); }
        // ADR-040: a late answer from the previous phone does not speak for the one now shown.
        catch (TimeoutException) { if (IsShown(peer)) StatusText = "The phone did not answer. Try again."; }
        catch (Exception error)
        {
            VeyroTrace.Error("Loading call log failed", error);
            if (IsShown(peer)) StatusText = "Could not load calls from the phone.";
        }
        finally { SetLoading(false); }
    }

    public async Task DialAsync()
    {
        if (await RunAsync(CallService.ActionDial, _dialNumber)) DialNumber = string.Empty;
    }

    public Task CallBackAsync(CallLogItemViewModel entry) => RunAsync(CallService.ActionDial, entry.Number);
    public async Task AnswerAsync()
    {
        if (_peerId is not { } peer) return;
        StatusText = string.Empty;
        try
        {
            if (await _router.AnswerAsync(peer) == CallAnswerRoute.Bluetooth)
                StatusText = "Answered on this PC. Call audio plays here over Bluetooth.";
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Answering call failed", error);
            StatusText = "Could not reach the phone.";
        }
    }

    public Task DeclineAsync() => RunAsync(CallService.ActionDecline);
    public Task SilenceAsync() => RunAsync(CallService.ActionSilence);
    public Task EndAsync() => RunAsync(CallService.ActionEnd);

    private async Task<bool> RunAsync(string action, string? number = null)
    {
        if (_peerId is not { } peer) return false;
        StatusText = string.Empty;
        try
        {
            await _calls.SendActionAsync(peer, action, number);
            if (action == CallService.ActionDial) StatusText = "Dialing on the phone. Audio stays on the phone.";
            return true;
        }
        catch (ArgumentException) { StatusText = "Enter a valid phone number."; }
        catch (Exception error)
        {
            VeyroTrace.Error($"Call action '{action}' failed", error);
            StatusText = "Could not reach the phone.";
        }
        return false;
    }

    private bool IsShown(string peerId) => string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase);

    private void OnChanged(CallChange change)
    {
        if (!string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
        if (change.Kind == CallChangeKind.Cleared) _requested = false;
        Sync();
        if (change.Kind == CallChangeKind.Availability) EnsureLoaded();
        // A finished call changes the log; refresh it if the user has been looking at it.
        if (change is { Kind: CallChangeKind.State, Call.State: CallService.StateEnded } && _requested && _isActive) _ = RefreshAsync();
    }

    private void EnsureLoaded()
    {
        if (_isActive && !_requested && IsAvailable) _ = RefreshAsync();
    }

    private void Sync()
    {
        _call = _peerId is null ? null : _calls.GetCurrentCall(_peerId);
        IReadOnlyList<CallLogEntry> log = _peerId is null ? [] : _calls.GetCallLog(_peerId);
        Entries.Clear();
        foreach (CallLogEntry entry in log) Entries.Add(new CallLogItemViewModel(entry));
        foreach (string name in new[]
                 {
                     nameof(IsAvailable), nameof(IsUnavailable), nameof(UnavailableText), nameof(HasNoEntries), nameof(HasCurrentCall),
                     nameof(IsRinging), nameof(IsInCall), nameof(CurrentCallTitle), nameof(CurrentCallText), nameof(CanDial),
                     nameof(IsBluetoothReady), nameof(HeaderText), nameof(AnswerText)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private void SetLoading(bool value)
    {
        _isLoading = value;
        OnPropertyChanged(nameof(IsLoading));
        OnPropertyChanged(nameof(HasNoEntries));
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}

public sealed class CallLogItemViewModel(CallLogEntry entry)
{
    public string Number => entry.Number;
    public string Title => string.IsNullOrWhiteSpace(entry.Name) ? (entry.Number.Length > 0 ? entry.Number : "Unknown") : entry.Name;
    public bool HasNumberLine => !string.IsNullOrWhiteSpace(entry.Name) && entry.Number.Length > 0;
    public bool CanCall => PhoneAddress.Normalize(entry.Number) is not null;
    public string TimeText => MessagesViewModel.FormatTime(entry.Ts);
    public bool IsMissed => entry.Type == "missed";

    public string Glyph => entry.Type switch
    {
        "out" => "\uE898",
        "missed" => "\uE7BA",
        "rejected" => "\uE711",
        "blocked" => "\uE8F8",
        _ => "\uE896"
    };

    public string TypeText => entry.Type switch
    {
        "out" => "Outgoing",
        "missed" => "Missed",
        "rejected" => "Declined",
        "blocked" => "Blocked",
        _ => "Incoming"
    };

    public string DetailText
    {
        get
        {
            if (entry.DurationSec <= 0) return TypeText;
            var duration = TimeSpan.FromSeconds(entry.DurationSec);
            string text = duration.TotalHours >= 1
                ? duration.ToString(@"h\:mm\:ss", CultureInfo.InvariantCulture)
                : duration.ToString(@"m\:ss", CultureInfo.InvariantCulture);
            return $"{TypeText}, {text}";
        }
    }

    public string CallLabel => $"Call {Title} on the phone";
}
