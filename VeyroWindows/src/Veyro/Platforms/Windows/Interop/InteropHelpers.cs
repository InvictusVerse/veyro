using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace Veyro.Platforms.Windows.Interop;

public static partial class InteropHelpers
{
    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetForegroundWindow(nint hWnd);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    public static extern nint CreateEvent(nint lpEventAttributes, bool bManualReset, bool bInitialState, string? lpName);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetEvent(nint hEvent);

    [DllImport("ole32.dll")]
    public static extern uint CoWaitForMultipleObjects(
        uint dwFlags,
        uint dwMilliseconds,
        ulong nHandles,
        nint[] pHandles,
        out uint dwIndex);
}
