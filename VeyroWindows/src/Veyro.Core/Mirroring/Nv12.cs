namespace Veyro.Core.Mirroring;

/// <summary>Identity of the "Veyro Phone Camera" COM class (ADR-067); matches VeyroVirtualCamera.cpp.</summary>
public static class VirtualCameraIds
{
    public const string Clsid = "{8A4F7E21-3C6B-4D59-9E1A-5B2C7D3F6A10}";
}

/// <summary>NV12 helpers for the virtual camera (pure, unit-tested).</summary>
public static class Nv12
{
    public const byte BlackY = 16, BlackUV = 128;

    /// <summary>
    /// Turns <paramref name="sw"/> x <paramref name="sh"/> NV12 clockwise by <paramref name="rotation"/>,
    /// mirrors it when asked, and centres it in <paramref name="tw"/> x <paramref name="th"/> with black bars.
    /// </summary>
    public static void Compose(byte[] srcY, byte[] srcUV, int sw, int sh, int rotation, bool mirror, byte[] dst, int tw, int th)
    {
        bool quarter = rotation is 90 or 270;
        int pw = Math.Min(quarter ? sh : sw, tw) & ~1, ph = Math.Min(quarter ? sw : sh, th) & ~1;
        int ox = ((tw - pw) / 2) & ~1, oy = ((th - ph) / 2) & ~1;
        int uvOffset = tw * th;
        if (pw != tw || ph != th)
        {
            dst.AsSpan(0, uvOffset).Fill(BlackY);
            dst.AsSpan(uvOffset, tw * th / 2).Fill(BlackUV);
        }
        if (rotation == 0 && !mirror && pw == sw && ph == sh)
        {
            for (int y = 0; y < ph; y++) Buffer.BlockCopy(srcY, y * sw, dst, (oy + y) * tw + ox, pw);
            for (int y = 0; y < ph / 2; y++) Buffer.BlockCopy(srcUV, y * sw, dst, uvOffset + (oy / 2 + y) * tw + ox, pw);
            return;
        }
        for (int y = 0; y < ph; y++)
        {
            int row = (oy + y) * tw + ox;
            for (int x = 0; x < pw; x++)
            {
                (int sx, int sy) = Source(x, y, sw, sh, rotation);
                dst[row + (mirror ? pw - 1 - x : x)] = srcY[sy * sw + sx];
            }
        }
        int cw = sw / 2, ch = sh / 2;
        for (int y = 0; y < ph / 2; y++)
        {
            int row = uvOffset + (oy / 2 + y) * tw + ox;
            for (int x = 0; x < pw / 2; x++)
            {
                (int sx, int sy) = Source(x, y, cw, ch, rotation);
                int to = row + 2 * (mirror ? pw / 2 - 1 - x : x);
                int from = sy * sw + 2 * sx;
                dst[to] = srcUV[from];
                dst[to + 1] = srcUV[from + 1];
            }
        }
    }

    /// <summary>The source pixel for destination (x, y) of a picture turned clockwise by <paramref name="rotation"/>.</summary>
    private static (int X, int Y) Source(int x, int y, int sw, int sh, int rotation) => rotation switch
    {
        90 => (Math.Min(y, sw - 1), Math.Max(0, sh - 1 - x)),
        180 => (Math.Max(0, sw - 1 - x), Math.Max(0, sh - 1 - y)),
        270 => (Math.Max(0, sw - 1 - y), Math.Min(x, sh - 1)),
        _ => (Math.Min(x, sw - 1), Math.Min(y, sh - 1)),
    };

    /// <summary>One BGRA row into NV12 (BT.709 limited range), chroma from every second row and column.</summary>
    public static void FromBgraRow(byte[] bgra, int row, int w, byte[] y, byte[] uv)
    {
        for (int x = 0; x < w; x++)
        {
            int b = bgra[4 * x], g = bgra[4 * x + 1], r = bgra[4 * x + 2];
            y[row * w + x] = (byte)Math.Clamp(16 + ((47 * r + 157 * g + 16 * b) >> 8), 16, 235);
            if ((row & 1) == 0 && (x & 1) == 0)
            {
                int i = row / 2 * w + x;
                uv[i] = (byte)Math.Clamp(128 + ((-26 * r - 87 * g + 112 * b) >> 8), 16, 240);
                uv[i + 1] = (byte)Math.Clamp(128 + ((112 * r - 102 * g - 10 * b) >> 8), 16, 240);
            }
        }
    }
}
