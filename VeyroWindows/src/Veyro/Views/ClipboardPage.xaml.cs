using Veyro.Core.Diagnostics;

namespace Veyro.Views;

public sealed partial class ClipboardPage : Page
{
    public ClipboardViewModel ViewModel => VeyroContainer.Instance.ClipboardViewModel;

    public ClipboardPage()
    {
        InitializeComponent();
    }

    private static ClipRowViewModel? Row(object sender) => (sender as FrameworkElement)?.Tag as ClipRowViewModel;

    private async void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (Row(sender) is not { } row) return;
        try { await ViewModel.CopyAsync(row); }
        catch (Exception error) { VeyroTrace.Warn($"Copy failed: {error.GetType().Name}"); }
    }

    private async void Open_Click(object sender, RoutedEventArgs e)
    {
        // Only http(s) links are ever offered (ClipboardService.FindLink).
        if (Row(sender)?.Item.Link is { } link) await global::Windows.System.Launcher.LaunchUriAsync(link);
    }

    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if (Row(sender) is { } row) ViewModel.Remove(row);
    }

    private void Clear_Click(object sender, RoutedEventArgs e) => ViewModel.Clear();
}
