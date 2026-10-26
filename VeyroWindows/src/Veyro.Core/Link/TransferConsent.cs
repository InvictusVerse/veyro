using Veyro.Core.Diagnostics;

namespace Veyro.Core.Link;

/// <summary>The receiver's answer to an inbound offer (04-PROTOCOL.md 6.4).</summary>
public enum OfferVerdict
{
    Accepted,
    /// <summary>The user chose Decline (or the peer is no longer trusted).</summary>
    Declined,
    /// <summary>Nobody answered the prompt in time.</summary>
    TimedOut,
    /// <summary>Another transfer is running on the session.</summary>
    Busy,
    /// <summary>The receiver could not ask or prepare (no prompt could be shown, no data port).</summary>
    Failed
}

/// <summary>
/// <c>decision.reason</c> values for <c>accept:false</c> (04-PROTOCOL.md 6.4, ADR-036). The field
/// is free text on the wire; these are the values Windows sends so the phone can name the cause.
/// </summary>
public static class OfferDeclineReasons
{
    public const string Declined = "declined";
    public const string Timeout = "timeout";
    public const string Busy = "busy";
    public const string Error = "error";

    public static string For(OfferVerdict verdict) => verdict switch
    {
        OfferVerdict.TimedOut => Timeout,
        OfferVerdict.Busy => Busy,
        OfferVerdict.Failed => Error,
        _ => Declined
    };
}

/// <summary>
/// The UI half of the incoming-files prompt. The app implements it over WinUI and app
/// notifications; tests fake it. File names are user data: implementations never log them.
/// </summary>
public interface ITransferConsentUi
{
    /// <summary>True when the main window is on screen (shown, not minimised), so a dialog would be seen.</summary>
    Task<bool> IsWindowVisibleAsync();

    /// <summary>
    /// In-window prompt; true is Accept. Throws when no dialog can be shown right now (another one
    /// is open, no XamlRoot, the window went away). Cancelling <paramref name="ct"/> closes it.
    /// </summary>
    Task<bool> ShowDialogAsync(InboundOfferRequest request, CancellationToken ct);

    /// <summary>
    /// Actionable notification with Accept and Decline. True or false for the buttons, null when
    /// the user clicked the notification itself (Veyro was brought forward instead of answering).
    /// Cancelling <paramref name="ct"/> removes it.
    /// </summary>
    Task<bool?> ShowNotificationAsync(InboundOfferRequest request, CancellationToken ct);
}

/// <summary>
/// Asks the user about inbound offers one at a time (ADR-036). A visible window gets a dialog; a
/// hidden or minimised one, or a dialog that cannot open, gets an actionable notification.
/// Unanswered within <see cref="Timeout"/> (queue time included) is <see cref="OfferVerdict.TimedOut"/>,
/// never a silent Decline. Logs the verdict and exception types only.
/// </summary>
public sealed class TransferConsentCoordinator
{
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(60);

    private readonly ITransferConsentUi _ui;
    private readonly SemaphoreSlim _queue = new(1, 1);

    public TransferConsentCoordinator(ITransferConsentUi ui, TimeSpan? timeout = null)
    {
        _ui = ui;
        Timeout = timeout ?? DefaultTimeout;
    }

    /// <summary>How long the user has, from the moment the offer arrived.</summary>
    public TimeSpan Timeout { get; }

    public async Task<OfferVerdict> DecideAsync(InboundOfferRequest request)
    {
        using var deadline = new CancellationTokenSource(Timeout);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token, request.Abandoned);
        CancellationToken ct = linked.Token;
        try { await _queue.WaitAsync(ct).ConfigureAwait(false); }
        catch (OperationCanceledException) { return Ended(request); }
        try
        {
            OfferVerdict verdict = await AskAsync(request, ct).ConfigureAwait(false);
            VeyroTrace.Info($"Incoming transfer prompt ended: {verdict}");
            return verdict;
        }
        finally
        {
            _queue.Release();
        }
    }

    private async Task<OfferVerdict> AskAsync(InboundOfferRequest request, CancellationToken ct)
    {
        bool dialogTried = false;
        while (true)
        {
            if (!dialogTried && await IsVisibleAsync().ConfigureAwait(false))
            {
                dialogTried = true;
                try
                {
                    return await _ui.ShowDialogAsync(request, ct).ConfigureAwait(false) ? OfferVerdict.Accepted : OfferVerdict.Declined;
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { return Ended(request); }
                catch (Exception error)
                {
                    // The old prompt turned exactly this into an instant "declined" on the phone.
                    VeyroTrace.Warn($"Transfer dialog unavailable ({error.GetType().Name}); asking with a notification");
                }
            }

            bool? answer;
            try { answer = await _ui.ShowNotificationAsync(request, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return Ended(request); }
            catch (Exception error)
            {
                VeyroTrace.Warn($"Transfer notification failed: {error.GetType().Name}");
                return OfferVerdict.Failed;
            }
            if (answer is { } accepted) return accepted ? OfferVerdict.Accepted : OfferVerdict.Declined;
            // Clicked the notification body: Veyro is in front now, so ask in the window (again
            // with a notification if the dialog already failed once).
            dialogTried = false;
        }
    }

    private async Task<bool> IsVisibleAsync()
    {
        try { return await _ui.IsWindowVisibleAsync().ConfigureAwait(false); }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Window visibility unknown: {error.GetType().Name}");
            return false;
        }
    }

    /// <summary>Phone gave up (its cancel or the session ended) or the deadline passed.</summary>
    private static OfferVerdict Ended(InboundOfferRequest request)
        => request.Abandoned.IsCancellationRequested ? OfferVerdict.Declined : OfferVerdict.TimedOut;
}
