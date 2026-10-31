using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Veyro.Views;

/// <summary>Call log, call in progress and dial pad. All state and actions are in <see cref="CallsPageViewModel"/>.</summary>
public sealed partial class CallsPage : Page
{
    public CallsPage()
    {
        InitializeComponent();
    }

    public CallsPageViewModel ViewModel => VeyroContainer.Instance.CallsPageViewModel;

    private void Page_Loaded(object sender, RoutedEventArgs e) => ViewModel.Activate();

    private void Page_Unloaded(object sender, RoutedEventArgs e) => ViewModel.Deactivate();

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshAsync();

    // ---- call in progress ----------------------------------------------------------------

    private async void Answer_Click(object sender, RoutedEventArgs e) => await ViewModel.AnswerAsync();

    private async void Silence_Click(object sender, RoutedEventArgs e) => await ViewModel.SilenceAsync();

    private async void Decline_Click(object sender, RoutedEventArgs e) => await ViewModel.DeclineAsync();

    private async void End_Click(object sender, RoutedEventArgs e) => await ViewModel.EndAsync();

    // ---- dial pad ------------------------------------------------------------------------

    private async void Dial_Click(object sender, RoutedEventArgs e) => await ViewModel.DialAsync();

    private async void DialBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        await ViewModel.DialAsync();
    }

    private void Key_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string key }) TypeIntoDialBox(key);
    }

    /// <summary>Right-clicking the 0 key types a plus sign, as on a phone's long press.</summary>
    private void ZeroKey_RightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        e.Handled = true;
        TypeIntoDialBox("+");
    }

    private void Backspace_Click(object sender, RoutedEventArgs e)
    {
        string typed = ViewModel.DialNumber;
        if (typed.Length > 0) ViewModel.DialNumber = typed[..^1];
    }

    private void TypeIntoDialBox(string text)
    {
        string typed = ViewModel.DialNumber;
        if (typed.Length + text.Length <= DialBox.MaxLength) ViewModel.DialNumber = typed + text;
        DialBox.SelectionStart = ViewModel.DialNumber.Length;
    }

    // ---- call log ------------------------------------------------------------------------

    private async void CallBack_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: CallLogItemViewModel entry }) await ViewModel.CallBackAsync(entry);
    }
}
