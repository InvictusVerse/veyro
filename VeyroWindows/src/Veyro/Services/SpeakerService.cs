using Veyro.Core.Diagnostics;
using Veyro.Core.Mirroring;
using Veyro.Core.Protocol.Media;
using Veyro.Platform.Media;

namespace Veyro.Services;

/// <summary>
/// "Phone as speaker" on the PC (ADR-073): plays what this PC plays on one connected phone. Start
/// opens a VMS1 speaker listener, sends <c>speakerStart</c>, and once the phone dialled streams the
/// loopback capture as PCM. The phone's <c>speakerState</c> / <c>speakerStop</c>, its session
/// closing, or Stop here end it. One phone at a time. Status changes are raised on the UI thread.
/// </summary>
public sealed class SpeakerService : IDisposable
{
    private readonly IPeerSessionRegistry _sessions;
    private readonly string _pcId;
    private readonly DispatcherQueue? _dispatcher;
    private readonly object _gate = new();
    private Active? _active;

    private sealed class Active(string peerId, string sid)
    {
        public string PeerId { get; } = peerId;
        public string Sid { get; } = sid;
        public CancellationTokenSource Stop { get; } = new();
        public Vms1Listener? Listener;
        public Vms1Connection? Connection;
        public LoopbackCapture? Capture;
        public bool Playing;
    }

    public SpeakerService(IPeerSessionRegistry sessions, string pcId)
    {
        _sessions = sessions;
        _pcId = pcId;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _sessions.StreamMessageReceived += OnMessage;
        _sessions.SessionClosed += OnSessionClosed;
    }

    /// <summary>The phone the PC's sound goes to, or null.</summary>
    public string? PeerId { get { lock (_gate) return _active?.PeerId; } }

    public bool IsPlaying { get { lock (_gate) return _active?.Playing == true; } }

    /// <summary>Raised on the UI thread with a sentence for the user (null: started or stopped normally).</summary>
    public event Action<string?>? Changed;

    public bool CanUse(string peerId) =>
        _sessions.IsConnected(peerId) && (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.PcAudio) != 0;

    /// <summary>Starts sending this PC's sound to <paramref name="peerId"/>; stops any other phone first.</summary>
    public async Task<string?> StartAsync(string peerId)
    {
        await StopAsync("user");
        if (!CanUse(peerId)) return "Update Veyro on the phone to play this PC's sound there.";
        string? host = _sessions.GetAdvertisableHost(peerId);
        if (host is null || _sessions.GetExportKey(peerId) is not { } export) return "The phone is not connected.";
        string sid = StreamRules.NewSid();
        var active = new Active(peerId, sid);
        try
        {
            active.Listener = Vms1Listener.Start(_pcId, StreamRules.SidBytes(sid)!, new Vms1PendingSession(peerId, export), source: Vms1.SourceSpeaker);
        }
        catch (IOException)
        {
            return "Every media port (5150-5169) is in use.";
        }
        lock (_gate) _active = active;
        _ = RunAsync(active);
        try
        {
            await _sessions.SendAsync(peerId, new PeerMessage.SpeakerStart(sid, host, active.Listener.Port));
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException)
        {
            await EndAsync(active, null, "The phone is not connected.");
            return "The phone is not connected.";
        }
        VeyroTrace.Info("PC sound requested on the phone");
        return null;
    }

    private async Task RunAsync(Active active)
    {
        Vms1Connection? connection = await active.Listener!.TakeAsync().ConfigureAwait(false);
        if (connection is null || active.Stop.IsCancellationRequested)
        {
            connection?.Dispose();
            if (!active.Stop.IsCancellationRequested) await EndAsync(active, null, "The phone did not answer.");
            return;
        }
        active.Connection = connection;
        var capture = new LoopbackCapture();
        active.Capture = capture;
        if (!capture.Start())
        {
            await EndAsync(active, "error", "This PC's sound could not be captured.");
            return;
        }
        var writer = connection.Channel.Writer;
        var packets = System.Threading.Channels.Channel.CreateBounded<byte[]>(new System.Threading.Channels.BoundedChannelOptions(20)
        {
            FullMode = System.Threading.Channels.BoundedChannelFullMode.DropOldest,
            SingleReader = true,
        });
        capture.Packet += p => packets.Writer.TryWrite(p);
        capture.Ended += () => packets.Writer.TryComplete();
        try
        {
            await writer.WriteAudioConfigAsync(new Vms1.AudioConfig(2, capture.Rate), active.Stop.Token).ConfigureAwait(false);
            long start = Environment.TickCount64;
            await foreach (byte[] packet in packets.Reader.ReadAllAsync(active.Stop.Token).ConfigureAwait(false))
            {
                long pts = (Environment.TickCount64 - start) * 1000;
                await writer.WriteAudioAsync(pts, packet, active.Stop.Token).ConfigureAwait(false);
            }
            if (!active.Stop.IsCancellationRequested) await EndAsync(active, "error", "This PC's sound stopped.");
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException or InvalidDataException)
        {
            if (!active.Stop.IsCancellationRequested) await EndAsync(active, null, "The connection to the phone was lost.");
        }
    }

    /// <summary>Stops playing on the phone (tells it with <paramref name="reason"/>).</summary>
    public async Task StopAsync(string reason = "user")
    {
        Active? active;
        lock (_gate) active = _active;
        if (active is not null) await EndAsync(active, reason, null);
    }

    private async Task EndAsync(Active active, string? tellPhone, string? message)
    {
        lock (_gate)
        {
            if (!ReferenceEquals(_active, active)) return;
            _active = null;
        }
        active.Stop.Cancel();
        if (tellPhone is not null)
        {
            try { await _sessions.SendAsync(active.PeerId, new PeerMessage.SpeakerStop(active.Sid, tellPhone)).ConfigureAwait(false); }
            catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException) { }
        }
        active.Capture?.Dispose();
        active.Connection?.Dispose();
        if (active.Listener is { } listener) await listener.DisposeAsync().ConfigureAwait(false);
        VeyroTrace.Info("PC sound on the phone ended");
        Post(() => Changed?.Invoke(message));
    }

    private void OnMessage(string peerId, PeerMessage message)
    {
        Active? active;
        lock (_gate) active = _active;
        if (active is null || !string.Equals(active.PeerId, peerId, StringComparison.OrdinalIgnoreCase)) return;
        switch (message)
        {
            case PeerMessage.SpeakerState state when state.Sid == active.Sid && SpeakerCaptureRules.IsValid(state):
                if (state.State == SpeakerCaptureRules.StatePlaying)
                {
                    lock (_gate) active.Playing = true;
                    Post(() => Changed?.Invoke(null));
                }
                else
                {
                    _ = EndAsync(active, null, state.Reason == "user" ? null : StopText(state.Reason));
                }
                break;
            case PeerMessage.SpeakerStop stop when stop.Sid == active.Sid && SpeakerCaptureRules.IsValid(stop):
                _ = EndAsync(active, null, stop.Reason == "user" ? null : StopText(stop.Reason));
                break;
        }
    }

    private static string StopText(string? reason) => reason switch
    {
        "disabled" => "\"Play PC sound on this phone\" is off on the phone.",
        "busy" => "The phone is already playing another PC's sound.",
        "network" => "The connection to the phone was lost.",
        _ => "The phone stopped playing this PC's sound.",
    };

    private void OnSessionClosed(string peerId)
    {
        Active? active;
        lock (_gate) active = _active;
        if (active is not null && string.Equals(active.PeerId, peerId, StringComparison.OrdinalIgnoreCase))
            _ = EndAsync(active, null, null);
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }

    public void Dispose()
    {
        _sessions.StreamMessageReceived -= OnMessage;
        _sessions.SessionClosed -= OnSessionClosed;
        // Not awaited: the phone hears speakerStop while the session is still up; the socket closes with it anyway.
        _ = StopAsync();
    }
}
