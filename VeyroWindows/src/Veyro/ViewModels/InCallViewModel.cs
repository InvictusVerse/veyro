// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/ViewModels/CallSessionViewModel.cs
// and src/Sefirah/ViewModels/CallWindowViewModel.cs, GPL-3.0.
// Modified for Veyro.
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// One Bluetooth call in the in-call window (ADR-029): caller, duration, mute, hold, audio route,
/// answer/decline/end. Name and number prefer the phone's Wi-Fi callState. Never logged.
/// </summary>
public sealed class InCallViewModel : BaseViewModel, IDisposable
{
    private readonly IBluetoothCall _call;
    private readonly ICallService _calls;
    private readonly string? _peerId;
    private readonly DispatcherQueue? _dispatcher;
    private readonly DispatcherQueueTimer? _timer;
    private DateTimeOffset? _talkingSince;
    private string _statusText = string.Empty;
    private string _errorText = string.Empty;
    private bool _busy;
    private bool _disposed;

    public InCallViewModel(IBluetoothCall call, ICallService calls, string? peerId, DispatcherQueue? dispatcher)
    {
        _call = call;
        _calls = calls;
        _peerId = peerId;
        _dispatcher = dispatcher;
        if (dispatcher is not null)
        {
            _timer = dispatcher.CreateTimer();
            _timer.Interval = TimeSpan.FromSeconds(1);
            _timer.IsRepeating = true;
            _timer.Tick += (_, _) => RefreshStatus();
        }
        _call.Changed += OnCallChanged;
        _calls.Changed += OnWifiChanged;
        Refresh();
    }

    /// <summary>Status changed on the UI thread; the window service re-evaluates visibility.</summary>
    public event Action? StateChanged;

    public string CallId => _call.CallId;
    public BluetoothCallStatus Status => _call.Status;

    public string Title => CallPresentation.Title(WifiCall, _call.Name, _call.Number);
    public string Subtitle => CallPresentation.Subtitle(WifiCall, _call.Name, _call.Number) ?? string.Empty;
    public bool HasSubtitle => Subtitle.Length > 0;

    public string StatusText
    {
        get => _statusText;
        private set => SetProperty(ref _statusText, value);
    }

    public string ErrorText
    {
        get => _errorText;
        private set
        {
            if (SetProperty(ref _errorText, value)) OnPropertyChanged(nameof(HasError));
        }
    }

    public bool HasError => _errorText.Length > 0;

    public bool IsIncoming => _call.Status == BluetoothCallStatus.Incoming;
    public bool IsNotIncoming => !IsIncoming;
    public bool IsHeld => _call.Status == BluetoothCallStatus.Held;
    public bool IsMuted => _call.IsMuted;
    public bool CanHold => !_busy && _call.Status is BluetoothCallStatus.Talking or BluetoothCallStatus.Held;
    public bool CanMute => CanHold;
    public string HoldText => IsHeld ? "Resume" : "Hold";
    public string MuteText => IsMuted ? "Unmute" : "Mute";

    public bool CanSwitchAudio => !_busy && !IsIncoming && _call.AudioRoute != CallAudioRoute.Unknown;
    public string AudioRouteText => _call.AudioRoute == CallAudioRoute.Phone ? "Audio: phone" : "Audio: this PC";
    public string AudioSwitchText => _call.AudioRoute == CallAudioRoute.Phone ? "Use this PC" : "Use phone";

    public Task AnswerAsync() => RunAsync(_call.AcceptAsync, "Could not answer the call.");
    public Task DeclineAsync() => RunAsync(_call.RejectAsync, "Could not decline the call.");
    public Task EndAsync() => RunAsync(_call.EndAsync, "Could not end the call.");
    public Task ToggleHoldAsync() => RunAsync(IsHeld ? _call.ResumeAsync : _call.HoldAsync, "The phone did not change hold.");
    public Task ToggleMuteAsync() => RunAsync(IsMuted ? _call.UnmuteAsync : _call.MuteAsync, "Could not change mute.");

    public Task SwitchAudioAsync()
    {
        CallAudioRoute next = _call.AudioRoute == CallAudioRoute.Phone ? CallAudioRoute.ThisPc : CallAudioRoute.Phone;
        return RunAsync(() => _call.SetAudioRouteAsync(next), "Could not move the call audio.");
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _call.Changed -= OnCallChanged;
        _calls.Changed -= OnWifiChanged;
        _timer?.Stop();
    }

    private PhoneCall? WifiCall => _peerId is null ? null : _calls.GetCurrentCall(_peerId);

    private async Task RunAsync(Func<Task<bool>> operation, string failure)
    {
        if (_busy) return;
        SetBusy(true);
        ErrorText = string.Empty;
        try
        {
            if (!await operation()) ErrorText = failure;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Bluetooth call operation failed", error);
            ErrorText = failure;
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void SetBusy(bool value)
    {
        _busy = value;
        foreach (string name in new[] { nameof(CanHold), nameof(CanMute), nameof(CanSwitchAudio) }) OnPropertyChanged(name);
    }

    private void OnCallChanged() => Post(() =>
    {
        Refresh();
        StateChanged?.Invoke();
    });

    private void OnWifiChanged(CallChange change)
    {
        if (string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase)) Post(RefreshCaller);
    }

    private void Refresh()
    {
        if (_disposed) return;
        RefreshCaller();
        RefreshStatus();
        foreach (string name in new[]
                 {
                     nameof(Status), nameof(IsIncoming), nameof(IsNotIncoming), nameof(IsHeld), nameof(IsMuted), nameof(CanHold),
                     nameof(CanMute), nameof(HoldText), nameof(MuteText), nameof(CanSwitchAudio), nameof(AudioRouteText), nameof(AudioSwitchText)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private void RefreshCaller()
    {
        if (_disposed) return;
        OnPropertyChanged(nameof(Title));
        OnPropertyChanged(nameof(Subtitle));
        OnPropertyChanged(nameof(HasSubtitle));
    }

    private void RefreshStatus()
    {
        if (_disposed) return;
        BluetoothCallStatus status = _call.Status;
        if (status == BluetoothCallStatus.Talking)
        {
            _talkingSince ??= DateTimeOffset.UtcNow;
            if (_timer is { IsRunning: false }) _timer.Start();
        }
        else
        {
            _timer?.Stop();
        }
        TimeSpan? talking = _talkingSince is { } since ? DateTimeOffset.UtcNow - since : null;
        StatusText = CallPresentation.StatusText(status, talking);
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}

/// <summary>What the in-call window shows: the current Bluetooth call, or nothing.</summary>
public sealed class CallWindowViewModel : BaseViewModel
{
    private InCallViewModel? _call;

    public InCallViewModel? Call
    {
        get => _call;
        set => SetProperty(ref _call, value);
    }
}
