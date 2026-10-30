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
    string? ErrorMessage = null,
    // ADR-064: the saved name of a received folder, for "Folder X, N files". Older records have none.
    string? FolderName = null
);
