// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Views/CallsPage.xaml.cs, GPL-3.0.
// Modified for Veyro.

using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Veyro.Views;

public sealed partial class CallsPage : Page
{
    public CallsPageViewModel ViewModel => VeyroContainer.Instance.CallsPageViewModel;

    public CallsPage()
    {
        InitializeComponent();
    }

    private void Page_Loaded(object sender, RoutedEventArgs e) => ViewModel.Activate();

    private void Page_Unloaded(object sender, RoutedEventArgs e) => ViewModel.Deactivate();

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshAsync();

    private async void Dial_Click(object sender, RoutedEventArgs e) => await ViewModel.DialAsync();

    private void DialKey_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string key) Append(key);
    }

    // Sefirah: holding or right-clicking 0 types "+".
    private void DialZero_RightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        e.Handled = true;
        Append("+");
    }

    private void Backspace_Click(object sender, RoutedEventArgs e)
    {
        string number = ViewModel.DialNumber ?? string.Empty;
        if (number.Length > 0) ViewModel.DialNumber = number[..^1];
    }

    private void Append(string key)
    {
        string number = ViewModel.DialNumber ?? string.Empty;
        if (number.Length < DialBox.MaxLength) ViewModel.DialNumber = number + key;
        DialBox.SelectionStart = ViewModel.DialNumber?.Length ?? 0;
    }

    private async void DialBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        await ViewModel.DialAsync();
    }

    private async void CallBack_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is CallLogItemViewModel entry) await ViewModel.CallBackAsync(entry);
    }

    private async void Answer_Click(object sender, RoutedEventArgs e) => await ViewModel.AnswerAsync();

    private async void Silence_Click(object sender, RoutedEventArgs e) => await ViewModel.SilenceAsync();

    private async void Decline_Click(object sender, RoutedEventArgs e) => await ViewModel.DeclineAsync();

    private async void End_Click(object sender, RoutedEventArgs e) => await ViewModel.EndAsync();
}
