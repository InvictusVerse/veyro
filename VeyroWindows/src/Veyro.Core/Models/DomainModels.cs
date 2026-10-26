namespace Veyro.Core.Models;

public enum PlatformType
{
    Android,
    Windows,
    Unknown
}

public enum TransferRoute
{
    WifiDirect,
    LanTcp,
    WebSocket
}

public enum TransferStage
{
    Preparing,
    Connecting,
    AwaitingConsent,
    Transferring,
    Verifying,
    Publishing,
    RecordingHistory,
    Completed,
    Failed,
    Cancelled
}

public enum VerificationOutcome
{
    Pending,
    Verified,
    Failed
}

public sealed record PeerEndpoint(string Host, int Port, string Source = "LAN");

public sealed record PeerDevice(
    string DeviceId,
    string DisplayName,
    PlatformType Platform,
    IReadOnlyList<PeerEndpoint> Endpoints,
    bool IsTrusted,
    bool IsConnected,
    string? Fingerprint = null,
    DateTimeOffset LastSeen = default
);

public sealed record TrustedPeer(
    string DeviceId,
    string DisplayName,
    PlatformType Platform,
    string PublicKeyBase64,
    string PairingSecretBase64,
    bool AutoAcceptTransfers,
    DateTimeOffset PairedAt,
    DateTimeOffset? LastConnectedAt = null
);

public sealed record TransferItem(
    int Index,
    string RelativePath,
    long SizeBytes,
    string Sha256Hex,
    string? MimeType = null,
    string? DestinationPath = null,
    VerificationOutcome Verification = VerificationOutcome.Pending
);

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
