// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/UserControls/NotificationsControl.xaml.cs, GPL-3.0.
// Modified for Veyro.

using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Veyro.UserControls;

public sealed partial class NotificationsControl : UserControl
{
    public NotificationsViewModel ViewModel => VeyroContainer.Instance.NotificationsViewModel;

    private static ISettingsStore Settings => VeyroContainer.Instance.SettingsStore;
    private bool _loadingQuiet;

    public NotificationsControl()
    {
        InitializeComponent();
        UpdateQuietIcon(Settings.Current);
        Settings.SettingsChanged += OnSettingsChanged;
        Unloaded += (_, _) => Settings.SettingsChanged -= OnSettingsChanged;
    }

    private void OnSettingsChanged(AppSettings settings) => DispatcherQueue.TryEnqueue(() => UpdateQuietIcon(settings));

    /// <summary>Quiet-hours moon while Do not disturb is on, a bell otherwise.</summary>
    private void UpdateQuietIcon(AppSettings settings)
    {
        QuietIcon.Glyph = settings.NotificationPopupsPaused ? "\uE708" : "\uEA8F";
        ToolTipService.SetToolTip(QuietButton, settings.NotificationPopupsPaused ? "Do not disturb is on" : "Do not disturb");
    }

    private void QuietFlyout_Opening(object sender, object e)
    {
        _loadingQuiet = true;
        QuietSwitch.IsOn = Settings.Current.NotificationPopupsPaused;
        QuietMirrorSwitch.IsOn = Settings.Current.QuietWhileMirroring;
        _loadingQuiet = false;
    }

    private async void QuietSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_loadingQuiet) return;
        bool on = QuietSwitch.IsOn;
        await Settings.UpdateAsync(s => s with { NotificationPopupsPaused = on });
    }

    private async void QuietMirrorSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_loadingQuiet) return;
        bool on = QuietMirrorSwitch.IsOn;
        await Settings.UpdateAsync(s => s with { QuietWhileMirroring = on });
    }

    private static NotificationItemViewModel? ItemOf(object sender)
        => (sender as FrameworkElement)?.DataContext as NotificationItemViewModel;

    // Sefirah reveals the card's buttons on hover.
    private void Card_PointerEntered(object sender, PointerRoutedEventArgs e) => SetHoverActionsOpacity(sender as DependencyObject, 1);

    private void Card_PointerExited(object sender, PointerRoutedEventArgs e) => SetHoverActionsOpacity(sender as DependencyObject, 0);

    /// <summary>Keyboard focus on a hidden card button shows the card's buttons.</summary>
    private void HoverAction_GotFocus(object sender, RoutedEventArgs e)
    {
        if (sender is DependencyObject button && VisualTreeHelper.GetParent(button) is UIElement panel) panel.Opacity = 1;
    }

    private static void SetHoverActionsOpacity(DependencyObject? root, double opacity)
    {
        if (root is null) return;
        int count = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < count; i++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, i);
            if (child is FrameworkElement { Tag: "HoverActions" } panel)
            {
                panel.Opacity = opacity;
                return;
            }
            SetHoverActionsOpacity(child, opacity);
        }
    }

    private async void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } item) await ViewModel.DismissAsync(item);
    }

    private async void ClearAll_Click(object sender, RoutedEventArgs e) => await ViewModel.ClearAllAsync();

    private void ClearHistory_Click(object sender, RoutedEventArgs e) => ViewModel.ClearHistory();

    private async void CopyCode_Click(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } item) await ViewModel.CopyCodeAsync(item);
    }

    /// <summary>ADR-063: the card's menu, built on click so it always shows the app's current choice.</summary>
    private void More_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement button || ItemOf(sender) is not { } item) return;
        var popups = new MenuFlyoutItem
        {
            Text = item.PopupsMenuText,
            // Bell to turn pop-ups back on, quiet-hours moon to turn them off.
            Icon = new FontIcon { Glyph = item.Mode == NotificationAppMode.FeedOnly ? "\uEA8F" : "\uE708" }
        };
        popups.Click += async (_, _) => await ViewModel.TogglePopupsAsync(item);
        var menu = new MenuFlyout();
        menu.Items.Add(popups);
        menu.ShowAt(button);
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
