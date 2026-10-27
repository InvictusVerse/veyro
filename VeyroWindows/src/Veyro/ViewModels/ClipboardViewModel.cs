using Microsoft.UI.Xaml.Media.Imaging;
using Veyro.Core.Clipboard;

namespace Veyro.ViewModels;

/// <summary>One row of the PC's virtual clipboard.</summary>
public sealed class ClipRowViewModel
{
    public ClipRowViewModel(ClipItem item, string source)
    {
        Item = item;
        Source = source;
    }

    public ClipItem Item { get; }
    public string Source { get; }
    public string Preview => Item.Image is not null ? "Image" : Item.Text ?? string.Empty;
    public string Time => Item.At.ToLocalTime().ToString("t");
    public bool HasLink => Item.Link is not null;
    public Visibility LinkVisibility => HasLink ? Visibility.Visible : Visibility.Collapsed;
    public Visibility ImageVisibility => Item.Image is not null ? Visibility.Visible : Visibility.Collapsed;
    public Visibility TextVisibility => Item.Image is null ? Visibility.Visible : Visibility.Collapsed;

    public BitmapImage? Thumbnail
    {
        get
        {
            if (Item.Image is null) return null;
            var image = new BitmapImage { DecodePixelHeight = 120 };
            image.SetSource(new MemoryStream(Item.Image).AsRandomAccessStream());
            return image;
        }
    }
}

/// <summary>
/// Veyro's virtual clipboard on the PC (ADR-043): what phones sent with "Send clipboard", plus
/// what they shared. Copy puts an item on the Windows clipboard. In memory only.
/// </summary>
public sealed class ClipboardViewModel : BaseViewModel
{
    private readonly ClipboardService _service;
    private readonly ISettingsStore _settings;
    private readonly Func<string, string?> _phoneName;

    public ClipboardViewModel(ClipboardService service, ISettingsStore settings, Func<string, string?> phoneName)
    {
        _service = service;
        _settings = settings;
        _phoneName = phoneName;
        _service.Changed += () => App.MainWindow?.DispatcherQueue.TryEnqueue(Reload);
        Reload();
    }

    public ObservableCollection<ClipRowViewModel> Items { get; } = [];
    public bool IsEmpty => Items.Count == 0;
    public Visibility EmptyVisibility => IsEmpty ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>What this PC copies goes to the phones (default on).</summary>
    public bool SendToPhones
    {
        get => _settings.Current.SendClipboardToPhones;
        set
        {
            if (value == SendToPhones) return;
            _ = _settings.UpdateAsync(s => s with { SendClipboardToPhones = value });
            OnPropertyChanged();
        }
    }

    /// <summary>Include copied images (default off).</summary>
    public bool SyncImages
    {
        get => _settings.Current.SyncClipboardImages;
        set
        {
            if (value == SyncImages) return;
            _ = _settings.UpdateAsync(s => s with { SyncClipboardImages = value });
            OnPropertyChanged();
        }
    }

    public Task CopyAsync(ClipRowViewModel row) => _service.CopyToPcAsync(row.Item);

    public void Remove(ClipRowViewModel row) => _service.Remove(row.Item.Id);

    public void Clear() => _service.Clear();

    private void Reload()
    {
        Items.Clear();
        foreach (ClipItem item in _service.Items)
            Items.Add(new ClipRowViewModel(item, _phoneName(item.PeerId) ?? "Phone"));
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(EmptyVisibility));
    }
}
