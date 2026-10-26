using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Veyro.Core.Crypto;
using Veyro.Core.Diagnostics;

namespace Veyro.Core.Link;

public sealed record SecureLinkMessage(LinkEnvelope Envelope);

/// <summary>
/// Authenticated VLP control channel over a WebSocket. The preamble and mode selection happen
/// outside this class; once a key is selected every message is authenticated here.
/// </summary>
public sealed class SecureLinkSession : IAsyncDisposable
{
    public const int MaxFrameSizeBytes = 24 * 1024 * 1024;

    private readonly WebSocket _socket;
    private readonly string _localDeviceId;
    private readonly string? _expectedPeerId;
    private readonly NonceCache _nonces = new();
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private readonly Dictionary<string, long> _lastPeerSequence = new(StringComparer.Ordinal);
    private byte[] _key;
    private bool _encrypt;
    private long _outboundSequence;

    public SecureLinkSession(
        WebSocket socket,
        string localDeviceId,
        byte[] key,
        bool encrypt,
        string? expectedPeerId = null)
    {
        _socket = socket;
        _localDeviceId = localDeviceId;
        _key = FrameCrypto.SessionKeyFromBytes(key).ToArray();
        _encrypt = encrypt;
        _expectedPeerId = expectedPeerId;
    }

    public async Task SendAsync(LinkMessage message, CancellationToken ct = default)
        => await SendInternalAsync(message, encrypt: _encrypt, ct).ConfigureAwait(false);

    public async Task SendSealedAsync(LinkMessage message, CancellationToken ct = default)
        => await SendInternalAsync(message, encrypt: true, ct).ConfigureAwait(false);

    private async Task SendInternalAsync(LinkMessage message, bool encrypt, CancellationToken ct)
    {
        // The sequence number is allocated and the frame written under one gate. Allocating it
        // outside would let two concurrent senders (receive loop answering a Ping while the app
        // sends a notification request) put seq N+1 on the wire before seq N, which the peer
        // correctly rejects as out-of-order.
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            LinkEnvelope envelope = new()
            {
                MsgId = Guid.NewGuid().ToString(),
                From = _localDeviceId,
                Seq = ++_outboundSequence,
                Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Payload = message
            };
            string payloadJson = LinkJson.Encode(envelope);
            SealedFrame sealedFrame = FrameCrypto.Seal(payloadJson, _key, encrypt, _localDeviceId);
            string wire = LinkJson.Encode(new LinkWireFrame
            {
                From = sealedFrame.FromDeviceId,
                Nonce = Convert.ToBase64String(sealedFrame.Nonce),
                Ciphertext = sealedFrame.Encrypted ? Convert.ToBase64String(sealedFrame.Body) : null,
                Plaintext = sealedFrame.Encrypted ? null : Convert.ToBase64String(sealedFrame.Body),
                Mac = Convert.ToBase64String(sealedFrame.Mac)
            });
            byte[] bytes = Encoding.UTF8.GetBytes(wire);
            if (bytes.Length > MaxFrameSizeBytes) throw new InvalidOperationException("Control frame exceeds the protocol limit.");

            await _socket.SendAsync(bytes, WebSocketMessageType.Text, true, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>
    /// Receives the next authenticated message. A frame that authenticates but carries a payload
    /// type this build does not know is consumed (its sequence number still counts) and skipped,
    /// so a newer peer can add message types without tearing down the session.
    /// </summary>
    public async Task<SecureLinkMessage?> ReceiveAsync(CancellationToken ct = default)
    {
        while (true)
        {
            byte[] bytes = await ReceiveTextAsync(ct).ConfigureAwait(false);
            if (bytes.Length > MaxFrameSizeBytes) throw new InvalidDataException("Control frame exceeds the protocol limit.");

            LinkWireFrame? wire = LinkJson.Decode<LinkWireFrame>(Encoding.UTF8.GetString(bytes));
            if (wire is null || string.IsNullOrWhiteSpace(wire.From) || wire.Ciphertext is not null == (wire.Plaintext is not null))
                throw new InvalidDataException("Malformed VLP control frame.");
            if (_encrypt && wire.Plaintext is not null)
                throw new InvalidDataException("Plaintext downgrade in encrypted session.");

            byte[] nonce = DecodeExact(wire.Nonce, FrameCrypto.NonceBytes, "nonce");
            if (!_nonces.CheckAndStore(wire.Nonce)) throw new InvalidDataException("Replayed VLP nonce.");
            byte[] body = wire.Ciphertext is not null
                ? Convert.FromBase64String(wire.Ciphertext)
                : Encoding.UTF8.GetBytes(wire.Plaintext!);
            byte[] mac = DecodeExact(wire.Mac, 32, "mac");
            string? json = FrameCrypto.Open(new SealedFrame(nonce, body, mac, wire.Ciphertext is not null, wire.From), _key);
            if (json is null) throw new UnauthorizedAccessException("VLP control frame authentication failed.");

            LinkEnvelope? envelope;
            try
            {
                envelope = LinkJson.Decode<LinkEnvelope>(json);
            }
            catch (Exception error) when (error is JsonException or NotSupportedException)
            {
                EnvelopeHeader header = ReadUnknownEnvelopeHeader(json, error);
                AcceptSequence(header.From, header.V, header.Seq, wire.From);
                VeyroTrace.Info($"Ignoring unsupported VLP message type '{header.Type}'");
                continue;
            }
            if (envelope is null)
                throw new InvalidDataException("Malformed VLP envelope.");
            AcceptSequence(envelope.From, envelope.V, envelope.Seq, wire.From);
            return new SecureLinkMessage(envelope);
        }
    }

    private void AcceptSequence(string from, int version, long seq, string wireFrom)
    {
        if (from != wireFrom || version <= 0 || seq <= 0)
            throw new InvalidDataException("Malformed VLP envelope.");
        if (_expectedPeerId is not null && !string.Equals(_expectedPeerId, from, StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("VLP peer identity mismatch.");
        if (_lastPeerSequence.TryGetValue(from, out long previous) && seq <= previous)
            throw new InvalidDataException("Out-of-order or replayed VLP sequence.");
        _lastPeerSequence[from] = seq;
    }

    private sealed record EnvelopeHeader(string From, int V, long Seq, string Type);

    /// <summary>
    /// Extracts the envelope header when the payload could not be decoded. Only a well-formed
    /// envelope whose payload discriminator is genuinely unknown is tolerated; a known type that
    /// fails to decode is a malformed message and still fails the session.
    /// </summary>
    private static EnvelopeHeader ReadUnknownEnvelopeHeader(string json, Exception decodeError)
    {
        try
        {
            using JsonDocument doc = JsonDocument.Parse(json);
            JsonElement root = doc.RootElement;
            string from = root.GetProperty("from").GetString() ?? throw new InvalidDataException("Missing from.");
            int v = root.GetProperty("v").GetInt32();
            long seq = root.GetProperty("seq").GetInt64();
            string type = root.GetProperty("payload").GetProperty("t").GetString() ?? string.Empty;
            if (type.Length == 0 || KnownPayloadTypes.Contains(type))
                throw new InvalidDataException("Malformed VLP payload.", decodeError);
            // The type name is peer-controlled; only echo it to diagnostics if it looks like one.
            string safeType = type.Length <= 32 && type.All(char.IsAsciiLetterOrDigit) ? type : "<invalid>";
            return new EnvelopeHeader(from, v, seq, safeType);
        }
        catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            throw new InvalidDataException("Malformed VLP envelope.", error);
        }
    }

    private static readonly HashSet<string> KnownPayloadTypes = typeof(LinkMessage)
        .GetCustomAttributes(typeof(JsonDerivedTypeAttribute), inherit: false)
        .Cast<JsonDerivedTypeAttribute>()
        .Select(attribute => attribute.TypeDiscriminator as string)
        .OfType<string>()
        .ToHashSet(StringComparer.Ordinal);

    public void Rekey(byte[] sessionSecret)
    {
        _key = FrameCrypto.SessionKeyFromBytes(sessionSecret).ToArray();
        _encrypt = true;
        _nonces.Clear();
        _lastPeerSequence.Clear();
        Interlocked.Exchange(ref _outboundSequence, 0);
    }

    public async ValueTask DisposeAsync()
    {
        _sendGate.Dispose();
        CryptographicOperations.ZeroMemory(_key);
        if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "closed", CancellationToken.None).ConfigureAwait(false);
        _socket.Dispose();
    }

    private async Task<byte[]> ReceiveTextAsync(CancellationToken ct)
    {
        using var buffer = new MemoryStream();
        byte[] chunk = new byte[64 * 1024];
        while (true)
        {
            WebSocketReceiveResult result = await _socket.ReceiveAsync(chunk, ct).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
                throw new WebSocketException("Peer closed the VLP control channel.");
            if (result.MessageType != WebSocketMessageType.Text)
                throw new InvalidDataException("VLP control channel received a non-text frame.");
            if (buffer.Length + result.Count > MaxFrameSizeBytes)
                throw new InvalidDataException("Control frame exceeds the protocol limit.");
            buffer.Write(chunk, 0, result.Count);
            if (result.EndOfMessage) return buffer.ToArray();
        }
    }

    private static byte[] DecodeExact(string value, int length, string field)
    {
        byte[] bytes;
        try { bytes = Convert.FromBase64String(value); }
        catch (FormatException ex) { throw new InvalidDataException($"Invalid {field} encoding.", ex); }
        if (bytes.Length != length) throw new InvalidDataException($"Invalid {field} length.");
        return bytes;
    }
}