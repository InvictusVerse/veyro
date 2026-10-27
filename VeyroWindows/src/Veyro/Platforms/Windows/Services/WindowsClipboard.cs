using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Veyro.Core.Clipboard;
using Veyro.Core.Diagnostics;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyro.Platforms.Windows.Services;

/// <summary>
/// The Windows clipboard for ADR-043: watches what the user copies and writes what phones share.
/// WinRT clipboard calls run on the UI thread. Content marked private (password managers set
/// these formats, and Windows clipboard history honours them) is never sent.
/// </summary>
public sealed class WindowsClipboard : IClipboardSink
{
    private static readonly string[] PrivateFormats =
    [
        "ExcludeClipboardContentFromMonitorProcessing",
        "CanIncludeInClipboardHistory",
        "Clipboard Viewer Ignore",
    ];

    private readonly ClipboardService _service;
    private readonly Func<bool> _imagesEnabled;
    private DispatcherQueue? _dispatcher;

    public WindowsClipboard(ClipboardService service, Func<bool> imagesEnabled)
    {
        _service = service;
        _imagesEnabled = imagesEnabled;
    }

    /// <summary>Starts watching. Call once on the UI thread.</summary>
    public void Start(DispatcherQueue dispatcher)
    {
        _dispatcher = dispatcher;
        global::Windows.ApplicationModel.DataTransfer.Clipboard.ContentChanged += OnContentChanged;
    }

    private async void OnContentChanged(object? sender, object e)
    {
        try
        {
            DataPackageView view = global::Windows.ApplicationModel.DataTransfer.Clipboard.GetContent();
            if (view.AvailableFormats.Any(f => PrivateFormats.Contains(f, StringComparer.OrdinalIgnoreCase))) return;
            if (view.Contains(StandardDataFormats.Text))
            {
                string text = await view.GetTextAsync();
                await _service.OnLocalTextCopiedAsync(text);
            }
            else if (_imagesEnabled() && view.Contains(StandardDataFormats.Bitmap))
            {
                RandomAccessStreamReference reference = await view.GetBitmapAsync();
                byte[]? png = await ToPngUnderCapAsync(reference);
                if (png is not null) await _service.OnLocalImageCopiedAsync(png);
            }
        }
        catch (Exception error)
        {
            // Another app can hold the clipboard open; the next change is tried again.
            VeyroTrace.Warn($"Reading the clipboard failed: {error.GetType().Name}");
        }
    }

    public Task SetTextAsync(string text) => OnUiAsync(() =>
    {
        var package = new DataPackage();
        package.SetText(text);
        global::Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
        // Keeps the content after Veyro exits.
        global::Windows.ApplicationModel.DataTransfer.Clipboard.Flush();
    });

    public async Task SetImageAsync(byte[] image)
    {
        var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(image.AsBuffer());
        stream.Seek(0);
        await OnUiAsync(() =>
        {
            var package = new DataPackage();
            package.SetBitmap(RandomAccessStreamReference.CreateFromStream(stream));
            global::Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
            global::Windows.ApplicationModel.DataTransfer.Clipboard.Flush();
        });
    }

    private Task OnUiAsync(Action action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (_dispatcher is null || !_dispatcher.TryEnqueue(() =>
            {
                try { action(); done.TrySetResult(); }
                catch (Exception error) { done.TrySetException(error); }
            }))
        {
            done.TrySetException(new InvalidOperationException("The clipboard is not ready."));
        }
        return done.Task;
    }

    /// <summary>PNG of the copied bitmap, scaled down until it fits the protocol cap.</summary>
    private static async Task<byte[]?> ToPngUnderCapAsync(RandomAccessStreamReference reference)
    {
        using IRandomAccessStreamWithContentType source = await reference.OpenReadAsync();
        BitmapDecoder decoder = await BitmapDecoder.CreateAsync(source);
        SoftwareBitmap bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
        uint width = decoder.PixelWidth, height = decoder.PixelHeight;
        for (int attempt = 0; attempt < 5 && width > 0 && height > 0; attempt++)
        {
            using var output = new InMemoryRandomAccessStream();
            BitmapEncoder encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, output);
            encoder.SetSoftwareBitmap(bitmap);
            encoder.BitmapTransform.ScaledWidth = width;
            encoder.BitmapTransform.ScaledHeight = height;
            encoder.BitmapTransform.InterpolationMode = BitmapInterpolationMode.Fant;
            await encoder.FlushAsync();
            if (output.Size <= (ulong)Veyro.Core.Link.LinkMessage.Clip.MaxImageBytes)
            {
                byte[] bytes = new byte[output.Size];
                output.Seek(0);
                await output.ReadAsync(bytes.AsBuffer(), (uint)bytes.Length, InputStreamOptions.None);
                return bytes;
            }
            width = width * 3 / 4;
            height = height * 3 / 4;
        }
        return null;
    }
}

/// <summary>Toasts for clipboard events (ADR-043). A link gets Open, which launches the browser directly.</summary>
public sealed class ClipboardToasts : IClipboardNotifier
{
    private const int MaxPreview = 160;
    private readonly Func<string, string?> _phoneName;

    public ClipboardToasts(Func<string, string?> phoneName) => _phoneName = phoneName;

    public void ShowApplied(string peerId, ClipItem item) =>
        Show($"Copied from {Name(peerId)}", item, includeOpen: true);

    public void ShowStored(string peerId, ClipItem item) =>
        Show($"{Name(peerId)} sent its clipboard", item, includeOpen: true, footer: "Saved in Veyro's clipboard");

    private string Name(string peerId) => _phoneName(peerId) ?? "Your phone";

    private static void Show(string title, ClipItem item, bool includeOpen, string? footer = null)
    {
        try
        {
            string preview = item.Image is not null
                ? "Image"
                : item.Text is { } text && text.Length > MaxPreview ? text[..MaxPreview] + "..." : item.Text ?? string.Empty;
            var builder = new AppNotificationBuilder()
                .AddArgument("action", "clip-open")
                .AddText(title)
                .AddText(preview)
                .SetGroup("veyro-clipboard");
            if (footer is not null) builder.AddText(footer);
            if (includeOpen && item.Link is { } link)
            {
                // Launches the URL without activating Veyro. Only http(s) reaches here.
                builder.AddButton(new AppNotificationButton("Open").SetInvokeUri(link));
            }
            AppNotificationManager.Default.Show(builder.BuildNotification());
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Clipboard toast failed: {error.GetType().Name}");
        }
    }
}
