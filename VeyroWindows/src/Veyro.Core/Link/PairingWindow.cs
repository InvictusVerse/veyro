using System.Net;
using System.Security.Cryptography;
using Veyro.Core.Diagnostics;
using Veyro.Core.Models;

namespace Veyro.Core.Link;

/// <summary>
/// One use of the open window's secret by an inbound connection. <see cref="Secret"/> is a copy
/// of the 16-byte QR secret; the caller zeroes it once the pairing keys exist. Never logged.
/// </summary>
public sealed record PairingAttempt(byte[] Secret, long Generation)
{
    public override string ToString() => $"PairingAttempt(generation={Generation})";
}

/// <summary>
/// The PC's user-initiated pairing window (04-PROTOCOL.md 4.2, ADR-030). While it is open, an
/// unknown device that scans the QR code pairs without a consent prompt: showing the screen is
/// the consent. The QR code carries a fresh 128-bit secret (format 2, ADR-058); the phone proves
/// it holds it and the secret enters the pairing keys.
///
/// Rules: a fresh random 16-byte secret per window open, per "New code" and per replacement; at
/// most 5 minutes; closed by the UI or a successful pairing; after 5 failed attempts the secret
/// is replaced. The old secret is zeroed on replacement and on close. UI-free and thread-safe.
/// The secret and the QR content are never logged.
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
    private byte[]? _secret;
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
            lock (_gate) return _secret is not null;
        }
    }

    public DateTimeOffset? ExpiresAt
    {
        get
        {
            ExpireIfDue();
            lock (_gate) return _secret is null ? null : _expiresAt;
        }
    }

    public TimeSpan Remaining
    {
        get
        {
            ExpireIfDue();
            lock (_gate)
            {
                if (_secret is null) return TimeSpan.Zero;
                TimeSpan left = _expiresAt - _time.GetUtcNow();
                return left > TimeSpan.Zero ? left : TimeSpan.Zero;
            }
        }
    }

    /// <summary>Failed attempts against the current secret.</summary>
    public int Failures
    {
        get { lock (_gate) return _failures; }
    }

    /// <summary>
    /// The QR string for the current secret, or null when closed or when the listener has no
    /// advertisable IPv4 endpoint. Recomputed on each read so network changes are picked up.
    /// </summary>
    public string? QrContent
    {
        get
        {
            ExpireIfDue();
            byte[] secret;
            DateTimeOffset expiresAt;
            lock (_gate)
            {
                if (_secret is null) return null;
                secret = (byte[])_secret.Clone();
                expiresAt = _expiresAt;
            }
            try
            {
                IReadOnlyList<IPEndPoint> endpoints = _endpoints();
                if (endpoints.Count == 0) return null;
                return PairingQr.Build(new PairingQrPayload(
                    _localDeviceId, _localName(), endpoints, secret, expiresAt.ToUnixTimeSeconds()));
            }
            finally
            {
                CryptographicOperations.ZeroMemory(secret);
            }
        }
    }

    /// <summary>Opens the window, or replaces the secret and restarts the clock if already open.</summary>
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

    /// <summary>A copy of the secret an inbound unknown device must prove, or null when no window is open.</summary>
    public PairingAttempt? TryBeginAttempt()
    {
        ExpireIfDue();
        lock (_gate) return _secret is null ? null : new PairingAttempt((byte[])_secret.Clone(), _generation);
    }

    /// <summary>Counts a failed attempt against the current secret.</summary>
    public bool RecordFailure()
    {
        long generation;
        lock (_gate)
        {
            if (_secret is null) return false;
            generation = _generation;
        }
        return RecordFailure(new PairingAttempt([], generation));
    }

    /// <summary>
    /// Counts a failed attempt. A failure against a secret that has since been replaced is
    /// ignored. Returns true when this failure used up the limit and a new code was issued.
    /// </summary>
    public bool RecordFailure(PairingAttempt attempt)
    {
        int failures;
        lock (_gate)
        {
            if (_secret is null || attempt.Generation != _generation) return false;
            failures = ++_failures;
            if (failures < MaxFailures) return false;
            CloseLocked();
            OpenLocked();
        }
        VeyroTrace.Warn($"Pairing window: {MaxFailures} failed attempts; secret replaced");
        Raise(Changed);
        return true;
    }

    /// <summary>A device paired through the window: the window closes.</summary>
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
            if (_secret is not null && _time.GetUtcNow() >= _expiresAt)
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
        if (_secret is not null) CryptographicOperations.ZeroMemory(_secret);
        _secret = RandomNumberGenerator.GetBytes(PairingQr.SecretSize);
        _expiresAt = _time.GetUtcNow() + Lifetime;
        _failures = 0;
        _generation++;
        _expiryTimer = _time.CreateTimer(_ => ExpireIfDue(), null, Lifetime, Timeout.InfiniteTimeSpan);
    }

    private bool CloseLocked()
    {
        _expiryTimer?.Dispose();
        _expiryTimer = null;
        bool wasOpen = _secret is not null;
        if (_secret is not null) CryptographicOperations.ZeroMemory(_secret);
        _secret = null;
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
