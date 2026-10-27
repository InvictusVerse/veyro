namespace Veyro.Core.Models;

public sealed record TransferItem(
    int Index,
    string RelativePath,
    long SizeBytes,
    string Sha256Hex,
    string? MimeType = null,
    string? DestinationPath = null,
    VerificationOutcome Verification = VerificationOutcome.Pending
);
