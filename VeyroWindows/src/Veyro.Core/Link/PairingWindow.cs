using System.Net;
using Veyro.Core.Crypto;
using Veyro.Core.Diagnostics;
using Veyro.Core.Models;

namespace Veyro.Core.Link;

/// <summary>One use of the open window's code by an inbound connection.</summary>
public sealed record PairingAttempt(string Code, long Generation)
{
    public override string ToString() => $"PairingAttempt(generation={Generation})";
}

/// <summary>
/// The PC's user-initiated pairing window (04-PROTOCOL.md 4.2, ADR-030). While it is open, an
/// unknown device that connects pairs with the window's one-time code instead of a fresh PIN and
/// without a consent prompt: showing the screen is the consent.
///
/// Rules: a fresh random 6-digit code per window; at most 5 minutes; closed by the UI or a
/// successful pairing; after 5 failed bootstrap attempts the code is replaced. UI-free and
/// thread-safe. The code and the QR content are never logged.
/// </summary>
public sealed class PairingWindow : IDisposable
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);
    public const int MaxFailures = 5;

    private readonly string _localDeviceId;
    private readonly Func<string> _localName;
    private readonly Func<IReadOnlyList<IPEndPoint>> _endpoints;
    private readonly TimeProvider _time;
    private readonly Lock _gate = new();
    private string? _code;
    private DateTimeOffset _expiresAt;
    private int _failures;
    private long _generation;
    private ITimer? _expiryTimer;

    public PairingWindow(
        string localDeviceId,
        Func<string> localName,
        Func<IReadOnlyList<IPEndPoint>> endpoints,
        TimeProvider? timeProvider = null)
    {
        _localDeviceId = localDeviceId;
        _localName = localName;
        _endpoints = endpoints;
        _time = timeProvider ?? TimeProvider.System;
    }

    /// <summary>Opened, closed, expired or code replaced. Raised on the caller's or a timer thread.</summary>
    public event Action? Changed;

    /// <summary>A device paired through this window, which is now closed.</summary>
    public event Action<TrustedPeer>? Completed;

    public bool IsOpen
    {
        get
        {
            ExpireIfDue();
            lock (_gate) return _code is not null;
        }
    }

    /// <summary>The current code, or null when closed. For display only; never log it.</summary>
    public string? Code
    {
        get
        {
            ExpireIfDue();
            lock (_gate) return _code;
        }
    }

    public DateTimeOffset? ExpiresAt
    {
        get
        {
            ExpireIfDue();
            lock (_gate) return _code is null ? null : _expiresAt;
        }
    }

    public TimeSpan Remaining
    {
        get
        {
            ExpireIfDue();
            lock (_gate)
            {
                if (_code is null) return TimeSpan.Zero;
                TimeSpan left = _expiresAt - _time.GetUtcNow();
                return left > TimeSpan.Zero ? left : TimeSpan.Zero;
            }
        }
    }

    /// <summary>Failed bootstrap attempts against the current code.</summary>
    public int Failures
    {
        get { lock (_gate) return _failures; }
    }

    /// <summary>
    /// The QR string for the current code, or null when closed or when the listener has no
    /// advertisable IPv4 endpoint. Recomputed on each read so network changes are picked up.
    /// </summary>
    public string? QrContent
    {
        get
        {
            ExpireIfDue();
            string code;
            DateTimeOffset expiresAt;
            lock (_gate)
            {
                if (_code is null) return null;
                code = _code;
                expiresAt = _expiresAt;
            }
            IReadOnlyList<IPEndPoint> endpoints = _endpoints();
            if (endpoints.Count == 0) return null;
            return PairingQr.Build(new PairingQrPayload(
                _localDeviceId, _localName(), endpoints, code, expiresAt.ToUnixTimeSeconds()));
        }
    }

    /// <summary>Opens the window, or replaces the code and restarts the clock if already open.</summary>
    public void Open()
    {
        lock (_gate) OpenLocked();
        VeyroTrace.Info($"Pairing window opened for {(int)Lifetime.TotalSeconds} s");
        Raise(Changed);
    }

    public void Close()
    {
        bool wasOpen;
        lock (_gate) wasOpen = CloseLocked();
        if (!wasOpen) return;
        VeyroTrace.Info("Pairing window closed");
        Raise(Changed);
    }

    /// <summary>The code an inbound unknown device must use, or null when no window is open.</summary>
    public PairingAttempt? TryBeginAttempt()
    {
        ExpireIfDue();
        lock (_gate) return _code is null ? null : new PairingAttempt(_code, _generation);
    }

    /// <summary>Counts a failed bootstrap against the current code.</summary>
    public bool RecordFailure()
    {
        PairingAttempt? current;
        lock (_gate) current = _code is null ? null : new PairingAttempt(_code, _generation);
        return current is not null && RecordFailure(current);
    }

    /// <summary>
    /// Counts a failed bootstrap. A failure against a code that has since been replaced is
    /// ignored. Returns true when this failure used up the limit and a new code was issued.
    /// </summary>
    public bool RecordFailure(PairingAttempt attempt)
    {
        int failures;
        lock (_gate)
        {
            if (_code is null || attempt.Generation != _generation) return false;
            failures = ++_failures;
            if (failures < MaxFailures) return false;
            CloseLocked();
            OpenLocked();
        }
        VeyroTrace.Warn($"Pairing window: {MaxFailures} failed attempts; code replaced");
        Raise(Changed);
        return true;
    }

    /// <summary>A device paired with the window's code: the window closes.</summary>
    public void Complete(TrustedPeer peer)
    {
        lock (_gate) CloseLocked();
        VeyroTrace.Info($"Pairing window completed by {peer.DeviceId}");
        Raise(Changed);
        try { Completed?.Invoke(peer); }
        catch (Exception error) { VeyroTrace.Error("Pairing window Completed handler failed", error); }
    }

    /// <summary>Closes the window if its time is up. Called by the expiry timer and on every read.</summary>
    public void ExpireIfDue()
    {
        bool expired = false;
        lock (_gate)
        {
            if (_code is not null && _time.GetUtcNow() >= _expiresAt)
                expired = CloseLocked();
        }
        if (!expired) return;
        VeyroTrace.Info("Pairing window expired");
        Raise(Changed);
    }

    public void Dispose()
    {
        lock (_gate) CloseLocked();
    }

    private void OpenLocked()
    {
        _expiryTimer?.Dispose();
        _code = FrameCrypto.NewPin();
        _expiresAt = _time.GetUtcNow() + Lifetime;
        _failures = 0;
        _generation++;
        _expiryTimer = _time.CreateTimer(_ => ExpireIfDue(), null, Lifetime, Timeout.InfiniteTimeSpan);
    }

    private bool CloseLocked()
    {
        _expiryTimer?.Dispose();
        _expiryTimer = null;
        bool wasOpen = _code is not null;
        _code = null;
        _failures = 0;
        if (wasOpen) _generation++;
        return wasOpen;
    }

    private static void Raise(Action? handler)
    {
        try { handler?.Invoke(); }
        catch (Exception error) { VeyroTrace.Error("Pairing window Changed handler failed", error); }
    }
}
