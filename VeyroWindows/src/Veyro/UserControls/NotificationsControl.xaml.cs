// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/UserControls/NotificationsControl.xaml.cs, GPL-3.0.
// Modified for Veyro.

using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
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

    // Sefirah reveals the card's buttons on hover.
    private void Card_PointerEntered(object sender, PointerRoutedEventArgs e) => SetDismissOpacity(sender as DependencyObject, 1);

    private void Card_PointerExited(object sender, PointerRoutedEventArgs e) => SetDismissOpacity(sender as DependencyObject, 0);

    private void DismissButton_GotFocus(object sender, RoutedEventArgs e)
    {
        if (sender is UIElement button) button.Opacity = 1;
    }

    private static void SetDismissOpacity(DependencyObject? root, double opacity)
    {
        if (root is null) return;
        int count = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < count; i++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, i);
            if (child is Button { Tag: "DismissButton" } button)
            {
                button.Opacity = opacity;
                return;
            }
            SetDismissOpacity(child, opacity);
        }
    }

    private async void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } item) await ViewModel.DismissAsync(item);
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
