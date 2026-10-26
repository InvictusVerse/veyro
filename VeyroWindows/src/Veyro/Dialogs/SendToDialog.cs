using Microsoft.UI.Xaml.Automation;
using Veyro.Core.Handoff;

namespace Veyro.Dialogs;

/// <summary>
/// The compact "Send to" chooser for files from the Share sheet (ADR-039): every paired phone with
/// its connection state, plus "All connected phones". Counts and sizes only, never file names.
/// </summary>
internal sealed class SendToDialog : ContentDialog
{
    /// <summary>The user's answer: one phone, or every connected phone.</summary>
    public readonly record struct Choice(string? DeviceId, bool AllConnected);

    private const string AllConnectedTag = "all-connected";
    private readonly RadioButtons _phones = new();

    public SendToDialog(IReadOnlyList<SendCandidate> paired, string? preselectedDeviceId, int fileCount, long totalBytes)
    {
        Title = "Send to";
        PrimaryButtonText = "Send";
        CloseButtonText = "Cancel";
        DefaultButton = ContentDialogButton.Primary;
        if (Application.Current.Resources.TryGetValue("DefaultContentDialogStyle", out object style) && style is Style dialogStyle)
            Style = dialogStyle;

        string files = fileCount == 1 ? "1 file" : $"{fileCount} files";
        var summary = new TextBlock
        {
            Text = $"{files}, {TransferProgressMath.FormatBytes(totalBytes)}",
            TextWrapping = TextWrapping.Wrap
        };

        int selected = -1;
        foreach (SendCandidate phone in paired)
        {
            if (string.Equals(phone.DeviceId, preselectedDeviceId, StringComparison.OrdinalIgnoreCase)) selected = _phones.Items.Count;
            _phones.Items.Add(new RadioButton
            {
                Content = phone.IsConnected ? $"{phone.DisplayName} (connected)" : $"{phone.DisplayName} (not connected)",
                Tag = phone.DeviceId
            });
        }
        int connected = paired.Count(phone => phone.IsConnected);
        _phones.Items.Add(new RadioButton
        {
            Content = connected == 1 ? "All connected phones (1)" : $"All connected phones ({connected})",
            Tag = AllConnectedTag,
            IsEnabled = connected > 0
        });
        _phones.SelectedIndex = selected;
        AutomationProperties.SetName(_phones, "Phone to send to");
        _phones.SelectionChanged += (_, _) => IsPrimaryButtonEnabled = _phones.SelectedIndex >= 0;
        IsPrimaryButtonEnabled = selected >= 0;

        var panel = new StackPanel { Spacing = 12, MinWidth = 320 };
        panel.Children.Add(summary);
        panel.Children.Add(_phones);
        Content = panel;
    }

    /// <summary>Shows the dialog. Null when the user cancelled. UI thread; XamlRoot must be set.</summary>
    public async Task<Choice?> AskAsync()
    {
        if (await ShowAsync() != ContentDialogResult.Primary) return null;
        if (_phones.SelectedItem is not RadioButton { Tag: string tag }) return null;
        return tag == AllConnectedTag ? new Choice(null, true) : new Choice(tag, false);
    }
}
