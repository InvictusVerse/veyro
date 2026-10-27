namespace Veyro.Data.Contracts;

public interface IFileTransferService
{
    ActiveTransfer? ActiveTransfer { get; }
    ObservableCollection<TransferRecord> History { get; }
    event Action<ActiveTransfer?>? ActiveTransferChanged;
    Task SendFilesAsync(PairedDeviceUiModel target, IReadOnlyList<string> filePaths);
    void CancelCurrentTransfer();

    /// <summary>Hides a finished, failed or cancelled transfer from the banner. No effect while one runs.</summary>
    void DismissFinishedTransfer();
}
