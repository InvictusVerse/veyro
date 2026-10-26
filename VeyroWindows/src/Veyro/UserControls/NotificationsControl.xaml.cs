using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Veyro.UserControls;

public sealed partial class NotificationsControl : UserControl
{
    public NotificationsViewModel ViewModel => VeyroContainer.Instance.NotificationsViewModel;

    public NotificationsControl()
    {
        InitializeComponent();
    }

    private static NotificationItemViewModel? ItemOf(object sender)
        => (sender as FrameworkElement)?.DataContext as NotificationItemViewModel;

    private async void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } item) await ViewModel.DismissAsync(item);
    }

    private void ReplyToggle_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } item) item.IsReplyOpen = !item.IsReplyOpen;
    }

    private async void SendReply_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } item) await ViewModel.SendReplyAsync(item);
    }

    private async void ReplyBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter || ItemOf(sender) is not { } item) return;
        e.Handled = true;
        await ViewModel.SendReplyAsync(item);
    }

    private async void Action_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is NotificationActionItem action)
            await ViewModel.InvokeActionAsync(action);
    }
}
