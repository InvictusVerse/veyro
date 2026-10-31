namespace Veyro.Views;

/// <summary>
/// Pairing with a phone that discovery found, shared by the Classic and Modern shells (ADR-074).
/// Discovery is untrusted: the phone's key must match the id it advertised, and the user compares
/// the code on both screens and accepts on the phone (04-PROTOCOL.md 4.3).
/// </summary>
internal static class PairingFlow
{
    public static async Task PairWithDiscoveredAsync(XamlRoot xamlRoot, DispatcherQueue dispatcher, string deviceId, MainPageViewModel viewModel)
    {
        var container = VeyroContainer.Instance;
        PeerDevice? phone = container.DeviceManager.FindDiscoveredPeer(deviceId);
        PeerEndpoint? endpoint = phone?.Endpoints.FirstOrDefault();
        if (phone is null || endpoint is null)
        {
            viewModel.RequestPairing();
            return;
        }

        using var cancel = new CancellationTokenSource();
        var code = new TextBlock
        {
            FontSize = 40,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            HorizontalAlignment = HorizontalAlignment.Center,
            CharacterSpacing = 120,
            Text = "..."
        };
        var status = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            HorizontalAlignment = HorizontalAlignment.Center,
            TextAlignment = TextAlignment.Center,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            Text = $"Connecting to {phone.DisplayName}..."
        };
        var dialog = new ContentDialog
        {
            Title = $"Pair with {phone.DisplayName}",
            Content = new StackPanel { Spacing = 16, MinWidth = 320, Children = { code, status } },
            CloseButtonText = "Cancel",
            XamlRoot = xamlRoot
        };
        dialog.Closed += (_, _) => cancel.Cancel();

        Task<PcPairResult> pairing = container.LinkServer.PairWithPhoneAsync(
            endpoint,
            deviceId,
            showCode: digits => dispatcher.TryEnqueue(() =>
            {
                code.Text = digits.Length == 6 ? $"{digits[..3]} {digits[3..]}" : digits;
                status.Text = $"Check that {phone.DisplayName} shows the same code, then tap Accept on the phone.";
            }),
            cancel.Token);
        _ = dialog.ShowAsync();

        PcPairResult result = await pairing;
        if (result.Outcome == PcPairOutcome.Paired)
        {
            dialog.Hide();
            container.DeviceManager.ReloadPairedDevices();
            return;
        }
        if (result.Outcome == PcPairOutcome.Cancelled) return;
        code.Text = string.Empty;
        status.Text = result.Outcome switch
        {
            PcPairOutcome.Declined => "Pairing was declined on the phone.",
            PcPairOutcome.TimedOut => "Nobody accepted on the phone in time. Try again.",
            PcPairOutcome.Unreachable => "The phone could not be reached. Keep Veyro open on it and both devices on the same Wi-Fi.",
            PcPairOutcome.Busy => "The phone is busy with another pairing. Try again in a moment.",
            PcPairOutcome.IdentityMismatch => "The device that answered is not the phone you picked. Nothing was saved.",
            PcPairOutcome.Refused => "The phone refused to pair. Update Veyro on the phone and try again.",
            _ => "Pairing failed. Try again, or pair from the phone instead."
        };
        dialog.CloseButtonText = "Close";
    }

    /// <summary>Files and whole folders dropped on a shell go to the shown phone (ADR-064).</summary>
    public static async Task SendDroppedAsync(Microsoft.UI.Xaml.DragEventArgs e)
    {
        var items = await e.DataView.GetStorageItemsAsync();
        var paths = items
            .Where(item => item is Windows.Storage.StorageFile or Windows.Storage.StorageFolder)
            .Select(item => item.Path)
            .Where(p => !string.IsNullOrEmpty(p))
            .ToList();
        if (paths.Count > 0) await VeyroContainer.Instance.TransfersPageViewModel.SendFilesToActiveDeviceAsync(paths);
    }
}
