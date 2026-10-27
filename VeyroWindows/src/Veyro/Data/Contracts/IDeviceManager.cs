namespace Veyro.Data.Contracts;

/// <summary>
/// Paired phones for the sidebar switcher (trust store) with live connection state (session
/// registry), the phone the UI follows, and phones discovery currently sees. UI thread only;
/// events are raised on the UI thread.
/// </summary>
public interface IDeviceManager
{
    ObservableCollection<PairedDeviceUiModel> PairedDevices { get; }

    /// <summary>Unpaired phones seen by discovery. Untrusted: offered for pairing only.</summary>
    ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices { get; }

    PairedDeviceUiModel? ActiveDevice { get; set; }
    event Action<PairedDeviceUiModel?>? ActiveDeviceChanged;

    /// <summary>A paired phone connected or disconnected (argument: device id).</summary>
    event Action<string>? ConnectionChanged;

    /// <summary>Discovery saw, refreshed or lost a phone.</summary>
    event Action? DiscoveryChanged;

    /// <summary>Starts discovery if needed and re-reads its current peers.</summary>
    Task RefreshDiscoveryAsync();

    /// <summary>
    /// Refresh in the device header (ADR-040): dials the phone now, bypassing the phone's own
    /// reconnect grace, and rescans discovery. <see cref="PairedDeviceUiModel.IsConnecting"/> is
    /// true while the dial is in flight. Completes when both are done; never throws.
    /// </summary>
    Task ConnectNowAsync(string deviceId);

    /// <summary>Shows this paired phone on every page (a toast from it was clicked). No effect for an unknown id.</summary>
    void Select(string deviceId);

    /// <summary>Re-reads the trust store, for example after a new device paired or one was removed.</summary>
    void ReloadPairedDevices();

    /// <summary>The latest discovery record for a phone (untrusted), or null.</summary>
    PeerDevice? FindDiscoveredPeer(string deviceId);

    /// <summary>Moves the selection through the paired phones (mouse wheel over the phone).</summary>
    void SelectNext(int delta);

    /// <summary>Addresses discovery currently reports for the phone; empty when it is not seen. Untrusted.</summary>
    IReadOnlyList<string> GetSeenEndpoints(string deviceId);
}
