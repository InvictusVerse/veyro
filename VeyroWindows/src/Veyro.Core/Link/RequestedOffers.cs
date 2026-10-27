namespace Veyro.Core.Link;

/// <summary>Where an offer the PC asked for is written (a per-session cache folder, never Downloads).</summary>
public sealed record RequestedOfferClaim(string DestinationDirectory);

/// <summary>
/// Offers that answer a request this PC made (<c>offer.requestId</c>, e.g. <c>photoFetch</c>,
/// ADR-034). Only a requestId the handler issued and is still waiting for is claimed; such an offer
/// is accepted without a consent prompt. Anything else takes the normal consent path.
/// </summary>
public interface IRequestedOfferHandler
{
    /// <summary>Session thread. Non-null claims the offer; null leaves it to the normal path.</summary>
    RequestedOfferClaim? TryClaim(string peerId, PeerMessage.TransferOffer offer);

    /// <summary>The claimed transfer finished and verified. Paths are absolute, inside the claim's folder.</summary>
    void OnCompleted(string peerId, string requestId, IReadOnlyList<string> filePaths);

    /// <summary>The claimed transfer failed, was cancelled, or the session ended.</summary>
    void OnFailed(string peerId, string requestId, Exception error);
}
