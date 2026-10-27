namespace Veyro.Data.Contracts;

/// <summary>This PC's Wi-Fi signal, published to the phone (ADR-038). Windows: NetworkInformation.</summary>
public interface IWindowsNetworkSource : IPcNetworkSource, IDisposable
{
    void Start();
}
