using Veyro.ViewModels.Settings;

namespace Veyro.Views;

public sealed partial class SettingsPage : Page
{
    public GeneralViewModel ViewModel => VeyroContainer.Instance.GeneralViewModel;

    public SettingsPage()
    {
        InitializeComponent();
    }

    private void ResetIdentity_Click(object sender, RoutedEventArgs e)
    {
        ViewModel.ResetIdentity();
    }
}
