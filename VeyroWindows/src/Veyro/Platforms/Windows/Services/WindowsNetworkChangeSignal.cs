using Veyro.Core.Diagnostics;

namespace Veyro.Platforms.Windows.Services;

/// <summary>
/// Reports network changes to the <see cref="SessionKeeper"/> (ADR-040), so a phone that failed
/// to answer on the old network is dialled again without waiting out its backoff.
/// </summary>
public sealed class WindowsNetworkChangeSignal : IWindowsNetworkChangeSignal
{
    private bool _started;

    public event Action? NetworkChanged;

    public void Start()
    {
        if (_started) return;
        try
        {
            global::Windows.Networking.Connectivity.NetworkInformation.NetworkStatusChanged += OnNetworkStatusChanged;
            _started = true;
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Watching network changes failed: {error.GetType().Name}");
        }
    }

    public void Dispose()
    {
        if (!_started) return;
        _started = false;
        try { global::Windows.Networking.Connectivity.NetworkInformation.NetworkStatusChanged -= OnNetworkStatusChanged; }
        catch (Exception) { }
    }

    private void OnNetworkStatusChanged(object sender)
    {
        try { NetworkChanged?.Invoke(); }
        catch (Exception error) { VeyroTrace.Error("Network change handler failed", error); }
    }
}
