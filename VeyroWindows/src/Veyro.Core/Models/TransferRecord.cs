namespace Veyro.Core.Models;

public sealed record TransferRecord(
    string TransferId,
    string PeerDeviceId,
    string PeerDisplayName,
    bool IsIncoming,
    DateTimeOffset Timestamp,
    long TotalBytes,
    VerificationOutcome Verification,
    IReadOnlyList<TransferItem> Items,
    string? DestinationFolder = null,
    string? ErrorMessage = null
);
