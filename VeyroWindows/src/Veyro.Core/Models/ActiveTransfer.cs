namespace Veyro.Core.Models;

public sealed record ActiveTransfer(
    string TransferId,
    string PeerDeviceId,
    string PeerDisplayName,
    bool IsIncoming,
    TransferStage Stage,
    TransferRoute Route,
    long BytesTransferred,
    long TotalBytes,
    long SpeedBytesPerSecond,
    TimeSpan? EstimatedRemaining,
    string? CurrentFileName,
    IReadOnlyList<TransferItem> Items,
    string? ErrorMessage = null
)
{
    public double ProgressFraction => TotalBytes > 0
        ? Math.Clamp((double)BytesTransferred / TotalBytes, 0.0, 1.0)
        : 0.0;
}
