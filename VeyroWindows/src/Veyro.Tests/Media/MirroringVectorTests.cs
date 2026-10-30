using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Link;
using Veyro.Core.Protocol.Media;
using Xunit;

namespace Veyro.Tests.Media;

/// <summary>
/// Screen mirroring wire formats against the shared vectors (ADR-066): the VMS1 media socket
/// (media/vms1.json, generated with JDK primitives, so .NET's ChaCha20-Poly1305 is checked against
/// an independent implementation), fragmentation, the coded size rule and the stream messages.
/// The Android suite drives the same files.
/// </summary>
public sealed class MirroringVectorTests
{
    internal static JsonDocument Load(string relative)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(dir!.FullName, "protocol", "testvectors", relative)));
    }

    private static byte[] Hex(JsonElement e) => Convert.FromHexString(e.GetString()!);
    private static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    private static readonly Vms1.VideoConfig Config = new(Vms1.VideoCodecH264, 1072, 2448, 0, 0, 1072, 2448, 0, false, 1);

    private static byte[] RecordsOf(JsonElement root, string dir) => root.GetProperty("records").EnumerateArray()
        .Where(r => r.GetProperty("dir").GetString() == dir).SelectMany(r => Hex(r.GetProperty("record"))).ToArray();

    private static JsonElement Record(JsonElement root, string name) =>
        root.GetProperty("records").EnumerateArray().Single(r => r.GetProperty("name").GetString() == name);

    [Fact]
    public void Prefaces_Context_AndLabel_Match()
    {
        using var doc = Load("media/vms1.json");
        var v = doc.RootElement;
        byte[] sid = Hex(v.GetProperty("sid")), saltPhone = Hex(v.GetProperty("saltPhone")), saltPc = Hex(v.GetProperty("saltPc"));
        Assert.Equal(v.GetProperty("phonePreface").GetString(), Hex(Vms1.EncodePreface(Vms1.SourceScreen, sid, saltPhone)));
        Assert.Equal(v.GetProperty("pcPreface").GetString(), Hex(Vms1.EncodePreface(Vms1.SourceScreen, sid, saltPc)));
        Assert.Equal(v.GetProperty("exportContext").GetString(), Hex(Vms1.ExportContext(Vms1.SourceScreen, sid, saltPhone, saltPc)));
        Assert.Equal(v.GetProperty("exportLabel").GetString(), Vms1.ExportLabel(Vms1.SourceScreen));
        var parsed = Vms1.ParsePreface(Hex(v.GetProperty("phonePreface")));
        Assert.Equal(sid, parsed.Sid);
        Assert.Equal(saltPhone, parsed.Salt);
        foreach (var bad in v.GetProperty("badPrefaces").EnumerateArray())
            Assert.Throws<InvalidDataException>(() => Vms1.ParsePreface(Hex(bad.GetProperty("bytes"))));
    }

    [Fact]
    public async Task Writer_ProducesEveryRecord_ByteForByte()
    {
        using var doc = Load("media/vms1.json");
        var v = doc.RootElement;
        byte[] sid = Hex(v.GetProperty("sid"));
        using var up = new MemoryStream();
        using (var phone = Vms1Channel.FromOkm(up, Hex(v.GetProperty("okm")), phone: true))
        {
            var ct = CancellationToken.None;
            await phone.Writer.WriteAuthAsync(v.GetProperty("phoneId").GetString()!, v.GetProperty("pcId").GetString()!, Vms1.SourceScreen, sid, ct);
            await phone.Writer.WriteVideoConfigAsync(Config, ct);
            await phone.Writer.WriteAudioConfigAsync(new Vms1.AudioConfig(2, 48_000), ct);
            await phone.Writer.WriteAccessUnitAsync(1_000_000, Enumerable.Range(0, 20).Select(i => (byte)(0xA0 + i)).ToArray(), key: true, discontinuity: false, ct);
            await phone.Writer.WriteAudioAsync(1_005_000, Enumerable.Range(1, 8).Select(i => (byte)i).ToArray(), ct);
            await phone.Writer.WriteCloseAsync(0, "user", ct);
        }
        Assert.Equal(Hex(RecordsOf(v, "up")), Hex(up.ToArray()));

        using var down = new MemoryStream();
        using (var pc = Vms1Channel.FromOkm(down, Hex(v.GetProperty("okm")), phone: false))
            await pc.Writer.WriteAuthAckAsync(CancellationToken.None);
        Assert.Equal(Hex(RecordsOf(v, "down")), Hex(down.ToArray()));
    }

    [Fact]
    public async Task PcReader_DecodesEveryUpwardRecord()
    {
        using var doc = Load("media/vms1.json");
        var v = doc.RootElement;
        using var pc = Vms1Channel.FromOkm(new MemoryStream(RecordsOf(v, "up")), Hex(v.GetProperty("okm")), phone: false);
        var ct = CancellationToken.None;
        var auth = Assert.IsType<Vms1Frame.AuthFrame>(await pc.Reader.ReadAsync(ct)).Auth;
        Assert.Equal(v.GetProperty("phoneId").GetString(), auth.PhoneId);
        Assert.Equal(Hex(v.GetProperty("sid")), auth.Sid);
        Assert.Equal(Config, Assert.IsType<Vms1Frame.VideoConfigFrame>(await pc.Reader.ReadAsync(ct)).Config);
        Assert.Equal(new Vms1.AudioConfig(2, 48_000), Assert.IsType<Vms1Frame.AudioConfigFrame>(await pc.Reader.ReadAsync(ct)).Config);
        var video = Assert.IsType<Vms1Frame.Video>(await pc.Reader.ReadAsync(ct));
        Assert.Equal(Vms1.FlagKey | Vms1.FlagStart | Vms1.FlagEnd, video.Flags);
        Assert.Equal(1_000_000, video.PtsUs);
        var unit = new AccessUnitReassembler().Accept(video);
        Assert.NotNull(unit);
        Assert.True(unit!.Key);
        Assert.Equal(20, unit.Data.Length);
        Assert.Equal(1_005_000, Assert.IsType<Vms1Frame.Audio>(await pc.Reader.ReadAsync(ct)).PtsUs);
        Assert.Equal("user", Assert.IsType<Vms1Frame.CloseFrame>(await pc.Reader.ReadAsync(ct)).Close.Reason);
        Assert.Null(await pc.Reader.ReadAsync(ct));
    }

    [Theory]
    [InlineData("tampered", 5)]
    [InlineData("dropped", 1)]
    [InlineData("replayed", 1)]
    [InlineData("first-not-auth", 0)]
    public async Task BadRecords_FailAndTheReaderStaysFailed(string how, int skip)
    {
        using var doc = Load("media/vms1.json");
        var v = doc.RootElement;
        byte[] auth = Hex(Record(v, "auth").GetProperty("record"));
        byte[] bytes = how switch
        {
            "tampered" => Flip(RecordsOf(v, "up")),
            "dropped" => [.. auth, .. Hex(Record(v, "audioConfig").GetProperty("record"))],
            "replayed" => [.. auth, .. auth],
            _ => Hex(Record(v, "videoConfig").GetProperty("record")),
        };
        using var pc = Vms1Channel.FromOkm(new MemoryStream(bytes), Hex(v.GetProperty("okm")), phone: false);
        for (int i = 0; i < skip; i++) await pc.Reader.ReadAsync(CancellationToken.None);
        await Assert.ThrowsAsync<InvalidDataException>(() => pc.Reader.ReadAsync(CancellationToken.None));
        await Assert.ThrowsAsync<InvalidDataException>(() => pc.Reader.ReadAsync(CancellationToken.None));

        static byte[] Flip(byte[] b) { b[^1] ^= 1; return b; }
    }

    [Fact]
    public void HeaderRules_Match()
    {
        using var doc = Load("media/vms1.json");
        foreach (var h in doc.RootElement.GetProperty("badHeaders").EnumerateArray())
            Assert.Throws<InvalidDataException>(() => Vms1.CheckHeader((byte)h.GetProperty("kind").GetInt32(), (byte)h.GetProperty("flags").GetInt32(),
                h.GetProperty("length").GetInt32(), h.GetProperty("fromPhone").GetBoolean()));
        foreach (var h in doc.RootElement.GetProperty("goodHeaders").EnumerateArray())
            Vms1.CheckHeader((byte)h.GetProperty("kind").GetInt32(), (byte)h.GetProperty("flags").GetInt32(),
                h.GetProperty("length").GetInt32(), h.GetProperty("fromPhone").GetBoolean());
    }

    [Fact]
    public void Fragmentation_Matches()
    {
        using var doc = Load("media/fragmentation.json");
        int max = doc.RootElement.GetProperty("maxFragment").GetInt32();
        foreach (var c in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var plan = AccessUnitFragmenter.Plan(c.GetProperty("size").GetInt32(), c.GetProperty("key").GetBoolean(), c.GetProperty("discontinuity").GetBoolean(), max);
            var expected = c.GetProperty("fragments").EnumerateArray()
                .Select(f => new AccessUnitFragmenter.Fragment(f[0].GetInt32(), f[1].GetInt32(), (byte)f[2].GetInt32())).ToList();
            Assert.Equal(expected, plan);
        }
    }

    [Fact]
    public void Reassembler_RebuildsAndRefusesBadOrder()
    {
        byte[] au = Enumerable.Range(0, 150_000).Select(i => (byte)(i * 31)).ToArray();
        var plan = AccessUnitFragmenter.Plan(au.Length, key: true, discontinuity: true);
        var r = new AccessUnitReassembler();
        AccessUnit? unit = null;
        foreach (var f in plan)
        {
            bool start = (f.Flags & Vms1.FlagStart) != 0;
            unit = r.Accept(new Vms1Frame.Video(f.Flags, start ? 9 : null, start ? au.Length : null, au.AsSpan(f.Offset, f.Length).ToArray()));
        }
        Assert.NotNull(unit);
        Assert.Equal(au, unit!.Data);
        Assert.True(unit.Key && unit.Discontinuity);
        Assert.Equal(9, unit.PtsUs);

        Assert.Throws<InvalidDataException>(() => new AccessUnitReassembler().Accept(new Vms1Frame.Video(Vms1.FlagEnd, null, null, [1])));
        var twoStarts = new AccessUnitReassembler();
        twoStarts.Accept(new Vms1Frame.Video(Vms1.FlagStart, 0, 10, [1]));
        Assert.Throws<InvalidDataException>(() => twoStarts.Accept(new Vms1Frame.Video(Vms1.FlagStart, 0, 10, [1])));
        var tooMuch = new AccessUnitReassembler();
        tooMuch.Accept(new Vms1Frame.Video(Vms1.FlagStart, 0, 2, [1]));
        Assert.Throws<InvalidDataException>(() => tooMuch.Accept(new Vms1Frame.Video(Vms1.FlagEnd, null, null, [1, 2])));
        var tooShort = new AccessUnitReassembler();
        tooShort.Accept(new Vms1Frame.Video(Vms1.FlagStart, 0, 3, [1]));
        Assert.Throws<InvalidDataException>(() => tooShort.Accept(new Vms1Frame.Video(Vms1.FlagEnd, null, null, [1])));
    }

    [Fact]
    public void StreamGeometry_Matches()
    {
        using var doc = Load("media/stream-geometry.json");
        var cases = doc.RootElement.GetProperty("cases").EnumerateArray().ToList();
        Assert.True(cases.Count >= 9);
        foreach (var c in cases)
        {
            var s = c.GetProperty("screen");
            var e = c.GetProperty("encoder");
            var fit = c.GetProperty("fit").GetString() == "fit" ? StreamGeometry.Fit.Fit : StreamGeometry.Fit.Stretch;
            var result = StreamGeometry.Compute(s[0].GetInt32(), s[1].GetInt32(), c.GetProperty("maxLong").GetInt32(), e[0].GetInt32(), e[1].GetInt32(), fit);
            var expected = c.GetProperty("expected");
            if (expected.ValueKind == JsonValueKind.Null)
            {
                Assert.Null(result);
                continue;
            }
            var x = expected.EnumerateArray().Select(i => i.GetInt32()).ToArray();
            Assert.Equal(new StreamGeometry.Result(x[0], x[1], x[2], x[3], x[4], x[5]), result);
        }
    }

    [Fact]
    public void StreamMessages_RoundTrip()
    {
        using var doc = Load("link-stream.json");
        var vectors = doc.RootElement.GetProperty("vectors").EnumerateArray().ToList();
        Assert.Equal(10, vectors.Count);
        foreach (var vector in vectors)
        {
            string json = vector.GetProperty("json").GetString()!;
            PeerMessage? decoded = PeerMessageJson.Decode<PeerMessage>(json);
            Type expected = vector.GetProperty("type").GetString() switch
            {
                "streamCaps" => typeof(PeerMessage.StreamCaps),
                "streamStart" => typeof(PeerMessage.StreamStart),
                "streamState" => typeof(PeerMessage.StreamState),
                "streamConfig" => typeof(PeerMessage.StreamConfig),
                "streamKeyframe" => typeof(PeerMessage.StreamKeyframe),
                "streamStop" => typeof(PeerMessage.StreamStop),
                var other => throw new InvalidOperationException(other),
            };
            Assert.IsType(expected, decoded);
            string reEncoded = PeerMessageJson.Encode(decoded);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)), $"re-encoded as {reEncoded}");
            switch (decoded)
            {
                case PeerMessage.StreamState state: Assert.True(StreamRules.IsValid(state)); break;
                case PeerMessage.StreamStop stop: Assert.True(StreamRules.IsValid(stop)); break;
                case PeerMessage.StreamCaps caps: Assert.NotNull(StreamRules.Clean(caps)); break;
            }
        }
    }

    [Fact]
    public void ThePc_DropsWhatBreaksAFieldRule()
    {
        using var doc = Load("link-stream.json");
        var invalid = doc.RootElement.GetProperty("invalid").EnumerateArray().Where(e => e.GetProperty("receiver").GetString() == "pc").ToList();
        Assert.True(invalid.Count >= 3);
        foreach (var entry in invalid)
        {
            bool valid = PeerMessageJson.Decode<PeerMessage>(entry.GetProperty("json").GetString()!) switch
            {
                PeerMessage.StreamState s => StreamRules.IsValid(s),
                PeerMessage.StreamStop s => StreamRules.IsValid(s),
                PeerMessage.StreamCaps c => StreamRules.Clean(c) is not null,
                _ => true,
            };
            Assert.False(valid, entry.GetProperty("why").GetString());
        }
    }

    [Fact]
    public void SessionIds_AreCanonical()
    {
        string sid = StreamRules.NewSid();
        Assert.Equal(22, sid.Length);
        Assert.NotNull(StreamRules.SidBytes(sid));
        Assert.NotNull(StreamRules.SidBytes("EBESExQVFhcYGRobHB0eHw"));
        Assert.Null(StreamRules.SidBytes("EBESExQVFhcYGRobHB0eHx"));
        Assert.Null(StreamRules.SidBytes("EBESExQVFhcYGRobHB0eH"));
        Assert.Null(StreamRules.SidBytes("EBESExQVFhcYGRobHB0e+w"));
    }
}
