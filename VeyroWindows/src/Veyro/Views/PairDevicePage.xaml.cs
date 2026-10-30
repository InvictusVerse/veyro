namespace Veyro.Views;

/// <summary>
/// Settings > Devices > Pair new device (ADR-030): the same pairing screen as onboarding. The
/// pairing window is open only while this page is shown.
/// </summary>
public sealed partial class PairDevicePage : Page
{
    private PairingViewModel ViewModel => VeyroContainer.Instance.PairingViewModel;

    public PairDevicePage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        ViewModel.Paired += ViewModel_Paired;
        ShowPairing();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        ViewModel.Paired -= ViewModel_Paired;
        ViewModel.Stop();
        base.OnNavigatedFrom(e);
    }

    private void ShowPairing()
    {
        PairedInfoBar.IsOpen = false;
        Pairing.Visibility = Visibility.Visible;
        ViewModel.Start();
    }

    private void ViewModel_Paired(TrustedPeer peer)
    {
        string fingerprint = DeviceId.ParseOrNull(peer.DeviceId) is { } id ? DeviceId.FormatFingerprint(id) : peer.DeviceId;
        PairedInfoBar.Message = $"{peer.ShownName} ({fingerprint}) can now connect to this PC.";
        PairedInfoBar.IsOpen = true;
        Pairing.Visibility = Visibility.Collapsed;
    }

    private void PairAnother_Click(object sender, RoutedEventArgs e) => ShowPairing();

    private void Back_Click(object sender, RoutedEventArgs e)
    {
        if (Frame.CanGoBack) Frame.GoBack();
    }
}
