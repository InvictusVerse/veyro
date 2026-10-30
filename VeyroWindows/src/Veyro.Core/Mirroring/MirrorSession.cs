using Veyro.Core.Link;

namespace Veyro.Core.Mirroring;

public enum MirrorPhase { Idle, Starting, Asking, Connecting, Streaming, Paused, Stopped, Unavailable }

/// <summary>The phone's sound in a mirroring session: internal audio only, never the microphone.</summary>
public enum PhoneAudioState { Off, Unsupported, Waiting, Playing, Denied, Blocked }

/// <summary>What the mirror window shows. Texts are in the user's words; no ids, no names in logs.</summary>
public sealed record MirrorStatus(
    MirrorPhase Phase,
    string Title,
    string? Detail,
    bool CanAskAgain,
    string AskAgainLabel,
    PhoneAudioState Audio,
    string? AudioText,
    bool CanAskAudioAgain)
{
    public bool IsLive => Phase is MirrorPhase.Starting or MirrorPhase.Asking or MirrorPhase.Connecting or MirrorPhase.Streaming or MirrorPhase.Paused;
}

/// <summary>
/// One mirroring session as the PC sees it (ADR-066), from <c>streamStart</c> to the end. Turns
/// the phone's <c>streamState</c> / <c>streamStop</c>, the media socket and timeouts into a
/// <see cref="MirrorStatus"/>, including a refusal on the phone: screen sharing declined, or the
/// audio permission denied or blocked, each with "Ask again" as often as the user likes (each
/// press at most once per <see cref="AskInterval"/>, so a stuck key cannot flood the phone).
/// Pure apart from the clock, so it is unit-tested. Not thread-safe: the owner serialises calls.
/// </summary>
public sealed class MirrorSession
{
    public static readonly TimeSpan AnswerTimeout = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan ConsentTimeout = TimeSpan.FromSeconds(75);
    public static readonly TimeSpan AskInterval = TimeSpan.FromSeconds(3);

    private readonly string _phone;
    private readonly TimeProvider _time;
    private long _phaseSince;
    private long _lastAsk = long.MinValue;
    private long _lastAudioAsk = long.MinValue;
    private bool _audioRequested;
    private bool _audioSupported;
    private PhoneAudioState _audio = PhoneAudioState.Off;
    private MirrorPhase _phase = MirrorPhase.Idle;
    private string? _reason;

    public MirrorSession(string phoneName, TimeProvider? time = null)
    {
        _phone = string.IsNullOrWhiteSpace(phoneName) ? "the phone" : phoneName;
        _time = time ?? TimeProvider.System;
        Status = Build();
    }

    /// <summary>The live session's id, or null between sessions.</summary>
    public string? Sid { get; private set; }

    /// <summary>The values in use, from the phone's latest <c>streamState</c>.</summary>
    public PeerMessage.StreamState? LastState { get; private set; }

    public MirrorStatus Status { get; private set; }

    /// <summary>Raised after every change of <see cref="Status"/>.</summary>
    public event Action<MirrorStatus>? Changed;

    /// <summary>Why the PC cannot start now, or null when it can.</summary>
    public static string? WhyUnavailable(string phone, PeerMessage.StreamCaps? caps, StreamPlan? plan)
    {
        if (caps is null) return $"{phone} has not said it can share its screen. Update Veyro on the phone.";
        if (!caps.Allowed) return $"Mirroring is off on {phone}. In Veyro on the phone, turn on \"Let PCs mirror this screen\".";
        if (plan is null) return $"{phone} and this PC have no video format in common.";
        return null;
    }

    /// <summary>
    /// Builds <c>streamStart</c> for <paramref name="plan"/> and enters <see cref="MirrorPhase.Starting"/>,
    /// or enters <see cref="MirrorPhase.Unavailable"/> and returns null.
    /// </summary>
    public PeerMessage.StreamStart? Begin(string sid, PeerMessage.StreamCaps? caps, StreamPlan? plan, bool audio, string host, int port)
    {
        _lastAsk = _time.GetTimestamp();
        if (WhyUnavailable(_phone, caps, plan) is { } why)
        {
            Sid = null;
            _reason = why;
            Enter(MirrorPhase.Unavailable);
            return null;
        }
        Sid = sid;
        LastState = null;
        _reason = null;
        _audioRequested = audio;
        _audioSupported = caps!.Audio;
        _audio = !audio ? PhoneAudioState.Off
            : !caps.Audio ? PhoneAudioState.Unsupported
            : caps.AudioPermission == StreamRules.AudioBlocked ? PhoneAudioState.Blocked
            : PhoneAudioState.Waiting;
        Enter(MirrorPhase.Starting);
        return new PeerMessage.StreamStart(sid, StreamRules.SourceScreen, plan!.Codec, plan.HwEncode, plan.MaxLong,
            plan.Fps, plan.Bitrate, audio && caps.Audio, host, port);
    }

    /// <summary>"Ask again" / "Start again" may be pressed now (rate limit and phase).</summary>
    public bool MayAskAgain()
    {
        if (Status.IsLive || !Status.CanAskAgain) return false;
        return Elapsed(_lastAsk) >= AskInterval;
    }

    /// <summary>"Ask again" for the phone's sound may be pressed now (rate limit and state).</summary>
    public bool MayAskAudioAgain() => Status.CanAskAudioAgain && Sid is not null && Elapsed(_lastAudioAsk) >= AskInterval;

    /// <summary>The PC cannot start for a reason of its own (no connection, no address, no port). <paramref name="detail"/> is shown as is.</summary>
    public void SetUnavailable(string detail)
    {
        _lastAsk = _time.GetTimestamp();
        Sid = null;
        _reason = detail;
        Enter(MirrorPhase.Unavailable);
    }

    /// <summary>The phone's name as shown in the texts.</summary>
    public string PhoneName => _phone;

    public void OnState(PeerMessage.StreamState state)
    {
        if (state.Sid != Sid || !Status.IsLive || !StreamRules.IsValid(state)) return;
        LastState = state;
        UpdateAudio(state);
        switch (state.State)
        {
            case StreamRules.StateAsking: Enter(MirrorPhase.Asking); break;
            case StreamRules.StateStarting: if (_phase is MirrorPhase.Starting or MirrorPhase.Asking) Enter(MirrorPhase.Connecting); else Publish(); break;
            case StreamRules.StateStreaming: Enter(MirrorPhase.Streaming); break;
            case StreamRules.StatePaused: Enter(MirrorPhase.Paused); break;
            case StreamRules.StateStopped: Stop(state.Reason ?? "error"); break;
        }
    }

    public void OnStop(PeerMessage.StreamStop stop)
    {
        if (stop.Sid != Sid || !Status.IsLive || !StreamRules.IsValid(stop)) return;
        Stop(stop.Reason);
    }

    /// <summary>The media socket authenticated.</summary>
    public void OnSocketConnected()
    {
        if (_phase is MirrorPhase.Starting or MirrorPhase.Asking or MirrorPhase.Connecting) Enter(MirrorPhase.Streaming);
    }

    /// <summary>The media socket closed or failed while the session was live.</summary>
    public void OnSocketLost()
    {
        if (Status.IsLive) Stop("network");
    }

    /// <summary>The phone's control session closed.</summary>
    public void OnPhoneDisconnected()
    {
        if (Status.IsLive) Stop("session-closed");
    }

    /// <summary>The user closed the window or pressed Stop. Returns the <c>streamStop</c> to send, if any.</summary>
    public PeerMessage.StreamStop? UserStop()
    {
        if (!Status.IsLive || Sid is null) return null;
        var stop = new PeerMessage.StreamStop(Sid, "user");
        Stop("user");
        return stop;
    }

    /// <summary>Checks the answer and consent timeouts; call about once a second. Returns a <c>streamStop</c> to send on timeout.</summary>
    public PeerMessage.StreamStop? Tick()
    {
        bool late = _phase switch
        {
            MirrorPhase.Starting => Elapsed(_phaseSince) >= AnswerTimeout,
            MirrorPhase.Asking or MirrorPhase.Connecting => Elapsed(_phaseSince) >= ConsentTimeout,
            _ => false,
        };
        if (!late || Sid is null) return null;
        var stop = new PeerMessage.StreamStop(Sid, "timeout");
        Stop("timeout");
        return stop;
    }

    /// <summary>
    /// "Ask again" for the phone's sound. Returns the <c>streamAskAudio</c> to send, or null when
    /// it is not needed now or was pressed less than <see cref="AskInterval"/> ago.
    /// </summary>
    public PeerMessage.StreamAskAudio? AskAudioAgain()
    {
        if (!Status.CanAskAudioAgain || Sid is null || Elapsed(_lastAudioAsk) < AskInterval) return null;
        _lastAudioAsk = _time.GetTimestamp();
        _audio = PhoneAudioState.Waiting;
        Publish();
        return new PeerMessage.StreamAskAudio(Sid);
    }

    /// <summary>
    /// The user switched the phone's sound on or off during a live session. Returns the
    /// <c>streamConfig</c> to send, or null when nothing changes.
    /// </summary>
    public PeerMessage.StreamConfig? SetAudioRequested(bool audio)
    {
        if (!Status.IsLive || Sid is null || audio == _audioRequested) return null;
        _audioRequested = audio;
        _audio = !audio ? PhoneAudioState.Off : !_audioSupported ? PhoneAudioState.Unsupported : PhoneAudioState.Waiting;
        Publish();
        return _audioSupported ? new PeerMessage.StreamConfig(Sid, Audio: audio) : null;
    }

    private void UpdateAudio(PeerMessage.StreamState state)
    {
        if (!_audioRequested) { _audio = PhoneAudioState.Off; return; }
        if (!_audioSupported) { _audio = PhoneAudioState.Unsupported; return; }
        if (state.Audio == true) { _audio = PhoneAudioState.Playing; return; }
        _audio = state.AudioPermission switch
        {
            StreamRules.AudioDenied => PhoneAudioState.Denied,
            StreamRules.AudioBlocked => PhoneAudioState.Blocked,
            _ => PhoneAudioState.Waiting,
        };
    }

    private void Stop(string reason)
    {
        _reason = reason;
        Enter(MirrorPhase.Stopped);
    }

    private void Enter(MirrorPhase phase)
    {
        _phase = phase;
        _phaseSince = _time.GetTimestamp();
        Publish();
    }

    private void Publish()
    {
        Status = Build();
        Changed?.Invoke(Status);
    }

    private TimeSpan Elapsed(long since) => since == long.MinValue ? TimeSpan.MaxValue : _time.GetElapsedTime(since);

    private MirrorStatus Build()
    {
        (string title, string? detail) = _phase switch
        {
            MirrorPhase.Idle => ("Not mirroring", (string?)null),
            MirrorPhase.Unavailable => ("Cannot mirror right now", _reason),
            MirrorPhase.Starting => ($"Asking {_phone}…", "Waiting for the phone to answer."),
            MirrorPhase.Asking => ($"Allow screen sharing on {_phone}", "Tap \"Start now\" in the dialog on the phone."),
            MirrorPhase.Connecting => ($"Connecting to {_phone}…", null),
            MirrorPhase.Streaming => (_phone, null),
            MirrorPhase.Paused => ("Paused", "The phone's screen is off, or this window is minimised."),
            _ => StopText(_reason ?? "error"),
        };
        bool stopped = _phase is MirrorPhase.Stopped or MirrorPhase.Unavailable;
        string label = _reason is "declined" or "timeout" or "permission" ? "Ask again" : "Start again";
        bool live = _phase is MirrorPhase.Starting or MirrorPhase.Asking or MirrorPhase.Connecting or MirrorPhase.Streaming or MirrorPhase.Paused;
        PhoneAudioState audio = live ? _audio : PhoneAudioState.Off;
        string? audioText = audio switch
        {
            PhoneAudioState.Unsupported => $"{_phone} cannot share its sound (it needs Android 10 or later).",
            PhoneAudioState.Waiting => $"Waiting for {_phone} to allow its sound.",
            PhoneAudioState.Denied => $"Phone sound was denied on {_phone}.",
            PhoneAudioState.Blocked => $"Android on {_phone} will not ask again. Ask again opens Veyro's settings on the phone: Permissions > Microphone > Allow. Veyro uses it only for the phone's own sound, never the microphone.",
            _ => null,
        };
        return new MirrorStatus(_phase, title, detail, stopped, label, audio, audioText,
            live && audio is PhoneAudioState.Denied or PhoneAudioState.Blocked or PhoneAudioState.Waiting);
    }

    private (string, string?) StopText(string reason) => reason switch
    {
        "declined" => ($"Screen sharing was declined on {_phone}", "You can ask again; the phone shows its dialog each time."),
        "timeout" => ($"{_phone} did not answer in time", "Make sure the phone is unlocked and Veyro may show notifications."),
        "busy" => ($"{_phone} is already sharing its screen", "Stop the other session first."),
        "disabled" => ($"Mirroring is off on {_phone}", "In Veyro on the phone, turn on \"Let PCs mirror this screen\"."),
        "permission" => ($"Veyro on {_phone} is missing a permission", "Open Veyro on the phone; it will say which."),
        "unsupported" => ($"{_phone} cannot mirror with these settings", "Pick a lower quality and try again."),
        "projection-ended" => ($"Screen sharing was stopped on {_phone}", null),
        "locked" => ($"{_phone} is locked", "Unlock the phone and start again."),
        "thermal" => ($"{_phone} got too hot", "Mirroring stopped to protect the phone. Let it cool down."),
        "encoder" => ($"{_phone}'s video encoder stopped", "Start again, or turn off hardware encoding."),
        "network" => ($"The connection to {_phone} was lost", null),
        "session-closed" => ($"{_phone} disconnected", null),
        "user" => ("Mirroring stopped", null),
        _ => ("Mirroring stopped because of an error", null),
    };
}
