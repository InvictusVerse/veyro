using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// What the in-call window shows for one Bluetooth call (ADR-029): who is calling, the call
/// timer, and the answer, decline, end, hold, mute and audio route actions. The caller's name and
/// number come from the phone's Wi-Fi call state when it has them, otherwise from the hands-free
/// line. They are shown, never logged. Windows holds the real call state, so every property reads
/// it again instead of keeping a copy.
/// </summary>
public sealed class InCallViewModel : BaseViewModel, IDisposable
{
    private static readonly string[] CallStateProperties =
    [
        nameof(Status), nameof(IsIncoming), nameof(IsNotIncoming), nameof(IsHeld), nameof(IsMuted),
        nameof(HoldText), nameof(MuteText), nameof(AudioRouteText), nameof(AudioSwitchText)
    ];

    private static readonly string[] CallerProperties = [nameof(Title), nameof(Subtitle), nameof(HasSubtitle)];
    private static readonly string[] ActionProperties = [nameof(CanHold), nameof(CanMute), nameof(CanSwitchAudio)];

    private readonly IBluetoothCall _call;
    private readonly ICallService _phoneCalls;
    private readonly string? _peerId;
    private readonly DispatcherQueue? _uiThread;
    private readonly DispatcherQueueTimer? _clock;
    private DateTimeOffset? _connectedAt;
    private string _statusText = string.Empty;
    private string _errorText = string.Empty;
    private bool _working;
    private bool _disposed;

    /// <param name="call">The hands-free call this view model presents.</param>
    /// <param name="phoneCalls">Source of the phone's Wi-Fi call state, used for the contact name.</param>
    /// <param name="peerId">The Veyro device the hands-free phone is linked to, or null.</param>
    /// <param name="uiThread">Queue of the UI thread. Null in tests, where everything runs inline.</param>
    public InCallViewModel(IBluetoothCall call, ICallService phoneCalls, string? peerId, DispatcherQueue? uiThread)
    {
        _call = call;
        _phoneCalls = phoneCalls;
        _peerId = peerId;
        _uiThread = uiThread;

        if (uiThread is not null)
        {
            _clock = uiThread.CreateTimer();
            _clock.Interval = TimeSpan.FromSeconds(1);
            _clock.IsRepeating = true;
            _clock.Tick += (_, _) => UpdateStatusText();
        }

        _call.Changed += OnHandsFreeCallChanged;
        _phoneCalls.Changed += OnPhoneCallChanged;
        RefreshAll();
    }

    /// <summary>Raised on the UI thread after the call changed, so the window service can re-decide what to show.</summary>
    public event Action? StateChanged;

    public string CallId => _call.CallId;
    public BluetoothCallStatus Status => _call.Status;

    public string Title => CallPresentation.Title(PhoneSideCall, _call.Name, _call.Number);
    public string Subtitle => CallPresentation.Subtitle(PhoneSideCall, _call.Name, _call.Number) ?? string.Empty;
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

    public bool IsIncoming => Status == BluetoothCallStatus.Incoming;
    public bool IsNotIncoming => !IsIncoming;
    public bool IsHeld => Status == BluetoothCallStatus.Held;
    public bool IsMuted => _call.IsMuted;

    public bool CanHold => !_working && Status is BluetoothCallStatus.Talking or BluetoothCallStatus.Held;
    public bool CanMute => CanHold;
    public string HoldText => IsHeld ? "Resume" : "Hold";
    public string MuteText => IsMuted ? "Unmute" : "Mute";

    public bool CanSwitchAudio => !_working && !IsIncoming && _call.AudioRoute != CallAudioRoute.Unknown;
    public string AudioRouteText => OnPhone ? "Audio: phone" : "Audio: this PC";
    public string AudioSwitchText => OnPhone ? "Use this PC" : "Use phone";

    public Task AnswerAsync() => PerformAsync(_call.AcceptAsync, "Could not answer the call.");
    public Task DeclineAsync() => PerformAsync(_call.RejectAsync, "Could not decline the call.");
    public Task EndAsync() => PerformAsync(_call.EndAsync, "Could not end the call.");
    public Task ToggleHoldAsync() => PerformAsync(IsHeld ? _call.ResumeAsync : _call.HoldAsync, "The phone did not change hold.");
    public Task ToggleMuteAsync() => PerformAsync(IsMuted ? _call.UnmuteAsync : _call.MuteAsync, "Could not change mute.");

    public Task SwitchAudioAsync()
    {
        CallAudioRoute target = OnPhone ? CallAudioRoute.ThisPc : CallAudioRoute.Phone;
        return PerformAsync(() => _call.SetAudioRouteAsync(target), "Could not move the call audio.");
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _call.Changed -= OnHandsFreeCallChanged;
        _phoneCalls.Changed -= OnPhoneCallChanged;
        _clock?.Stop();
    }

    private bool OnPhone => _call.AudioRoute == CallAudioRoute.Phone;

    private PhoneCall? PhoneSideCall => _peerId is null ? null : _phoneCalls.GetCurrentCall(_peerId);

    private async Task PerformAsync(Func<Task<bool>> operation, string failureMessage)
    {
        if (_working) return;
        SetWorking(true);
        ErrorText = string.Empty;
        try
        {
            if (!await operation()) ErrorText = failureMessage;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Bluetooth call operation failed", error);
            ErrorText = failureMessage;
        }
        finally
        {
            SetWorking(false);
        }
    }

    private void SetWorking(bool working)
    {
        _working = working;
        RaiseAll(ActionProperties);
    }

    private void OnHandsFreeCallChanged() => RunOnUiThread(() =>
    {
        RefreshAll();
        StateChanged?.Invoke();
    });

    private void OnPhoneCallChanged(CallChange change)
    {
        if (string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase))
            RunOnUiThread(() =>
            {
                if (!_disposed) RaiseAll(CallerProperties);
            });
    }

    private void RefreshAll()
    {
        if (_disposed) return;
        RaiseAll(CallerProperties);
        RaiseAll(CallStateProperties);
        RaiseAll(ActionProperties);
        UpdateStatusText();
    }

    /// <summary>Shows the call state, and the running time while the call is connected.</summary>
    private void UpdateStatusText()
    {
        if (_disposed) return;
        if (Status == BluetoothCallStatus.Talking)
        {
            _connectedAt ??= DateTimeOffset.UtcNow;
            if (_clock is { IsRunning: false }) _clock.Start();
        }
        else
        {
            _clock?.Stop();
        }
        TimeSpan? elapsed = _connectedAt is { } start ? DateTimeOffset.UtcNow - start : null;
        StatusText = CallPresentation.StatusText(Status, elapsed);
    }

    private void RaiseAll(string[] propertyNames)
    {
        foreach (string name in propertyNames) OnPropertyChanged(name);
    }

    private void RunOnUiThread(Action action)
    {
        if (_uiThread is null || _uiThread.HasThreadAccess) action();
        else _uiThread.TryEnqueue(() => action());
    }
}

/// <summary>Binding source of the in-call window: the call it currently shows, or none.</summary>
public sealed class CallWindowViewModel : BaseViewModel
{
    private InCallViewModel? _call;

    public InCallViewModel? Call
    {
        get => _call;
        set => SetProperty(ref _call, value);
    }
}
