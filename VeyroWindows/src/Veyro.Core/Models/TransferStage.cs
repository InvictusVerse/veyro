namespace Veyro.Core.Models;

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
