using System.Runtime.InteropServices;
using Veyro.Core.Mirroring;
using Xunit;

namespace Veyro.Tests.Media;

/// <summary>The virtual camera's frame placement and its DLL's COM entry point (ADR-067).</summary>
public sealed class VirtualCameraTests
{
    [Fact]
    public void Compose_TurnsClockwise()
    {
        byte[] y = [0, 1, 2, 3, 4, 5, 6, 7];   // 4 x 2
        byte[] uv = [10, 11, 20, 21];          // 2 x 1 chroma pairs
        byte[] dst = new byte[2 * 4 * 3 / 2];
        Nv12.Compose(y, uv, 4, 2, 90, false, dst, 2, 4);
        Assert.Equal([4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21], dst);

        Nv12.Compose(y, uv, 4, 2, 270, false, dst, 2, 4);
        Assert.Equal([3, 7, 2, 6, 1, 5, 0, 4, 20, 21, 10, 11], dst);
    }

    [Fact]
    public void Compose_MirrorsAndCentresWithBlackBars()
    {
        byte[] y = [1, 2, 3, 4];   // 2 x 2
        byte[] uv = [9, 8];
        byte[] dst = new byte[6 * 2 * 3 / 2];
        Nv12.Compose(y, uv, 2, 2, 0, true, dst, 6, 2);
        Assert.Equal([16, 16, 2, 1, 16, 16, 16, 16, 4, 3, 16, 16, 128, 128, 9, 8, 128, 128], dst);

        byte[] copy = new byte[6 * 2 * 3 / 2];
        Nv12.Compose(y, uv, 2, 2, 0, false, copy, 6, 2);
        Assert.Equal([16, 16, 1, 2, 16, 16, 16, 16, 3, 4, 16, 16, 128, 128, 9, 8, 128, 128], copy);
    }

    [Fact]
    public void FromBgra_GivesLimitedRangeBlackAndWhite()
    {
        byte[] yy = new byte[2 * 2], uv = new byte[2];
        Nv12.FromBgraRow([0, 0, 0, 255, 255, 255, 255, 255], 0, 2, yy, uv);
        Assert.Equal(16, yy[0]);
        Assert.InRange(yy[1], 233, 235);
        Assert.InRange(uv[0], 126, 130);
    }

    [ComImport, Guid("00000001-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IClassFactory
    {
        [PreserveSig] int CreateInstance(IntPtr outer, ref Guid iid, out IntPtr instance);
        [PreserveSig] int LockServer(bool lockServer);
    }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int DllGetClassObject(ref Guid clsid, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out IClassFactory factory);

    private static string? FindDll()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            string path = Path.Combine(dir.FullName, "VeyroVirtualCamera", "bin", "x64", "VeyroVirtualCamera.dll");
            if (File.Exists(path)) return path;
        }
        return null;
    }

    [Fact]
    public void Dll_HandsOutTheCameraActivator_WithoutRegistration()
    {
        if (FindDll() is not { } path || !Environment.Is64BitProcess) return; // built with MSVC on this machine only
        IntPtr module = NativeLibrary.Load(path);
        try
        {
            Assert.NotEqual(IntPtr.Zero, NativeLibrary.GetExport(module, "VeyroVcamRegister"));
            Assert.NotEqual(IntPtr.Zero, NativeLibrary.GetExport(module, "VeyroVcamRemove"));
            var getClassObject = Marshal.GetDelegateForFunctionPointer<DllGetClassObject>(NativeLibrary.GetExport(module, "DllGetClassObject"));
            Guid clsid = new(VirtualCameraIds.Clsid);
            Guid factoryIid = typeof(IClassFactory).GUID;
            Assert.Equal(0, getClassObject(ref clsid, ref factoryIid, out IClassFactory factory));
            Guid activateIid = new("7FEE9E9A-4A89-47a6-899C-B6A53A70FB67"); // IMFActivate
            Assert.Equal(0, factory.CreateInstance(IntPtr.Zero, ref activateIid, out IntPtr activate));
            Assert.NotEqual(IntPtr.Zero, activate);
            Marshal.Release(activate);
            Marshal.ReleaseComObject(factory);

            Guid other = Guid.NewGuid();
            Assert.NotEqual(0, getClassObject(ref other, ref factoryIid, out _));
        }
        finally
        {
            // Not freed: COM may still hold the class object; the test process ends soon.
            GC.KeepAlive(module);
        }
    }
}
