using System.Text;

namespace Veyro.Core.Models;

/// <summary>
/// Crockford Base32 encoding/decoding (RFC/Crockford spec, no padding, case-insensitive, I/L->1, O->0).
/// Mirrors com.veyro.core.model.Crockford32 (REQ-I02).
/// </summary>
public static class Crockford32
{
    private const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static string Encode(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty) return string.Empty;

        var sb = new StringBuilder((data.Length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;

        foreach (byte b in data)
        {
            buffer = (buffer << 8) | b;
            bitsLeft += 8;
            while (bitsLeft >= 5)
            {
                bitsLeft -= 5;
                sb.Append(Alphabet[(buffer >> bitsLeft) & 0x1F]);
            }
        }

        if (bitsLeft > 0)
        {
            sb.Append(Alphabet[(buffer << (5 - bitsLeft)) & 0x1F]);
        }

        return sb.ToString();
    }

    public static byte[] Decode(string input)
    {
        ArgumentNullException.ThrowIfNull(input);
        return DecodeOrNull(input)
               ?? throw new FormatException("Invalid Crockford32 input.");
    }

    /// <summary>
    /// Decodes, or returns null on any invalid character. Mirrors
    /// com.veyro.core.model.encoding.Crockford32.decodeOrNull — parsing peer-supplied identifiers
    /// must not throw.
    /// </summary>
    public static byte[]? DecodeOrNull(string? input)
    {
        if (input is null) return null;

        var bytes = new List<byte>((input.Length * 5) / 8);
        int buffer = 0;
        int bitsLeft = 0;

        foreach (char raw in input)
        {
            if (raw == '-' || char.IsWhiteSpace(raw)) continue;

            int val = DecodeCharOrMinusOne(char.ToUpperInvariant(raw));
            if (val < 0) return null;

            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8)
            {
                bitsLeft -= 8;
                bytes.Add((byte)((buffer >> bitsLeft) & 0xFF));
            }
        }

        return bytes.ToArray();
    }

    private static int DecodeCharOrMinusOne(char c) => c switch
    {
        '0' or 'O' => 0,
        '1' or 'I' or 'L' => 1,
        >= '2' and <= '9' => c - '0',
        >= 'A' and <= 'H' => c - 'A' + 10,
        'J' or 'K' => c - 'J' + 18,
        'M' or 'N' => c - 'M' + 20,
        >= 'P' and <= 'T' => c - 'P' + 22,
        >= 'V' and <= 'Z' => c - 'V' + 27,
        _ => -1
    };
}
