using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Veyro.Core.Link;
using Veyro.Core.Mirroring;
using Veyro.Core.Protocol.Media;
using Xunit;

namespace Veyro.Tests.Media;

/// <summary>The PC's VMS1 handshake over loopback, the stream planner (PC-owned quality) and A/V sync (ADR-066).</summary>
public sealed class MirroringLogicTests
{
    private const string PhoneId = "0123456789ABCDEFGHJKMNPQRS";
    private const string PcId = "PCPCPCPCPCPCPCPCPCPCPCPCPC";

    private static Func<string, byte[], int, byte[]> Exporter(byte seed) => (label, ctx, len) =>
    {
        byte[] secret = Enumerable.Repeat(seed, 32).ToArray();
        byte[] a = SHA256.HashData([.. secret, .. System.Text.Encoding.ASCII.GetBytes(label), 1, .. ctx]);
        byte[] b = SHA256.HashData([.. secret, .. System.Text.Encoding.ASCII.GetBytes(label), 2, .. ctx]);
        return [.. a.Concat(b).Take(len)];
    };

    /// <summary>The phone's side, written the way 04-PROTOCOL.md 7.5.2 describes it (the real one is Android's Vms1Channel).</summary>
    private static async Task<Vms1Channel> PhoneConnectAsync(Stream stream, byte[] sid, Func<string, byte[], int, byte[]> export, CancellationToken ct)
    {
        byte[] saltPhone = RandomNumberGenerator.GetBytes(16);
        await stream.WriteAsync(Vms1.EncodePreface(Vms1.SourceScreen, sid, saltPhone), ct);
        byte[] buffer = new byte[Vms1.PrefaceSize];
        await stream.ReadExactlyAsync(buffer, ct);
        var pc = Vms1.ParsePreface(buffer);
        var channel = Vms1Channel.FromOkm(stream, export(Vms1.ExportLabel(Vms1.SourceScreen), Vms1.ExportContext(Vms1.SourceScreen, sid, saltPhone, pc.Salt), Vms1.OkmSize), phone: true);
        await channel.Writer.WriteAuthAsync(PhoneId, PcId, Vms1.SourceScreen, sid, ct);
        Assert.IsType<Vms1Frame.AuthAck>(await channel.Reader.ReadAsync(ct));
        return channel;
    }

    private static async Task<(TcpListener Listener, TcpClient Client, NetworkStream PhoneStream, Task<TcpClient> Accept)> LoopbackAsync()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        Task<TcpClient> accept = listener.AcceptTcpClientAsync();
        var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port);
        return (listener, client, client.GetStream(), accept);
    }

    [Fact]
    public async Task Pc_AcceptsItsSession_AndMediaFlows()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        var ct = timeout.Token;
        byte[] sid = RandomNumberGenerator.GetBytes(16);
        var (listener, client, phoneStream, accept) = await LoopbackAsync();
        try
        {
            Task<Vms1Channel> phone = PhoneConnectAsync(phoneStream, sid, Exporter(1), ct);
            using TcpClient pcSocket = await accept;
            var (pc, preface) = await Vms1Channel.AcceptAsPcAsync(pcSocket.GetStream(), PcId,
                p => p.Sid.AsSpan().SequenceEqual(sid) ? new Vms1PendingSession(PhoneId, Exporter(1)) : null, ct);
            using var phoneChannel = await phone;
            Assert.Equal(Vms1.SourceScreen, preface.Source);

            await phoneChannel.Writer.WriteVideoConfigAsync(new Vms1.VideoConfig(Vms1.VideoCodecH264, 832, 1920, 0, 12, 832, 1895, 0, false, 1), ct);
            byte[] au = RandomNumberGenerator.GetBytes(100_000);
            await phoneChannel.Writer.WriteAccessUnitAsync(42, au, key: true, discontinuity: false, ct);
            Assert.IsType<Vms1Frame.VideoConfigFrame>(await pc.Reader.ReadAsync(ct));
            var r = new AccessUnitReassembler();
            AccessUnit? unit = null;
            while (unit is null) unit = r.Accept(Assert.IsType<Vms1Frame.Video>(await pc.Reader.ReadAsync(ct)));
            Assert.Equal(au, unit.Data);
            Assert.Equal(42, unit.PtsUs);
            pc.Dispose();
        }
        finally
        {
            client.Dispose();
            listener.Stop();
        }
    }

    [Fact]
    public async Task Pc_RefusesAnUnknownSession_AndAuthUnderOtherKeys()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        var ct = timeout.Token;
        byte[] sid = RandomNumberGenerator.GetBytes(16);

        var (l1, c1, s1, a1) = await LoopbackAsync();
        try
        {
            await s1.WriteAsync(Vms1.EncodePreface(Vms1.SourceScreen, sid, new byte[16]), ct);
            using TcpClient pcSocket = await a1;
            await Assert.ThrowsAsync<InvalidDataException>(() =>
                Vms1Channel.AcceptAsPcAsync(pcSocket.GetStream(), PcId, _ => null, ct));
        }
        finally { c1.Dispose(); l1.Stop(); }

        var (l2, c2, s2, a2) = await LoopbackAsync();
        try
        {
            Task<Vms1Channel> phone = PhoneConnectAsync(s2, sid, Exporter(2), ct);
            using TcpClient pcSocket = await a2;
            await Assert.ThrowsAsync<InvalidDataException>(() =>
                Vms1Channel.AcceptAsPcAsync(pcSocket.GetStream(), PcId, _ => new Vms1PendingSession(PhoneId, Exporter(1)), ct));
            pcSocket.Dispose();
            await Assert.ThrowsAnyAsync<Exception>(() => phone);
        }
        finally { c2.Dispose(); l2.Stop(); }

        var (l3, c3, s3, a3) = await LoopbackAsync();
        try
        {
            Task<Vms1Channel> phone = PhoneConnectAsync(s3, sid, Exporter(1), ct);
            using TcpClient pcSocket = await a3;
            // The control session belongs to another phone: AUTH's phone id does not match.
            await Assert.ThrowsAsync<InvalidDataException>(() =>
                Vms1Channel.AcceptAsPcAsync(pcSocket.GetStream(), PcId, _ => new Vms1PendingSession("ANOTHERPHONE00000000000000", Exporter(1)), ct));
            pcSocket.Dispose();
            await Assert.ThrowsAnyAsync<Exception>(() => phone);
        }
        finally { c3.Dispose(); l3.Stop(); }
    }

    private static PeerMessage.StreamCaps K50i(bool hevc = true) => new(
        true, new PeerMessage.StreamScreen(1080, 2460, 120),
        [
            new PeerMessage.StreamEncoder("h264", true, 4096, 2304, 60),
            .. (hevc ? new[] { new PeerMessage.StreamEncoder("h265", true, 4096, 2304, 60) } : Array.Empty<PeerMessage.StreamEncoder>()),
            new PeerMessage.StreamEncoder("h264", false, 2048, 2048, 30),
        ], true);

    private static readonly PcDecoder[] RtxPc =
    [
        new("h264", true, 4096, 4096), new("h265", true, 8192, 8192), new("h264", false, 4096, 2304),
    ];

    [Fact]
    public void Planner_Balanced_IsHardwareH264_At1920()
    {
        var plan = StreamPlanner.Plan(K50i(), RtxPc, MirrorQuality.Balanced)!;
        Assert.Equal(("h264", true, true), (plan.Codec, plan.HwEncode, plan.HwDecode));
        Assert.Equal((832, 1920, 60), (plan.Width, plan.Height, plan.Fps));
        Assert.Equal(1920, plan.MaxLong);
        Assert.Equal((int)(832 * 1920 * 60 * 0.07), plan.Bitrate);
    }

    [Fact]
    public void Planner_UltraHd_PicksH265_AndKeepsNativeSize()
    {
        var plan = StreamPlanner.Plan(K50i(), RtxPc, MirrorQuality.UltraHd)!;
        Assert.Equal("h265", plan.Codec);
        Assert.Equal((1072, 2448, 60), (plan.Width, plan.Height, plan.Fps));
    }

    [Fact]
    public void Planner_WithoutH265_FallsBackToH264_AndCapsFpsForLevel51()
    {
        var caps = new PeerMessage.StreamCaps(true, new PeerMessage.StreamScreen(2160, 3840, 60),
            [new PeerMessage.StreamEncoder("h264", true, 4096, 2304, 60)], false);
        var plan = StreamPlanner.Plan(caps, RtxPc, MirrorQuality.UltraHd)!;
        Assert.Equal("h264", plan.Codec);
        // 2160x3840 in H.264 fits level 5.1 only at 30 fps.
        Assert.Equal((2160, 3840, 30), (plan.Width, plan.Height, plan.Fps));
        Assert.True(plan.Width * plan.Height / 256 <= StreamPlanner.H264MaxMacroblocks);
        Assert.True(plan.Fps * (plan.Width / 16) * (plan.Height / 16) <= StreamPlanner.H264MaxMacroblocksPerSecond);
    }

    [Fact]
    public void Planner_HardwareOff_UsesSoftwareH264_AndNeverSoftwareH265()
    {
        var plan = StreamPlanner.Plan(K50i(), RtxPc, MirrorQuality.Balanced with { HardwareEncode = false, HardwareDecode = false })!;
        Assert.Equal(("h264", false, false), (plan.Codec, plan.HwEncode, plan.HwDecode));
        Assert.Equal(30, plan.Fps);
        var noHw = new PeerMessage.StreamCaps(true, new PeerMessage.StreamScreen(1080, 2460, 60), [new PeerMessage.StreamEncoder("h265", false, 4096, 4096, 30)], false);
        Assert.Null(StreamPlanner.Plan(noHw, RtxPc, MirrorQuality.UltraHd));
    }

    [Fact]
    public void Planner_RespectsTheUsersCaps_AndThePhonesConsent()
    {
        var plan = StreamPlanner.Plan(K50i(), RtxPc, MirrorQuality.BatterySaver)!;
        Assert.Equal((560, 1280, 30), (plan.Width, plan.Height, plan.Fps));
        Assert.True(plan.Bitrate <= 4_000_000);
        Assert.Null(StreamPlanner.Plan(K50i() with { Allowed = false }, RtxPc, MirrorQuality.Balanced));
        Assert.Null(StreamPlanner.Plan(K50i(), [], MirrorQuality.Balanced));
    }

    [Theory]
    [InlineData(1_000_000, null, AvDecision.Present)]
    [InlineData(1_000_000, 1_000_000L, AvDecision.Present)]
    [InlineData(1_020_000, 1_000_000L, AvDecision.Wait)]
    [InlineData(1_008_000, 1_000_000L, AvDecision.Present)]
    [InlineData(960_000, 1_000_000L, AvDecision.Present)]
    [InlineData(940_000, 1_000_000L, AvDecision.Drop)]
    public void AvSync_FollowsTheAudioClock(long videoPts, long? audioClock, AvDecision expected)
    {
        Assert.Equal(expected, AvSync.Decide(videoPts, audioClock, frameIntervalUs: 16_667));
    }

    [Fact]
    public void AudioClock_CountsPlayedFrames()
    {
        Assert.Null(AvSync.AudioClock(5_000_000, 0, 48_000, 20_000));
        Assert.Equal(5_000_000 + 1_000_000 - 20_000, AvSync.AudioClock(5_000_000, 48_000, 48_000, 20_000));
    }
}
