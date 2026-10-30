namespace Veyro.Core.Link;

/// <summary>How an inbound offer ended, for the Transfers history.</summary>
public enum InboundTransferOutcome
{
    Completed,
    Failed,
    Cancelled,
    Declined,
    /// <summary>Nobody answered the incoming-files prompt in time.</summary>
    TimedOut
}

/// <summary>
/// One file of a finished inbound offer. <paramref name="RelativePath"/> is the path it is (or
/// would have been) saved under, as the sender named it: shown, never trusted for anything else.
/// <paramref name="SavedPath"/> is the absolute path of a file that arrived and verified; it was
/// checked to be inside the destination root when the file was saved. Null otherwise.
/// </summary>
public sealed record InboundTransferReportItem(int Index, string RelativePath, long SizeBytes, string? SavedPath);

/// <summary>
/// An inbound offer this PC did not ask for has ended (<see cref="LinkServer.InboundTransferFinished"/>).
/// File and folder names are user data: never logged.
/// </summary>
/// <param name="FolderName">The saved name of the folder when the offer is one folder (ADR-064).</param>
/// <param name="DestinationRoot">The received-files folder at the time of the offer.</param>
public sealed record InboundTransferReport(
    string PeerDeviceId,
    string TransferId,
    IReadOnlyList<InboundTransferReportItem> Items,
    long TotalBytes,
    string? FolderName,
    InboundTransferOutcome Outcome,
    string DestinationRoot);
