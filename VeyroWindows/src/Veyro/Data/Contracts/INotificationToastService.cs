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

    /// <summary>
    /// A plain status toast (ADR-047): what Veyro did while its window was closed, such as files
    /// sent from the Share sheet or Explorer. A later toast with the same <paramref name="tag"/>
    /// replaces it. Clicking it opens Veyro. Never throws.
    /// </summary>
    void ShowStatus(string title, string text, string? tag = null);

    /// <summary>
    /// A status toast that removes itself from Windows notifications after
    /// <paramref name="lifetime"/> (send and receive results, 1.0.5). Never throws.
    /// </summary>
    void ShowStatus(string title, string text, string? tag, TimeSpan lifetime);

    /// <summary>
    /// Files from a phone arrived (1.0.5): Open (one file) and Show in folder. The paths stay in
    /// Veyro; the toast only carries a random token. Removed after <paramref name="lifetime"/>.
    /// Never throws.
    /// </summary>
    void ShowReceived(string title, string text, string? openPath, string? folderOrFilePath, TimeSpan lifetime);
}
