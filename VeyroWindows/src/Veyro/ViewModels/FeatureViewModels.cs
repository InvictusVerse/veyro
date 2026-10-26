using System.Collections.Specialized;
using System.Globalization;

namespace Veyro.ViewModels;

/// <summary>
/// Transfers page and the active-transfer banner. History is the persisted transfer store;
/// progress, speed and remaining time are only what the running transfer measured.
/// </summary>
public sealed class TransfersPageViewModel : BaseViewModel
{
    private readonly IDeviceManager _deviceManager;
    private readonly IFileTransferService _transferService;
    private readonly ISettingsStore _settingsStore;
    private ActiveTransfer? _activeTransfer;
    private string? _hiddenTransferId;

    public ObservableCollection<TransferHistoryItemViewModel> History { get; } = [];
    public bool HasNoHistory => History.Count == 0;
    public ObservableCollection<PairedDeviceUiModel> PairedDevices => _deviceManager.PairedDevices;

    public string DownloadDirectory => _settingsStore.Current.DownloadDirectory;

    public ActiveTransfer? ActiveTransfer
    {
        get => _activeTransfer;
        private set
        {
            if (SetProperty(ref _activeTransfer, value))
            {
                foreach (string name in new[]
                         {
                             nameof(HasActiveTransfer), nameof(IsTransferRunning), nameof(IsTransferFinished), nameof(BannerTitle),
                             nameof(BannerStage), nameof(BannerProgressText), nameof(BannerProgress), nameof(BannerError), nameof(HasBannerError),
                             nameof(BannerGlyph), nameof(IsBannerVisible), nameof(IsTransferSucceeded), nameof(DismissLabel)
                         })
                {
                    OnPropertyChanged(name);
                }
            }
        }
    }

    public bool HasActiveTransfer => ActiveTransfer is not null;
    public bool IsTransferFinished => ActiveTransfer?.Stage is TransferStage.Completed or TransferStage.Failed or TransferStage.Cancelled;
    public bool IsTransferRunning => HasActiveTransfer && !IsTransferFinished;

    /// <summary>Completed: the banner slides out on its own (ADR-038). Failures stay until dismissed.</summary>
    public bool IsTransferSucceeded => ActiveTransfer?.Stage == TransferStage.Completed;

    /// <summary>The banner is shown unless the user hid this transfer's banner.</summary>
    public bool IsBannerVisible => ActiveTransfer is { } transfer && transfer.TransferId != _hiddenTransferId;

    public string DismissLabel => IsTransferRunning ? "Hide (the transfer continues)" : "Dismiss";

    /// <summary>
    /// The banner's close button. A finished transfer is cleared; a running one keeps going and
    /// only its banner is hidden (progress stays on the Transfers tab history when it ends).
    /// </summary>
    public void HideBanner()
    {
        if (ActiveTransfer is not { } transfer) return;
        if (IsTransferFinished)
        {
            DismissTransfer();
            return;
        }
        _hiddenTransferId = transfer.TransferId;
        OnPropertyChanged(nameof(IsBannerVisible));
    }

    public string BannerTitle => ActiveTransfer is { } transfer
        ? $"{(transfer.IsIncoming ? "From" : "To")} {transfer.PeerDisplayName}: {transfer.CurrentFileName}"
        : string.Empty;

    public string BannerStage => ActiveTransfer?.Stage switch
    {
        TransferStage.Preparing => "Preparing",
        TransferStage.Connecting => "Connecting to the phone",
        TransferStage.AwaitingConsent => "Waiting for the phone to accept",
        TransferStage.Transferring => "Sending over LAN TCP",
        TransferStage.Verifying => "Verifying",
        TransferStage.Publishing or TransferStage.RecordingHistory => "Finishing",
        TransferStage.Completed => "Sent and verified (SHA-256)",
        TransferStage.Failed => "Failed",
        TransferStage.Cancelled => "Cancelled",
        _ => string.Empty
    };

    public string BannerGlyph => ActiveTransfer?.Stage switch
    {
        TransferStage.Completed => "\uE73E",
        TransferStage.Failed => "\uE783",
        TransferStage.Cancelled => "\uE711",
        _ => "\uE898"
    };

    public double BannerProgress => ActiveTransfer?.ProgressFraction ?? 0;

    public string BannerProgressText
    {
        get
        {
            if (ActiveTransfer is not { } transfer) return string.Empty;
            var parts = new List<string>
            {
                $"{TransferProgressMath.FormatBytes(transfer.BytesTransferred)} of {TransferProgressMath.FormatBytes(transfer.TotalBytes)}"
            };
            if (IsTransferRunning && transfer.SpeedBytesPerSecond > 0)
                parts.Add($"{TransferProgressMath.FormatBytes(transfer.SpeedBytesPerSecond)}/s");
            if (IsTransferRunning && transfer.EstimatedRemaining is { } remaining)
                parts.Add($"{TransferProgressMath.FormatDuration(remaining)} left");
            return string.Join("  ·  ", parts);
        }
    }

    public string BannerError => ActiveTransfer?.Stage == TransferStage.Failed ? ActiveTransfer.ErrorMessage ?? string.Empty : string.Empty;
    public bool HasBannerError => !string.IsNullOrEmpty(BannerError);

    public TransfersPageViewModel(
        IDeviceManager deviceManager,
        IFileTransferService transferService,
        ISettingsStore settingsStore)
    {
        _deviceManager = deviceManager;
        _transferService = transferService;
        _settingsStore = settingsStore;
        _activeTransfer = _transferService.ActiveTransfer;

        _transferService.ActiveTransferChanged += t => ActiveTransfer = t;
        _transferService.History.CollectionChanged += OnHistoryChanged;
        History.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoHistory));
        // ADR-040: history follows the phone shown, like every other page.
        _deviceManager.ActiveDeviceChanged += _ => RebuildHistory();
        RebuildHistory();
    }

    /// <summary>The shown phone's transfers; every transfer when no phone is shown.</summary>
    private bool IsShown(TransferRecord record)
        => _deviceManager.ActiveDevice is not { } device ||
           string.Equals(record.PeerDeviceId, device.DeviceId, StringComparison.OrdinalIgnoreCase);

    public async Task SendFilesToActiveDeviceAsync(IReadOnlyList<string> filePaths)
    {
        var target = _deviceManager.ActiveDevice;
        if (target is null) return;
        await _transferService.SendFilesAsync(target, filePaths);
    }

    public void CancelTransfer() => _transferService.CancelCurrentTransfer();

    public void DismissTransfer() => _transferService.DismissFinishedTransfer();

    private void OnHistoryChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        // New records are inserted at the top (FileTransferService.History.Insert(0, ...)).
        if (e.Action == NotifyCollectionChangedAction.Add && e.NewStartingIndex == 0 && e.NewItems is { } added)
        {
            int index = 0;
            foreach (TransferRecord record in added)
            {
                if (IsShown(record)) History.Insert(index++, new TransferHistoryItemViewModel(record));
            }
            return;
        }
        RebuildHistory();
    }

    private void RebuildHistory()
    {
        History.Clear();
        foreach (TransferRecord record in _transferService.History)
        {
            if (IsShown(record)) History.Add(new TransferHistoryItemViewModel(record));
        }
    }
}

/// <summary>One past transfer, worded from the stored record.</summary>
public sealed class TransferHistoryItemViewModel(TransferRecord record)
{
    public TransferRecord Record { get; } = record;

    public string Title => Record.Items.Count switch
    {
        0 => "No files",
        1 => Record.Items[0].RelativePath,
        _ => $"{Record.Items[0].RelativePath} and {Record.Items.Count - 1} more"
    };

    public string Detail
    {
        get
        {
            string direction = Record.IsIncoming ? $"From {Record.PeerDisplayName}" : $"To {Record.PeerDisplayName}";
            string when = Record.Timestamp.ToLocalTime().ToString("g", CultureInfo.CurrentCulture);
            return $"{direction}  ·  {TransferProgressMath.FormatBytes(Record.TotalBytes)}  ·  {when}";
        }
    }

    public string OutcomeText => Record.Verification switch
    {
        VerificationOutcome.Verified => "Verified",
        VerificationOutcome.Failed => "Failed",
        _ => "Not verified"
    };

    public string Glyph => Record.IsIncoming ? "\uE896" : "\uE898";
    public string OutcomeGlyph => Record.Verification == VerificationOutcome.Verified ? "\uE73E" : "\uE783";
    public string ErrorText => Record.ErrorMessage ?? string.Empty;
    public bool HasError => !string.IsNullOrEmpty(Record.ErrorMessage);
}
