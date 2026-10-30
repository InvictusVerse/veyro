using Veyro.Core.Devices;
using Veyro.Core.Diagnostics;

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

/// <summary>
/// The sidebar device switcher (multi-device). Paired phones come from the trust store only;
/// <see cref="PairedDeviceUiModel.IsConnected"/> follows <see cref="IPeerSessionRegistry"/> live.
/// Discovery (untrusted) only feeds the unpaired "available devices" list and the "seen on the
/// network" hint. Selection rules live in <see cref="PairedDeviceDirectory"/> (tested in Core).
/// </summary>
public sealed class DeviceManagerService : IDeviceManager
{
    private readonly ITrustStore _trustStore;
    private readonly IDiscoveryService _discovery;
    private readonly IPeerSessionRegistry _sessions;
    private readonly SessionKeeper? _keeper;
    private readonly DispatcherQueue? _dispatcher;
    private readonly object _discoveryGate = new();
    private IReadOnlyList<PeerDevice> _latestDiscovered = [];
    private IReadOnlyList<PairedDeviceEntry> _entries = [];
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
                MarkActive();
                ActiveDeviceChanged?.Invoke(_activeDevice);
            }
        }
    }

    public event Action<PairedDeviceUiModel?>? ActiveDeviceChanged;
    public event Action<string>? ConnectionChanged;
    public event Action? DiscoveryChanged;

    public DeviceManagerService(ITrustStore trustStore, IDiscoveryService discovery, IPeerSessionRegistry sessions, SessionKeeper? keeper = null)
    {
        _trustStore = trustStore;
        _discovery = discovery;
        _sessions = sessions;
        _keeper = keeper;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _discovery.PeersUpdated += peers =>
        {
            lock (_discoveryGate) _latestDiscovered = peers;
            Post(SyncDiscovered);
        };
        _sessions.SessionOpened += peerId => Post(() => OnConnectionChanged(peerId, opened: true));
        _sessions.SessionClosed += peerId => Post(() => OnConnectionChanged(peerId, opened: false));
        // ADR-040: the switcher shows "Connecting" while this PC dials a phone.
        if (_keeper is not null) _keeper.DialingChanged += peerId => Post(() => OnDialingChanged(peerId));
        ReloadPairedDevices();
    }

    public void Select(string deviceId)
    {
        if (Find(deviceId) is { } device) ActiveDevice = device;
    }

    /// <summary>
    /// The device header's refresh (ADR-040): dials the phone at once (no grace period) and
    /// rescans discovery. The phone's IsConnecting is true while the dial is in flight.
    /// </summary>
    public async Task ConnectNowAsync(string deviceId)
    {
        Task rescan = RefreshDiscoveryAsync();
        if (_keeper is not null)
        {
            try { await _keeper.ConnectNowAsync(deviceId).ConfigureAwait(true); }
            catch (Exception error) { VeyroTrace.Warn($"Connecting to the phone failed: {error.GetType().Name}"); }
        }
        await rescan.ConfigureAwait(true);
    }

    private void OnDialingChanged(string peerId)
    {
        if (Find(peerId) is not { } device) return;
        device.IsConnecting = _keeper?.IsDialing(peerId) == true;
        ConnectionChanged?.Invoke(device.DeviceId);
    }

    public void ReloadPairedDevices()
    {
        string? currentId = _activeDevice?.DeviceId;
        _entries = PairedDeviceDirectory.Build(_trustStore.GetTrustedPeers(), _sessions.IsConnected);

        PairedDevices.Clear();
        foreach (PairedDeviceEntry entry in _entries)
        {
            PairedDevices.Add(new PairedDeviceUiModel
            {
                DeviceId = entry.DeviceId,
                Name = entry.DisplayName,
                Fingerprint = FingerprintOf(entry.PublicKeyBase64),
                Platform = entry.Platform,
                IsConnected = entry.IsConnected,
                IsConnecting = _keeper?.IsDialing(entry.DeviceId) == true,
                AutoAcceptTransfers = _trustStore.GetPeer(entry.DeviceId)?.AutoAcceptTransfers == true
            });
        }

        // A rebuilt list holds new objects: always raise, so every page re-reads the phone.
        _activeDevice = Find(PairedDeviceDirectory.SelectAfterReload(_entries, currentId));
        MarkActive();
        ActiveDeviceChanged?.Invoke(_activeDevice);
        SyncDiscovered();
    }

    public void RefreshName(string deviceId) => Post(() =>
    {
        if (Find(deviceId) is not { } device) return;
        string? name = PairedDeviceDirectory.Build(_trustStore.GetTrustedPeers(), _sessions.IsConnected)
            .FirstOrDefault(entry => string.Equals(entry.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase))?.DisplayName;
        if (name is null || name == device.Name) return;
        device.Name = name;
        _entries = _entries
            .Select(entry => string.Equals(entry.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase) ? entry with { DisplayName = name } : entry)
            .ToList();
        ConnectionChanged?.Invoke(device.DeviceId);
    });

    public void SelectNext(int delta)
    {
        if (PairedDevices.Count < 2) return;
        ActiveDevice = Find(PairedDeviceDirectory.Step(_entries, _activeDevice?.DeviceId, delta)) ?? ActiveDevice;
    }

    public IReadOnlyList<string> GetSeenEndpoints(string deviceId)
    {
        PeerDevice? peer = FindDiscoveredPeer(deviceId);
        return peer is null ? [] : peer.Endpoints.Select(endpoint => $"{endpoint.Host}:{endpoint.Port}").ToList();
    }

    public async Task RefreshDiscoveryAsync()
    {
        try
        {
            await _discovery.StartAsync().ConfigureAwait(true);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Discovery start failed", error);
        }
        lock (_discoveryGate) _latestDiscovered = _discovery.DiscoveredPeers;
        Post(SyncDiscovered);
    }

    /// <summary>Discovery record for a paired phone, used to reach it for an outbound transfer. Untrusted.</summary>
    public PeerDevice? FindDiscoveredPeer(string deviceId)
    {
        lock (_discoveryGate)
            return _latestDiscovered.FirstOrDefault(peer => string.Equals(peer.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
    }

    private void OnConnectionChanged(string peerId, bool opened)
    {
        PairedDeviceUiModel? device = Find(peerId);
        if (device is null)
        {
            // A session from a peer missing from the list (just paired): rebuild from trust.
            if (opened && _trustStore.GetPeer(peerId) is not null) ReloadPairedDevices();
            return;
        }

        bool connected = _sessions.IsConnected(peerId);
        device.IsConnected = connected;
        _entries = _entries
            .Select(entry => string.Equals(entry.DeviceId, peerId, StringComparison.OrdinalIgnoreCase) ? entry with { IsConnected = connected } : entry)
            .ToList();
        ConnectionChanged?.Invoke(device.DeviceId);

        if (opened)
        {
            PairedDeviceUiModel? follow = Find(PairedDeviceDirectory.SelectAfterSessionOpened(_entries, _activeDevice?.DeviceId, peerId));
            if (follow is not null) ActiveDevice = follow;
        }
    }

    private void SyncDiscovered()
    {
        IReadOnlyList<PeerDevice> peers;
        lock (_discoveryGate) peers = _latestDiscovered;
        DiscoveredDevices.Clear();
        foreach (PeerDevice peer in peers)
        {
            // Paired phones are listed in the switcher; this list is for phones to pair.
            if (Find(peer.DeviceId) is not null) continue;
            DiscoveredDevices.Add(new DiscoveredDeviceUiModel(
                peer.DeviceId,
                peer.DisplayName,
                peer.Fingerprint ?? peer.DeviceId,
                string.Join(", ", peer.Endpoints.Select(endpoint => $"{endpoint.Host}:{endpoint.Port}")),
                peer.Platform));
        }
        DiscoveryChanged?.Invoke();
    }

    private void MarkActive()
    {
        foreach (PairedDeviceUiModel device in PairedDevices) device.IsActive = ReferenceEquals(device, _activeDevice);
    }

    private PairedDeviceUiModel? Find(string? deviceId)
        => deviceId is null
            ? null
            : PairedDevices.FirstOrDefault(device => string.Equals(device.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));

    private static string FingerprintOf(string publicKeyBase64)
    {
        try { return DeviceId.FormatFingerprint(Convert.FromBase64String(publicKeyBase64)); }
        catch (FormatException) { return string.Empty; }
        catch (ArgumentException) { return string.Empty; }
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}

/// <summary>
/// Outbound file transfer to a paired phone over VTC1 (ADR-020/022). Progress, speed and the
/// remaining time come only from bytes actually sent; history is the persisted store.
/// </summary>
public sealed class FileTransferService : IFileTransferService
{
    private readonly ITransferHistoryStore _historyStore;
    private readonly ITrustStore _trustStore;
    private readonly IIdentityManager _identityManager;
    private readonly ISettingsStore _settingsStore;
    private readonly DeviceManagerService _deviceManager;
    private readonly IPeerSessionRegistry _sessions;
    private CancellationTokenSource? _transferCancellation;
    public ActiveTransfer? ActiveTransfer { get; private set; }
    public ObservableCollection<TransferRecord> History { get; } = [];
    public event Action<ActiveTransfer?>? ActiveTransferChanged;

    public FileTransferService(
        ITransferHistoryStore historyStore,
        ITrustStore trustStore,
        IIdentityManager identityManager,
        ISettingsStore settingsStore,
        DeviceManagerService deviceManager,
        IPeerSessionRegistry sessions)
    {
        _historyStore = historyStore;
        _trustStore = trustStore;
        _identityManager = identityManager;
        _settingsStore = settingsStore;
        _deviceManager = deviceManager;
        _sessions = sessions;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        foreach (var item in _historyStore.GetHistory())
        {
            History.Add(item);
        }
    }

    private readonly DispatcherQueue? _dispatcher;

    /// <summary>
    /// A file or folder the phone sent ended (LinkServer.InboundTransferFinished, server thread).
    /// Recorded like a send, on the UI thread, so the Transfers tab shows it at once.
    /// </summary>
    public void RecordReceived(InboundTransferReport report)
    {
        string peerName = _trustStore.GetPeer(report.PeerDeviceId)?.ShownName ?? "Unknown device";
        TransferRecord record = ReceivedHistory.ToRecord(report, peerName, DateTimeOffset.UtcNow);
        if (_dispatcher is null || _dispatcher.HasThreadAccess) _ = SaveRecordAsync(record);
        else _dispatcher.TryEnqueue(() => _ = SaveRecordAsync(record));
    }

    private static bool IsFinished(ActiveTransfer transfer)
        => transfer.Stage is TransferStage.Completed or TransferStage.Failed or TransferStage.Cancelled;

    public async Task SendFilesAsync(PairedDeviceUiModel target, IReadOnlyList<string> filePaths)
    {
        if (filePaths.Count == 0) return;
        if (ActiveTransfer is { } running && !IsFinished(running)) return; // one at a time, as the banner shows

        // ADR-064: files and whole folders; a folder is walked once, off the UI thread, and that
        // same plan is what is offered.
        OutgoingPlan plan;
        try
        {
            plan = await Task.Run(() => OutgoingPlanner.Build(filePaths)).ConfigureAwait(true);
        }
        catch (FolderTooLargeException error)
        {
            ShowFailedBeforeStart(target, error.Message);
            return;
        }
        if (plan.FileCount == 0)
        {
            if (plan.FolderName is { } empty) ShowFailedBeforeStart(target, $"{empty} has no files to send.");
            return;
        }
        if (plan.Skipped > 0) VeyroTrace.Info($"Outbound plan left out {plan.Skipped} item(s)");
        var items = plan.Files
            .Select(entry => new TransferItem(entry.Index, entry.RelativePath, entry.Size, string.Empty))
            .ToList();

        long totalBytes = plan.TotalBytes;
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
            // "Photos folder, 120 files" for a folder (ADR-064).
            CurrentFileName: plan.Skipped > 0 ? $"{plan.Describe()}, {plan.Skipped} left out" : plan.Describe(),
            Items: items);

        ActiveTransferChanged?.Invoke(ActiveTransfer);
        // ADR-034: the phone's live session carries the offer; the discovered endpoint is only
        // needed when the phone is visible but not connected.
        var peer = _deviceManager.FindDiscoveredPeer(target.DeviceId);
        var trusted = _trustStore.GetPeer(target.DeviceId);
        if (trusted is null || (peer is null && !_sessions.IsConnected(target.DeviceId)))
        {
            ActiveTransfer = ActiveTransfer with
            {
                Stage = TransferStage.Failed,
                ErrorMessage = trusted is null
                    ? "This phone is not paired any more."
                    : "The phone is not visible on the network. Open Veyro on the phone, keep both on the same Wi-Fi, then try again."
            };
            ActiveTransferChanged?.Invoke(ActiveTransfer);
            await RecordAsync(ActiveTransfer, VerificationOutcome.Failed).ConfigureAwait(true);
            return;
        }

        _transferCancellation?.Cancel();
        _transferCancellation?.Dispose();
        _transferCancellation = new CancellationTokenSource();
        var clock = Stopwatch.StartNew();
        try
        {
            ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Connecting };
            ActiveTransferChanged?.Invoke(ActiveTransfer);
            var progress = new Progress<long>(bytes =>
            {
                if (ActiveTransfer is null || IsFinished(ActiveTransfer)) return;
                long? rate = TransferProgressMath.AverageBytesPerSecond(bytes, clock.Elapsed);
                ActiveTransfer = ActiveTransfer with
                {
                    Stage = TransferStage.Transferring,
                    BytesTransferred = bytes,
                    SpeedBytesPerSecond = rate ?? 0,
                    EstimatedRemaining = TransferProgressMath.EstimateRemaining(bytes, ActiveTransfer.TotalBytes, rate)
                };
                ActiveTransferChanged?.Invoke(ActiveTransfer);
            });
            var client = new LinkClient(_identityManager.DeviceId.Value, _settingsStore.Current.DeviceName);
            BulkTransferResult result = await client.SendFilesAsync(
                _sessions, peer, trusted, filePaths, progress, _transferCancellation.Token, planned: plan).ConfigureAwait(true);
            var verifiedItems = items.Select(item => item with { Verification = VerificationOutcome.Verified }).ToList();
            ActiveTransfer = ActiveTransfer with
            {
                Stage = TransferStage.Completed,
                BytesTransferred = result.BytesTransferred,
                EstimatedRemaining = null,
                Items = verifiedItems
            };
            await RecordAsync(ActiveTransfer, VerificationOutcome.Verified).ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
            ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Cancelled, EstimatedRemaining = null, ErrorMessage = "Transfer cancelled." };
        }
        catch (Exception error)
        {
            // Type only: exception messages can name the user's files.
            VeyroTrace.Warn($"Outbound transfer failed: {error.GetType().Name}");
            ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Failed, EstimatedRemaining = null, ErrorMessage = error.Message };
            await RecordAsync(ActiveTransfer, VerificationOutcome.Failed).ConfigureAwait(true);
        }
        finally
        {
            ActiveTransferChanged?.Invoke(ActiveTransfer);
        }
    }

    /// <summary>A send that cannot start (a folder too big or with no files) still says why in the banner.</summary>
    private void ShowFailedBeforeStart(PairedDeviceUiModel target, string message)
    {
        ActiveTransfer = new ActiveTransfer(
            TransferId: Guid.NewGuid().ToString("N")[..8],
            PeerDeviceId: target.DeviceId,
            PeerDisplayName: target.Name,
            IsIncoming: false,
            Stage: TransferStage.Failed,
            Route: TransferRoute.LanTcp,
            BytesTransferred: 0,
            TotalBytes: 0,
            SpeedBytesPerSecond: 0,
            EstimatedRemaining: null,
            CurrentFileName: "Nothing sent",
            Items: [],
            ErrorMessage: message);
        ActiveTransferChanged?.Invoke(ActiveTransfer);
    }

    public void CancelCurrentTransfer()
    {
        if (ActiveTransfer is null || IsFinished(ActiveTransfer)) return;
        _transferCancellation?.Cancel();
        ActiveTransfer = ActiveTransfer with { Stage = TransferStage.Cancelled, EstimatedRemaining = null };
        ActiveTransferChanged?.Invoke(ActiveTransfer);
    }

    public void DismissFinishedTransfer()
    {
        if (ActiveTransfer is null || !IsFinished(ActiveTransfer)) return;
        ActiveTransfer = null;
        ActiveTransferChanged?.Invoke(null);
    }

    private async Task RecordAsync(ActiveTransfer transfer, VerificationOutcome outcome)
    {
        var record = new TransferRecord(
            transfer.TransferId,
            transfer.PeerDeviceId,
            transfer.PeerDisplayName,
            transfer.IsIncoming,
            DateTimeOffset.UtcNow,
            transfer.TotalBytes,
            outcome,
            transfer.Items,
            ErrorMessage: outcome == VerificationOutcome.Failed ? transfer.ErrorMessage : null);
        await SaveRecordAsync(record).ConfigureAwait(true);
    }

    private async Task SaveRecordAsync(TransferRecord record)
    {
        try
        {
            await _historyStore.UpsertAsync(record).ConfigureAwait(true);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Saving transfer history failed: {error.GetType().Name}");
        }
        // The store replaced a record with the same id (a phone retrying a transfer); so does the list.
        if (History.FirstOrDefault(existing => existing.TransferId == record.TransferId) is { } replaced) History.Remove(replaced);
        History.Insert(0, record);
    }
}
