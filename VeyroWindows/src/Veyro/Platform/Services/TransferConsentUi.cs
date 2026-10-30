using Veyro.Core.Diagnostics;

namespace Veyro.Platform.Services;

/// <summary>
/// WinUI side of the incoming-files prompt (ADR-036): a ContentDialog when the window is on
/// screen, an actionable toast otherwise. Never opens a second dialog while one is open; throws
/// instead, so <see cref="TransferConsentCoordinator"/> falls back to the toast. Shows the sender
/// and counts only; file names are neither shown in the toast nor logged.
/// </summary>
public sealed class TransferConsentUi(INotificationToastService toasts) : ITransferConsentUi
{
    /// <summary>A dialog that closes on its own faster than this was not answered by a person.</summary>
    private static readonly TimeSpan MinimumHumanAnswer = TimeSpan.FromMilliseconds(400);

    public Task<bool> IsWindowVisibleAsync()
        => OnUiThreadAsync(() => App.MainWindow is { } window && App.IsWindowOnScreen(window));

    public Task<ConsentAnswer> ShowDialogAsync(InboundOfferRequest request, CancellationToken ct)
    {
        var completion = new TaskCompletionSource<ConsentAnswer>(TaskCreationOptions.RunContinuationsAsynchronously);
        DispatcherQueue? dispatcher = App.MainWindow?.DispatcherQueue;
        if (dispatcher is null || !dispatcher.TryEnqueue(() => _ = RunDialogAsync(request, completion, ct)))
            completion.TrySetException(new InvalidOperationException("The window is not available."));
        return completion.Task;
    }

    public Task<ConsentAnswer?> ShowNotificationAsync(InboundOfferRequest request, CancellationToken ct)
        => toasts.ShowTransferRequestAsync(
            request.Peer.DisplayName,
            request.Offer.Items.Count(item => !item.IsDir),
            request.Offer.TotalBytes,
            ct);

    private static async Task RunDialogAsync(InboundOfferRequest request, TaskCompletionSource<ConsentAnswer> completion, CancellationToken ct)
    {
        if (!App.TryEnterDialog())
        {
            completion.TrySetException(new InvalidOperationException("Another dialog is open."));
            return;
        }
        try
        {
            XamlRoot root = App.MainWindow.Content?.XamlRoot
                ?? throw new InvalidOperationException("The window has no XamlRoot.");
            int count = request.Offer.Items.Count(item => !item.IsDir);
            bool? clicked = null;
            // ADR-038: pre-ticked, so the first file prompts and later ones arrive on their own.
            var always = new CheckBox
            {
                Content = "Always accept from this phone",
                IsChecked = true
            };
            var dialog = new ContentDialog
            {
                Title = "Incoming files",
                Content = new StackPanel
                {
                    Spacing = 12,
                    Children =
                    {
                        new TextBlock
                        {
                            // ADR-064: "Pixel wants to send the Photos folder, 120 files (1.2 GB)."
                            Text = InboundFolderLayout.FolderNameOf(request.Offer.Items) is { } folder
                                ? $"{request.Peer.DisplayName} wants to send the {OutgoingPlan.FolderSummary(folder, count)} ({TransferProgressMath.FormatBytes(request.Offer.TotalBytes)})."
                                : $"{request.Peer.DisplayName} wants to send {(count == 1 ? "1 file" : $"{count:N0} files")} ({TransferProgressMath.FormatBytes(request.Offer.TotalBytes)}).",
                            TextWrapping = TextWrapping.Wrap
                        },
                        always,
                        new TextBlock
                        {
                            Text = "You can change this later in Settings > Devices.",
                            TextWrapping = TextWrapping.Wrap,
                            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"]
                        }
                    }
                },
                PrimaryButtonText = "Accept",
                CloseButtonText = "Decline",
                DefaultButton = ContentDialogButton.Primary,
                XamlRoot = root
            };
            dialog.PrimaryButtonClick += (_, _) => clicked = true;
            dialog.CloseButtonClick += (_, _) => clicked = false;
            using CancellationTokenRegistration close = ct.Register(() => dialog.DispatcherQueue.TryEnqueue(dialog.Hide));
            var shown = Stopwatch.StartNew();
            ContentDialogResult result = await dialog.ShowAsync();
            if (ct.IsCancellationRequested) completion.TrySetCanceled(ct);
            else if (result == ContentDialogResult.Primary || clicked == true) completion.TrySetResult(new ConsentAnswer(true, always.IsChecked == true));
            else if (clicked == false || shown.Elapsed >= MinimumHumanAnswer) completion.TrySetResult(ConsentAnswer.Declined); // Decline or Esc
            else completion.TrySetException(new InvalidOperationException("The dialog closed without an answer."));
        }
        catch (Exception error)
        {
            completion.TrySetException(error);
        }
        finally
        {
            App.ExitDialog();
        }
    }

    private static Task<T> OnUiThreadAsync<T>(Func<T> read)
    {
        DispatcherQueue? dispatcher = App.MainWindow?.DispatcherQueue;
        if (dispatcher is null) return Task.FromResult(default(T)!);
        if (dispatcher.HasThreadAccess) return Task.FromResult(read());
        var completion = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!dispatcher.TryEnqueue(() =>
            {
                try { completion.TrySetResult(read()); }
                catch (Exception error) { completion.TrySetException(error); }
            }))
        {
            VeyroTrace.Warn("UI thread unavailable for the transfer prompt");
            completion.TrySetResult(default!);
        }
        return completion.Task;
    }
}
