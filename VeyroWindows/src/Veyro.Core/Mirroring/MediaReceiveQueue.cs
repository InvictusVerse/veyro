using Veyro.Core.Protocol.Media;

namespace Veyro.Core.Mirroring;

/// <summary>
/// Access units waiting to be decoded on the PC (04-PROTOCOL.md 7.5.6): at most
/// <see cref="MaxUnits"/> or <see cref="MaxBytes"/>. On overflow or a sender
/// <c>DISCONTINUITY</c> it discards until the next keyframe and asks for one (at most once per
/// <see cref="KeyframeInterval"/>). The decoder never sees a frame whose reference is missing,
/// so the picture never smears. Thread-safe: the socket reader enqueues, the player dequeues.
/// </summary>
public sealed class MediaReceiveQueue
{
    public const int MaxUnits = 6;
    public const int MaxBytes = 16 * 1024 * 1024;
    public static readonly TimeSpan KeyframeInterval = TimeSpan.FromMilliseconds(500);

    private readonly object _gate = new();
    private readonly Queue<AccessUnit> _units = new();
    private readonly SemaphoreSlim _available = new(0);
    private readonly TimeProvider _time;
    private long _bytes;
    private bool _waitingForKey = true;
    private long _lastKeyRequest = long.MinValue;
    private bool _closed;

    public MediaReceiveQueue(TimeProvider? time = null) => _time = time ?? TimeProvider.System;

    /// <summary>Units dropped so far (for the bitrate controller and diagnostics).</summary>
    public long Dropped { get; private set; }

    public int Count { get { lock (_gate) return _units.Count; } }

    /// <summary>
    /// Adds <paramref name="unit"/>. Returns true when the caller should send
    /// <c>streamKeyframe</c> now (already throttled).
    /// </summary>
    public bool Enqueue(AccessUnit unit)
    {
        bool askKey = false;
        lock (_gate)
        {
            if (_closed) return false;
            if (unit.Discontinuity && !unit.Key) _waitingForKey = true;
            if (_waitingForKey && !unit.Key)
            {
                Dropped++;
                return TryKeyRequest();
            }
            if (_units.Count >= MaxUnits || _bytes + unit.Data.Length > MaxBytes)
            {
                // Overflow: everything queued is stale. Keep the new unit only if it can start a picture.
                Dropped += _units.Count;
                _units.Clear();
                _bytes = 0;
                DrainSignals();
                if (!unit.Key)
                {
                    Dropped++;
                    _waitingForKey = true;
                    return TryKeyRequest();
                }
            }
            if (unit.Key) _waitingForKey = false;
            _units.Enqueue(unit);
            _bytes += unit.Data.Length;
        }
        _available.Release();
        return askKey;
    }

    /// <summary>A decode error on the PC: drop until the next keyframe. Returns true to send <c>streamKeyframe</c>.</summary>
    public bool Resync()
    {
        lock (_gate)
        {
            Dropped += _units.Count;
            _units.Clear();
            _bytes = 0;
            DrainSignals();
            _waitingForKey = true;
            return TryKeyRequest();
        }
    }

    /// <summary>The next unit, or null once <see cref="Close"/> was called.</summary>
    public async Task<AccessUnit?> DequeueAsync(CancellationToken ct)
    {
        while (true)
        {
            await _available.WaitAsync(ct).ConfigureAwait(false);
            lock (_gate)
            {
                if (_units.TryDequeue(out AccessUnit? unit))
                {
                    _bytes -= unit.Data.Length;
                    return unit;
                }
                if (_closed) return null;
            }
        }
    }

    public void Close()
    {
        lock (_gate)
        {
            if (_closed) return;
            _closed = true;
            _units.Clear();
            _bytes = 0;
        }
        _available.Release(int.MaxValue / 2);
    }

    private bool TryKeyRequest()
    {
        long now = _time.GetTimestamp();
        if (_lastKeyRequest != long.MinValue && _time.GetElapsedTime(_lastKeyRequest, now) < KeyframeInterval) return false;
        _lastKeyRequest = now;
        return true;
    }

    private void DrainSignals()
    {
        while (_available.CurrentCount > 0 && _available.Wait(0)) { }
    }
}

/// <summary>
/// The PC's bitrate control (ADR-066: the PC owns quality). Every second it looks at what the
/// queue dropped: drops cut the bitrate to 70 %, four clean seconds add 10 % of the target back,
/// never below 15 % of the target or 500 kbit/s. Returns the bitrate to send in
/// <c>streamConfig</c> when it changed, else null. Pure.
/// </summary>
public sealed class BitrateController(int target)
{
    public const int Floor = 500_000;
    private int _clean;

    public int Target { get; private set; } = target;
    public int Current { get; private set; } = target;

    /// <summary>The user picked another quality mid-session.</summary>
    public void Retarget(int target)
    {
        Target = target;
        Current = Math.Min(Current, target);
        _clean = 0;
    }

    /// <summary>One second passed; <paramref name="droppedThisSecond"/> access units were dropped.</summary>
    public int? Tick(long droppedThisSecond)
    {
        int floor = Math.Max(Floor, (int)(Target * 0.15));
        int next = Current;
        if (droppedThisSecond > 0)
        {
            _clean = 0;
            next = Math.Max(floor, (int)(Current * 0.7));
        }
        else if (++_clean >= 4 && Current < Target)
        {
            _clean = 0;
            next = Math.Min(Target, Current + Target / 10);
        }
        if (next == Current) return null;
        Current = next;
        return next;
    }
}
