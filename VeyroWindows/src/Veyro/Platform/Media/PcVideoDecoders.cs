using System.Runtime.InteropServices;
using Veyro.Core.Diagnostics;
using Veyro.Core.Mirroring;

namespace Veyro.Platform.Media;

/// <summary>
/// The video decoders this PC has, from Media Foundation (ADR-066): hardware (DXVA, the GPU) and
/// software, for H.264 and H.265. H.265 needs a GPU decoder or the HEVC Video Extensions. The
/// player itself picks hardware whenever the GPU has it and falls back to software on its own.
/// </summary>
public static class PcVideoDecoders
{
    private static readonly Guid VideoDecoderCategory = new("d6c02d4b-6833-45b4-971a-05a4b04bab91");
    private static readonly Guid MediaTypeVideo = new("73646976-0000-0010-8000-00AA00389B71");
    private static readonly Guid SubtypeH264 = new("34363248-0000-0010-8000-00AA00389B71");
    private static readonly Guid SubtypeHevc = new("43564548-0000-0010-8000-00AA00389B71");

    private const uint EnumSyncMft = 0x1, EnumAsyncMft = 0x2, EnumHardware = 0x4, EnumSortAndFilter = 0x40;
    private const uint MfVersion = 0x00020070, MfStartupLite = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct RegisterTypeInfo
    {
        public Guid MajorType;
        public Guid Subtype;
    }

    [DllImport("mfplat.dll", ExactSpelling = true)]
    private static extern int MFStartup(uint version, uint flags);

    [DllImport("mfplat.dll", ExactSpelling = true)]
    private static extern int MFShutdown();

    [DllImport("mfplat.dll", ExactSpelling = true)]
    private static extern int MFTEnumEx(Guid category, uint flags, ref RegisterTypeInfo input, IntPtr output, out IntPtr activates, out uint count);

    private static IReadOnlyList<PcDecoder>? _cached;

    /// <summary>Probed once per run; cheap after that.</summary>
    public static IReadOnlyList<PcDecoder> Find()
    {
        if (_cached is not null) return _cached;
        var found = new List<PcDecoder>();
        try
        {
            bool started = MFStartup(MfVersion, MfStartupLite) >= 0;
            try
            {
                if (Count(SubtypeH264, hardware: true) > 0) found.Add(new PcDecoder("h264", true, 4096, 4096));
                if (Count(SubtypeH264, hardware: false) > 0) found.Add(new PcDecoder("h264", false, 4096, 2304));
                if (Count(SubtypeHevc, hardware: true) > 0) found.Add(new PcDecoder("h265", true, 8192, 8192));
                if (Count(SubtypeHevc, hardware: false) > 0) found.Add(new PcDecoder("h265", false, 4096, 2304));
            }
            finally
            {
                if (started) MFShutdown();
            }
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException or COMException)
        {
            VeyroTrace.Warn($"Video decoder probe failed: {error.GetType().Name}");
        }
        // Windows always ships a software H.264 decoder; assume it if the probe failed.
        if (!found.Any(d => d.Codec == "h264")) found.Add(new PcDecoder("h264", false, 4096, 2304));
        VeyroTrace.Info($"Video decoders: {string.Join(", ", found.Select(d => $"{d.Codec}{(d.Hw ? " hw" : " sw")}"))}");
        _cached = found;
        return found;
    }

    private static int Count(Guid subtype, bool hardware)
    {
        var input = new RegisterTypeInfo { MajorType = MediaTypeVideo, Subtype = subtype };
        uint flags = (hardware ? EnumHardware : EnumSyncMft | EnumAsyncMft) | EnumSortAndFilter;
        if (MFTEnumEx(VideoDecoderCategory, flags, ref input, IntPtr.Zero, out IntPtr array, out uint count) < 0) return 0;
        try
        {
            for (int i = 0; i < count; i++)
            {
                IntPtr activate = Marshal.ReadIntPtr(array, i * IntPtr.Size);
                if (activate != IntPtr.Zero) Marshal.Release(activate);
            }
        }
        finally
        {
            if (array != IntPtr.Zero) Marshal.FreeCoTaskMem(array);
        }
        return (int)count;
    }
}
