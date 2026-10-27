using System.Runtime.InteropServices;

namespace Veyro.Platform.Shell;

/// <summary>
/// The shell interfaces for a Windows 11 File Explorer command (shobjidl_core.h), declared for the
/// built-in COM interop. Method order must match the native vtables exactly. Item arrays arrive as
/// raw pointers and are read through their vtables (<see cref="ShellItems"/>), so no runtime
/// wrapper is involved on Explorer's call path.
/// </summary>
internal static class ExplorerCommandInterop
{
    public const int S_OK = 0;
    public const int S_FALSE = 1;
    public const int E_NOTIMPL = unchecked((int)0x80004001);
    public const int E_POINTER = unchecked((int)0x80004003);
    public const int E_FAIL = unchecked((int)0x80004005);
    public const int CLASS_E_NOAGGREGATION = unchecked((int)0x80040110);

    // EXPCMDSTATE / EXPCMDFLAGS
    public const uint ECS_ENABLED = 0x0;
    public const uint ECS_DISABLED = 0x1;
    public const uint ECS_HIDDEN = 0x2;
    public const uint ECF_DEFAULT = 0x0;
    public const uint ECF_HASSUBCOMMANDS = 0x1;

    public const uint CLSCTX_LOCAL_SERVER = 0x4;
    public const uint REGCLS_MULTIPLEUSE = 0x1;

    [DllImport("ole32.dll")]
    public static extern int CoRegisterClassObject(ref Guid rclsid, nint pUnk, uint dwClsContext, uint flags, out uint lpdwRegister);

    [DllImport("ole32.dll")]
    public static extern int CoRevokeClassObject(uint dwRegister);
}

[ComImport]
[Guid("a08ce4d0-fa25-44ab-b57c-c7b1c323e0b9")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IExplorerCommand
{
    [PreserveSig] int GetTitle(nint psiItemArray, out nint ppszName);
    [PreserveSig] int GetIcon(nint psiItemArray, out nint ppszIcon);
    [PreserveSig] int GetToolTip(nint psiItemArray, out nint ppszInfotip);
    [PreserveSig] int GetCanonicalName(out Guid pguidCommandName);
    [PreserveSig] int GetState(nint psiItemArray, int fOkToBeSlow, out uint pCmdState);
    [PreserveSig] int Invoke(nint psiItemArray, nint pbc);
    [PreserveSig] int GetFlags(out uint pFlags);
    [PreserveSig] int EnumSubCommands(out nint ppEnum);
}

[ComImport]
[Guid("a88826f8-186f-4987-aade-ea0cef8fbfe8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IEnumExplorerCommand
{
    /// <summary>Raw pointers: <paramref name="pceltFetched"/> may be null when one item is asked for.</summary>
    [PreserveSig] int Next(uint celt, nint pUICommand, nint pceltFetched);
    [PreserveSig] int Skip(uint celt);
    [PreserveSig] int Reset();
    [PreserveSig] int Clone(out nint ppenum);
}

[ComImport]
[Guid("00000001-0000-0000-C000-000000000046")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IClassFactory
{
    [PreserveSig] int CreateInstance(nint pUnkOuter, ref Guid riid, out nint ppvObject);
    [PreserveSig] int LockServer(int fLock);
}

/// <summary>Reads file-system paths out of an IShellItemArray pointer. Paths are user data: never logged.</summary>
internal static class ShellItems
{
    private const uint SIGDN_FILESYSPATH = 0x80058000;
    private const int ShellItemArrayGetCountSlot = 7;
    private const int ShellItemArrayGetItemAtSlot = 8;
    private const int ShellItemGetDisplayNameSlot = 5;
    private const int MaxItems = 4096;

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetCountFn(nint self, out uint count);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetItemAtFn(nint self, uint index, out nint item);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetDisplayNameFn(nint self, uint sigdn, out nint name);

    public static IReadOnlyList<string> GetFileSystemPaths(nint itemArray)
    {
        var paths = new List<string>();
        if (itemArray == 0) return paths;
        if (Slot<GetCountFn>(itemArray, ShellItemArrayGetCountSlot)(itemArray, out uint count) < 0) return paths;
        var getItemAt = Slot<GetItemAtFn>(itemArray, ShellItemArrayGetItemAtSlot);
        for (uint i = 0; i < Math.Min(count, (uint)MaxItems); i++)
        {
            if (getItemAt(itemArray, i, out nint item) < 0 || item == 0) continue;
            try
            {
                if (Slot<GetDisplayNameFn>(item, ShellItemGetDisplayNameSlot)(item, SIGDN_FILESYSPATH, out nint name) < 0 || name == 0) continue;
                try
                {
                    if (Marshal.PtrToStringUni(name) is { Length: > 0 } path) paths.Add(path);
                }
                finally
                {
                    Marshal.FreeCoTaskMem(name);
                }
            }
            finally
            {
                Marshal.Release(item);
            }
        }
        return paths;
    }

    private static T Slot<T>(nint instance, int slot) where T : Delegate
    {
        nint vtable = Marshal.ReadIntPtr(instance);
        return Marshal.GetDelegateForFunctionPointer<T>(Marshal.ReadIntPtr(vtable, slot * nint.Size));
    }
}
