using Veyro.Core.Models;

namespace Veyro.Core.Discovery;

public interface IDiscoveryService
{
    IReadOnlyList<PeerDevice> DiscoveredPeers { get; }
    event Action<IReadOnlyList<PeerDevice>>? PeersUpdated;
    Task StartAsync(CancellationToken ct = default);
    Task StopAsync();
}

public interface ITransferService
{
    ActiveTransfer? CurrentTransfer { get; }
    event Action<ActiveTransfer?>? TransferStateChanged;
    Task SendFilesAsync(string peerDeviceId, IReadOnlyList<string> filePaths, CancellationToken ct = default);
    void CancelActiveTransfer();
}
