using Veyro.Core.Data;
using Veyro.Core.Diagnostics;

namespace Veyro.Core.Link;

/// <summary>
/// Removing a phone here removes this PC on the phone too (ADR-042), verified (ADR-057).
/// A connected phone gets <c>unpair</c> now; it forgets this PC and closes, and that close is the
/// confirmation. A phone that is away, or that did not close in time, is told the next time it
/// dials in: its pairing secret is kept for that one purpose (<see cref="ITrustStore.RemoveAndNotifyAsync"/>).
/// </summary>
public static class PeerRemoval
{
    /// <summary>A connected phone told on Remove closes at once; longer means it did not hear it.</summary>
    public static readonly TimeSpan LiveCloseTimeout = TimeSpan.FromSeconds(3);

    /// <summary>Removes <paramref name="deviceId"/>. True when the phone was told now.</summary>
    public static async Task<bool> RemoveAsync(
        ITrustStore trust, IPeerSessionRegistry? sessions, string deviceId, TimeSpan? closeTimeout = null)
    {
        bool told = sessions is not null && await TellNowAsync(sessions, deviceId, closeTimeout ?? LiveCloseTimeout).ConfigureAwait(false);
        if (told) await trust.RemoveAsync(deviceId).ConfigureAwait(false);
        else await trust.RemoveAndNotifyAsync(deviceId).ConfigureAwait(false);
        VeyroTrace.Info(told ? "Phone removed and told" : "Phone removed; it is told when it next connects");
        return told;
    }

    private static async Task<bool> TellNowAsync(IPeerSessionRegistry sessions, string deviceId, TimeSpan timeout)
    {
        if (!sessions.IsConnected(deviceId)) return false;
        var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnClosed(string peerId)
        {
            if (string.Equals(peerId, deviceId, StringComparison.OrdinalIgnoreCase) && !sessions.IsConnected(deviceId))
                closed.TrySetResult();
        }
        sessions.SessionClosed += OnClosed;
        try
        {
            await sessions.SendAsync(deviceId, new PeerMessage.Unpair()).ConfigureAwait(false);
            if (!sessions.IsConnected(deviceId)) return true;
            await closed.Task.WaitAsync(timeout).ConfigureAwait(false);
            return true;
        }
        catch (TimeoutException)
        {
            return false;
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or System.Net.WebSockets.WebSocketException or OperationCanceledException)
        {
            VeyroTrace.Warn($"Unpair notice not sent: {error.GetType().Name}");
            return false;
        }
        finally
        {
            sessions.SessionClosed -= OnClosed;
        }
    }
}
