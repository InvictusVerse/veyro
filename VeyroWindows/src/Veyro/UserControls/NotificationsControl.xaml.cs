using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Veyro.UserControls;

/// <summary>
/// The sidebar list of the phone's notifications (ADR-025) with its Do not disturb flyout and
/// the Earlier group (ADR-063). The cards are bound to <see cref="NotificationItemViewModel"/>;
/// this class forwards clicks to <see cref="NotificationsViewModel"/> and handles the
/// quiet-hours switches.
/// </summary>
public sealed partial class NotificationsControl : UserControl
{
    /// <summary>Name of the small tool strip inside every card (see the card template).</summary>
    private const string CardToolsName = "CardTools";

    private static ISettingsStore Settings => VeyroContainer.Instance.SettingsStore;

    // Set while the Do not disturb flyout copies the stored values into its switches, so those
    // programmatic changes are not written back.
    private bool _fillingQuietSwitches;

    public NotificationsViewModel ViewModel => VeyroContainer.Instance.NotificationsViewModel;

    public NotificationsControl()
    {
        InitializeComponent();
        ShowQuietState(Settings.Current);
        Settings.SettingsChanged += OnSettingsChanged;
        Unloaded += (_, _) => Settings.SettingsChanged -= OnSettingsChanged;
    }

    // ---- Do not disturb ------------------------------------------------------------------

    private void OnSettingsChanged(AppSettings settings) => DispatcherQueue.TryEnqueue(() => ShowQuietState(settings));

    /// <summary>A moon while pop-ups are paused, a bell otherwise.</summary>
    private void ShowQuietState(AppSettings settings)
    {
        bool paused = settings.NotificationPopupsPaused;
        QuietIcon.Glyph = paused ? "\uE708" : "\uEA8F";
        ToolTipService.SetToolTip(QuietButton, paused ? "Do not disturb is on" : "Do not disturb");
    }

    private void QuietFlyout_Opening(object sender, object e)
    {
        _fillingQuietSwitches = true;
        try
        {
            QuietSwitch.IsOn = Settings.Current.NotificationPopupsPaused;
            QuietMirrorSwitch.IsOn = Settings.Current.QuietWhileMirroring;
        }
        finally
        {
            _fillingQuietSwitches = false;
        }
    }

    private async void QuietSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_fillingQuietSwitches) return;
        bool paused = QuietSwitch.IsOn;
        await Settings.UpdateAsync(s => s with { NotificationPopupsPaused = paused });
    }

    private async void QuietMirrorSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_fillingQuietSwitches) return;
        bool quiet = QuietMirrorSwitch.IsOn;
        await Settings.UpdateAsync(s => s with { QuietWhileMirroring = quiet });
    }

    // ---- List-level actions --------------------------------------------------------------

    private async void ClearAll_Click(object sender, RoutedEventArgs e) => await ViewModel.ClearAllAsync();

    private void ClearHistory_Click(object sender, RoutedEventArgs e) => ViewModel.ClearHistory();

    // ---- Card tools: shown on hover, or while one of them has keyboard focus -------------

    private void Card_PointerEntered(object sender, PointerRoutedEventArgs e) => ShowCardTools(sender, visible: true);

    private void Card_PointerExited(object sender, PointerRoutedEventArgs e) => ShowCardTools(sender, visible: false);

    /// <summary>A tool reached by keyboard reveals its strip, which stays shown until the pointer passes over the card.</summary>
    private void CardTool_GotFocus(object sender, RoutedEventArgs e)
    {
        if (sender is DependencyObject tool && VisualTreeHelper.GetParent(tool) is UIElement strip) strip.Opacity = 1;
    }

    private static void ShowCardTools(object card, bool visible)
    {
        if (card is DependencyObject root && FindNamed(root, CardToolsName) is { } strip)
            strip.Opacity = visible ? 1 : 0;
    }

    /// <summary>Depth-first search of the visual tree under <paramref name="root"/> for an element with the given Name.</summary>
    private static FrameworkElement? FindNamed(DependencyObject root, string name)
    {
        int children = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < children; i++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, i);
            if (child is FrameworkElement element && element.Name == name) return element;
            if (FindNamed(child, name) is { } found) return found;
        }
        return null;
    }

    // ---- Per-card actions ----------------------------------------------------------------

    private static NotificationItemViewModel? CardOf(object sender)
        => (sender as FrameworkElement)?.DataContext as NotificationItemViewModel;

    private async void CardDismiss_Click(object sender, RoutedEventArgs e)
    {
        if (CardOf(sender) is { } card) await ViewModel.DismissAsync(card);
    }

    private async void CopyCode_Click(object sender, RoutedEventArgs e)
    {
        if (CardOf(sender) is { } card) await ViewModel.CopyCodeAsync(card);
    }

    /// <summary>ADR-063: the card's menu is built on click so it always shows the app's current choice.</summary>
    private void CardMenu_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement anchor || CardOf(sender) is not { } card) return;

        // A bell turns pop-ups back on; the quiet-hours moon turns them off.
        string icon = card.Mode == NotificationAppMode.FeedOnly ? "\uEA8F" : "\uE708";
        var item = new MenuFlyoutItem { Text = card.PopupsMenuText, Icon = new FontIcon { Glyph = icon } };
        item.Click += async (_, _) => await ViewModel.TogglePopupsAsync(card);

        var menu = new MenuFlyout();
        menu.Items.Add(item);
        menu.ShowAt(anchor);
    }

    private async void ReplySend_Click(object sender, RoutedEventArgs e)
    {
        if (CardOf(sender) is { } card) await ViewModel.SendReplyAsync(card);
    }

    private async void ReplyBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter || CardOf(sender) is not { } card) return;
        e.Handled = true;
        await ViewModel.SendReplyAsync(card);
    }

    /// <summary>One of the phone's own action buttons; its DataContext is the action, not the card.</summary>
    private async void CardAction_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is NotificationActionItem action)
            await ViewModel.InvokeActionAsync(action);
    }
}
