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

        var decoded = LinkJson.Decode<LinkMessage>(json);
        Assert.NotNull(decoded);

        // Re-encoding must produce the same key set, and decoding that must give an equal object.
        string reEncoded = LinkJson.Encode(decoded);
        Assert.Equal(KeysOf(json), KeysOf(reEncoded));
        Assert.Equal(decoded, LinkJson.Decode<LinkMessage>(reEncoded));

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
        var v = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray().First();

        var decoded = LinkJson.Decode<LinkPreamble>(v.GetProperty("json").GetString()!);
        Assert.NotNull(decoded);
        Assert.Equal("6JE8415YZQQYQMBD34569HVYX4", decoded!.Id);
        Assert.Equal("android", decoded.Platform);
        Assert.Equal(1, decoded.ProtocolVersion);

        // encodeDefaults: platform and protocolVersion must be emitted even at their defaults.
        string emitted = LinkJson.Encode(new LinkPreamble("X", "Y"));
        var keys = KeysOf(emitted);
        Assert.Contains("platform", keys);
        Assert.Contains("protocolVersion", keys);
    }

    [Fact]
    public void PreambleAck_OmitsNullReason()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray().ToList();

        var accept = LinkJson.Decode<LinkPreambleAck>(vectors[1].GetProperty("json").GetString()!);
        Assert.Equal("session", accept!.Mode);
        Assert.Null(accept.Reason);
        AssertKeyPolicy(vectors[1], LinkJson.Encode(accept));

        var refused = LinkJson.Decode<LinkPreambleAck>(vectors[2].GetProperty("json").GetString()!);
        Assert.Equal("refused", refused!.Mode);
        Assert.Equal("not paired", refused.Reason);
        AssertKeyPolicy(vectors[2], LinkJson.Encode(refused));
    }

    [Fact]
    public void ValidModes_AreExactlyTheFourDeclared()
    {
        using var doc = LoadVectors();
        var modes = doc.RootElement.GetProperty("handshakeVectors").EnumerateArray()
            .Single(v => v.TryGetProperty("modes", out _))
            .GetProperty("modes").EnumerateArray().Select(m => m.GetString()).ToList();

        Assert.Equal(
            new[] { LinkPreambleAck.ModeSession, LinkPreambleAck.ModePairing,
                    LinkPreambleAck.ModeBusy, LinkPreambleAck.ModeRefused },
            modes);
    }

    [Fact]
    public void WireFrame_SessionCarriesCipher_PairingCarriesPlaintext()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("wireFrameVectors").EnumerateArray().ToList();

        var session = LinkJson.Decode<LinkWireFrame>(vectors[0].GetProperty("json").GetString()!);
        Assert.NotNull(session!.Ciphertext);
        Assert.Null(session.Plaintext);
        AssertKeyPolicy(vectors[0], LinkJson.Encode(session));

        var pairing = LinkJson.Decode<LinkWireFrame>(vectors[1].GetProperty("json").GetString()!);
        Assert.NotNull(pairing!.Plaintext);
        Assert.Null(pairing.Ciphertext);
        AssertKeyPolicy(vectors[1], LinkJson.Encode(pairing));
    }

    [Fact]
    public void Envelope_RoundTripsWithPayload()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("envelopeVectors").EnumerateArray().First();

        var env = LinkJson.Decode<LinkEnvelope>(v.GetProperty("json").GetString()!);
        Assert.NotNull(env);

        var expected = v.GetProperty("expected");
        Assert.Equal(expected.GetProperty("v").GetInt32(), env!.V);
        Assert.Equal(expected.GetProperty("seq").GetInt64(), env.Seq);

        var ping = Assert.IsType<LinkMessage.Ping>(env.Payload);
        Assert.Equal("abc", ping.Nonce);

        AssertKeyPolicy(v, LinkJson.Encode(env));
    }

    [Fact]
    public void ForwardCompatibility_UnknownKeysIgnored_UnknownDiscriminatorRejected()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("forwardCompatibilityVectors").EnumerateArray().ToList();

        // Unknown field inside a known message.
        var ping = LinkJson.Decode<LinkMessage>(vectors[0].GetProperty("json").GetString()!);
        var p = Assert.IsType<LinkMessage.Ping>(ping);
        Assert.Equal("abc", p.Nonce);

        // Unknown field inside the envelope.
        var env = LinkJson.Decode<LinkEnvelope>(vectors[1].GetProperty("json").GetString()!);
        Assert.NotNull(env);

        // Unknown message type must be refused rather than guessed.
        Assert.ThrowsAny<JsonException>(
            () => LinkJson.Decode<LinkMessage>(vectors[2].GetProperty("json").GetString()!));
    }

    [Fact]
    public void Decision_OmitsEveryNullOptional()
    {
        // The null policy matters: a peer emitting "reason": null instead of omitting it is a
        // conformance bug even though a tolerant parser accepts it.
        var decision = new LinkMessage.TransferDecision(
            TransferId: "tx-1",
            Accept: true,
            Route: "LAN_TCP",
            TcpHost: "192.168.1.50",
            TcpPort: 47651,
            AuthToken: "0123456789abcdef");

        var keys = KeysOf(LinkJson.Encode(decision));

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

        var offer = Assert.IsType<LinkMessage.TransferOffer>(LinkJson.Decode<LinkMessage>(json));
        Assert.Equal(["LAN_TCP", "WIFI_DIRECT"], offer.PreferredRoutes);
    }
}
