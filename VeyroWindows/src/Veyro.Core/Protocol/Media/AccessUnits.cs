namespace Veyro.Core.Protocol.Media;

/// <summary>
/// How an access unit is split into VIDEO records (04-PROTOCOL.md 7.5.5). Mirrors
/// com.veyro.core.protocol.media.AccessUnitFragmenter; vectors in media/fragmentation.json.
/// </summary>
public static class AccessUnitFragmenter
{
    public readonly record struct Fragment(int Offset, int Length, byte Flags);

    public static IReadOnlyList<Fragment> Plan(int size, bool key, bool discontinuity, int maxFragment = Vms1.MaxFragment)
    {
        if (size is < 1 or > Vms1.MaxAccessUnit) throw new ArgumentOutOfRangeException(nameof(size));
        if (maxFragment is < 1 or > Vms1.MaxFragment) throw new ArgumentOutOfRangeException(nameof(maxFragment));
        int count = (size + maxFragment - 1) / maxFragment;
        var plan = new Fragment[count];
        for (int i = 0; i < count; i++)
        {
            byte flags = 0;
            if (i == 0)
            {
                flags |= Vms1.FlagStart;
                if (key) flags |= Vms1.FlagKey;
                if (discontinuity) flags |= Vms1.FlagDiscontinuity;
            }
            if (i == count - 1) flags |= Vms1.FlagEnd;
            int offset = i * maxFragment;
            plan[i] = new Fragment(offset, Math.Min(maxFragment, size - offset), flags);
        }
        return plan;
    }
}

/// <summary>One whole encoded video frame. <see cref="PtsUs"/> is on the phone's capture clock, shared with audio.</summary>
public sealed record AccessUnit(long PtsUs, bool Key, bool Discontinuity, byte[] Data);

/// <summary>
/// Puts VIDEO fragments back together (04-PROTOCOL.md 7.5.5). Any order violation throws
/// <see cref="InvalidDataException"/>: the connection is then closed.
/// </summary>
public sealed class AccessUnitReassembler
{
    private byte[]? _buffer;
    private int _filled;
    private long _pts;
    private byte _flags;

    /// <summary>True between a START fragment and its END.</summary>
    public bool InProgress => _buffer is not null;

    /// <summary>The finished access unit at END, else null.</summary>
    public AccessUnit? Accept(Vms1Frame.Video fragment)
    {
        bool start = (fragment.Flags & Vms1.FlagStart) != 0;
        if (start)
        {
            if (_buffer is not null) throw new InvalidDataException("VMS1 START inside an access unit.");
            if (fragment.AuLength is not { } length || fragment.PtsUs is not { } pts) throw new InvalidDataException("VMS1 START without its prefix.");
            _buffer = new byte[length];
            _filled = 0;
            _pts = pts;
            _flags = fragment.Flags;
        }
        else if (_buffer is null)
        {
            throw new InvalidDataException("VMS1 fragment outside an access unit.");
        }
        if (fragment.Data.Length == 0 || _filled + fragment.Data.Length > _buffer.Length)
            throw new InvalidDataException("VMS1 fragment beyond its access unit.");
        fragment.Data.CopyTo(_buffer, _filled);
        _filled += fragment.Data.Length;
        if ((fragment.Flags & Vms1.FlagEnd) == 0) return null;
        if (_filled != _buffer.Length) throw new InvalidDataException("VMS1 access unit ended short.");
        var unit = new AccessUnit(_pts, (_flags & Vms1.FlagKey) != 0, (_flags & Vms1.FlagDiscontinuity) != 0, _buffer);
        _buffer = null;
        return unit;
    }
}

/// <summary>
/// The coded size of a mirrored screen (04-PROTOCOL.md 7.5.7). Integer arithmetic, identical to
/// com.veyro.core.protocol.media.StreamGeometry; vectors in media/stream-geometry.json.
/// </summary>
public static class StreamGeometry
{
    public enum Fit { Stretch, Fit }

    public readonly record struct Result(int Width, int Height, int VisX, int VisY, int VisW, int VisH);

    public static Result? Compute(int screenW, int screenH, int maxLong, int encMaxW, int encMaxH, Fit fit)
    {
        if (screenW < 16 || screenH < 16 || maxLong < 16 || encMaxW < 16 || encMaxH < 16) return null;
        bool landscape = screenW >= screenH;
        long srcLong = Math.Max(screenW, screenH), srcShort = Math.Min(screenW, screenH);
        long encLong = Math.Max(encMaxW, encMaxH), encShort = Math.Min(encMaxW, encMaxH);

        long lng = Math.Min(Math.Min(srcLong, maxLong), encLong);
        long shrt = srcShort * lng / srcLong;
        if (shrt > encShort)
        {
            shrt = encShort;
            lng = encShort * srcLong / srcShort;
        }
        int codedLong = (int)Math.Max(16, lng / 16 * 16);
        int codedShort = (int)Math.Max(16, shrt / 16 * 16);
        int w = landscape ? codedLong : codedShort;
        int h = landscape ? codedShort : codedLong;
        if (fit == Fit.Stretch) return new Result(w, h, 0, 0, w, h);

        long sw = screenW, sh = screenH;
        int visW, visH;
        if (w * sh > h * sw)
        {
            visH = h;
            visW = (int)Math.Clamp(h * sw / sh, 1, w);
        }
        else
        {
            visW = w;
            visH = (int)Math.Clamp(w * sh / sw, 1, h);
        }
        return new Result(w, h, (w - visW) / 2, (h - visH) / 2, visW, visH);
    }
}
