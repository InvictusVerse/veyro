using System.Collections.Specialized;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml.Input;
using Windows.System;
using Windows.UI.Core;

namespace Veyro.Views;

public sealed partial class MessagesPage : Page
{
    public MessagesViewModel ViewModel => VeyroContainer.Instance.MessagesViewModel;

    public MessagesPage()
    {
        InitializeComponent();
    }

    private void Page_Loaded(object sender, RoutedEventArgs e)
    {
        ViewModel.Messages.CollectionChanged += Messages_CollectionChanged;
        ViewModel.Activate();
        ScrollToNewest();
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        ViewModel.Messages.CollectionChanged -= Messages_CollectionChanged;
        ViewModel.Deactivate();
    }

    /// <summary>Follow the conversation when a message is appended; older pages insert above.</summary>
    private void Messages_CollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        if (e.Action == NotifyCollectionChangedAction.Add && e.NewStartingIndex == ViewModel.Messages.Count - 1)
            ScrollToNewest();
    }

    private void ScrollToNewest()
    {
        if (ViewModel.Messages.Count > 0)
            DispatcherQueue.TryEnqueue(() =>
            {
                if (ViewModel.Messages.Count > 0) MessageList.ScrollIntoView(ViewModel.Messages[^1]);
            });
    }

    private async void Thread_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not SmsThreadItemViewModel thread) return;
        await ViewModel.SelectThreadAsync(thread);
        ScrollToNewest();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshAsync();

    private async void LoadOlder_Click(object sender, RoutedEventArgs e) => await ViewModel.LoadOlderAsync();

    private async void Send_Click(object sender, RoutedEventArgs e) => await ViewModel.SendAsync();

    private async void Retry_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is SmsMessageItemViewModel message)
            await ViewModel.RetryAsync(message);
    }

    /// <summary>Copies the whole message body. User data: never logged.</summary>
    private void CopyMessage_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not string { Length: > 0 } body) return;
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(body);
        try { Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package); }
        catch (Exception error) { Core.Diagnostics.VeyroTrace.Warn($"Copying a message failed: {error.GetType().Name}"); }
    }

    private void SimPicker_SelectionChanged(object sender, SelectionChangedEventArgs e)
        => ViewModel.SelectedSim = SimPicker.SelectedItem as SimItem;

    /// <summary>Enter sends; Shift+Enter inserts a new line.</summary>
    private async void ComposeBox_PreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        bool shift = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(CoreVirtualKeyStates.Down);
        if (shift) return;
        e.Handled = true;
        await ViewModel.SendAsync();
    }

    private async void NewMessage_Click(object sender, RoutedEventArgs e)
    {
        var number = new TextBox
        {
            Header = "Phone number",
            PlaceholderText = "+1 555 010 0100",
            InputScope = new Microsoft.UI.Xaml.Input.InputScope
            {
                Names = { new InputScopeName(InputScopeNameValue.TelephoneNumber) }
            }
        };
        var error = new TextBlock
        {
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SystemFillColorCriticalBrush"],
            TextWrapping = TextWrapping.Wrap,
            Visibility = Visibility.Collapsed
        };
        var dialog = new ContentDialog
        {
            Title = "New message",
            Content = new StackPanel { Spacing = 8, Children = { number, error } },
            PrimaryButtonText = "Start",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (ViewModel.StartNewMessage(number.Text) is { } message)
            {
                error.Text = message;
                error.Visibility = Visibility.Visible;
                args.Cancel = true;
            }
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) ComposeBox.Focus(FocusState.Programmatic);
    }
}

/// <summary>Incoming bubbles on the left, outgoing on the right.</summary>
public sealed partial class MessageTemplateSelector : DataTemplateSelector
{
    public DataTemplate? Incoming { get; set; }
    public DataTemplate? Outgoing { get; set; }

    protected override DataTemplate? SelectTemplateCore(object item)
        => item is SmsMessageItemViewModel { IsOutgoing: true } ? Outgoing : Incoming;

    protected override DataTemplate? SelectTemplateCore(object item, DependencyObject container)
        => SelectTemplateCore(item);
}
