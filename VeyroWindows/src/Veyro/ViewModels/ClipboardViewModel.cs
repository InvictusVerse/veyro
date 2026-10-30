using System.Text.RegularExpressions;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyro.Core.Clipboard;

namespace Veyro.ViewModels;

/// <summary>What a clip is, for its icon and quick action (ADR-043).</summary>
public enum ClipKind { Link, Email, Code, Text, Image }

/// <summary>The list's filter tabs.</summary>
public enum ClipFilter { All, Links, Text, Images }

/// <summary>One row of the PC's virtual clipboard.</summary>
public sealed partial class ClipRowViewModel
{
    public ClipRowViewModel(ClipItem item, string source, bool isFirst)
    {
        Item = item;
        Source = source;
        IsFirst = isFirst;
        Kind = item.Image is not null ? ClipKind.Image
            : item.Link is not null ? ClipKind.Link
            : EmailPattern().IsMatch(item.Text?.Trim() ?? string.Empty) ? ClipKind.Email
            : FindCode(item.Text) is not null ? ClipKind.Code
            : ClipKind.Text;
    }

    public ClipItem Item { get; }
    public string Source { get; }
    public ClipKind Kind { get; }

    /// <summary>The divider above a row is drawn for every row but the first: one container, split lines.</summary>
    public bool IsFirst { get; }
    public Visibility DividerVisibility => IsFirst ? Visibility.Collapsed : Visibility.Visible;

    public string Preview => Item.Image is not null ? "Image" : Item.Text ?? string.Empty;
    public string Time => Item.At.ToLocalTime().ToString("t");
    public string Meta => $"{KindLabel}  ·  {Source}  ·  {Time}";

    public string KindLabel => Kind switch
    {
        ClipKind.Link => "Link",
        ClipKind.Email => "Email",
        ClipKind.Code => "Code",
        ClipKind.Image => "Image",
        _ => "Text",
    };

    /// <summary>Segoe Fluent Icons glyph for the kind.</summary>
    public string Glyph => Kind switch
    {
        ClipKind.Link => "\uE71B",
        ClipKind.Email => "\uE715",
        ClipKind.Code => "\uE192",
        ClipKind.Image => "\uEB9F",
        _ => "\uE8D2",
    };

    public string Host => Item.Link?.Host is { } host && host.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? host[4..] : Item.Link?.Host ?? string.Empty;
    public string LinkText => Item.Link?.ToString() ?? string.Empty;
    public string Code => FindCode(Item.Text) ?? string.Empty;

    public Visibility LinkVisibility => Kind == ClipKind.Link ? Visibility.Visible : Visibility.Collapsed;
    public Visibility CodeVisibility => Kind == ClipKind.Code ? Visibility.Visible : Visibility.Collapsed;
    public Visibility ImageVisibility => Kind == ClipKind.Image ? Visibility.Visible : Visibility.Collapsed;
    public Visibility TextVisibility => Kind is ClipKind.Text or ClipKind.Email ? Visibility.Visible : Visibility.Collapsed;
    public Visibility EmailVisibility => Kind == ClipKind.Email ? Visibility.Visible : Visibility.Collapsed;

    public bool Matches(ClipFilter filter) => filter switch
    {
        ClipFilter.Links => Kind == ClipKind.Link,
        ClipFilter.Images => Kind == ClipKind.Image,
        ClipFilter.Text => Kind is ClipKind.Text or ClipKind.Email or ClipKind.Code,
        _ => true,
    };

    private BitmapImage? _thumbnail;

    public BitmapImage? Thumbnail
    {
        get
        {
            if (Item.Image is null) return null;
            if (_thumbnail is not null) return _thumbnail;
            _thumbnail = new BitmapImage { DecodePixelHeight = 160 };
            _thumbnail.SetSource(new MemoryStream(Item.Image).AsRandomAccessStream());
            return _thumbnail;
        }
    }

    /// <summary>A one-time code: 4 to 8 digits alone, or next to a word like "code" or "OTP".</summary>
    internal static string? FindCode(string? text)
    {
        string trimmed = text?.Trim() ?? string.Empty;
        if (trimmed.Length is >= 4 and <= 8 && trimmed.All(char.IsAsciiDigit)) return trimmed;
        if (trimmed.Length > 300 || !CodeHintPattern().IsMatch(trimmed)) return null;
        Match match = CodePattern().Match(trimmed);
        return match.Success ? match.Value : null;
    }

    [GeneratedRegex(@"^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$")]
    private static partial Regex EmailPattern();

    [GeneratedRegex(@"\b(code|otp|verification|verify|passcode|pin|one[- ]time)\b", RegexOptions.IgnoreCase)]
    private static partial Regex CodeHintPattern();

    [GeneratedRegex(@"(?<![\d-])\d{4,8}(?![\d-])")]
    private static partial Regex CodePattern();
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
    private List<ClipRowViewModel> _all = [];
    private ClipFilter _filter = ClipFilter.All;

    public ClipboardViewModel(ClipboardService service, ISettingsStore settings, Func<string, string?> phoneName)
    {
        _service = service;
        _settings = settings;
        _phoneName = phoneName;
        _service.Changed += () => App.MainWindow?.DispatcherQueue.TryEnqueue(Reload);
        _service.ReceivePreferenceChanged += _ => App.MainWindow?.DispatcherQueue.TryEnqueue(RaiseReceiveOff);
        Reload();
    }

    /// <summary>ADR-059: "Auto-fetch is off on Pixel. ..." while a phone has it off; empty otherwise.</summary>
    public string ReceiveOffText => ClipboardService.DescribeReceiveOff(
        _service.PeersWithReceiveOff.Select(id => _phoneName(id) ?? "a phone").ToList()) ?? string.Empty;

    public Visibility ReceiveOffVisibility => string.IsNullOrEmpty(ReceiveOffText) ? Visibility.Collapsed : Visibility.Visible;

    /// <summary>ADR-059: a phone renamed itself; rows and the auto-fetch note show the new name.</summary>
    public void RefreshNames()
    {
        Reload();
        RaiseReceiveOff();
    }

    private void RaiseReceiveOff()
    {
        OnPropertyChanged(nameof(ReceiveOffText));
        OnPropertyChanged(nameof(ReceiveOffVisibility));
    }

    public ObservableCollection<ClipRowViewModel> Items { get; } = [];
    public bool IsEmpty => Items.Count == 0;
    public Visibility EmptyVisibility => IsEmpty ? Visibility.Visible : Visibility.Collapsed;
    public Visibility ListVisibility => IsEmpty ? Visibility.Collapsed : Visibility.Visible;
    public bool HasAny => _all.Count > 0;

    public string EmptyTitle => _all.Count > 0 ? "Nothing of this kind yet" : "Your phone's clips land here";

    public string EmptyText => _all.Count > 0
        ? "Pick All to see every clip."
        : "On your phone, share text or a link to Veyro, or open Veyro > Clipboard and tap Send clipboard to PC.";

    public string CountText => _all.Count switch
    {
        0 => "No clips",
        1 => "1 clip",
        int n => $"{n} clips",
    };

    public ClipFilter Filter
    {
        get => _filter;
        set
        {
            if (value == _filter) return;
            _filter = value;
            OnPropertyChanged();
            Apply();
        }
    }

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

    /// <summary>"Copy code": only the digits.</summary>
    public Task CopyCodeAsync(ClipRowViewModel row) =>
        row.Code.Length > 0 && _service.Sink is { } sink ? sink.SetTextAsync(row.Code) : CopyAsync(row);

    public void Remove(ClipRowViewModel row) => _service.Remove(row.Item.Id);

    public void Clear() => _service.Clear();

    private void Reload()
    {
        _all = _service.Items.Select(item => new ClipRowViewModel(item, _phoneName(item.PeerId) ?? "Phone", isFirst: false)).ToList();
        Apply();
    }

    private void Apply()
    {
        Items.Clear();
        bool first = true;
        foreach (ClipRowViewModel row in _all.Where(r => r.Matches(_filter)))
        {
            Items.Add(new ClipRowViewModel(row.Item, row.Source, first));
            first = false;
        }
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(EmptyVisibility));
        OnPropertyChanged(nameof(ListVisibility));
        OnPropertyChanged(nameof(HasAny));
        OnPropertyChanged(nameof(EmptyTitle));
        OnPropertyChanged(nameof(EmptyText));
        OnPropertyChanged(nameof(CountText));
    }
}
