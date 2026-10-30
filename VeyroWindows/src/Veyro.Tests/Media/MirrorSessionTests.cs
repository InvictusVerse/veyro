using System.Net;
using System.Security.Cryptography;
using Veyro.Core.Link;
using Veyro.Core.Mirroring;
using Veyro.Core.Protocol.Media;
using Xunit;

namespace Veyro.Tests.Media;

/// <summary>The PC's mirroring session states, refusals with "Ask again", the receive queue, bitrate control and the media listener (ADR-066).</summary>
public sealed class MirrorSessionTests
{
    private sealed class ManualTime : TimeProvider
    {
        private long _ticks = 1_000_000;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override long GetTimestamp() => _ticks;
        public void Advance(TimeSpan by) => _ticks += by.Ticks;
    }

    private static readonly PeerMessage.StreamCaps Caps = new(true, new PeerMessage.StreamScreen(1080, 2460, 120),
        [new PeerMessage.StreamEncoder("h264", true, 4096, 2304, 60)], Audio: true, AudioPermission: "ask");

    private static readonly StreamPlan Plan = new("h264", true, true, 1920, 60, 6_000_000, 832, 1920);

    private static (MirrorSession Session, ManualTime Time, string Sid, PeerMessage.StreamStart Start) Started(PeerMessage.StreamCaps? caps = null, bool audio = true)
    {
        var time = new ManualTime();
        var session = new MirrorSession("Redmi", time);
        string sid = StreamRules.NewSid();
        var start = session.Begin(sid, caps ?? Caps, Plan, audio, "192.168.31.191", 5151)!;
        return (session, time, sid, start);
    }

    [Fact]
    public void Begin_SendsThePlansValues_AndWaitsForThePhone()
    {
        var (session, _, sid, start) = Started();
        Assert.Equal(new PeerMessage.StreamStart(sid, "screen", "h264", true, 1920, 60, 6_000_000, true, "192.168.31.191", 5151), start);
        Assert.Equal(MirrorPhase.Starting, session.Status.Phase);
        Assert.Equal(PhoneAudioState.Waiting, session.Status.Audio);
    }

    [Fact]
    public void Unavailable_SaysWhy_AndSendsNothing()
    {
        var session = new MirrorSession("Redmi");
        Assert.Null(session.Begin(StreamRules.NewSid(), Caps with { Allowed = false }, Plan, true, "10.0.0.2", 5151));
        Assert.Equal(MirrorPhase.Unavailable, session.Status.Phase);
        Assert.Contains("Let PCs mirror this screen", session.Status.Detail);
        Assert.Null(new MirrorSession("Redmi").Begin(StreamRules.NewSid(), null, Plan, true, "10.0.0.2", 5151));
        Assert.Null(new MirrorSession("Redmi").Begin(StreamRules.NewSid(), Caps, null, true, "10.0.0.2", 5151));
    }

    [Fact]
    public void Phases_FollowThePhone_AndTheSocket()
    {
        var (session, _, sid, _) = Started();
        session.OnState(new PeerMessage.StreamState(sid, "asking"));
        Assert.Equal(MirrorPhase.Asking, session.Status.Phase);
        Assert.Contains("Start now", session.Status.Detail);
        session.OnState(new PeerMessage.StreamState(sid, "starting"));
        Assert.Equal(MirrorPhase.Connecting, session.Status.Phase);
        session.OnSocketConnected();
        Assert.Equal(MirrorPhase.Streaming, session.Status.Phase);
        session.OnState(new PeerMessage.StreamState(sid, "paused"));
        Assert.Equal(MirrorPhase.Paused, session.Status.Phase);
        session.OnState(new PeerMessage.StreamState(StreamRules.NewSid(), "stopped", "user"));
        Assert.Equal(MirrorPhase.Paused, session.Status.Phase);
        session.OnSocketLost();
        Assert.Equal(MirrorPhase.Stopped, session.Status.Phase);
        Assert.Contains("lost", session.Status.Title);
    }

    [Fact]
    public void DeclinedOnThePhone_IsShown_AndAskAgainWorksAsOftenAsWanted()
    {
        var (session, time, sid, _) = Started();
        session.OnStop(new PeerMessage.StreamStop(sid, "declined"));
        Assert.Equal(MirrorPhase.Stopped, session.Status.Phase);
        Assert.Equal("Screen sharing was declined on Redmi", session.Status.Title);
        Assert.True(session.Status.CanAskAgain);
        Assert.Equal("Ask again", session.Status.AskAgainLabel);
        Assert.False(session.MayAskAgain(), "pressed less than 3 s after the last request");
        for (int i = 0; i < 5; i++)
        {
            time.Advance(MirrorSession.AskInterval);
            Assert.True(session.MayAskAgain());
            string next = StreamRules.NewSid();
            Assert.NotNull(session.Begin(next, Caps, Plan, true, "192.168.31.191", 5151));
            Assert.False(session.MayAskAgain());
            session.OnStop(new PeerMessage.StreamStop(next, "declined"));
            Assert.True(session.Status.CanAskAgain);
        }
    }

    [Fact]
    public void AudioDenied_And_Blocked_AreShown_WithAskAgain()
    {
        var (session, time, sid, _) = Started();
        session.OnState(new PeerMessage.StreamState(sid, "streaming", Audio: false, AudioPermission: "denied"));
        Assert.Equal(PhoneAudioState.Denied, session.Status.Audio);
        Assert.Equal("Phone sound was denied on Redmi.", session.Status.AudioText);
        Assert.True(session.Status.CanAskAudioAgain);
        Assert.Equal(new PeerMessage.StreamAskAudio(sid), session.AskAudioAgain());
        Assert.Null(session.AskAudioAgain());
        time.Advance(MirrorSession.AskInterval);

        session.OnState(new PeerMessage.StreamState(sid, "streaming", Audio: false, AudioPermission: "blocked"));
        Assert.Equal(PhoneAudioState.Blocked, session.Status.Audio);
        Assert.Contains("Permissions > Microphone", session.Status.AudioText);
        Assert.Contains("never the microphone", session.Status.AudioText);
        Assert.NotNull(session.AskAudioAgain());
        time.Advance(MirrorSession.AskInterval);

        session.OnState(new PeerMessage.StreamState(sid, "streaming", Audio: true, AudioPermission: "granted"));
        Assert.Equal(PhoneAudioState.Playing, session.Status.Audio);
        Assert.False(session.Status.CanAskAudioAgain);
        Assert.Null(session.AskAudioAgain());
    }

    [Fact]
    public void Audio_OffOrUnsupported_NeverAsks()
    {
        var (off, _, sidOff, startOff) = Started(audio: false);
        Assert.False(startOff.Audio);
        off.OnState(new PeerMessage.StreamState(sidOff, "streaming", AudioPermission: "denied"));
        Assert.Equal(PhoneAudioState.Off, off.Status.Audio);
        Assert.Null(off.AskAudioAgain());

        var (old, _, _, startOld) = Started(Caps with { Audio = false });
        Assert.False(startOld.Audio);
        Assert.Equal(PhoneAudioState.Unsupported, old.Status.Audio);
        Assert.False(old.Status.CanAskAudioAgain);
    }

    [Fact]
    public void SoundSwitch_MidSession_GoesAsStreamConfig_AndAskAudioIsRateLimited()
    {
        var (session, time, sid, _) = Started();
        session.OnState(new PeerMessage.StreamState(sid, "streaming", Audio: false, AudioPermission: "denied"));
        Assert.True(session.MayAskAudioAgain());
        Assert.NotNull(session.AskAudioAgain());
        Assert.False(session.MayAskAudioAgain());
        time.Advance(MirrorSession.AskInterval);
        Assert.True(session.MayAskAudioAgain());

        Assert.Equal(new PeerMessage.StreamConfig(sid, Audio: false), session.SetAudioRequested(false));
        Assert.Equal(PhoneAudioState.Off, session.Status.Audio);
        Assert.Null(session.Status.AudioText);
        Assert.False(session.MayAskAudioAgain());
        Assert.Null(session.SetAudioRequested(false));
        Assert.Equal(new PeerMessage.StreamConfig(sid, Audio: true), session.SetAudioRequested(true));
        Assert.Equal(PhoneAudioState.Waiting, session.Status.Audio);

        var (old, _, _, _) = Started(Caps with { Audio = false }, audio: false);
        Assert.Null(old.SetAudioRequested(true));
        Assert.Equal(PhoneAudioState.Unsupported, old.Status.Audio);
    }

    [Fact]
    public void PcSideProblems_AreShown_AndStartAgainIsRateLimited()
    {
        var time = new ManualTime();
        var session = new MirrorSession("Redmi", time);
        session.SetUnavailable("Redmi is not connected.");
        Assert.Equal(MirrorPhase.Unavailable, session.Status.Phase);
        Assert.Equal("Redmi is not connected.", session.Status.Detail);
        Assert.Equal("Start again", session.Status.AskAgainLabel);
        Assert.Null(session.Sid);
        Assert.False(session.MayAskAgain());
        time.Advance(MirrorSession.AskInterval);
        Assert.True(session.MayAskAgain());
    }

    [Fact]
    public void Timeouts_EndTheSession_AndTellThePhone()
    {
        var (session, time, sid, _) = Started();
        time.Advance(MirrorSession.AnswerTimeout - TimeSpan.FromSeconds(1));
        Assert.Null(session.Tick());
        time.Advance(TimeSpan.FromSeconds(1));
        Assert.Equal(new PeerMessage.StreamStop(sid, "timeout"), session.Tick());
        Assert.Equal("Ask again", session.Status.AskAgainLabel);
        Assert.Null(session.Tick());

        var (asking, t2, sid2, _) = Started();
        asking.OnState(new PeerMessage.StreamState(sid2, "asking"));
        t2.Advance(MirrorSession.AnswerTimeout);
        Assert.Null(asking.Tick());
        t2.Advance(MirrorSession.ConsentTimeout);
        Assert.NotNull(asking.Tick());
    }

    [Fact]
    public void UserStop_And_PhoneDisconnect()
    {
        var (session, _, sid, _) = Started();
        Assert.Equal(new PeerMessage.StreamStop(sid, "user"), session.UserStop());
        Assert.Null(session.UserStop());
        var (other, _, _, _) = Started();
        other.OnPhoneDisconnected();
        Assert.Equal("Redmi disconnected", other.Status.Title);
        Assert.Equal("Start again", other.Status.AskAgainLabel);
    }

    private static AccessUnit Unit(bool key, int size = 1000, bool discontinuity = false) => new(0, key, discontinuity, new byte[size]);

    [Fact]
    public async Task ReceiveQueue_StartsAtAKeyframe_AndRecoversFromOverflow()
    {
        var time = new ManualTime();
        var queue = new MediaReceiveQueue(time);
        Assert.True(queue.Enqueue(Unit(key: false)), "nothing to decode from: ask for a keyframe");
        Assert.False(queue.Enqueue(Unit(key: false)), "asked less than 500 ms ago");
        Assert.Equal(0, queue.Count);
        Assert.False(queue.Enqueue(Unit(key: true)));
        for (int i = 0; i < MediaReceiveQueue.MaxUnits - 1; i++) queue.Enqueue(Unit(key: false));
        Assert.Equal(MediaReceiveQueue.MaxUnits, queue.Count);

        time.Advance(MediaReceiveQueue.KeyframeInterval);
        Assert.True(queue.Enqueue(Unit(key: false)), "overflow: everything stale, ask for a keyframe");
        Assert.Equal(0, queue.Count);
        Assert.False(queue.Enqueue(Unit(key: false)));
        queue.Enqueue(Unit(key: true));
        Assert.Equal(1, queue.Count);
        Assert.True((await queue.DequeueAsync(CancellationToken.None))!.Key);

        time.Advance(MediaReceiveQueue.KeyframeInterval);
        Assert.True(queue.Enqueue(Unit(key: false, discontinuity: true)), "the phone dropped frames: wait for a keyframe");
        Assert.Equal(0, queue.Count);
        Assert.True(queue.Dropped >= MediaReceiveQueue.MaxUnits);

        queue.Close();
        Assert.Null(await queue.DequeueAsync(CancellationToken.None));
    }

    [Fact]
    public void Bitrate_FallsOnDrops_AndRecoversSlowly()
    {
        var c = new BitrateController(10_000_000);
        Assert.Equal(7_000_000, c.Tick(3));
        Assert.Equal(4_900_000, c.Tick(1));
        Assert.Null(c.Tick(0));
        Assert.Null(c.Tick(0));
        Assert.Null(c.Tick(0));
        Assert.Equal(5_900_000, c.Tick(0));
        for (int i = 0; i < 20; i++) c.Tick(1);
        Assert.Equal(1_500_000, c.Current);
        c.Retarget(4_000_000);
        Assert.Equal(1_500_000, c.Current);
        Assert.True(new BitrateController(2_000_000).Tick(5) >= BitrateController.Floor);
    }

    private static Func<string, byte[], int, byte[]> Exporter(byte seed) => (label, ctx, len) =>
    {
        byte[] secret = Enumerable.Repeat(seed, 32).ToArray();
        byte[] a = SHA256.HashData([.. secret, .. System.Text.Encoding.ASCII.GetBytes(label), 1, .. ctx]);
        byte[] b = SHA256.HashData([.. secret, .. System.Text.Encoding.ASCII.GetBytes(label), 2, .. ctx]);
        return [.. a.Concat(b).Take(len)];
    };

    private const string PhoneId = "0123456789ABCDEFGHJKMNPQRS";
    private const string PcId = "PCPCPCPCPCPCPCPCPCPCPCPCPC";

    private static async Task<(System.Net.Sockets.TcpClient Client, Vms1Channel? Channel)> PhoneDialAsync(int port, byte[] sid, byte seed, CancellationToken ct)
    {
        var client = new System.Net.Sockets.TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, port, ct);
        var stream = client.GetStream();
        try
        {
            byte[] salt = RandomNumberGenerator.GetBytes(16);
            await stream.WriteAsync(Vms1.EncodePreface(Vms1.SourceScreen, sid, salt), ct);
            byte[] buffer = new byte[Vms1.PrefaceSize];
            await stream.ReadExactlyAsync(buffer, ct);
            var pc = Vms1.ParsePreface(buffer);
            var channel = Vms1Channel.FromOkm(stream, Exporter(seed)(Vms1.ExportLabel(Vms1.SourceScreen), Vms1.ExportContext(Vms1.SourceScreen, sid, salt, pc.Salt), Vms1.OkmSize), phone: true);
            await channel.Writer.WriteAuthAsync(PhoneId, PcId, Vms1.SourceScreen, sid, ct);
            return await channel.Reader.ReadAsync(ct) is Vms1Frame.AuthAck ? (client, channel) : (client, null);
        }
        catch (Exception error) when (error is IOException or InvalidDataException or EndOfStreamException)
        {
            return (client, null);
        }
    }

    [Fact]
    public async Task Listener_AcceptsTheRightPhone_AfterRefusingOthers_ThenStopsListening()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var ct = timeout.Token;
        byte[] sid = RandomNumberGenerator.GetBytes(16);
        await using var listener = Vms1Listener.Start(PcId, sid, new Vms1PendingSession(PhoneId, Exporter(1)), IPAddress.Loopback, 49_500, 49_520);

        var (wrongSidClient, wrongSid) = await PhoneDialAsync(listener.Port, RandomNumberGenerator.GetBytes(16), 1, ct);
        Assert.Null(wrongSid);
        wrongSidClient.Dispose();
        var (wrongKeyClient, wrongKey) = await PhoneDialAsync(listener.Port, sid, 2, ct);
        Assert.Null(wrongKey);
        wrongKeyClient.Dispose();

        var (client, phone) = await PhoneDialAsync(listener.Port, sid, 1, ct);
        Assert.NotNull(phone);
        using Vms1Connection? connection = await listener.TakeAsync().WaitAsync(ct);
        Assert.NotNull(connection);
        await phone!.Writer.WriteVideoConfigAsync(new Vms1.VideoConfig(Vms1.VideoCodecH264, 832, 1920, 0, 0, 832, 1920, 0, false, 1), ct);
        Assert.IsType<Vms1Frame.VideoConfigFrame>(await connection!.Channel.Reader.ReadAsync(ct));

        // Listening stopped after the first authenticated connection.
        using var late = new System.Net.Sockets.TcpClient();
        await Assert.ThrowsAnyAsync<System.Net.Sockets.SocketException>(() => late.ConnectAsync(IPAddress.Loopback, listener.Port, ct).AsTask());
        phone.Dispose();
        client.Dispose();
    }

    [Fact]
    public async Task Listener_GivesUpAfterItsWait()
    {
        await using var listener = Vms1Listener.Start(PcId, RandomNumberGenerator.GetBytes(16), new Vms1PendingSession(PhoneId, Exporter(1)),
            IPAddress.Loopback, 49_530, 49_540, waitTimeout: TimeSpan.FromMilliseconds(300));
        Assert.Null(await listener.TakeAsync().WaitAsync(TimeSpan.FromSeconds(10)));
    }
}
