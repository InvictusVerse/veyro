namespace Veyro.Data.Contracts;

/// <summary>Shows mirrored phone notifications as Windows toasts and routes toast input back.</summary>
public interface INotificationToastService : IDisposable
{
    /// <summary>Registers for toast activation. Call once at startup, before any toast is shown.</summary>
    void Initialize();

    /// <summary>
    /// Incoming-files toast (ADR-036, ADR-038): Accept (and accept this phone's files from now on,
    /// the pre-ticked default), Only this time, Decline. Null when the user clicked the toast body
    /// (Veyro is shown). Throws when toasts are unavailable; cancelling <paramref name="ct"/>
    /// removes the toast. Counts only, never file names.
    /// </summary>
    Task<ConsentAnswer?> ShowTransferRequestAsync(string peerName, int fileCount, long totalBytes, CancellationToken ct);
}
