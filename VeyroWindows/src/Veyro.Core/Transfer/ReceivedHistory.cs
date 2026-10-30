using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Protocol;

namespace Veyro.Core.Transfer;

/// <summary>
/// A finished inbound offer as a Transfers history entry, recorded like a send but incoming.
/// </summary>
public static class ReceivedHistory
{
    /// <summary>Keyed apart from sends, which use their own ids, and per phone.</summary>
    public static string RecordId(InboundTransferReport report) => $"rx-{report.PeerDeviceId}-{report.TransferId}";

    /// <param name="peerName">The phone's shown name (<see cref="TrustedPeer.ShownName"/>).</param>
    public static TransferRecord ToRecord(InboundTransferReport report, string peerName, DateTimeOffset when)
    {
        VerificationOutcome outcome = report.Outcome switch
        {
            InboundTransferOutcome.Completed => VerificationOutcome.Verified,
            InboundTransferOutcome.Cancelled => VerificationOutcome.Cancelled,
            InboundTransferOutcome.Declined or InboundTransferOutcome.TimedOut => VerificationOutcome.Declined,
            _ => VerificationOutcome.Failed
        };
        var items = report.Items
            .Select(item => new TransferItem(
                item.Index,
                item.RelativePath,
                item.SizeBytes,
                string.Empty,
                DestinationPath: item.SavedPath,
                Verification: item.SavedPath is null ? VerificationOutcome.Pending : VerificationOutcome.Verified))
            .ToList();
        string? error = report.Outcome switch
        {
            InboundTransferOutcome.Failed => "The transfer did not finish.",
            InboundTransferOutcome.TimedOut => "Nobody answered in time.",
            _ => null
        };
        return new TransferRecord(
            RecordId(report),
            report.PeerDeviceId,
            peerName,
            IsIncoming: true,
            when,
            report.TotalBytes,
            outcome,
            items,
            DestinationFolder: report.DestinationRoot,
            ErrorMessage: error,
            FolderName: report.FolderName);
    }

    /// <summary>
    /// What Open and Show in folder use for a received entry: the saved folder for a folder, else
    /// the first saved file. Null for a send, or when nothing was saved or the path is not inside
    /// the record's received-files folder.
    /// </summary>
    public static string? SavedTarget(TransferRecord record)
    {
        if (!record.IsIncoming || record.DestinationFolder is not { Length: > 0 } destination) return null;
        if (record.Items.FirstOrDefault(item => item.DestinationPath is not null)?.DestinationPath is not { } first) return null;
        try
        {
            string root = Path.GetFullPath(destination).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            string target = record.FolderName is { } folder && RelativePath.IsValid(folder) && !folder.Contains('/')
                ? Path.GetFullPath(Path.Combine(root, folder))
                : Path.GetFullPath(first);
            return target.StartsWith(root, StringComparison.OrdinalIgnoreCase) ? target : null;
        }
        catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return null;
        }
    }
}
