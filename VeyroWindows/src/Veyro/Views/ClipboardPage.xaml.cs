using Veyro.Core.Diagnostics;

namespace Veyro.Views;

public sealed partial class ClipboardPage : Page
{
    private DispatcherQueueTimer? _copiedTimer;

    public ClipboardViewModel ViewModel => VeyroContainer.Instance.ClipboardViewModel;

    public ClipboardPage()
    {
        InitializeComponent();
    }

    private static ClipRowViewModel? Row(object sender) => (sender as FrameworkElement)?.Tag as ClipRowViewModel;

    /// <summary>Clicking a row copies it (a code: only the digits).</summary>
    private async void Row_Click(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is ClipRowViewModel row) await CopyAsync(row, codeOnly: true);
    }

    private async void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (Row(sender) is { } row) await CopyAsync(row, codeOnly: false);
    }

    private async Task CopyAsync(ClipRowViewModel row, bool codeOnly)
    {
        try
        {
            if (codeOnly && row.Kind == ClipKind.Code) await ViewModel.CopyCodeAsync(row);
            else await ViewModel.CopyAsync(row);
            ShowCopied();
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Copy failed: {error.GetType().Name}");
        }
    }

    private void ShowCopied()
    {
        CopiedBar.IsOpen = true;
        _copiedTimer ??= DispatcherQueue.CreateTimer();
        _copiedTimer.Interval = TimeSpan.FromSeconds(2);
        _copiedTimer.IsRepeating = false;
        _copiedTimer.Tick -= HideCopied;
        _copiedTimer.Tick += HideCopied;
        _copiedTimer.Start();
    }

    private void HideCopied(DispatcherQueueTimer sender, object args) => CopiedBar.IsOpen = false;

    private async void Open_Click(object sender, RoutedEventArgs e)
    {
        // Only http(s) links are ever offered (ClipboardService.FindLink).
        if (Row(sender)?.Item.Link is { } link) await global::Windows.System.Launcher.LaunchUriAsync(link);
    }

    private async void Email_Click(object sender, RoutedEventArgs e)
    {
        if (Row(sender) is { Kind: ClipKind.Email } row &&
            Uri.TryCreate("mailto:" + row.Preview.Trim(), UriKind.Absolute, out Uri? uri))
        {
            await global::Windows.System.Launcher.LaunchUriAsync(uri);
        }
    }

    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if (Row(sender) is { } row) ViewModel.Remove(row);
    }

    private void Clear_Click(object sender, RoutedEventArgs e) => ViewModel.Clear();

    private void Filter_SelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (sender.SelectedItem?.Tag is string tag && Enum.TryParse(tag, out ClipFilter filter)) ViewModel.Filter = filter;
    }
}
