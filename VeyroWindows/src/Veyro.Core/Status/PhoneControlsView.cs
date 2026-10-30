using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Status;

/// <summary>The phone controls the PC offers next to ring, ringer and DND (ADR-060).</summary>
public enum PhoneControlKind { Torch, Volume, Brightness }

/// <summary>
/// Pure rules for the PC's flashlight, media volume and brightness controls (ADR-060), kept out
/// of the WinUI view model so they are testable: whether a control can be used, and if not, the
/// reason its tooltip gives.
/// </summary>
public static class PhoneControlsView
{
    /// <summary>
    /// Null when the control can be used now; otherwise why not. <paramref name="connected"/>: the
    /// phone has a live session. <paramref name="capable"/>: it advertises DeviceStatus and
    /// PhoneControls. <paramref name="status"/>: its latest (sanitized) devStatus.
    /// </summary>
    public static string? Unavailable(bool connected, bool capable, PeerMessage.DeviceStatus? status, PhoneControlKind kind)
    {
        if (!connected) return "The phone is not connected.";
        if (!capable) return "Veyro on this phone cannot do this yet. Update Veyro on the phone.";
        string? state = kind switch
        {
            PhoneControlKind.Torch => status?.Controls?.Torch,
            PhoneControlKind.Volume => status?.Controls?.Volume,
            _ => status?.Controls?.Brightness,
        };
        return state switch
        {
            PhoneControlState.Ok => null,
            PhoneControlState.Off =>
                "Not allowed on the phone. In Veyro's device settings there, turn on \"Let PCs control flashlight, volume and brightness\".",
            PhoneControlState.Permission =>
                "The phone needs one more permission. In Veyro's device settings there, tap \"Allow brightness changes\".",
            PhoneControlState.Busy => "Another app on the phone is using the flashlight, such as the camera.",
            PhoneControlState.Unsupported => kind switch
            {
                PhoneControlKind.Torch => "This phone has no flashlight.",
                PhoneControlKind.Volume => "This phone's media volume cannot be changed.",
                _ => "This phone does not report its brightness.",
            },
            _ => "The phone has not reported this yet.",
        };
    }
}

/// <summary>
/// What a slider shows while a change is on its way (ADR-060): the value the user picked, until the
/// phone reports that value or <see cref="HoldFor"/> passes, so a status that was already in flight
/// does not pull the slider back under the user's hand.
/// </summary>
public sealed class HeldLevel
{
    public static readonly TimeSpan HoldFor = TimeSpan.FromSeconds(2);

    private readonly TimeProvider _time;
    private int? _held;
    private DateTimeOffset _heldAt;

    public HeldLevel(TimeProvider? time = null) => _time = time ?? TimeProvider.System;

    public void Request(int value)
    {
        _held = value;
        _heldAt = _time.GetUtcNow();
    }

    /// <summary>The level to show given what the phone last reported.</summary>
    public int? Shown(int? reported)
    {
        if (_held is { } held)
        {
            if (reported != held && _time.GetUtcNow() - _heldAt <= HoldFor) return held;
            _held = null;
        }
        return reported;
    }
}

/// <summary>
/// Sends the latest of a stream of values with at most one send in flight (ADR-060): a slider
/// dragged across its range sends its first value at once, then only the newest value each time
/// the previous send finishes. The last value is always sent; none is sent twice in a row.
/// </summary>
public sealed class LatestValueSender<T> where T : struct
{
    private readonly Func<T, Task> _send;
    private readonly object _gate = new();
    private T? _pending;
    private bool _running;

    public LatestValueSender(Func<T, Task> send) => _send = send;

    /// <summary>Completes when the send this call started (if any) and those queued behind it are done.</summary>
    public Task Post(T value)
    {
        lock (_gate)
        {
            _pending = value;
            if (_running) return Task.CompletedTask;
            _running = true;
        }
        return RunAsync();
    }

    private async Task RunAsync()
    {
        while (true)
        {
            T value;
            lock (_gate)
            {
                if (_pending is not { } next)
                {
                    _running = false;
                    return;
                }
                value = next;
                _pending = null;
            }
            try { await _send(value).ConfigureAwait(true); }
            catch (Exception error) { VeyroTrace.Warn($"Phone control not sent: {error.GetType().Name}"); }
        }
    }
}
