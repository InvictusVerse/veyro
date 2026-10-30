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
/// Authenticated VLP control channel over a WebSocket (link protocol v3, ADR-058). The preamble,
/// the ack and the key schedule happen outside this class; every frame after them is sealed with
/// ChaCha20-Poly1305 under this connection's direction keys (<see cref="LinkKeys"/>).
///
/// Fail closed: a tag failure throws <see cref="UnauthorizedAccessException"/>; a malformed,
/// out-of-order, disallowed or oversized frame throws <see cref="InvalidDataException"/>; a peer
/// close throws <see cref="WebSocketException"/>. After any fault the session is failed and every
/// later call throws <see cref="InvalidOperationException"/>; the owner disposes it.
/// Mirrors com.veyro.core.link.PeerConnection.
/// </summary>
public sealed class SecureLinkSession : IAsyncDisposable
{
    public const int MaxFrameSizeBytes = 24 * 1024 * 1024;

    /// <summary>How long <see cref="DisposeAsync"/> waits for the peer's answering close frame.</summary>
    public static readonly TimeSpan CloseHandshakeTimeout = TimeSpan.FromSeconds(2);

    private static readonly HashSet<string> PairingTypes = new(StringComparer.Ordinal) { "pairHello", "pairOk", "pairFail" };

    private readonly WebSocket _socket;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private readonly LinkRole _role;
    private readonly byte _sendDir;
    private readonly byte _recvDir;

    /// <summary>Guarded by <see cref="_sendGate"/>.</summary>
    private LinkKeys _keys;
    private SessionSealer _sealer;

    /// <summary>Receive state: replaced as a whole, read once per frame after the socket read.</summary>
    private sealed record RecvState(SessionOpener Opener, LinkMode Mode);
    private RecvState _recv;
    private byte[]? _expSecret;
    private bool _sawPairHello;
    private volatile bool _established;
    private volatile bool _failed;
    private volatile bool _disposed;

    public SecureLinkSession(WebSocket socket, LinkKeys keys, Func<bool>? aeadSupported = null)
    {
        if (!(aeadSupported ?? (() => System.Security.Cryptography.ChaCha20Poly1305.IsSupported))())
            throw new PlatformNotSupportedException("ChaCha20-Poly1305 is not available on this version of Windows.");
        _socket = socket;
        _keys = keys;
        _role = keys.Role;
        LocalDeviceId = keys.LocalId;
        PeerDeviceId = keys.PeerId;
        _sendDir = keys.Role == LinkRole.Initiator ? FrameCrypto.DirI2R : FrameCrypto.DirR2I;
        _recvDir = keys.Role == LinkRole.Initiator ? FrameCrypto.DirR2I : FrameCrypto.DirI2R;
        _sealer = new SessionSealer(keys.SendKey, LocalDeviceId, _sendDir);
        _recv = new RecvState(new SessionOpener(keys.RecvKey, PeerDeviceId, _recvDir), keys.Mode);
        _expSecret = keys.ExpSecret;
    }

    public string LocalDeviceId { get; }
    public string PeerDeviceId { get; }

    /// <summary>True once a frame opened under session keys (04-PROTOCOL.md 4.4).</summary>
    public bool IsEstablished => _established;

    /// <summary>The key schedule the next received frame is opened under.</summary>
    public LinkMode Mode => Volatile.Read(ref _recv).Mode;

    public async Task SendAsync(PeerMessage message, CancellationToken ct = default)
    {
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await SendLockedAsync(message, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>
    /// Tests only: seals an envelope JSON built for the next <c>seq</c>, under the send gate, so a
    /// test can send a payload type this build cannot encode (an unknown future type).
    /// </summary>
    internal async Task SendRawEnvelopeForTestAsync(Func<long, string> envelopeJson, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            string wire = _sealer.Seal(envelopeJson(_sealer.NextCounter + 1));
            await _socket.SendAsync(Encoding.UTF8.GetBytes(wire), WebSocketMessageType.Text, true, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>Call under the send gate: counter, seal and write as one step (FR-F4).</summary>
    private async Task SendLockedAsync(PeerMessage message, CancellationToken ct)
    {
        if (_failed || _disposed) throw new InvalidOperationException("The VLP session has failed or closed.");
        LinkEnvelope envelope = new()
        {
            MsgId = Guid.NewGuid().ToString(),
            From = LocalDeviceId,
            Seq = _sealer.NextCounter + 1,
            Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Payload = message
        };
        string json = PeerMessageJson.Encode(envelope);
        // Sized before sealing: an oversized message fails alone and spends no counter (review issue 1).
        if (_sealer.WireLength(Encoding.UTF8.GetByteCount(json)) > MaxFrameSizeBytes)
            throw new InvalidOperationException("Control frame exceeds the protocol limit.");
        string wire;
        try
        {
            wire = _sealer.Seal(json);
        }
        catch (InvalidDataException)
        {
            _failed = true;
            throw;
        }
        try
        {
            await _socket.SendAsync(Encoding.UTF8.GetBytes(wire), WebSocketMessageType.Text, true, ct).ConfigureAwait(false);
        }
        catch
        {
            // The counter is spent whether or not the frame left: a later frame would no longer
            // open at the peer, so the session fails here and the socket is dropped (review issue 1).
            _failed = true;
            try { _socket.Abort(); } catch (ObjectDisposedException) { }
            throw;
        }
    }

    /// <summary>
    /// Receives the next authenticated message. A frame that authenticates but carries a payload
    /// type this build does not know is consumed (its counter counts) and skipped.
    /// </summary>
    public async Task<SecureLinkMessage?> ReceiveAsync(CancellationToken ct = default)
    {
        while (true)
        {
            if (_failed || _disposed) throw new InvalidOperationException("The VLP session has failed or closed.");
            byte[] bytes = await ReceiveTextAsync(ct).ConfigureAwait(false);
            try
            {
                SecureLinkMessage? message = Open(bytes);
                if (message is not null) return message;
            }
            catch (Exception error) when (error is UnauthorizedAccessException or InvalidDataException)
            {
                _failed = true;
                throw;
            }
        }
    }

    /// <summary>Opens one frame; null for an authenticated frame of an unknown type.</summary>
    private SecureLinkMessage? Open(byte[] bytes)
    {
        RecvState state = Volatile.Read(ref _recv);
        long counter = state.Opener.NextCounter;
        string json = state.Opener.Open(Encoding.UTF8.GetString(bytes));

        string type;
        try
        {
            using JsonDocument doc = JsonDocument.Parse(json);
            JsonElement root = doc.RootElement;
            int v = root.GetProperty("v").GetInt32();
            string? from = root.GetProperty("from").GetString();
            long seq = root.GetProperty("seq").GetInt64();
            if (v != LinkProtocol.Version || !string.Equals(from, PeerDeviceId, StringComparison.Ordinal) || seq != counter + 1)
                throw new InvalidDataException("VLP envelope header mismatch.");
            type = root.GetProperty("payload").GetProperty("t").GetString() ?? throw new InvalidDataException("Missing payload type.");
        }
        catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            throw new InvalidDataException("Malformed VLP envelope.", error);
        }

        bool establishing = state.Mode == LinkMode.Session && !_established;
        if (!KnownPayloadTypes.Contains(type))
        {
            if (establishing) _established = true;
            // The type name is peer-controlled; only echo it to diagnostics if it looks like one.
            string safeType = type.Length is > 0 and <= 32 && type.All(char.IsAsciiLetterOrDigit) ? type : "<invalid>";
            VeyroTrace.Info($"Ignoring unsupported VLP message type '{safeType}'");
            return null;
        }
        if (!Allowed(type, state.Mode)) throw new InvalidDataException("VLP message type not allowed in this phase.");

        LinkEnvelope? envelope;
        try { envelope = PeerMessageJson.Decode<LinkEnvelope>(json); }
        catch (Exception error) when (error is JsonException or NotSupportedException)
        {
            throw new InvalidDataException("Malformed VLP payload.", error);
        }
        if (envelope is null || HasMissingRequiredField(envelope.Payload)) throw new InvalidDataException("Malformed VLP envelope.");
        if (establishing) _established = true;
        return new SecureLinkMessage(envelope);
    }

    /// <summary>The per-phase allowlist (04-PROTOCOL.md 5.3).</summary>
    private bool Allowed(string type, LinkMode mode)
    {
        if (mode == LinkMode.Session) return !PairingTypes.Contains(type);
        if (_role == LinkRole.Responder)
        {
            if (type == "pairHello")
            {
                if (_sawPairHello) return false;
                _sawPairHello = true;
                return true;
            }
            return _sawPairHello && type is "pairFail" or "ping" or "pong";
        }
        return type is "pairOk" or "pairFail" or "ping" or "pong";
    }

    /// <summary>
    /// A known type "fails to decode" (04-PROTOCOL.md 5.3) when a non-nullable reference property
    /// is absent or null. System.Text.Json fills a missing constructor argument with null, so this
    /// is checked here, matching the Android decoder that refuses a missing non-null field.
    /// </summary>
    private static bool HasMissingRequiredField(PeerMessage? payload)
    {
        if (payload is null) return true;
        foreach (System.Reflection.PropertyInfo property in RequiredReferenceProperties(payload.GetType()))
            if (property.GetValue(payload) is null) return true;
        return false;
    }

    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Type, System.Reflection.PropertyInfo[]> RequiredProperties = new();

    private static System.Reflection.PropertyInfo[] RequiredReferenceProperties(Type type) => RequiredProperties.GetOrAdd(type, t =>
    {
        var nullability = new System.Reflection.NullabilityInfoContext();
        return t.GetProperties(System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Instance)
            .Where(p => p.GetIndexParameters().Length == 0 && !p.PropertyType.IsValueType
                && p.GetCustomAttributes(typeof(JsonPropertyNameAttribute), inherit: true).Length > 0
                && nullability.Create(p).ReadState == System.Reflection.NullabilityState.NotNull)
            .ToArray();
    });

    private static readonly HashSet<string> KnownPayloadTypes = typeof(PeerMessage)
        .GetCustomAttributes(typeof(JsonDerivedTypeAttribute), inherit: false)
        .Cast<JsonDerivedTypeAttribute>()
        .Select(attribute => attribute.TypeDiscriminator as string)
        .OfType<string>()
        .ToHashSet(StringComparer.Ordinal);

    /// <summary>
    /// The key exporter (04-PROTOCOL.md 5.4). Only on an established session-mode connection;
    /// <see cref="InvalidOperationException"/> otherwise, <see cref="ArgumentException"/> for bad arguments.
    /// </summary>
    public byte[] ExportKey(string label, byte[] context, int length)
    {
        byte[]? secret = Volatile.Read(ref _expSecret);
        if (!_established || _failed || _disposed || secret is null || Mode != LinkMode.Session)
            throw new InvalidOperationException("Key export needs an established session.");
        return ConnectionKeySchedule.Export(secret, label, context, length);
    }

    /// <summary>
    /// Responder side of the pairing switch, one operation under the send gate: install the
    /// session receive keys, seal <c>pairOk</c> under the bootstrap send key and write it, then
    /// install the session send keys with the counter at 0. The bootstrap keys are wiped.
    /// </summary>
    public async Task SendPairOkAndSwitchAsync(PeerMessage.PairOk pairOk, byte[] newPairingSecret, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            LinkKeys boot = _keys;
            if (boot.Mode == LinkMode.Session) throw new InvalidOperationException("Not in a pairing phase.");
            LinkKeys session = ConnectionKeySchedule.AfterPairing(boot, newPairingSecret);
            RecvState old = Volatile.Read(ref _recv);
            Volatile.Write(ref _recv, new RecvState(new SessionOpener(session.RecvKey, PeerDeviceId, _recvDir), LinkMode.Session));
            await SendLockedAsync(pairOk, ct).ConfigureAwait(false);
            SessionSealer oldSealer = _sealer;
            _sealer = new SessionSealer(session.SendKey, LocalDeviceId, _sendDir);
            _keys = session;
            Volatile.Write(ref _expSecret, session.ExpSecret);
            oldSealer.Dispose();
            old.Opener.Dispose();
            boot.Dispose();
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>Initiator side of the pairing switch, after <c>pairOk</c> arrived and before the next read.</summary>
    public async Task SwitchToSessionAsync(byte[] newPairingSecret, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            LinkKeys boot = _keys;
            if (boot.Mode == LinkMode.Session) throw new InvalidOperationException("Not in a pairing phase.");
            LinkKeys session = ConnectionKeySchedule.AfterPairing(boot, newPairingSecret);
            RecvState old = Volatile.Read(ref _recv);
            Volatile.Write(ref _recv, new RecvState(new SessionOpener(session.RecvKey, PeerDeviceId, _recvDir), LinkMode.Session));
            SessionSealer oldSealer = _sealer;
            _sealer = new SessionSealer(session.SendKey, LocalDeviceId, _sendDir);
            _keys = session;
            Volatile.Write(ref _expSecret, session.ExpSecret);
            oldSealer.Dispose();
            old.Opener.Dispose();
            boot.Dispose();
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>
    /// Starts a clean close (ADR-040): sends the WebSocket close frame after any send in progress.
    /// The receive loop then sees the peer's answering close and ends. Never throws.
    /// Bounded by <see cref="CloseHandshakeTimeout"/> (review issue 8): a send that holds the gate
    /// on a peer that stopped reading, or a close write that does not complete, aborts the socket.
    /// </summary>
    public async Task CloseOutputAsync(string reason)
    {
        using var timeout = new CancellationTokenSource(CloseHandshakeTimeout);
        try
        {
            await _sendGate.WaitAsync(timeout.Token).ConfigureAwait(false);
            try
            {
                if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                    await _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, reason, timeout.Token).ConfigureAwait(false);
            }
            finally
            {
                _sendGate.Release();
            }
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested)
        {
            try { _socket.Abort(); } catch (ObjectDisposedException) { }
        }
        catch (Exception error) when (error is WebSocketException or IOException or ObjectDisposedException or InvalidOperationException) { }
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        Volatile.Write(ref _expSecret, null);
        // The peer may close or drop the socket first (Android does right after a transfer). A
        // failed close handshake must not turn a finished transfer into a failure.
        // The handshake is bounded: a peer that never answers the close (silent, or a reader that
        // stopped) must not hold the caller forever. On timeout the socket is aborted.
        // Whatever the close throws, every key is disposed (review issue 9).
        try
        {
            if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                using var timeout = new CancellationTokenSource(CloseHandshakeTimeout);
                try { await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "closed", timeout.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) when (timeout.IsCancellationRequested) { _socket.Abort(); }
                catch (Exception error) when (error is WebSocketException or IOException or OperationCanceledException
                                                  or ObjectDisposedException or InvalidOperationException) { }
            }
        }
        finally
        {
            _socket.Dispose();
            _sealer.Dispose();
            Volatile.Read(ref _recv).Opener.Dispose();
            _keys.Dispose();
        }
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
            {
                _failed = true;
                throw new InvalidDataException("VLP control channel received a non-text frame.");
            }
            if (buffer.Length + result.Count > MaxFrameSizeBytes)
            {
                _failed = true;
                throw new InvalidDataException("Control frame exceeds the protocol limit.");
            }
            buffer.Write(chunk, 0, result.Count);
            if (result.EndOfMessage) return buffer.ToArray();
        }
    }
}
