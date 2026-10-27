using System.Runtime;
using System.Runtime.InteropServices;
using Veyro.Core.Diagnostics;

namespace Veyro.Platform.Interop;

/// <summary>
/// Process-wide power and memory controls for running in the tray (ADR-047).
///
/// <see cref="SetEfficiencyMode"/> is Windows 11's EcoQoS, what Task Manager calls "Efficiency
/// mode": the scheduler prefers efficient cores and low clock speeds for this process, and its
/// timers may be coalesced. Throughput-bound work (a transfer) must never run under it, so the
/// caller turns it off while files move.
///
/// <see cref="TrimMemory"/> collects the managed heap and hands the working set back to Windows.
/// Called once after the window is hidden, never on a timer: trimming repeatedly would only make
/// Windows page the same memory back in.
/// </summary>
internal static class ProcessPower
{
    private const int ProcessPowerThrottling = 4;
    private const uint PowerThrottlingCurrentVersion = 1;
    private const uint PowerThrottlingExecutionSpeed = 0x1;
    private const uint PowerThrottlingIgnoreTimerResolution = 0x4;

    private static bool? _efficient;

    /// <summary>True: EcoQoS and coarse timers. False: normal scheduling. Cheap to call repeatedly.</summary>
    public static void SetEfficiencyMode(bool enabled)
    {
        if (_efficient == enabled) return;
        var state = new PROCESS_POWER_THROTTLING_STATE
        {
            Version = PowerThrottlingCurrentVersion,
            ControlMask = PowerThrottlingExecutionSpeed | PowerThrottlingIgnoreTimerResolution,
            // Zero StateMask with the bits in ControlMask: explicitly opt out (full speed).
            StateMask = enabled ? PowerThrottlingExecutionSpeed | PowerThrottlingIgnoreTimerResolution : 0,
        };
        bool ok = SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref state, (uint)Marshal.SizeOf<PROCESS_POWER_THROTTLING_STATE>());
        if (ok)
        {
            _efficient = enabled;
            VeyroTrace.Info($"Efficiency mode {(enabled ? "on" : "off")}");
        }
        else
        {
            VeyroTrace.Warn($"Efficiency mode not changed: {Marshal.GetLastWin32Error()}");
        }
    }

    /// <summary>Full compacting collection, then the working set back to Windows. Thread pool.</summary>
    public static void TrimMemory()
    {
        try
        {
            GCSettings.LargeObjectHeapCompactionMode = GCLargeObjectHeapCompactionMode.CompactOnce;
            // Aggressive also decommits the free segments, so the memory really leaves the process.
            GC.Collect(GC.MaxGeneration, GCCollectionMode.Aggressive, blocking: true, compacting: true);
            GC.WaitForPendingFinalizers();
            long before = Environment.WorkingSet;
            SetProcessWorkingSetSize(GetCurrentProcess(), -1, -1);
            VeyroTrace.Info($"Memory trimmed: working set {before / (1024 * 1024)} MB -> {Environment.WorkingSet / (1024 * 1024)} MB");
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Memory trim failed: {error.GetType().Name}");
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_POWER_THROTTLING_STATE
    {
        public uint Version;
        public uint ControlMask;
        public uint StateMask;
    }

    [DllImport("kernel32.dll")]
    private static extern nint GetCurrentProcess();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetProcessInformation(nint process, int informationClass, ref PROCESS_POWER_THROTTLING_STATE information, uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetProcessWorkingSetSize(nint process, nint minimum, nint maximum);
}
