using Veyro.Core.Diagnostics;
using Veyro.Core.Link;
using Veyro.Platform.Interop;

namespace Veyro.Services;

/// <summary>
/// How much Veyro may do while it waits in the tray (ADR-047). One place decides, from three
/// facts: is the window on screen, is any phone connected, are files moving.
///
/// | State                                   | Volume read | Wallpaper / Wi-Fi poll | EcoQoS |
/// |-----------------------------------------|-------------|------------------------|--------|
/// | no phone connected                      | off         | off                    | on (hidden) |
/// | phone connected, window hidden          | every 3 s   | every 30 s             | on     |
/// | phone connected, window on screen       | every 1 s   | every 30 s             | off    |
/// | files moving (either way), any window   | as above    | as above               | off    |
///
/// Everything else Veyro does in the background is event-driven (sessions, notifications, media
/// sessions, battery, clipboard) and costs nothing while nothing happens. The discovery beacon
/// slows itself the same way (<see cref="Veyro.Core.Discovery.UdpDiscoveryService"/>).
/// </summary>
internal sealed class BackgroundPower
{
    private static readonly TimeSpan VolumeOnScreen = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan VolumeHidden = TimeSpan.FromSeconds(3);

    private readonly IPeerSessionRegistry _sessions;
    private readonly LinkServer _server;
    private readonly IFileTransferService _transfers;
    private readonly IWindowsMediaSource _media;
    private readonly IWindowsWallpaperSource _wallpaper;
    private readonly IWindowsNetworkSource _network;
    private readonly Func<bool> _windowOnScreen;
    private readonly object _gate = new();

    public BackgroundPower(
        IPeerSessionRegistry sessions,
        LinkServer server,
        IFileTransferService transfers,
        IWindowsMediaSource media,
        IWindowsWallpaperSource wallpaper,
        IWindowsNetworkSource network,
        Func<bool> windowOnScreen)
    {
        _sessions = sessions;
        _server = server;
        _transfers = transfers;
        _media = media;
        _wallpaper = wallpaper;
        _network = network;
        _windowOnScreen = windowOnScreen;
    }

    /// <summary>Starts following the facts. Call once, after the sources are started.</summary>
    public void Start()
    {
        _sessions.SessionOpened += _ => Apply();
        _sessions.SessionClosed += _ => Apply();
        _server.ReceivingChanged += Apply;
        _transfers.ActiveTransferChanged += _ => Apply();
        Apply();
    }

    /// <summary>The window was shown or hidden. UI thread.</summary>
    public void WindowChanged() => Apply();

    private void Apply()
    {
        try
        {
            lock (_gate)
            {
                bool onScreen = _windowOnScreen();
                bool phone = _sessions.GetConnectedPeers().Count > 0;
                bool busy = _server.IsReceiving || _transfers.ActiveTransfer is { } t && !IsFinished(t);

                _media.SetVolumePolling(phone ? (onScreen ? VolumeOnScreen : VolumeHidden) : null);
                _wallpaper.SetActive(phone);
                _network.SetActive(phone);
                // A transfer is throughput-bound: never slow it down, in or out.
                ProcessPower.SetEfficiencyMode(!onScreen && !busy);
            }
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Applying the background power policy failed", error);
        }
    }

    private static bool IsFinished(ActiveTransfer transfer)
        => transfer.Stage is TransferStage.Completed or TransferStage.Failed or TransferStage.Cancelled;
}
