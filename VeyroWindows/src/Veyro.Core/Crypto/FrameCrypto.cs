using System.Security.Cryptography;
using System.Text;

namespace Veyro.Core.Crypto;

/// <summary>A frame after sealing, before encoding for the wire.</summary>
public sealed record SealedFrame(
    byte[] Nonce,
    byte[] Body,
    byte[] Mac,
    bool Encrypted,
    string FromDeviceId);

/// <summary>
/// Frame cryptography for the Veyro link and connection protocol.
/// Mirrors com.veyro.core.link.FrameCrypto. See ProjectInfo/04-PROTOCOL.md section 5 and the
/// vectors in protocol/testvectors/link/framecrypto.json.
///
/// ## Two modes
///
/// 1. **Pairing** — the key is derived from a 6-digit PIN by PBKDF2. Payloads travel as plaintext
///    with an HMAC over <c>(fromId ‖ nonce ‖ payload)</c>. The window is short and the PIN is
///    single-use, so the work factor plus the attempt limit is what makes a 6-digit secret
///    acceptable.
///
/// 2. **Session** — the key is a persisted 32-byte shared secret. Payloads are AES-GCM sealed, and
///    the outer HMAC is kept as defence in depth: GCM already authenticates the ciphertext, but
///    the outer MAC also binds the frame to the sender id on the wire, which is what stops a frame
///    being replayed under a different claimed sender.
/// </summary>
public static class FrameCrypto
{
    /// <summary>
    /// 120k iterations. This is the only thing standing between a 6-digit PIN and an offline
    /// attacker who captured the pairing exchange, so it is deliberately slow — roughly a tenth
    /// of a second, unnoticeable once but expensive a million times over.
    /// </summary>
    public const int Pbkdf2Iterations = 120_000;

    public const int KeyBytes = 32;
    public const int NonceBytes = 12;
    public const int GcmTagBytes = 16;

    /// <summary>
    /// Derives the pairing bootstrap key from the PIN.
    ///
    /// The salt is canonically ordered <c>min(idA, idB):max(idA, idB)</c>, lower-cased. Sorting
    /// the ids lexicographically ensures both sides derive the identical shared secret regardless
    /// of who initiated the connection.
    /// </summary>
    public static byte[] DeriveBootstrapKey(string pin, string initiatorId, string targetId)
    {
        string a = initiatorId.ToLowerInvariant();
        string b = targetId.ToLowerInvariant();
        string salt = string.CompareOrdinal(a, b) <= 0 ? $"{a}:{b}" : $"{b}:{a}";

        return Rfc2898DeriveBytes.Pbkdf2(
            Encoding.UTF8.GetBytes(pin),
            Encoding.ASCII.GetBytes(salt),
            Pbkdf2Iterations,
            HashAlgorithmName.SHA256,
            KeyBytes);
    }

    public static byte[] SessionKeyFromBytes(byte[] secret)
    {
        if (secret.Length != KeyBytes)
            throw new ArgumentException("session secret must be 32 bytes", nameof(secret));
        return secret;
    }

    /// <summary>A fresh 32-byte session secret, minted by the device being paired *to*.</summary>
    public static byte[] NewSessionSecret() => RandomNumberGenerator.GetBytes(KeyBytes);

    /// <summary>Six digits, uniform. Never logged.</summary>
    public static string NewPin() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    public static SealedFrame Seal(string payloadJson, byte[] key, bool encrypt, string fromDeviceId)
        => Seal(payloadJson, key, encrypt, fromDeviceId, RandomNumberGenerator.GetBytes(NonceBytes));

    /// <summary>
    /// Seals with a caller-supplied nonce. Exposed only so the interop vectors can pin a
    /// deterministic frame; production callers must use the overload that generates one.
    /// </summary>
    public static SealedFrame Seal(
        string payloadJson, byte[] key, bool encrypt, string fromDeviceId, byte[] nonce)
    {
        if (nonce.Length != NonceBytes)
            throw new ArgumentException($"nonce must be {NonceBytes} bytes", nameof(nonce));

        byte[] plain = Encoding.UTF8.GetBytes(payloadJson);
        byte[] body;

        if (encrypt)
        {
            // Java's Cipher.doFinal returns ciphertext||tag as one array; .NET splits them, so
            // concatenate in that order to match the wire form.
            byte[] ct = new byte[plain.Length];
            byte[] tag = new byte[GcmTagBytes];
            using var gcm = new AesGcm(key, GcmTagBytes);
            gcm.Encrypt(nonce, plain, ct, tag);

            body = new byte[ct.Length + tag.Length];
            ct.CopyTo(body, 0);
            tag.CopyTo(body, ct.Length);
        }
        else
        {
            body = plain;
        }

        return new SealedFrame(nonce, body, Hmac(key, fromDeviceId, nonce, body), encrypt, fromDeviceId);
    }

    /// <summary>
    /// Returns the payload JSON, or null on any failure. Never throws on hostile input — an
    /// exception escaping to a connection handler is a denial-of-service lever.
    /// </summary>
    public static string? Open(SealedFrame frame, byte[] key)
    {
        byte[] expected = Hmac(key, frame.FromDeviceId, frame.Nonce, frame.Body);

        // Constant-time: a short-circuiting compare leaks how many leading MAC bytes an attacker
        // guessed, which is enough to forge a tag one byte at a time.
        if (!CryptographicOperations.FixedTimeEquals(expected, frame.Mac))
            return null;

        try
        {
            if (!frame.Encrypted)
                return Encoding.UTF8.GetString(frame.Body);

            if (frame.Body.Length < GcmTagBytes) return null;

            int ctLen = frame.Body.Length - GcmTagBytes;
            byte[] plain = new byte[ctLen];
            using var gcm = new AesGcm(key, GcmTagBytes);
            gcm.Decrypt(
                frame.Nonce,
                frame.Body.AsSpan(0, ctLen),
                frame.Body.AsSpan(ctLen, GcmTagBytes),
                plain);

            return Encoding.UTF8.GetString(plain);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// <c>HMAC-SHA256(key, utf8(fromDeviceId) ‖ nonce ‖ body)</c>.
    ///
    /// The sender id is inside the MAC deliberately. Without it, a frame could be lifted and
    /// re-attributed to a different peer sharing the same key — the MAC would still verify.
    /// </summary>
    public static byte[] Hmac(byte[] key, string fromDeviceId, byte[] nonce, byte[] body)
    {
        byte[] from = Encoding.UTF8.GetBytes(fromDeviceId);
        byte[] data = new byte[from.Length + nonce.Length + body.Length];
        from.CopyTo(data, 0);
        nonce.CopyTo(data, from.Length);
        body.CopyTo(data, from.Length + nonce.Length);
        return HMACSHA256.HashData(key, data);
    }

    public static string Sha256Hex(ReadOnlySpan<byte> data)
        => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
}

/// <summary>
/// Bounded LRU of recently-seen nonces. Rejects replays regardless of timestamp.
///
/// 4096 entries is far beyond the expected control-frame rate, and bounding it matters: an
/// unbounded set is a memory-exhaustion target for anything that can send us frames.
/// </summary>
public sealed class NonceCache(int capacity = 4096)
{
    private readonly LinkedList<string> _order = new();
    private readonly Dictionary<string, LinkedListNode<string>> _seen = new(StringComparer.Ordinal);
    private readonly Lock _gate = new();

    public bool CheckAndStore(string nonce)
    {
        lock (_gate)
        {
            // Deliberately does NOT refresh ordering on a hit. The Kotlin uses
            // LinkedHashMap.containsKey, which is not an access for access-order purposes, so the
            // cache is effectively insertion-ordered. Refreshing here would evict different
            // entries than the Android side under load.
            if (_seen.ContainsKey(nonce))
                return false;

            var node = _order.AddLast(nonce);
            _seen[nonce] = node;

            if (_seen.Count > capacity)
            {
                var eldest = _order.First!;
                _order.RemoveFirst();
                _seen.Remove(eldest.Value);
            }

            return true;
        }
    }

    public void Clear()
    {
        lock (_gate)
        {
            _order.Clear();
            _seen.Clear();
        }
    }
}
