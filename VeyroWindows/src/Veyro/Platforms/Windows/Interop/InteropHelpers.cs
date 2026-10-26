using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace Veyro.Platforms.Windows.Interop;

public static partial class InteropHelpers
{
    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetForegroundWindow(nint hWnd);

    /// <summary>Locks the workstation, as Win+L does (ADR-038 "Lock PC").</summary>
    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool LockWorkStation();

    public const uint SPI_GETDESKWALLPAPER = 0x0073;

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "SystemParametersInfoW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SystemParametersInfo(uint uiAction, uint uiParam, StringBuilder pvParam, uint fWinIni);

    /// <summary>The current desktop wallpaper path, or null when Windows reports none. User data: never log it.</summary>
    public static string? GetDesktopWallpaperPath()
    {
        var buffer = new StringBuilder(1024);
        if (!SystemParametersInfo(SPI_GETDESKWALLPAPER, (uint)buffer.Capacity, buffer, 0)) return null;
        string path = buffer.ToString();
        return string.IsNullOrWhiteSpace(path) ? null : path;
    }

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
