namespace Veyro.Services;

public sealed class AppThemeModeService : IAppThemeModeService
{
    public AppThemeMode CurrentTheme { get; private set; } = AppThemeMode.System;

    public void ApplyTheme(Window window, AppThemeMode mode)
    {
        CurrentTheme = mode;
        if (window.Content is FrameworkElement root)
        {
            root.RequestedTheme = mode switch
            {
                AppThemeMode.Light => ElementTheme.Light,
                AppThemeMode.Dark => ElementTheme.Dark,
                _ => ElementTheme.Default
            };
        }
    }
}

public sealed class DeviceManagerService : IDeviceManager
{
    private readonly ITrustStore _trustStore;
    private readonly IDiscoveryService _discovery;
    private readonly object _discoveryGate = new();
    private IReadOnlyList<PeerDevice> _latestDiscovered = [];
    private PairedDeviceUiModel? _activeDevice;

    public ObservableCollection<PairedDeviceUiModel> PairedDevices { get; } = [];
    public ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices { get; } = [];

    public PairedDeviceUiModel? ActiveDevice
    {
        get => _activeDevice;
        set
        {
            if (!ReferenceEquals(_activeDevice, value))
            {
                _activeDevice = value;
                ActiveDeviceChanged?.Invoke(_activeDevice);
            }
        }
    }

    public event Action<PairedDeviceUiModel?>? ActiveDeviceChanged;

    public DeviceManagerService(ITrustStore trustStore, IDiscoveryService discovery)
    {
        _trustStore = trustStore;
        _discovery = discovery;
        _discovery.PeersUpdated += peers =>
        {
            lock (_discoveryGate) _latestDiscovered = peers;
        };
        ReloadPairedDevices();
    }

    public void ReloadPairedDevices()
    {
        PairedDevices.Clear();
        foreach (var trusted in _trustStore.GetTrustedPeers())
        {
            byte[] pk = Convert.FromBase64String(trusted.PublicKeyBase64);
            var uiModel = new PairedDeviceUiModel
            {
                DeviceId = trusted.DeviceId,
                Name = trusted.DisplayName,
                Fingerprint = DeviceId.FormatFingerprint(pk),
                Platform = trusted.Platform,
                IsConnected = false
            };
            uiModel.AudioStreams.Add(new AudioStreamModel("media", "Media", "\uE8D6", 70));
            uiModel.AudioStreams.Add(new AudioStreamModel("ring", "Ring", "\uEA8F", 80));
            uiModel.AudioStreams.Add(new AudioStreamModel("alarm", "Alarm", "\uE823", 90));
            PairedDevices.Add(uiModel);
        }

        ActiveDevice = PairedDevices.FirstOrDefault();
    }

    public Task RefreshDiscoveryAsync()
    {
        return RefreshDiscoveryCoreAsync();
    }

    public PeerDevice? FindDiscoveredPeer(string deviceId)
    {
        lock (_discoveryGate)
            return _latestDiscovered.FirstOrDefault(peer => string.Equals(peer.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
    }

    private async Task RefreshDiscoveryCoreAsync()
    {
        await _discovery.StartAsync().ConfigureAwait(true);
        IReadOnlyList<PeerDevice> peers;
        lock (_discoveryGate) peers = _latestDiscovered;
        DiscoveredDevices.Clear();
        foreach (var peer in peers)
        {
            DiscoveredDevices.Add(new DiscoveredDeviceUiModel(
                peer.DeviceId,
                peer.DisplayName,
                peer.Fingerprint ?? peer.DeviceId,
                string.Join(", ", peer.Endpoints.Select(endpoint => $"{endpoint.Host}:{endpoint.Port}")),
                peer.Platform));
        }
    }
}

public sealed class FileTransferService : IFileTransferService
{
    private readonly ITransferHistoryStore _historyStore;
    private readonly ITrustStore _trustStore;
    private readonly IIdentityManager _identityManager;
    private readonly ISettingsStore _settingsStore;
    private readonly DeviceManagerService _deviceManager;
    private CancellationTokenSource? _transferCancellation;
    public ActiveTransfer? ActiveTransfer { get; private set; }
    public ObservableCollection<TransferRecord> History { get; } = [];
    public event Action<ActiveTransfer?>? ActiveTransferChanged;

    public FileTransferService(
        ITransferHistoryStore historyStore,
        ITrustStore trustStore,
        IIdentityManager identityManager,
        ISettingsStore settingsStore,
        DeviceManagerService deviceManager)
    {
        _historyStore = historyStore;
        _trustStore = trustStore;
        _identityManager = identityManager;
        _settingsStore = settingsStore;
        _deviceManager = deviceManager;
        foreach (var item in _historyStore.GetHistory())
        {
            History.Add(item);
        }
    }

    public async Task SendFilesAsync(PairedDeviceUiModel target, IReadOnlyList<string> filePaths)
    {
        if (filePaths.Count == 0) return;

        var items = filePaths
            .Where(File.Exists)
            .Select((p, idx) => new TransferItem(idx, Path.GetFileName(p), new FileInfo(p).Length, string.Empty))
            .ToList();

        long totalBytes = items.Sum(i => i.SizeBytes);
        ActiveTransfer = new ActiveTransfer(
            TransferId: Guid.NewGuid().ToString("N")[..8],
            PeerDeviceId: target.DeviceId,
            PeerDisplayName: target.Name,
            IsIncoming: false,
            Stage: TransferStage.Preparing,
            Route: TransferRoute.LanTcp,
            BytesTransferred: 0,
            TotalBytes: totalBytes,
            SpeedBytesPerSecond: 0,
            EstimatedRemaining: null,
            CurrentFileName: items.FirstOrDefault()?.RelativePath,
            Items: items);

        ActiveTransferChanged?.Invoke(ActiveTransfer);
        var peer = _deviceManager.FindDiscoveredPeer(target.DeviceId);
        var trusted = _trustStore.GetPeer(target.DeviceId);
        if (peer is null || trusted is null)
        {
            ActiveTransfer = ActiveTransfer with
            {
                Stage = TransferStage.Failed,
                ErrorMessage = peer is null ? "Peer is not currently discoverable." : "Peer is not paired."
            };
            ActiveTransferChanged?.Invoke(ActiveTransfer);
            return;
        }

        _transferCancellation?.Cancel();
        _transferCancellation?.Dispose();
        _transferCancellation = new CancellationTokenSource();
        try
        {
            ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Connecting };
            ActiveTransferChanged?.Invoke(ActiveTransfer);
            var progress = new Progress<long>(bytes =>
            {
                if (ActiveTransfer is null) return;
                ActiveTransfer = ActiveTransfer with
                {
                    Stage = TransferStage.Transferring,
                    BytesTransferred = bytes
                };
                ActiveTransferChanged?.Invoke(ActiveTransfer);
            });
            var client = new VeyroLinkClient(_identityManager.DeviceId.Value, _settingsStore.Current.DeviceName);
            Vtc1TransferResult result = await client.SendFilesAsync(
                peer, trusted, filePaths, progress, _transferCancellation.Token).ConfigureAwait(true);
            var verifiedItems = items.Select(item => item with { Verification = VerificationOutcome.Verified }).ToList();
            ActiveTransfer = ActiveTransfer with
            {
                Stage = TransferStage.Completed,
                BytesTransferred = result.BytesTransferred,
                Items = verifiedItems
            };
            await _historyStore.UpsertAsync(new TransferRecord(
                ActiveTransfer.TransferId,
                ActiveTransfer.PeerDeviceId,
                ActiveTransfer.PeerDisplayName,
                false,
                DateTimeOffset.UtcNow,
                ActiveTransfer.TotalBytes,
                VerificationOutcome.Verified,
                verifiedItems)).ConfigureAwait(true);
            History.Insert(0, new TransferRecord(
                ActiveTransfer.TransferId,
                ActiveTransfer.PeerDeviceId,
                ActiveTransfer.PeerDisplayName,
                false,
                DateTimeOffset.UtcNow,
                ActiveTransfer.TotalBytes,
                VerificationOutcome.Verified,
                verifiedItems));
        }
        catch (OperationCanceledException)
        {
            ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Cancelled, ErrorMessage = "Transfer cancelled." };
        }
        catch (Exception error)
        {
            ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Failed, ErrorMessage = error.Message };
        }
        finally
        {
            ActiveTransferChanged?.Invoke(ActiveTransfer);
        }
    }

    public void CancelCurrentTransfer()
    {
        if (ActiveTransfer is null) return;
        _transferCancellation?.Cancel();
        ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Cancelled };
        ActiveTransferChanged?.Invoke(ActiveTransfer);
    }
}

public sealed class ScreenMirrorService : IScreenMirrorService
{
    public bool IsMirroring { get; private set; }

    public Task StartMirroringAsync(PairedDeviceUiModel device)
    {
        return Task.CompletedTask;
    }
}
