using System.Runtime.InteropServices;
using Microsoft.Win32;
using Veyro.Core.Diagnostics;

namespace Veyro.Platform.Services;

/// <summary>
/// The Veyro icon in the notification area (Shell_NotifyIcon on a hidden window owned by the UI
/// thread), present while tray mode is on (ADR-047, default on). Left click opens the window;
/// right click offers Open Veyro, Send files..., Pause/Resume sync, "Start with Windows" and
/// "Keep running in the background" (ticked when on) and Quit. The icon is
/// re-added when Explorer restarts. <see cref="IsAvailable"/> is true only while the icon exists,
/// so "keep running in the tray" never hides the window without a way back.
/// </summary>
public sealed class SystemTrayService : ISystemTrayService
{
    private const uint WmTrayCallback = 0x8000 + 0x56; // WM_APP + 'V'
    private const uint WmNull = 0x0000;
    private const uint WmLButtonUp = 0x0202;
    private const uint WmLButtonDblClk = 0x0203;
    private const uint WmRButtonUp = 0x0205;
    private const uint NimAdd = 0x0;
    private const uint NimDelete = 0x2;
    private const uint NifMessage = 0x1;
    private const uint NifIcon = 0x2;
    private const uint NifTip = 0x4;
    private const uint MfString = 0x0;
    private const uint MfSeparator = 0x800;
    private const uint TpmRightButton = 0x2;
    private const uint TpmNoNotify = 0x80;
    private const uint TpmReturnCmd = 0x100;
    private const int SmCxSmIcon = 49;
    private const uint CommandOpen = 1;
    private const uint CommandQuit = 2;
    private const uint CommandSendFiles = 3;
    private const uint CommandToggleSync = 4;
    private const uint CommandToggleStartup = 5;
    private const uint CommandStopBackground = 6;
    private const uint MfChecked = 0x8;
    private const uint MfGrayed = 0x1;
    private const string WindowClass = "VeyroTrayWindow";

    private readonly Action _open;
    private readonly Action _sendFiles;
    private readonly Action _toggleSync;
    private readonly Func<bool> _isSyncPaused;
    private readonly Action _quit;
    private readonly Func<bool?> _isStartupEnabled;
    private readonly Action _toggleStartup;
    private readonly Action _stopBackground;
    private readonly uint _taskbarCreated;
    private WndProc? _wndProc; // kept alive: the native window calls into it
    private DispatcherQueue? _dispatcher;
    private nint _hwnd;
    private nint _icon;
    private bool _iconOwned;
    private bool _enabled;

    public SystemTrayService(
        Action open,
        Action sendFiles,
        Action toggleSync,
        Func<bool> isSyncPaused,
        Action quit,
        Func<bool?> isStartupEnabled,
        Action toggleStartup,
        Action stopBackground)
    {
        _open = open;
        _sendFiles = sendFiles;
        _toggleSync = toggleSync;
        _isSyncPaused = isSyncPaused;
        _quit = quit;
        _isStartupEnabled = isStartupEnabled;
        _toggleStartup = toggleStartup;
        _stopBackground = stopBackground;
        _taskbarCreated = RegisterWindowMessageW("TaskbarCreated");
    }

    public bool IsAvailable { get; private set; }

    public void SetEnabled(bool enabled)
    {
        _enabled = enabled;
        if (_hwnd == 0) return;
        if (enabled && !IsAvailable) IsAvailable = AddIcon();
        else if (!enabled && IsAvailable)
        {
            var data = NewData();
            Shell_NotifyIconW(NimDelete, ref data);
            IsAvailable = false;
        }
    }

    public void Initialize(nint windowHandle)
    {
        if (_hwnd != 0 || windowHandle == 0) return;
        try
        {
            _dispatcher = DispatcherQueue.GetForCurrentThread();
            _wndProc = OnMessage;
            nint instance = GetModuleHandleW(null);
            var windowClass = new WNDCLASSEXW
            {
                cbSize = (uint)Marshal.SizeOf<WNDCLASSEXW>(),
                lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_wndProc),
                hInstance = instance,
                lpszClassName = WindowClass
            };
            RegisterClassExW(ref windowClass); // fails harmlessly if already registered
            _hwnd = CreateWindowExW(0, WindowClass, "Veyro tray", 0, 0, 0, 0, 0, 0, 0, instance, 0);
            if (_hwnd == 0)
            {
                VeyroTrace.Warn($"Tray window not created: {Marshal.GetLastWin32Error()}");
                return;
            }
            _icon = LoadTrayIcon(out _iconOwned);
            if (_enabled) IsAvailable = AddIcon();
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Tray icon unavailable", error);
            IsAvailable = false;
        }
    }

    public void Dispose()
    {
        if (_hwnd != 0)
        {
            var data = NewData();
            Shell_NotifyIconW(NimDelete, ref data);
            DestroyWindow(_hwnd);
            _hwnd = 0;
        }
        if (_icon != 0 && _iconOwned) DestroyIcon(_icon);
        _icon = 0;
        IsAvailable = false;
    }

    private bool AddIcon()
    {
        var data = NewData();
        data.uFlags = NifMessage | NifIcon | NifTip;
        data.uCallbackMessage = WmTrayCallback;
        data.hIcon = _icon;
        data.szTip = Constants.AppDisplayName;
        bool added = Shell_NotifyIconW(NimAdd, ref data);
        if (!added) VeyroTrace.Warn("Tray icon could not be added");
        return added;
    }

    private NOTIFYICONDATAW NewData() => new()
    {
        cbSize = (uint)Marshal.SizeOf<NOTIFYICONDATAW>(),
        hWnd = _hwnd,
        uID = 1,
        szTip = string.Empty,
        szInfo = string.Empty,
        szInfoTitle = string.Empty
    };

    private nint OnMessage(nint hwnd, uint message, nint wParam, nint lParam)
    {
        if (message == WmTrayCallback)
        {
            uint mouse = (uint)(lParam.ToInt64() & 0xFFFF);
            if (mouse is WmLButtonUp or WmLButtonDblClk) Run(_open);
            else if (mouse == WmRButtonUp) ShowMenu(hwnd);
            return 0;
        }
        if (message == _taskbarCreated && _taskbarCreated != 0)
        {
            // Explorer restarted and forgot every notification-area icon.
            if (_enabled) IsAvailable = AddIcon();
            return 0;
        }
        return DefWindowProcW(hwnd, message, wParam, lParam);
    }

    private void ShowMenu(nint hwnd)
    {
        nint menu = CreatePopupMenu();
        if (menu == 0) return;
        try
        {
            AppendMenuW(menu, MfString, CommandOpen, "Open Veyro");
            AppendMenuW(menu, MfString, CommandSendFiles, "Send files...");
            AppendMenuW(menu, MfString, CommandToggleSync, _isSyncPaused() ? "Resume sync" : "Pause sync");
            AppendMenuW(menu, MfSeparator, 0, null);
            // ADR-047: both background choices right here, ticked when on.
            bool? startup = _isStartupEnabled();
            AppendMenuW(menu, MfString | (startup == true ? MfChecked : 0) | (startup is null ? MfGrayed : 0), CommandToggleStartup, "Start with Windows");
            AppendMenuW(menu, MfString | MfChecked, CommandStopBackground, "Keep running in the background");
            AppendMenuW(menu, MfSeparator, 0, null);
            AppendMenuW(menu, MfString, CommandQuit, "Quit Veyro");
            GetCursorPos(out POINT point);
            // Required so the menu closes when the user clicks elsewhere.
            SetForegroundWindow(hwnd);
            uint command = TrackPopupMenuEx(menu, TpmRightButton | TpmReturnCmd | TpmNoNotify, point.X, point.Y, hwnd, 0);
            PostMessageW(hwnd, WmNull, 0, 0);
            Action? action = command switch
            {
                CommandOpen => _open,
                CommandSendFiles => _sendFiles,
                CommandToggleSync => _toggleSync,
                CommandToggleStartup => _toggleStartup,
                CommandStopBackground => _stopBackground,
                CommandQuit => _quit,
                _ => null
            };
            if (action is not null) Run(action);
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    /// <summary>Runs after the current window message, so Quit can tear this window down safely.</summary>
    private void Run(Action action)
    {
        if (_dispatcher is null || !_dispatcher.TryEnqueue(() => action())) action();
    }

    private static nint LoadTrayIcon(out bool owned)
    {
        owned = false;
        int size = Math.Max(16, GetSystemMetrics(SmCxSmIcon));
        int target = new[] { 16, 20, 24, 32, 48, 64 }.FirstOrDefault(candidate => candidate >= size, 64);
        // Unplated assets: the light variant is drawn for a light taskbar.
        string variant = UsesLightTaskbar() ? "altform-lightunplated" : "altform-unplated";
        string path = Path.Combine(AppContext.BaseDirectory, "Assets", $"Square44x44Logo.targetsize-{target}_{variant}.png");
        try
        {
            if (File.Exists(path))
            {
                byte[] png = File.ReadAllBytes(path);
                nint icon = CreateIconFromResourceEx(png, (uint)png.Length, true, 0x00030000, target, target, 0);
                if (icon != 0)
                {
                    owned = true;
                    return icon;
                }
            }
        }
        catch (IOException error)
        {
            VeyroTrace.Error("Tray icon asset unreadable", error);
        }
        return LoadIconW(0, 32512); // IDI_APPLICATION: shared, never destroyed
    }

    private static bool UsesLightTaskbar()
    {
        try
        {
            using RegistryKey? key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("SystemUsesLightTheme") is int value && value != 0;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private delegate nint WndProc(nint hwnd, uint message, nint wParam, nint lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WNDCLASSEXW
    {
        public uint cbSize;
        public uint style;
        public nint lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public nint hInstance;
        public nint hIcon;
        public nint hCursor;
        public nint hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public nint hIconSm;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATAW
    {
        public uint cbSize;
        public nint hWnd;
        public uint uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public nint hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
        public uint dwState;
        public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string szInfo;
        public uint uVersionOrTimeout;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public nint hBalloonIcon;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassExW(ref WNDCLASSEXW windowClass);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint CreateWindowExW(uint exStyle, string className, string windowName, uint style,
        int x, int y, int width, int height, nint parent, nint menu, nint instance, nint param);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyWindow(nint hwnd);

    [DllImport("user32.dll")]
    private static extern nint DefWindowProcW(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessageW(string name);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool Shell_NotifyIconW(uint message, ref NOTIFYICONDATAW data);

    [DllImport("user32.dll")]
    private static extern nint CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AppendMenuW(nint menu, uint flags, nuint id, string? text);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyMenu(nint menu);

    [DllImport("user32.dll")]
    private static extern uint TrackPopupMenuEx(nint menu, uint flags, int x, int y, nint hwnd, nint parameters);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(nint hwnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessageW(nint hwnd, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [DllImport("user32.dll")]
    private static extern nint CreateIconFromResourceEx(byte[] bits, uint size, bool icon, uint version, int width, int height, uint flags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(nint icon);

    [DllImport("user32.dll")]
    private static extern nint LoadIconW(nint instance, nint name);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern nint GetModuleHandleW(string? moduleName);
}
