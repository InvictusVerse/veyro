namespace Veyro.Data.Contracts;

/// <summary>
/// Network changes for the reconnect backoff (ADR-040). Windows: NetworkInformation.NetworkStatusChanged.
/// </summary>
public interface IWindowsNetworkChangeSignal : INetworkChangeSignal, IDisposable
{
    void Start();
}
