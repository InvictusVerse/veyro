using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Veyro.Core.Crypto;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>Loads the shared link protocol v3 vector files (ADR-058, FR-T2).</summary>
internal static class V3Vectors
{
    private static readonly string Root = FindRoot();

    private static string FindRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "protocol", "testvectors", "link", "connection-schedule-v3.json")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return Path.Combine(dir!.FullName, "protocol", "testvectors");
    }

    public static JsonElement Load(string relative)
        => JsonDocument.Parse(File.ReadAllText(Path.Combine(Root, relative.Replace('/', Path.DirectorySeparatorChar)))).RootElement;

    public static string Str(this JsonElement e, string key) => e.GetProperty(key).GetString()!;
    public static byte[] Bytes(this JsonElement e, string key) => Convert.FromHexString(e.Str(key));
    public static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();
}

/// <summary>The v3 key schedules against link/connection-schedule-v3.json and pairing-schedule-v3.json.</summary>
public sealed class ConnectionScheduleVectorTests
{
    [Fact]
    public void SessionScheduleMatchesEveryVectorOnBothSides()
    {
        foreach (JsonElement v in V3Vectors.Load("link/connection-schedule-v3.json").GetProperty("vectors").EnumerateArray())
        {
            JsonElement input = v.GetProperty("input"), expected = v.GetProperty("expected");
            byte[] p = Encoding.UTF8.GetBytes(input.Str("preamble")), a = Encoding.UTF8.GetBytes(input.Str("ack"));
            Assert.Equal(expected.Str("preambleHex"), V3Vectors.Hex(p));
            byte[] transcript = ConnectionKeySchedule.Transcript(p, a);
            Assert.Equal(expected.Str("transcriptHex"), V3Vectors.Hex(transcript));
            using EphemeralKey eI = EphemeralKey.FromPrivate(input.Bytes("ephemeralPrivateIHex"));
            using EphemeralKey eR = EphemeralKey.FromPrivate(input.Bytes("ephemeralPrivateRHex"));
            Assert.Equal(expected.Str("ephemeralPublicIHex"), V3Vectors.Hex(eI.PublicKey));
            Assert.Equal(expected.Str("ephemeralPublicRHex"), V3Vectors.Hex(eR.PublicKey));
            byte[] dhI = eI.Agree(eR.PublicKey), dhR = eR.Agree(eI.PublicKey);
            Assert.Equal(expected.Str("dhHex"), V3Vectors.Hex(dhI));
            Assert.Equal(dhI, dhR);
            byte[] secret = input.Bytes("pairingSecretHex");
            LinkKeys i = ConnectionKeySchedule.Session(LinkRole.Initiator, input.Str("idI"), input.Str("idR"), transcript, dhI, secret);
            LinkKeys r = ConnectionKeySchedule.Session(LinkRole.Responder, input.Str("idI"), input.Str("idR"), transcript, dhR, secret);
            Assert.Equal(expected.Str("kI2rHex"), V3Vectors.Hex(i.SendKey));
            Assert.Equal(expected.Str("kR2iHex"), V3Vectors.Hex(i.RecvKey));
            Assert.Equal(expected.Str("kI2rHex"), V3Vectors.Hex(r.RecvKey));
            Assert.Equal(expected.Str("kR2iHex"), V3Vectors.Hex(r.SendKey));
            Assert.Equal(expected.Str("expSecretHex"), V3Vectors.Hex(i.ExpSecret!));
            Assert.Equal(expected.Str("prkHex"), V3Vectors.Hex(Hkdf.Extract(transcript, [.. dhI, .. secret])));
        }
    }

    [Fact]
    public void TwoConnectionsWithTheSamePairingSecretShareNoKey()
    {
        var v = V3Vectors.Load("link/connection-schedule-v3.json").GetProperty("vectors").EnumerateArray().Select(x => x.GetProperty("expected")).ToList();
        foreach (string field in new[] { "transcriptHex", "dhHex", "kI2rHex", "kR2iHex", "expSecretHex" })
            Assert.NotEqual(v[0].Str(field), v[1].Str(field));
    }

    [Fact]
    public void ConfirmScheduleAndTheSwitchMatch()
    {
        JsonElement c = V3Vectors.Load("link/pairing-schedule-v3.json").GetProperty("confirm");
        JsonElement input = c.GetProperty("input"), expected = c.GetProperty("expected");
        string idI = input.Str("idI"), idR = input.Str("idR");
        byte[] transcript = ConnectionKeySchedule.Transcript(Encoding.UTF8.GetBytes(input.Str("preamble")), Encoding.UTF8.GetBytes(input.Str("ack")));
        Assert.Equal(expected.Str("transcriptHex"), V3Vectors.Hex(transcript));
        byte[] pkI = X25519Crypto.FromSeed(input.Bytes("staticPrivateIHex")).PublicKey;
        byte[] pkR = X25519Crypto.FromSeed(input.Bytes("staticPrivateRHex")).PublicKey;
        Assert.Equal(expected.Str("commitHex"), V3Vectors.Hex(PairConfirm.Commitment(pkR, pkI, input.Bytes("nBHex"))));
        Assert.Equal(expected.Str("staticInfoHex"), V3Vectors.Hex(ConnectionKeySchedule.StaticInfo(idI, idR)));
        // What IIdentityManager.DeriveSharedKey(pk, salt = transcript, info) computes.
        byte[] staticKey = Hkdf.Expand(Hkdf.Extract(transcript, X25519Crypto.SharedSecret(input.Bytes("staticPrivateIHex"), pkR)), ConnectionKeySchedule.StaticInfo(idI, idR), 32);
        Assert.Equal(expected.Str("staticKeyHex"), V3Vectors.Hex(staticKey));
        using EphemeralKey eI = EphemeralKey.FromPrivate(input.Bytes("ephemeralPrivateIHex"));
        using EphemeralKey eR = EphemeralKey.FromPrivate(input.Bytes("ephemeralPrivateRHex"));
        LinkKeys boot = ConnectionKeySchedule.PairConfirm(LinkRole.Initiator, idI, idR, transcript, input.Bytes("nAHex"), input.Bytes("nBHex"), eI.Agree(eR.PublicKey), staticKey);
        Assert.Equal(expected.Str("kI2rHex"), V3Vectors.Hex(boot.SendKey));
        Assert.Equal(expected.Str("kR2iHex"), V3Vectors.Hex(boot.RecvKey));
        Assert.Equal(expected.Str("chainKeyHex"), V3Vectors.Hex(boot.ChainKey!));
        Assert.Null(boot.ExpSecret);
        LinkKeys session = ConnectionKeySchedule.AfterPairing(boot, input.Bytes("newPairingSecretHex"));
        Assert.Equal(expected.Str("sessionKI2rHex"), V3Vectors.Hex(session.SendKey));
        Assert.Equal(expected.Str("sessionKR2iHex"), V3Vectors.Hex(session.RecvKey));
        Assert.Equal(expected.Str("sessionExpSecretHex"), V3Vectors.Hex(session.ExpSecret!));
        Assert.All(boot.ChainKey!, b => Assert.Equal(0, b));
    }

    [Fact]
    public void QrScheduleSecretDerivationsAndProofMatch()
    {
        JsonElement q = V3Vectors.Load("link/pairing-schedule-v3.json").GetProperty("qr");
        JsonElement input = q.GetProperty("input"), expected = q.GetProperty("expected");
        string idI = input.Str("idI"), idR = input.Str("idR");
        PairingQrPayload parsed = PairingQr.TryParse(input.Str("qrString"))!;
        Assert.Equal(input.Str("qrSecretHex"), V3Vectors.Hex(parsed.Secret));
        Assert.Equal(expected.Str("qrSecretBase64Url"), PairingQr.EncodeSecret(parsed.Secret));
        Assert.Equal(idR, parsed.DeviceId);
        byte[] secret = input.Bytes("qrSecretHex");
        Assert.Equal(expected.Str("qrPrkHex"), V3Vectors.Hex(ConnectionKeySchedule.QrPrk(secret)));
        byte[] proofKey = ConnectionKeySchedule.QrProofKey(secret), qrMix = ConnectionKeySchedule.QrMix(secret);
        Assert.Equal(expected.Str("proofKeyHex"), V3Vectors.Hex(proofKey));
        Assert.Equal(expected.Str("qrMixHex"), V3Vectors.Hex(qrMix));
        byte[] transcript = ConnectionKeySchedule.Transcript(Encoding.UTF8.GetBytes(input.Str("preamble")), Encoding.UTF8.GetBytes(input.Str("ack")));
        byte[] proof = ConnectionKeySchedule.QrProof(proofKey, idI, idR, transcript);
        Assert.Equal(expected.Str("qrProofHex"), V3Vectors.Hex(proof));
        Assert.Equal(expected.Str("qrProofBase64"), Convert.ToBase64String(proof));
        byte[] pkI = X25519Crypto.FromSeed(input.Bytes("staticPrivateIHex")).PublicKey;
        byte[] staticKey = Hkdf.Expand(Hkdf.Extract(transcript, X25519Crypto.SharedSecret(input.Bytes("staticPrivateRHex"), pkI)), ConnectionKeySchedule.StaticInfo(idI, idR), 32);
        Assert.Equal(expected.Str("staticKeyHex"), V3Vectors.Hex(staticKey));
        using EphemeralKey eI = EphemeralKey.FromPrivate(input.Bytes("ephemeralPrivateIHex"));
        using EphemeralKey eR = EphemeralKey.FromPrivate(input.Bytes("ephemeralPrivateRHex"));
        LinkKeys boot = ConnectionKeySchedule.PairQr(LinkRole.Responder, idI, idR, transcript, eR.Agree(eI.PublicKey), staticKey, qrMix);
        Assert.Equal(expected.Str("kI2rHex"), V3Vectors.Hex(boot.RecvKey));
        Assert.Equal(expected.Str("kR2iHex"), V3Vectors.Hex(boot.SendKey));
        Assert.Equal(expected.Str("chainKeyHex"), V3Vectors.Hex(boot.ChainKey!));
        LinkKeys session = ConnectionKeySchedule.AfterPairing(boot, input.Bytes("newPairingSecretHex"));
        Assert.Equal(expected.Str("sessionKI2rHex"), V3Vectors.Hex(session.RecvKey));
        Assert.Equal(expected.Str("sessionExpSecretHex"), V3Vectors.Hex(session.ExpSecret!));
    }
}

/// <summary>Session frames against link/session-frames-v3.json.</summary>
public sealed class SessionFrameVectorTests
{
    private static readonly JsonElement File = V3Vectors.Load("link/session-frames-v3.json");
    private static readonly JsonElement Input = File.GetProperty("input");
    private static byte[] Key(string dir) => Input.Bytes(dir == "i2r" ? "kI2rHex" : "kR2iHex");
    private static byte Dir(string dir) => dir == "i2r" ? FrameCrypto.DirI2R : FrameCrypto.DirR2I;

    [Fact]
    public void SealerReproducesEveryFrameAndTheOpenerReadsItBack()
    {
        var sealers = new Dictionary<string, SessionSealer>
        {
            ["i2r"] = new(Key("i2r"), Input.Str("idI"), FrameCrypto.DirI2R),
            ["r2i"] = new(Key("r2i"), Input.Str("idR"), FrameCrypto.DirR2I),
        };
        var openers = new Dictionary<string, SessionOpener>
        {
            ["i2r"] = new(Key("i2r"), Input.Str("idI"), FrameCrypto.DirI2R),
            ["r2i"] = new(Key("r2i"), Input.Str("idR"), FrameCrypto.DirR2I),
        };
        foreach (JsonElement f in File.GetProperty("frames").EnumerateArray())
        {
            string dir = f.Str("direction");
            Assert.Equal(f.GetProperty("counter").GetInt64(), sealers[dir].NextCounter);
            Assert.Equal(f.Str("aadHex"), V3Vectors.Hex(FrameCrypto.Aad(f.Str("from"), Dir(dir), f.GetProperty("counter").GetInt64())));
            Assert.Equal(f.Str("wire"), sealers[dir].Seal(f.Str("envelope")));
            Assert.Equal(f.Str("envelope"), openers[dir].Open(f.Str("wire")));
        }
    }

    [Fact]
    public void EveryNegativeIsRefusedAtFailsAt()
    {
        foreach (JsonElement n in File.GetProperty("negatives").EnumerateArray())
        {
            string dir = n.Str("direction");
            using var opener = new SessionOpener(Key(dir), n.Str("peerId"), Dir(dir));
            string[] wires = n.GetProperty("wires").EnumerateArray().Select(w => w.GetString()!).ToArray();
            int failsAt = n.GetProperty("failsAt").GetInt32();
            for (int i = 0; i < failsAt; i++) opener.Open(wires[i]);
            Exception error = Assert.ThrowsAny<Exception>(() => opener.Open(wires[failsAt]));
            Assert.True(error is UnauthorizedAccessException or InvalidDataException, n.Str("name"));
        }
    }
}

/// <summary>The key exporter against link/exporter-v3.json.</summary>
public sealed class ExporterVectorTests
{
    private static readonly JsonElement File = V3Vectors.Load("link/exporter-v3.json");

    [Fact]
    public void ExporterOutputsMatch()
    {
        byte[] secret = File.Bytes("expSecretHex");
        foreach (JsonElement v in File.GetProperty("vectors").EnumerateArray())
            Assert.Equal(v.Str("outputHex"), V3Vectors.Hex(ConnectionKeySchedule.Export(secret, v.Str("label"), v.Bytes("contextHex"), v.GetProperty("length").GetInt32())));
    }

    [Fact]
    public void InvalidArgumentsAreRefused()
    {
        byte[] secret = File.Bytes("expSecretHex");
        foreach (JsonElement v in File.GetProperty("invalid").EnumerateArray())
            Assert.Throws<ArgumentException>(() => ConnectionKeySchedule.Export(secret, v.Str("label"), new byte[v.GetProperty("contextLength").GetInt32()], v.GetProperty("length").GetInt32()));
    }
}

/// <summary>VTC3 records against frames/vtc3-frames.json.</summary>
public sealed class Vtc3VectorTests
{
    private static readonly JsonElement File = V3Vectors.Load("frames/vtc3-frames.json");
    private static readonly JsonElement Input = File.GetProperty("input");
    private static readonly JsonElement Expected = File.GetProperty("expected");
    private static readonly string Tid = Input.Str("transferId");
    private static byte[] Key(string dir) => Expected.Bytes(dir == "s2r" ? "keyS2RHex" : "keyR2SHex");
    private static uint Stream(string dir) => dir == "s2r" ? Vtc3.StreamSenderToReceiver : Vtc3.StreamReceiverToSender;

    [Fact]
    public async Task StreamOpenRecordsAndPerAttemptKeysMatch()
    {
        byte[] saltA = Input.Bytes("saltAHex"), saltB = Input.Bytes("saltBHex");
        Assert.Equal(Expected.Str("contextHex"), V3Vectors.Hex(Vtc3.ExportContext(Tid, saltA, saltB)));
        var exporter = new FixedExporter(Input.Bytes("expSecretHex"));
        Assert.Equal(Expected.Str("okmHex"), V3Vectors.Hex(exporter.Deriver(Tid)(saltA, saltB)));
        Assert.Equal(Expected.Str("streamOpenSenderHex"), V3Vectors.Hex(Vtc3.StreamOpenRecord(Tid, saltA)));
        Assert.Equal(Expected.Str("streamOpenReceiverHex"), V3Vectors.Hex(Vtc3.StreamOpenRecord(Tid, saltB)));
        Assert.Equal(saltA, await Vtc3.ReadStreamOpenAsync(new MemoryStream(Expected.Bytes("streamOpenSenderHex")), Tid, CancellationToken.None));
    }

    [Fact]
    public async Task TheWriterReproducesEveryRecordAndTheReaderDecodesIt()
    {
        foreach (string dir in new[] { "s2r", "r2s" })
        {
            var records = File.GetProperty("records").EnumerateArray().Where(r => r.Str("direction") == dir).ToList();
            using var ms = new MemoryStream();
            using var writer = new Vtc3Writer(ms, Key(dir), Stream(dir), Tid);
            foreach (JsonElement r in records)
            {
                long before = ms.Length;
                await writer.WriteAsync(FrameOf(r.GetProperty("frame")));
                Assert.Equal(r.Str("recordHex"), V3Vectors.Hex(ms.ToArray()[(int)before..]));
            }
            using var reader = new Vtc3Reader(new MemoryStream(ms.ToArray()), Key(dir), Stream(dir), Tid);
            foreach (JsonElement r in records)
            {
                TcpFrame frame = (await reader.ReadOrNullAsync())!;
                Assert.Equal(r.GetProperty("seq").GetInt64(), frame.Seq);
                Assert.Equal(FrameOf(r.GetProperty("frame"), frame.Seq), frame);
            }
            Assert.Null(await reader.ReadOrNullAsync());
        }
    }

    [Fact]
    public async Task EveryNegativeFailsTheStreamAtFailsAt()
    {
        foreach (JsonElement n in File.GetProperty("negatives").EnumerateArray())
        {
            string dir = n.Str("direction");
            byte[] bytes = n.GetProperty("recordsHex").EnumerateArray().SelectMany(h => Convert.FromHexString(h.GetString()!)).ToArray();
            using var reader = new Vtc3Reader(new MemoryStream(bytes), Key(dir), Stream(dir), Tid);
            int failsAt = n.GetProperty("failsAt").GetInt32();
            for (int i = 0; i < failsAt; i++) await reader.ReadOrNullAsync();
            Exception error = await Assert.ThrowsAnyAsync<Exception>(() => reader.ReadOrNullAsync());
            Assert.True(error is InvalidDataException or EndOfStreamException, n.Str("name"));
            await Assert.ThrowsAsync<InvalidDataException>(() => reader.ReadOrNullAsync());
        }
    }

    private static TcpFrame FrameOf(JsonElement f, long seq = 0) => f.Str("type") switch
    {
        "Auth" => new TcpFrame.Auth(seq, Tid, f.Str("authToken"), f.Str("senderDeviceId"), f.Str("route") == "LAN_TCP" ? TransferRoute.LanTcp : TransferRoute.WifiDirect),
        "AuthAck" => new TcpFrame.AuthAck(seq, Tid, f.GetProperty("ok").GetBoolean()),
        "ItemBegin" => new TcpFrame.ItemBegin(seq, Tid, f.GetProperty("itemIndex").GetInt32(), f.GetProperty("sizeBytes").GetInt64(), f.GetProperty("chunkCount").GetInt32(), f.GetProperty("chunkSize").GetInt32()),
        "Chunk" => new TcpFrame.Chunk(seq, Tid, f.GetProperty("itemIndex").GetInt32(), f.GetProperty("chunkIndex").GetInt32(), f.Bytes("dataHex")),
        "ItemEnd" => new TcpFrame.ItemEnd(seq, Tid, f.GetProperty("itemIndex").GetInt32(), f.Str("sha256Hex")),
        "ItemResult" => new TcpFrame.ItemResult(seq, Tid, f.GetProperty("itemIndex").GetInt32(), f.GetProperty("ok").GetBoolean()),
        "TransferEnd" => new TcpFrame.TransferEnd(seq, Tid, f.GetProperty("completed").GetBoolean()),
        "Cancel" => new TcpFrame.Cancel(seq, Tid, f.Str("reason")),
        _ => throw new InvalidDataException()
    };
}

/// <summary>The RFC vectors in protocol/testvectors/crypto through the primitives Veyro uses.</summary>
public sealed class ChaChaPolyVectorTests
{
    [Fact]
    public void ChaCha20Poly1305IsSupportedOnTheBuildMachine() => Assert.True(ChaCha20Poly1305.IsSupported);

    [Fact]
    public void Rfc8439AeadVector()
    {
        JsonElement v = V3Vectors.Load("crypto/chacha20poly1305.json").GetProperty("vectors")[0];
        using var aead = new ChaCha20Poly1305(v.Bytes("keyHex"));
        byte[] plain = v.Bytes("plaintextHex");
        byte[] ct = new byte[plain.Length], tag = new byte[16];
        aead.Encrypt(v.Bytes("nonceHex"), plain, ct, tag, v.Bytes("aadHex"));
        Assert.Equal(v.Str("ciphertextHex"), V3Vectors.Hex(ct));
        Assert.Equal(v.Str("tagHex"), V3Vectors.Hex(tag));
    }

    [Fact]
    public void Rfc5869HkdfVectors()
    {
        foreach (JsonElement v in V3Vectors.Load("crypto/hkdf-sha256.json").GetProperty("vectors").EnumerateArray())
        {
            byte[] prk = Hkdf.Extract(v.Bytes("saltHex"), v.Bytes("ikmHex"));
            Assert.Equal(v.Str("prkHex"), V3Vectors.Hex(prk));
            Assert.Equal(v.Str("okmHex"), V3Vectors.Hex(Hkdf.Expand(prk, v.Bytes("infoHex"), v.GetProperty("length").GetInt32())));
        }
    }

    [Fact]
    public void Rfc7748X25519Vector()
    {
        JsonElement v = V3Vectors.Load("crypto/x25519.json").GetProperty("vectors")[0];
        Assert.Equal(v.Str("alicePublicHex"), V3Vectors.Hex(X25519Crypto.FromSeed(v.Bytes("alicePrivateHex")).PublicKey));
        Assert.Equal(v.Str("sharedHex"), V3Vectors.Hex(X25519Crypto.SharedSecret(v.Bytes("alicePrivateHex"), v.Bytes("bobPublicHex"))));
    }
}
