using System.Runtime.InteropServices;
using Veyro.Core.Devices;
using Veyro.Core.Diagnostics;
using Veyro.Core.Handoff;
using Veyro.Platform.Handoff;
using Veyro.Services;
using static Veyro.Platform.Shell.ExplorerCommandInterop;

namespace Veyro.Platform.Shell;

/// <summary>
/// "Send with Veyro" in the Windows 11 File Explorer menu (ADR-039), optional and off by default.
/// The command is an out-of-process COM server (Package.appxmanifest com:ExeServer), so no .NET
/// code is loaded into explorer.exe: while Veyro runs, Explorer's calls go to the running Veyro
/// (it registers the class object); otherwise COM starts Veyro.exe with
/// <see cref="ComServerArgument"/>, which serves only the menu, without a window, and exits when idle.
/// Explorer asks on every right-click; with the setting off the command reports ECS_HIDDEN.
/// </summary>
internal static class ExplorerCommandServer
{
    /// <summary>Must match com:Class Id and desktop5:Verb Clsid in Package.appxmanifest.</summary>
    public static readonly Guid CommandClsid = new("03FEE873-1C45-4C21-9509-78F1A3E62C1C");

    /// <summary>Must match com:ExeServer Arguments in Package.appxmanifest.</summary>
    public const string ComServerArgument = "-VeyroExplorerCommand";

    private static readonly TimeSpan StandaloneIdleExit = TimeSpan.FromMinutes(3);
    private static long _lastActivity = Environment.TickCount64;
    private static int _pendingDeliveries;

    public static bool IsComServerLaunch(IEnumerable<string> args)
        => args.Any(arg => string.Equals(arg, ComServerArgument, StringComparison.OrdinalIgnoreCase));

    /// <summary>Keeps a standalone server alive while Explorer is still using it.</summary>
    public static void Touch() => Interlocked.Exchange(ref _lastActivity, Environment.TickCount64);

    public static void BeginDelivery() => Interlocked.Increment(ref _pendingDeliveries);

    public static void EndDelivery()
    {
        Interlocked.Decrement(ref _pendingDeliveries);
        Touch();
    }

    /// <summary>The running Veyro answers Explorer itself. Never throws.</summary>
    public static void RegisterInMainInstance(IExplorerMenuSource source) => StartRegistration(source, stop: null);

    /// <summary>
    /// COM started Veyro.exe for Explorer. Serves the menu until it has been idle for a few
    /// minutes, then returns (and the process exits). No WinUI, no single-instance key.
    /// </summary>
    public static void RunStandalone()
    {
        VeyroTrace.Info("Explorer menu server started");
        using var stop = new ManualResetEventSlim();
        Thread registration = StartRegistration(new StandaloneExplorerMenuSource(), stop);
        Touch();
        while (Environment.TickCount64 - Interlocked.Read(ref _lastActivity) < StandaloneIdleExit.TotalMilliseconds ||
               Volatile.Read(ref _pendingDeliveries) > 0)
        {
            Thread.Sleep(2000);
        }
        stop.Set();
        registration.Join(TimeSpan.FromSeconds(5));
        VeyroTrace.Info("Explorer menu server idle, exiting");
    }

    /// <summary>
    /// Registers the class object from a dedicated MTA thread, so Explorer's calls run on COM
    /// worker threads and never wait for the UI thread.
    /// </summary>
    private static Thread StartRegistration(IExplorerMenuSource source, ManualResetEventSlim? stop)
    {
        var thread = new Thread(() =>
        {
            nint factory = 0;
            uint cookie = 0;
            try
            {
                factory = Marshal.GetComInterfaceForObject(new ExplorerCommandFactory(source), typeof(IClassFactory));
                Guid clsid = CommandClsid;
                int hr = CoRegisterClassObject(ref clsid, factory, CLSCTX_LOCAL_SERVER, REGCLS_MULTIPLEUSE, out cookie);
                if (hr < 0)
                {
                    VeyroTrace.Warn($"Explorer menu registration failed: 0x{hr:X8}");
                    return;
                }
                if (stop is null)
                {
                    // Main instance: keep the MTA (and the registration) for the life of the process.
                    Thread.Sleep(Timeout.Infinite);
                }
                else
                {
                    stop.Wait();
                    _ = CoRevokeClassObject(cookie);
                }
            }
            catch (Exception error)
            {
                VeyroTrace.Warn($"Explorer menu registration failed: {error.GetType().Name}");
            }
            finally
            {
                if (factory != 0) Marshal.Release(factory);
            }
        })
        {
            IsBackground = true,
            Name = "Veyro Explorer menu"
        };
        thread.SetApartmentState(ApartmentState.MTA);
        thread.Start();
        return thread;
    }

    internal static nint ToCoTaskString(string text) => Marshal.StringToCoTaskMemUni(text);

    internal static string IconPath => Path.Combine(AppContext.BaseDirectory, "Assets", "Veyro.ico");
}

[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
public sealed class ExplorerCommandFactory : IClassFactory
{
    private readonly IExplorerMenuSource _source;

    internal ExplorerCommandFactory(IExplorerMenuSource source) => _source = source;

    public int CreateInstance(nint pUnkOuter, ref Guid riid, out nint ppvObject)
    {
        ppvObject = 0;
        if (pUnkOuter != 0) return CLASS_E_NOAGGREGATION;
        ExplorerCommandServer.Touch();
        try
        {
            nint command = Marshal.GetComInterfaceForObject(new SendWithVeyroCommand(_source), typeof(IExplorerCommand));
            try
            {
                return Marshal.QueryInterface(command, in riid, out ppvObject);
            }
            finally
            {
                Marshal.Release(command);
            }
        }
        catch (Exception)
        {
            return E_FAIL;
        }
    }

    public int LockServer(int fLock)
    {
        ExplorerCommandServer.Touch();
        return S_OK;
    }
}

/// <summary>The top-level "Send with Veyro" entry. Hidden unless the setting is on.</summary>
[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
public sealed class SendWithVeyroCommand : IExplorerCommand
{
    // Stable identity for the verb; not the CLSID.
    private static readonly Guid CanonicalName = new("FF6BA7E5-0766-4446-95EF-A789C7A0932E");
    private readonly IExplorerMenuSource _source;

    internal SendWithVeyroCommand(IExplorerMenuSource source) => _source = source;

    public int GetTitle(nint psiItemArray, out nint ppszName)
    {
        ExplorerCommandServer.Touch();
        ppszName = ExplorerCommandServer.ToCoTaskString(ExplorerMenu.RootTitle);
        return S_OK;
    }

    public int GetIcon(nint psiItemArray, out nint ppszIcon)
    {
        ppszIcon = 0;
        string icon = ExplorerCommandServer.IconPath;
        if (!File.Exists(icon)) return E_NOTIMPL;
        ppszIcon = ExplorerCommandServer.ToCoTaskString(icon);
        return S_OK;
    }

    public int GetToolTip(nint psiItemArray, out nint ppszInfotip)
    {
        ppszInfotip = 0;
        return E_NOTIMPL;
    }

    public int GetCanonicalName(out Guid pguidCommandName)
    {
        pguidCommandName = CanonicalName;
        return S_OK;
    }

    public int GetState(nint psiItemArray, int fOkToBeSlow, out uint pCmdState)
    {
        ExplorerCommandServer.Touch();
        pCmdState = _source.IsEnabled() ? ECS_ENABLED : ECS_HIDDEN;
        return S_OK;
    }

    public int Invoke(nint psiItemArray, nint pbc) => S_OK; // the submenu entries act

    public int GetFlags(out uint pFlags)
    {
        pFlags = ECF_HASSUBCOMMANDS;
        return S_OK;
    }

    public int EnumSubCommands(out nint ppEnum)
    {
        ExplorerCommandServer.Touch();
        ppEnum = 0;
        try
        {
            var commands = ExplorerMenu.Build(_source.GetPhones())
                .Select(entry => (IExplorerCommand)new VeyroSubCommand(entry, _source))
                .ToList();
            ppEnum = Marshal.GetComInterfaceForObject(new ExplorerCommandEnumerator(commands, 0), typeof(IEnumExplorerCommand));
            return S_OK;
        }
        catch (Exception)
        {
            return E_FAIL;
        }
    }
}

/// <summary>One phone, "Send to all connected phones", or "Pair a phone...".</summary>
[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
public sealed class VeyroSubCommand : IExplorerCommand
{
    private readonly ExplorerMenuEntry _entry;
    private readonly IExplorerMenuSource _source;

    internal VeyroSubCommand(ExplorerMenuEntry entry, IExplorerMenuSource source)
    {
        _entry = entry;
        _source = source;
    }

    public int GetTitle(nint psiItemArray, out nint ppszName)
    {
        ppszName = ExplorerCommandServer.ToCoTaskString(_entry.Title);
        return S_OK;
    }

    public int GetIcon(nint psiItemArray, out nint ppszIcon)
    {
        ppszIcon = 0;
        return E_NOTIMPL;
    }

    public int GetToolTip(nint psiItemArray, out nint ppszInfotip)
    {
        ppszInfotip = 0;
        return E_NOTIMPL;
    }

    public int GetCanonicalName(out Guid pguidCommandName)
    {
        pguidCommandName = Guid.Empty;
        return S_OK;
    }

    public int GetState(nint psiItemArray, int fOkToBeSlow, out uint pCmdState)
    {
        pCmdState = _entry.IsEnabled ? ECS_ENABLED : ECS_DISABLED;
        return S_OK;
    }

    public int Invoke(nint psiItemArray, nint pbc)
    {
        ExplorerCommandServer.Touch();
        try
        {
            // Read the selection now, while Explorer's item array is valid; send afterwards.
            IReadOnlyList<string> paths = _entry.Kind == ExplorerMenuEntryKind.Pair ? [] : ShellItems.GetFileSystemPaths(psiItemArray);
            if (ExplorerMenu.ToRequest(_entry, paths) is { } request) _source.Deliver(request);
            return S_OK;
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Explorer menu invoke failed: {error.GetType().Name}");
            return E_FAIL;
        }
    }

    public int GetFlags(out uint pFlags)
    {
        pFlags = ECF_DEFAULT;
        return S_OK;
    }

    public int EnumSubCommands(out nint ppEnum)
    {
        ppEnum = 0;
        return E_NOTIMPL;
    }
}

[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
public sealed class ExplorerCommandEnumerator : IEnumExplorerCommand
{
    private readonly IReadOnlyList<IExplorerCommand> _commands;
    private int _index;

    internal ExplorerCommandEnumerator(IReadOnlyList<IExplorerCommand> commands, int index)
    {
        _commands = commands;
        _index = index;
    }

    public int Next(uint celt, nint pUICommand, nint pceltFetched)
    {
        if (pUICommand == 0) return E_POINTER;
        uint fetched = 0;
        while (fetched < celt && _index < _commands.Count)
        {
            nint command = Marshal.GetComInterfaceForObject(_commands[_index++], typeof(IExplorerCommand));
            Marshal.WriteIntPtr(pUICommand, (int)fetched * nint.Size, command);
            fetched++;
        }
        if (pceltFetched != 0) Marshal.WriteInt32(pceltFetched, (int)fetched);
        return fetched == celt ? S_OK : S_FALSE;
    }

    public int Skip(uint celt)
    {
        _index = (int)Math.Min(_commands.Count, _index + (long)celt);
        return _index < _commands.Count ? S_OK : S_FALSE;
    }

    public int Reset()
    {
        _index = 0;
        return S_OK;
    }

    public int Clone(out nint ppenum)
    {
        ppenum = Marshal.GetComInterfaceForObject(new ExplorerCommandEnumerator(_commands, _index), typeof(IEnumExplorerCommand));
        return S_OK;
    }
}

/// <summary>The running Veyro: live connection state, in-process hand-off.</summary>
internal sealed class LiveExplorerMenuSource : IExplorerMenuSource
{
    public bool IsEnabled()
    {
        try { return VeyroContainer.Instance.SettingsStore.Current.ShowInExplorerContextMenu; }
        catch (Exception) { return false; }
    }

    public IReadOnlyList<SendCandidate> GetPhones()
    {
        try
        {
            var container = VeyroContainer.Instance;
            return PairedDeviceDirectory.Build(container.TrustStore.GetTrustedPeers(), container.PeerSessions.IsConnected)
                .Select(device => new SendCandidate(device.DeviceId, device.DisplayName, device.IsConnected))
                .ToList();
        }
        catch (Exception)
        {
            return [];
        }
    }

    public void Deliver(HandoffRequest request) => _ = HandoffCoordinator.HandleFromAnyThreadAsync(request);
}

/// <summary>
/// Veyro is not running: settings and phone names straight from the data files (read-only), and
/// an invoked entry starts Veyro and hands the files to it over <see cref="HandoffPipe"/>.
/// </summary>
internal sealed class StandaloneExplorerMenuSource : IExplorerMenuSource
{
    private static readonly TimeSpan DeliveryTimeout = TimeSpan.FromMinutes(1);

    public bool IsEnabled() => ExplorerMenu.ReadEnabled(Path.Combine(Constants.LocalAppDataDirectory, "settings.json"));

    public IReadOnlyList<SendCandidate> GetPhones()
        => ExplorerMenu.ReadPairedForLabels(Path.Combine(Constants.LocalAppDataDirectory, "trust.json"));

    public void Deliver(HandoffRequest request)
    {
        ExplorerCommandServer.BeginDelivery();
        _ = Task.Run(async () =>
        {
            try
            {
                using var timeout = new CancellationTokenSource(DeliveryTimeout);
                await HandoffPipe.SendAsync(request, waitForResult: false, launchIfMissing: true, timeout.Token).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                VeyroTrace.Warn($"Explorer menu hand-off failed: {error.GetType().Name}");
            }
            finally
            {
                ExplorerCommandServer.EndDelivery();
            }
        });
    }
}
