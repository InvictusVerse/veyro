using Veyro.Core.Diagnostics;
using Veyro.Core.Link;
using Veyro.Core.Protocol;

namespace Veyro.Core.Photos;

/// <summary>What became of a photo or scan request.</summary>
public sealed record CaptureOutcome(string? FilePath, string? Reason)
{
    public bool Ok => FilePath is not null;
}

/// <summary>
/// "Take photo" / "Scan document" with the phone (ADR-073, 04-PROTOCOL.md 6.10). Sends
/// <c>captureReq</c>, then accepts, without a consent prompt, exactly one image transfer whose
/// offer carries that request's id. The file is moved from the session cache into the received
/// files folder under a free name (never overwriting). The phone's <c>captureResult</c>, the
/// session closing or <see cref="SpeakerCaptureRules.CaptureTimeout"/> end a request without one.
/// Images and names are user data: only outcomes are logged.
/// </summary>
public sealed class CaptureService : IRequestedOfferHandler, IDisposable
{
    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase) { ".jpg", ".jpeg", ".png" };

    private readonly IPeerSessionRegistry _sessions;
    private readonly string _cacheRoot;
    private readonly Func<string> _destination;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, Pending> _pending = new(StringComparer.Ordinal);

    private sealed class Pending(string peerId, string mode)
    {
        public string PeerId { get; } = peerId;
        public string Mode { get; } = mode;
        public TaskCompletionSource<CaptureOutcome> Done { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public bool Claimed;
        public ITimer? Timer;
    }

    /// <param name="cacheRoot">App cache; transfers land in a per-request folder there first.</param>
    /// <param name="destination">The received files folder (Settings), read when a file arrives.</param>
    public CaptureService(IPeerSessionRegistry sessions, string cacheRoot, Func<string> destination, TimeProvider? time = null)
    {
        _sessions = sessions;
        _cacheRoot = cacheRoot;
        _destination = destination;
        _time = time ?? TimeProvider.System;
        _sessions.StreamMessageReceived += OnMessage;
        _sessions.SessionClosed += OnSessionClosed;
    }

    public bool CanUse(string peerId) =>
        _sessions.IsConnected(peerId) && (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.PhotoCapture) != 0;

    /// <summary>Asks <paramref name="peerId"/> for a photo or scan and waits for the result.</summary>
    public async Task<CaptureOutcome> RequestAsync(string peerId, string mode, CancellationToken ct = default)
    {
        if (mode is not (SpeakerCaptureRules.ModePhoto or SpeakerCaptureRules.ModeScan)) throw new ArgumentException("Unknown mode.", nameof(mode));
        if (!CanUse(peerId)) return new CaptureOutcome(null, "unsupported");
        string rid = StreamRules.NewSid();
        var pending = new Pending(peerId, mode);
        lock (_gate)
        {
            // One request per phone: a new one replaces what is still waiting.
            foreach (var (key, old) in _pending.Where(p => string.Equals(p.Value.PeerId, peerId, StringComparison.OrdinalIgnoreCase) && !p.Value.Claimed).ToList())
                Finish(key, old, new CaptureOutcome(null, "cancelled"));
            _pending[rid] = pending;
            pending.Timer = _time.CreateTimer(_ => Fail(rid, "expired"), null, SpeakerCaptureRules.CaptureTimeout, Timeout.InfiniteTimeSpan);
        }
        try
        {
            await _sessions.SendAsync(peerId, new PeerMessage.CaptureRequest(rid, mode), ct).ConfigureAwait(false);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException)
        {
            Fail(rid, "network");
        }
        using (ct.Register(() => Fail(rid, "cancelled")))
            return await pending.Done.Task.ConfigureAwait(false);
    }

    public RequestedOfferClaim? TryClaim(string peerId, PeerMessage.TransferOffer offer)
    {
        if (offer.RequestId is not { } rid) return null;
        lock (_gate)
        {
            if (!_pending.TryGetValue(rid, out var pending) || pending.Claimed ||
                !string.Equals(pending.PeerId, peerId, StringComparison.OrdinalIgnoreCase)) return null;
            bool expected = offer.Items is { Count: 1 } items && !items[0].IsDir && RelativePath.IsValid(items[0].RelPath) &&
                ImageExtensions.Contains(Path.GetExtension(items[0].RelPath));
            if (!expected)
            {
                Finish(rid, pending, new CaptureOutcome(null, "error"));
                return null;
            }
            pending.Claimed = true;
        }
        // rid is base64url from NewSid: safe as a folder name.
        string folder = Path.Combine(_cacheRoot, "captures", rid);
        Directory.CreateDirectory(folder);
        return new RequestedOfferClaim(folder);
    }

    public void OnCompleted(string peerId, string requestId, IReadOnlyList<string> filePaths)
    {
        Pending? pending;
        lock (_gate) if (!_pending.TryGetValue(requestId, out pending)) return;
        if (filePaths.Count != 1)
        {
            Fail(requestId, "error");
            return;
        }
        try
        {
            string target = FreeName(_destination(), Path.GetFileName(filePaths[0]));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Move(filePaths[0], target);
            TryDeleteFolder(Path.GetDirectoryName(filePaths[0]));
            lock (_gate) Finish(requestId, pending, new CaptureOutcome(target, null));
            VeyroTrace.Info($"Capture received: mode={pending.Mode}");
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            VeyroTrace.Warn($"Capture not saved: {error.GetType().Name}");
            Fail(requestId, "error");
        }
    }

    public void OnFailed(string peerId, string requestId, Exception error) => Fail(requestId, "error");

    /// <summary>"Photo 20261031-101500.jpg", or "Photo 20261031-101500 (2).jpg" when taken.</summary>
    public static string FreeName(string folder, string name)
    {
        string stem = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name);
        string candidate = Path.Combine(folder, name);
        for (int n = 2; File.Exists(candidate) && n < 10_000; n++) candidate = Path.Combine(folder, $"{stem} ({n}){ext}");
        return candidate;
    }

    private void OnMessage(string peerId, PeerMessage message)
    {
        if (message is not PeerMessage.CaptureResult result || !SpeakerCaptureRules.IsValid(result) || result.Ok) return;
        lock (_gate)
        {
            if (!_pending.TryGetValue(result.Rid, out var pending) || pending.Claimed ||
                !string.Equals(pending.PeerId, peerId, StringComparison.OrdinalIgnoreCase)) return;
            Finish(result.Rid, pending, new CaptureOutcome(null, result.Reason ?? "error"));
        }
    }

    private void OnSessionClosed(string peerId)
    {
        lock (_gate)
        {
            foreach (var (rid, pending) in _pending.Where(p => string.Equals(p.Value.PeerId, peerId, StringComparison.OrdinalIgnoreCase)).ToList())
                Finish(rid, pending, new CaptureOutcome(null, "network"));
        }
    }

    private void Fail(string rid, string reason)
    {
        lock (_gate)
        {
            if (_pending.TryGetValue(rid, out var pending)) Finish(rid, pending, new CaptureOutcome(null, reason));
        }
    }

    /// <summary>Under <see cref="_gate"/>.</summary>
    private void Finish(string rid, Pending pending, CaptureOutcome outcome)
    {
        _pending.Remove(rid);
        pending.Timer?.Dispose();
        if (pending.Done.TrySetResult(outcome) && !outcome.Ok) VeyroTrace.Info($"Capture ended: {outcome.Reason}");
    }

    private static void TryDeleteFolder(string? folder)
    {
        try { if (folder is not null && Directory.Exists(folder) && !Directory.EnumerateFileSystemEntries(folder).Any()) Directory.Delete(folder); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    public void Dispose()
    {
        _sessions.StreamMessageReceived -= OnMessage;
        _sessions.SessionClosed -= OnSessionClosed;
        lock (_gate)
        {
            foreach (var (rid, pending) in _pending.ToList()) Finish(rid, pending, new CaptureOutcome(null, "cancelled"));
        }
    }
}

/// <summary>Several features that ask the phone for files (photos, captures) share LinkServer's one handler slot.</summary>
public sealed class RequestedOfferRouter(params IRequestedOfferHandler[] handlers) : IRequestedOfferHandler
{
    private readonly object _gate = new();
    private readonly Dictionary<(string Peer, string Request), IRequestedOfferHandler> _owners = new();

    public RequestedOfferClaim? TryClaim(string peerId, PeerMessage.TransferOffer offer)
    {
        foreach (IRequestedOfferHandler handler in handlers)
        {
            if (handler.TryClaim(peerId, offer) is not { } claim) continue;
            lock (_gate) _owners[(peerId, offer.RequestId!)] = handler;
            return claim;
        }
        return null;
    }

    public void OnCompleted(string peerId, string requestId, IReadOnlyList<string> filePaths) => Take(peerId, requestId)?.OnCompleted(peerId, requestId, filePaths);

    public void OnFailed(string peerId, string requestId, Exception error) => Take(peerId, requestId)?.OnFailed(peerId, requestId, error);

    private IRequestedOfferHandler? Take(string peerId, string requestId)
    {
        lock (_gate) return _owners.Remove((peerId, requestId), out var handler) ? handler : null;
    }
}
