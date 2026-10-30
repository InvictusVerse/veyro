using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using Microsoft.Win32;
using Veyro.Core.Diagnostics;

namespace Veyro.Platform.Media;

public enum VirtualCameraState { Unsupported, NotSetUp, Ready }

/// <summary>
/// The optional "Veyro Phone Camera" add-on (ADR-067), as NectarLink does it: nothing is installed
/// until the user presses Set up. Set up copies the camera DLL from the package to Program Files and
/// registers its COM class in HKLM (one administrator prompt; Windows' camera service cannot load
/// it from the package or from HKCU), then registers the camera with Windows 11's
/// MFCreateVirtualCamera for this user. Remove undoes both. Windows 11 (build 22000) only.
/// </summary>
public sealed class VirtualCameraSetup
{
    public const string Clsid = Veyro.Core.Mirroring.VirtualCameraIds.Clsid;
    private const string ClsidKey = @"SOFTWARE\Classes\CLSID\" + Clsid + @"\InprocServer32";
    private const string SettingsKey = @"SOFTWARE\Veyro\VirtualCamera";

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CameraCall();

    public static bool IsSupported => OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000);

    /// <summary>The DLL shipped in Veyro's package.</summary>
    public static string PackagedDll => Path.Combine(AppContext.BaseDirectory, "VirtualCamera", "VeyroVirtualCamera.dll");

    /// <summary>The installed DLL, from the HKLM registration, when it still exists.</summary>
    public static string? InstalledDll
    {
        get
        {
            try
            {
                using RegistryKey? key = Registry.LocalMachine.OpenSubKey(ClsidKey);
                return key?.GetValue(null) is string path && File.Exists(path) ? path : null;
            }
            catch (Exception error) when (error is System.Security.SecurityException or UnauthorizedAccessException or IOException)
            {
                return null;
            }
        }
    }

    public VirtualCameraState State =>
        !IsSupported ? VirtualCameraState.Unsupported : InstalledDll is null ? VirtualCameraState.NotSetUp : VirtualCameraState.Ready;

    /// <summary>Raised after Set up or Remove. Any thread.</summary>
    public event Action? Changed;

    /// <summary>Sets the camera up. Null on success, else what to tell the user.</summary>
    public async Task<string?> SetUpAsync()
    {
        if (!IsSupported) return "The virtual camera needs Windows 11.";
        if (!File.Exists(PackagedDll)) return "This build of Veyro has no virtual camera.";
        string hash = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(PackagedDll)))[..12];
        string sid = WindowsIdentity.GetCurrent().User?.Value ?? "";
        string script =
            "$ErrorActionPreference='Stop';" +
            $"$src={Quote(PackagedDll)};" +
            $"$dir=Join-Path $env:ProgramFiles {Quote(@"Veyro\VirtualCamera\" + hash)};" +
            "New-Item -ItemType Directory -Force -Path $dir | Out-Null;" +
            "$dll=Join-Path $dir 'VeyroVirtualCamera.dll';" +
            "if (-not (Test-Path -LiteralPath $dll)) { Copy-Item -LiteralPath $src -Destination $dll -Force };" +
            $"$k='HKLM:\\SOFTWARE\\Classes\\CLSID\\{Clsid}';" +
            "New-Item -Path $k -Force | Out-Null; Set-ItemProperty -Path $k -Name '(default)' -Value 'Veyro Phone Camera';" +
            "New-Item -Path \"$k\\InprocServer32\" -Force | Out-Null;" +
            "Set-ItemProperty -Path \"$k\\InprocServer32\" -Name '(default)' -Value $dll;" +
            "Set-ItemProperty -Path \"$k\\InprocServer32\" -Name 'ThreadingModel' -Value 'Both';" +
            "$s='HKLM:\\SOFTWARE\\Veyro\\VirtualCamera'; New-Item -Path $s -Force | Out-Null;" +
            $"Set-ItemProperty -Path $s -Name 'UserSid' -Value {Quote(sid)};" +
            "Set-ItemProperty -Path $s -Name 'Path' -Value $dll";
        if (await RunElevatedAsync(script) is { } failed) return failed;
        string? installed = InstalledDll;
        if (installed is null) return "Setup did not finish. Try again.";
        int hr = await Task.Run(() => Call(installed, "VeyroVcamRegister"));
        Changed?.Invoke();
        if (hr < 0)
        {
            VeyroTrace.Warn($"Virtual camera registration failed: 0x{hr:X8}");
            return hr == unchecked((int)0x80070005)
                ? "Windows refused the camera. In Windows Settings > Privacy & security > Camera, allow desktop apps to use the camera, then try again."
                : $"Windows did not add the camera (0x{hr:X8}).";
        }
        VeyroTrace.Info("Virtual camera set up");
        return null;
    }

    /// <summary>Removes the camera from Windows and the files from Program Files. Null on success.</summary>
    public async Task<string?> RemoveAsync()
    {
        string? installed = InstalledDll;
        if (installed is not null)
        {
            int hr = await Task.Run(() => Call(installed, "VeyroVcamRemove"));
            if (hr < 0) VeyroTrace.Warn($"Virtual camera removal reported 0x{hr:X8}");
        }
        string script =
            $"Remove-Item -Path 'HKLM:\\SOFTWARE\\Classes\\CLSID\\{Clsid}' -Recurse -Force -ErrorAction SilentlyContinue;" +
            "Remove-Item -Path 'HKLM:\\SOFTWARE\\Veyro\\VirtualCamera' -Recurse -Force -ErrorAction SilentlyContinue;" +
            // A camera app may still hold the DLL; what cannot go now goes with the next Remove.
            "Remove-Item -LiteralPath (Join-Path $env:ProgramFiles 'Veyro\\VirtualCamera') -Recurse -Force -ErrorAction SilentlyContinue;" +
            "exit 0";
        string? failed = await RunElevatedAsync(script);
        Changed?.Invoke();
        if (failed is null) VeyroTrace.Info("Virtual camera removed");
        return failed;
    }

    private static string Quote(string value) => "'" + value.Replace("'", "''") + "'";

    private static int Call(string dll, string export)
    {
        IntPtr module = NativeLibrary.Load(dll);
        try
        {
            var call = Marshal.GetDelegateForFunctionPointer<CameraCall>(NativeLibrary.GetExport(module, export));
            return call();
        }
        finally
        {
            NativeLibrary.Free(module);
        }
    }

    private static async Task<string?> RunElevatedAsync(string script)
    {
        try
        {
            var start = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
                    Convert.ToBase64String(System.Text.Encoding.Unicode.GetBytes(script)),
                Verb = "runas",
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using Process? process = Process.Start(start);
            if (process is null) return "The administrator prompt did not open.";
            await process.WaitForExitAsync();
            return process.ExitCode == 0 ? null : $"Setup failed (code {process.ExitCode}).";
        }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            return "Setup was cancelled.";
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Virtual camera setup failed", error);
            return "Setup failed.";
        }
    }
}

/// <summary>
/// Veyro's side of the VVC1 section the virtual camera creates in Windows' camera service
/// (ADR-067): reads whether an app is using the camera and at which size, and writes NV12 frames
/// under a sequence lock. The section exists only while the camera's source runs.
/// </summary>
public sealed class VirtualCameraFrames : IDisposable
{
    private const uint Magic = 0x31435656;
    private const int HeaderSize = 4096;
    private static readonly string[] Names = [@"Global\VeyroVirtualCamera_v1", @"Local\VeyroVirtualCamera_v1"];

    private readonly object _gate = new();
    private MemoryMappedFile? _file;
    private MemoryMappedViewAccessor? _view;
    private IntPtr _base;
    private int _maxBytes;

    /// <summary>Opens the section if the camera's source created it. False otherwise.</summary>
    public bool TryOpen()
    {
        lock (_gate)
        {
            if (_view is not null) return true;
            foreach (string name in Names)
            {
                try
                {
                    var file = MemoryMappedFile.OpenExisting(name, MemoryMappedFileRights.ReadWrite);
                    var view = file.CreateViewAccessor(0, 0, MemoryMappedFileAccess.ReadWrite);
                    if (view.ReadUInt32(0) != Magic || view.ReadUInt32(4) != 1 || view.ReadUInt32(8) != HeaderSize)
                    {
                        view.Dispose();
                        file.Dispose();
                        continue;
                    }
                    _file = file;
                    _view = view;
                    _maxBytes = (int)Math.Min(view.ReadUInt32(12), (uint)(view.Capacity - HeaderSize));
                    _base = view.SafeMemoryMappedViewHandle.DangerousGetHandle() + (nint)view.PointerOffset;
                    return true;
                }
                catch (Exception error) when (error is FileNotFoundException or UnauthorizedAccessException or IOException)
                {
                }
            }
            return false;
        }
    }

    /// <summary>An app has the camera open: the source read a frame in the last 2 s.</summary>
    public bool InDemand
    {
        get
        {
            lock (_gate)
            {
                if (_view is null) return false;
                long beat = _view.ReadInt64(16);
                return beat > 0 && Environment.TickCount64 - beat < 2000;
            }
        }
    }

    /// <summary>The size the app chose, or 1280 x 720 before it chose.</summary>
    public (int Width, int Height) Wanted
    {
        get
        {
            lock (_gate)
            {
                if (_view is null) return (1280, 720);
                int w = (int)_view.ReadUInt32(24), h = (int)_view.ReadUInt32(28);
                return w is >= 16 and <= 3840 && h is >= 16 and <= 2160 ? (w, h) : (1280, 720);
            }
        }
    }

    public void Write(byte[] frame, int width, int height)
    {
        int bytes = width * height * 3 / 2;
        lock (_gate)
        {
            if (_view is null || bytes > _maxBytes || bytes > frame.Length) return;
            uint seq = _view.ReadUInt32(32);
            if ((seq & 1) != 0) seq++;
            _view.Write(32, seq + 1);
            Thread.MemoryBarrier();
            Marshal.Copy(frame, 0, _base + HeaderSize, bytes);
            _view.Write(36, (uint)width);
            _view.Write(40, (uint)height);
            _view.Write(44, (uint)bytes);
            _view.Write(48, Environment.TickCount64);
            Thread.MemoryBarrier();
            _view.Write(32, seq + 2);
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _view?.Dispose();
            _file?.Dispose();
            _view = null;
            _file = null;
            _base = IntPtr.Zero;
        }
    }
}
