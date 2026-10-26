using System.Globalization;
using Veyro.Core.Diagnostics;

namespace Veyro.Core.Calls;

/// <summary>State of the PC's Bluetooth hands-free link to the phone (ADR-029).</summary>
public enum BluetoothLineStatus
{
    /// <summary>Windows 10 or no calling contract: Bluetooth calling needs Windows 11.</summary>
    NotSupported,
    /// <summary>No phone paired over Bluetooth exposes the hands-free profile.</summary>
    NotPaired,
    /// <summary>Paired phones exist but none is chosen for calls.</summary>
    NotSelected,
    Connecting,
    Connected,
    /// <summary>Chosen but not connected (disconnected by the user or not reachable at startup).</summary>
    Disconnected,
    /// <summary>Windows refused access, or another app owns the phone line.</summary>
    AccessDenied,
    Error
}

/// <summary>Platform-neutral view of a Windows <c>PhoneCallStatus</c>.</summary>
public enum BluetoothCallStatus { Unknown, Incoming, Dialing, Talking, Held, Ended, Lost }

/// <summary>Where call audio plays: on this PC (hands-free) or on the phone.</summary>
public enum CallAudioRoute { Unknown, ThisPc, Phone }

public enum CallAnswerRoute { Bluetooth, WiFi }

/// <summary>
/// The part of the Bluetooth calling service the answer routing needs. Implemented on Windows by
/// the WinRT phone line service; faked in tests.
/// </summary>
public interface IBluetoothCallLine
{
    /// <summary>Raised on any thread when status, linked peer or the set of calls changes.</summary>
    event Action? Changed;

    BluetoothLineStatus Status { get; }

    /// <summary>The Veyro peer the connected Bluetooth phone was linked to, or null.</summary>
    string? LinkedPeerId { get; }

    /// <summary>A call on the Bluetooth line is ringing.</summary>
    bool HasIncomingCall { get; }

    /// <summary>Answers the ringing Bluetooth call with audio on this PC. False if none or it failed.</summary>
    Task<bool> AnswerIncomingAsync();

    /// <summary>Rejects the ringing Bluetooth call. False if none or it failed.</summary>
    Task<bool> RejectIncomingAsync();
}

/// <summary>
/// Chooses how a call is answered (ADR-029): over Bluetooth when the hands-free link is connected
/// to the same phone (audio on the PC), otherwise with the Wi-Fi <c>callAction</c> (audio on the
/// phone). Decline prefers Wi-Fi because it works whether or not Bluetooth is up.
/// </summary>
public sealed class CallRouter
{
    /// <summary>The hands-free ring can arrive shortly after the phone's Wi-Fi callState.</summary>
    public static readonly TimeSpan IncomingWait = TimeSpan.FromSeconds(3);

    private readonly ICallService _calls;
    private readonly IBluetoothCallLine _line;
    private readonly TimeProvider _time;

    public CallRouter(ICallService calls, IBluetoothCallLine line, TimeProvider? time = null)
    {
        _calls = calls;
        _line = line;
        _time = time ?? TimeProvider.System;
    }

    /// <summary>Forwards <see cref="IBluetoothCallLine.Changed"/> so UI can refresh route labels.</summary>
    public event Action? RouteChanged
    {
        add => _line.Changed += value;
        remove => _line.Changed -= value;
    }

    public bool IsBluetoothReadyFor(string? peerId)
        => peerId is not null
           && _line.Status == BluetoothLineStatus.Connected
           && string.Equals(_line.LinkedPeerId, peerId, StringComparison.OrdinalIgnoreCase);

    public CallAnswerRoute PreferredAnswerRoute(string? peerId)
        => IsBluetoothReadyFor(peerId) ? CallAnswerRoute.Bluetooth : CallAnswerRoute.WiFi;

    /// <summary>Answers the current call of <paramref name="peerId"/>; returns the route used.</summary>
    public async Task<CallAnswerRoute> AnswerAsync(string peerId, CancellationToken ct = default)
    {
        if (IsBluetoothReadyFor(peerId))
        {
            if (await WaitForIncomingAsync(ct).ConfigureAwait(false) && await _line.AnswerIncomingAsync().ConfigureAwait(false))
            {
                VeyroTrace.Info("Call answered over Bluetooth");
                return CallAnswerRoute.Bluetooth;
            }
            VeyroTrace.Warn("Bluetooth answer unavailable; answering on the phone");
        }
        await _calls.SendActionAsync(peerId, CallService.ActionAnswer, ct: ct).ConfigureAwait(false);
        return CallAnswerRoute.WiFi;
    }

    /// <summary>Declines over Wi-Fi when the phone reported the call there, else over Bluetooth.</summary>
    public async Task<CallAnswerRoute> DeclineAsync(string peerId, CancellationToken ct = default)
    {
        if (_calls.IsAvailable(peerId) && _calls.GetCurrentCall(peerId) is not null)
        {
            await _calls.SendActionAsync(peerId, CallService.ActionDecline, ct: ct).ConfigureAwait(false);
            return CallAnswerRoute.WiFi;
        }
        if (IsBluetoothReadyFor(peerId) && _line.HasIncomingCall && await _line.RejectIncomingAsync().ConfigureAwait(false))
            return CallAnswerRoute.Bluetooth;
        throw new InvalidOperationException("There is no call on the phone.");
    }

    private async Task<bool> WaitForIncomingAsync(CancellationToken ct)
    {
        if (_line.HasIncomingCall) return true;
        var ringing = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnChanged()
        {
            if (_line.HasIncomingCall) ringing.TrySetResult();
        }
        _line.Changed += OnChanged;
        try
        {
            if (_line.HasIncomingCall) return true;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            Task delay = Task.Delay(IncomingWait, _time, timeout.Token);
            Task winner = await Task.WhenAny(ringing.Task, delay).ConfigureAwait(false);
            timeout.Cancel();
            ct.ThrowIfCancellationRequested();
            return winner == ringing.Task;
        }
        finally
        {
            _line.Changed -= OnChanged;
        }
    }
}

/// <summary>
/// Reconciles a Bluetooth call with the phone's Wi-Fi <c>callState</c> for display (ADR-029).
/// Names and numbers are user data: shown, never logged.
/// </summary>
public static class CallPresentation
{
    public const string UnknownCaller = "Unknown caller";

    /// <summary>
    /// The in-call window shows calls whose audio can be on the PC. A ringing Bluetooth call only
    /// gets the window when Wi-Fi is down, because the Wi-Fi incoming-call toast covers it otherwise.
    /// </summary>
    public static bool ShowsWindow(BluetoothCallStatus status, bool wifiAvailable) => status switch
    {
        BluetoothCallStatus.Dialing or BluetoothCallStatus.Talking or BluetoothCallStatus.Held or BluetoothCallStatus.Unknown => true,
        BluetoothCallStatus.Incoming => !wifiAvailable,
        _ => false
    };

    public static bool IsOver(BluetoothCallStatus status) => status is BluetoothCallStatus.Ended or BluetoothCallStatus.Lost;

    /// <summary>The Wi-Fi callState carries the phone's contact name, so it wins over Bluetooth.</summary>
    public static string Title(PhoneCall? wifiCall, string? btName, string? btNumber)
        => Clean(wifiCall?.Name) ?? Clean(btName) ?? Clean(wifiCall?.Number) ?? Clean(btNumber) ?? UnknownCaller;

    /// <summary>The number under the name, or null when the title already is the number.</summary>
    public static string? Subtitle(PhoneCall? wifiCall, string? btName, string? btNumber)
    {
        string? number = Clean(wifiCall?.Number) ?? Clean(btNumber);
        return number is not null && Title(wifiCall, btName, btNumber) != number ? number : null;
    }

    public static string FormatDuration(TimeSpan elapsed)
    {
        elapsed = elapsed.Duration();
        return elapsed.TotalHours >= 1
            ? string.Create(CultureInfo.InvariantCulture, $"{(int)elapsed.TotalHours}:{elapsed.Minutes:00}:{elapsed.Seconds:00}")
            : string.Create(CultureInfo.InvariantCulture, $"{(int)elapsed.TotalMinutes}:{elapsed.Seconds:00}");
    }

    public static string StatusText(BluetoothCallStatus status, TimeSpan? talking) => status switch
    {
        BluetoothCallStatus.Talking when talking is { } t => FormatDuration(t),
        BluetoothCallStatus.Talking => "On a call",
        BluetoothCallStatus.Incoming => "Incoming call",
        BluetoothCallStatus.Dialing => "Calling...",
        BluetoothCallStatus.Held => "On hold",
        BluetoothCallStatus.Ended or BluetoothCallStatus.Lost => "Call ended",
        _ => "Connecting..."
    };

    private static string? Clean(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
