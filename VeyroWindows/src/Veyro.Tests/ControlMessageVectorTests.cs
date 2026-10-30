using System.Text.Json;
using Veyro.Core.Link;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Drives protocol/testvectors/link/control-messages.json.
///
/// These vectors deliberately do NOT pin member order: JSON objects are unordered and the frame
/// MAC covers the bytes as received rather than a re-serialization, so order is not a correctness
/// requirement. What is pinned is the exact key set, the discriminator values, that defaults are
/// emitted, and that nulls are omitted rather than written.
/// </summary>
public sealed class ControlMessageVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");

        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link", "control-messages.json")));
    }

    private static HashSet<string> KeysOf(string json)
    {
        using var doc = JsonDocument.Parse(json);
        return doc.RootElement.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal);
    }

    private static void AssertKeyPolicy(JsonElement vector, string emitted)
    {
        var keys = KeysOf(emitted);

        if (vector.TryGetProperty("requiredKeys", out var required))
        {
            foreach (var k in required.EnumerateArray())
                Assert.Contains(k.GetString()!, keys);
        }

        if (vector.TryGetProperty("forbiddenKeys", out var forbidden))
        {
            foreach (var k in forbidden.EnumerateArray())
                Assert.DoesNotContain(k.GetString()!, keys);
        }
    }

    public static TheoryData<string> MessageNames()
    {
        var data = new TheoryData<string>();
        using var doc = LoadVectors();
        foreach (var v in doc.RootElement.GetProperty("messageVectors").EnumerateArray())
            data.Add(v.GetProperty("name").GetString()!);
        return data;
    }

    [Theory]
    [MemberData(nameof(MessageNames))]
    public void Message_DecodesAndReEncodesEquivalently(string name)
    {
        using var doc = LoadVectors();
        var vector = doc.RootElement.GetProperty("messageVectors").EnumerateArray()
            .Single(v => v.GetProperty("name").GetString() == name);

        string json = vector.GetProperty("json").GetString()!;

        var decoded = PeerMessageJson.Decode<PeerMessage>(json);
        Assert.NotNull(decoded);

        // Re-encoding must produce the same key set, and decoding that must give an equal object.
        string reEncoded = PeerMessageJson.Encode(decoded);
        Assert.Equal(KeysOf(json), KeysOf(reEncoded));
        Assert.Equal(decoded, PeerMessageJson.Decode<PeerMessage>(reEncoded));

        AssertKeyPolicy(vector, reEncoded);

        // The discriminator must survive both directions.
        using var round = JsonDocument.Parse(reEncoded);
        Assert.Equal(vector.GetProperty("discriminator").GetString(),
                     round.RootElement.GetProperty("t").GetString());
    }

    [Fact]
    public void Preamble_MatchesVector()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray()
            .Single(e => e.TryGetProperty("name", out var n) && n.GetString() == "LinkPreamble v3");

        var decoded = PeerMessageJson.Decode<LinkPreamble>(v.GetProperty("json").GetString()!);
        Assert.NotNull(decoded);
        Assert.Equal("2C9VV72DFSF1A2SC0TN6QBAPEC", decoded!.Id);
        Assert.Equal("android", decoded.Platform);
        Assert.Equal(3, decoded.ProtocolVersion);
        Assert.Equal(3, LinkProtocol.Version);
        Assert.Equal(32, Convert.FromBase64String(decoded.Cn!).Length);
        Assert.Equal(32, Convert.FromBase64String(decoded.Epk!).Length);
        AssertKeyPolicy(v, PeerMessageJson.Encode(decoded));

        // encodeDefaults: platform and protocolVersion must be emitted even at their defaults.
        string emitted = PeerMessageJson.Encode(new LinkPreamble("X", "Y", Cn: decoded.Cn, Epk: decoded.Epk));
        var keys = KeysOf(emitted);
        Assert.Contains("platform", keys);
        Assert.Contains("protocolVersion", keys);
        AssertKeyPolicy(v, emitted);
    }

    [Fact]
    public void ConfirmPairingFrames_MatchVectors()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray().ToList();
        JsonElement Named(string name) => vectors.Single(v => v.TryGetProperty("name", out var n) && n.GetString() == name);

        var preambleVector = Named("LinkPreamble for confirm pairing v3");
        var preamble = PeerMessageJson.Decode<LinkPreamble>(preambleVector.GetProperty("json").GetString()!)!;
        Assert.Equal(LinkPreamble.PairConfirm, preamble.Pair);
        Assert.Equal("hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=", preamble.Pk);
        AssertKeyPolicy(preambleVector, PeerMessageJson.Encode(preamble));

        var ackVector = Named("LinkPreambleAck for confirm pairing v3");
        var ack = PeerMessageJson.Decode<LinkPreambleAck>(ackVector.GetProperty("json").GetString()!)!;
        Assert.Equal(LinkPreambleAck.ModePairingConfirm, ack.Mode);
        Assert.Equal("SDrgTP6a5PIY+i7qEvbocaY0lV825gmpRytWpVLLpMM=", ack.Commit);
        AssertKeyPolicy(ackVector, PeerMessageJson.Encode(ack));

        var nonceVector = Named("pairNonce");
        var nonce = PeerMessageJson.Decode<LinkPairNonce>(nonceVector.GetProperty("json").GetString()!)!;
        Assert.Equal(LinkPairNonce.Type, nonce.T);
        Assert.Equal(32, Convert.FromBase64String(nonce.N).Length);
        AssertKeyPolicy(nonceVector, PeerMessageJson.Encode(nonce));
    }

    [Fact]
    public void PreambleAck_OmitsNullReason()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray().ToList();

        var accept = PeerMessageJson.Decode<LinkPreambleAck>(vectors[1].GetProperty("json").GetString()!);
        Assert.Equal("session", accept!.Mode);
        Assert.Null(accept.Reason);
        AssertKeyPolicy(vectors[1], PeerMessageJson.Encode(accept));

        var refused = PeerMessageJson.Decode<LinkPreambleAck>(vectors[2].GetProperty("json").GetString()!);
        Assert.Equal("refused", refused!.Mode);
        Assert.Equal("not paired", refused.Reason);
        AssertKeyPolicy(vectors[2], PeerMessageJson.Encode(refused));
    }

    [Fact]
    public void ValidModes_AreExactlyTheFiveDeclared()
    {
        using var doc = LoadVectors();
        var modes = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray()
            .Single(v => v.TryGetProperty("modes", out _))
            .GetProperty("modes").EnumerateArray().Select(m => m.GetString()).ToList();

        Assert.Equal(
            new[] { LinkPreambleAck.ModeSession, LinkPreambleAck.ModePairing,
                    LinkPreambleAck.ModePairingConfirm, LinkPreambleAck.ModeBusy,
                    LinkPreambleAck.ModeRefused },
            modes);
    }

    [Fact]
    public void QrPairingFrames_MatchVectors()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray().ToList();
        JsonElement Named(string name) => vectors.Single(v => v.TryGetProperty("name", out var n) && n.GetString() == name);

        var preambleVector = Named("LinkPreamble for QR pairing v3");
        var preamble = PeerMessageJson.Decode<LinkPreamble>(preambleVector.GetProperty("json").GetString()!)!;
        Assert.Equal(LinkPreamble.PairQr, preamble.Pair);
        AssertKeyPolicy(preambleVector, PeerMessageJson.Encode(preamble));

        var ackVector = Named("LinkPreambleAck for QR pairing v3");
        var ack = PeerMessageJson.Decode<LinkPreambleAck>(ackVector.GetProperty("json").GetString()!)!;
        Assert.Equal(LinkPreambleAck.ModePairing, ack.Mode);
        AssertKeyPolicy(ackVector, PeerMessageJson.Encode(ack));

        var sessionAck = Named("LinkPreambleAck accepting a session v3");
        var accepted = PeerMessageJson.Decode<LinkPreambleAck>(sessionAck.GetProperty("json").GetString()!)!;
        Assert.Equal(LinkPreambleAck.ModeSession, accepted.Mode);
        AssertKeyPolicy(sessionAck, PeerMessageJson.Encode(accepted));
    }

    [Fact]
    public void WireFrame_V3CarriesExactlyFromAndCiphertext()
    {
        using var doc = LoadVectors();
        var vector = doc.RootElement.GetProperty("wireFrameVectors").EnumerateArray()
            .Single(v => !v.TryGetProperty("retiredIn", out _));

        var frame = PeerMessageJson.Decode<LinkWireFrame>(vector.GetProperty("json").GetString()!);
        Assert.Equal("6JE8415YZQQYQMBD34569HVYX4", frame!.From);
        Assert.Equal("Y2lwaGVydGV4dA==", frame.Ciphertext);
        string emitted = PeerMessageJson.Encode(frame);
        Assert.Equal(new HashSet<string>(["f", "c"]), KeysOf(emitted));
        AssertKeyPolicy(vector, emitted);
    }

    [Fact]
    public void Envelope_RoundTripsWithPayload()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("envelopeVectors").EnumerateArray()
            .Single(e => !e.TryGetProperty("retiredIn", out _));

        var env = PeerMessageJson.Decode<LinkEnvelope>(v.GetProperty("json").GetString()!);
        Assert.NotNull(env);

        var expected = v.GetProperty("expected");
        Assert.Equal(expected.GetProperty("v").GetInt32(), env!.V);
        Assert.Equal(expected.GetProperty("seq").GetInt64(), env.Seq);

        var ping = Assert.IsType<PeerMessage.Ping>(env.Payload);
        Assert.Equal("abc", ping.Nonce);

        AssertKeyPolicy(v, PeerMessageJson.Encode(env));
    }

    [Fact]
    public void ForwardCompatibility_UnknownKeysIgnored_UnknownDiscriminatorRejected()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("forwardCompatibilityVectors").EnumerateArray().ToList();

        // Unknown field inside a known message.
        var ping = PeerMessageJson.Decode<PeerMessage>(vectors[0].GetProperty("json").GetString()!);
        var p = Assert.IsType<PeerMessage.Ping>(ping);
        Assert.Equal("abc", p.Nonce);

        // Unknown field inside the envelope.
        var env = PeerMessageJson.Decode<LinkEnvelope>(vectors[1].GetProperty("json").GetString()!);
        Assert.NotNull(env);

        // Unknown message type must be refused rather than guessed.
        Assert.ThrowsAny<JsonException>(
            () => PeerMessageJson.Decode<PeerMessage>(vectors[2].GetProperty("json").GetString()!));
    }

    [Fact]
    public void Decision_OmitsEveryNullOptional()
    {
        // The null policy matters: a peer emitting "reason": null instead of omitting it is a
        // conformance bug even though a tolerant parser accepts it.
        var decision = new PeerMessage.TransferDecision(
            TransferId: "tx-1",
            Accept: true,
            Route: "LAN_TCP",
            TcpHost: "192.168.1.50",
            TcpPort: 47651,
            AuthToken: "0123456789abcdef");

        var keys = KeysOf(PeerMessageJson.Encode(decision));

        Assert.Contains("route", keys);
        Assert.Contains("authToken", keys);
        foreach (string absent in new[] { "reason", "resume", "fallbackHost", "p2pSsid", "p2pPassphrase" })
            Assert.DoesNotContain(absent, keys);
    }

    [Fact]
    public void Offer_PreservesRouteOrder()
    {
        // preferredRoutes is an ordered preference list; ADR-023 has the sender rank it.
        const string json =
            "{\"t\":\"offer\",\"transferId\":\"tx-1\",\"items\":[],\"totalBytes\":0," +
            "\"senderName\":\"PC\",\"preferredRoutes\":[\"LAN_TCP\",\"WIFI_DIRECT\"]}";

        var offer = Assert.IsType<PeerMessage.TransferOffer>(PeerMessageJson.Decode<PeerMessage>(json));
        Assert.Equal(["LAN_TCP", "WIFI_DIRECT"], offer.PreferredRoutes);
    }
}
