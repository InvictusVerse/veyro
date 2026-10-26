namespace Veyro.UserControls;

public sealed partial class ActiveTransferBanner : UserControl
{
    public TransfersPageViewModel ViewModel => VeyroContainer.Instance.TransfersPageViewModel;

    public ActiveTransferBanner()
    {
        InitializeComponent();
    }

    private void CancelTransfer_Click(object sender, RoutedEventArgs e)
    {
        ViewModel.CancelTransfer();
    }
}
