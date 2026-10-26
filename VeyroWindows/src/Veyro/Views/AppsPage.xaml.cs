namespace Veyro.Views;

public sealed partial class AppsPage : Page
{
    public AppsViewModel ViewModel => VeyroContainer.Instance.AppsViewModel;

    public AppsPage()
    {
        InitializeComponent();
    }
}
