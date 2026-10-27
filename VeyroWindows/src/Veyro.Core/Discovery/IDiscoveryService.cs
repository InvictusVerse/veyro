using Veyro.Core.Models;

namespace Veyro.Core.Discovery;

public interface IDiscoveryService
{
    IReadOnlyList<PeerDevice> DiscoveredPeers { get; }
    event Action<IReadOnlyList<PeerDevice>>? PeersUpdated;
    Task StartAsync(CancellationToken ct = default);
    Task StopAsync();
}
