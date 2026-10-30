using System.Globalization;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// Mirrored notifications of the active device for the sidebar feed (ADR-025). Talks only to
/// <see cref="INotificationMirrorService"/>; never to sockets. ADR-063: an "Earlier" group with
/// what left the phone in the last 24 hours (memory only), "Copy code", and per-app pop-ups.
/// </summary>
public sealed class NotificationsViewModel : BaseViewModel
{
    /// <summary>Shown under "Earlier": history is not written to disk (ADR-063).</summary>
    public const string HistoryHelpText = "Removed or dismissed in the last 24 hours. Kept in memory only, so it is gone when Veyro quits.";

    public string HistoryHelp => HistoryHelpText;

    private static readonly TimeSpan CopiedFeedback = TimeSpan.FromSeconds(2);

    private readonly INotificationMirrorService _mirror;
    private readonly IDeviceManager _deviceManager;
    private readonly INotificationAppRules? _rules;
    private readonly Func<string, Task<bool>>? _copyText;
    private readonly DispatcherQueue? _dispatcher;
    private string? _peerId;

    public ObservableCollection<NotificationItemViewModel> Notifications { get; } = [];
    public ObservableCollection<NotificationItemViewModel> History { get; } = [];

    /// <summary>Nothing live and nothing earlier: the empty state is shown.</summary>
    public bool HasNoNotifications => Notifications.Count == 0 && History.Count == 0;

    /// <summary>Nothing live, but the Earlier group has entries.</summary>
    public bool HasOnlyHistory => Notifications.Count == 0 && History.Count > 0;

    public bool HasHistory => History.Count > 0;

    public string NoCurrentText => _deviceManager.ActiveDevice is { IsConnected: false }
        ? "Notifications from the phone appear here while it is connected."
        : "No notifications on the phone right now.";

    /// <summary>Why the feed is empty: offline phones have no mirrored notifications at all.</summary>
    public string EmptyText => _deviceManager.ActiveDevice switch
    {
        null => string.Empty,
        { IsConnected: false } => "Notifications from the phone appear here while it is connected.",
        _ => "No notifications. Turn on notification mirroring in Veyro on the phone if you expect some."
    };

    public NotificationsViewModel(
        INotificationMirrorService mirror,
        IDeviceManager deviceManager,
        INotificationAppRules? rules = null,
        Func<string, Task<bool>>? copyText = null)
    {
        _mirror = mirror;
        _deviceManager = deviceManager;
        _rules = rules;
        _copyText = copyText;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;

        Notifications.CollectionChanged += (_, _) => RaiseCounts();
        History.CollectionChanged += (_, _) => RaiseCounts();
        _mirror.Changed += change => Post(() =>
        {
            if (string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase)) Sync();
        });
        _deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            _peerId = device?.DeviceId;
            Sync();
            OnPropertyChanged(nameof(EmptyText));
            OnPropertyChanged(nameof(NoCurrentText));
        });
        _deviceManager.ConnectionChanged += peerId => Post(() =>
        {
            if (!string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
            OnPropertyChanged(nameof(EmptyText));
            OnPropertyChanged(nameof(NoCurrentText));
        });
        if (_rules is not null) _rules.Changed += () => Post(RefreshModes);
        Sync();
    }

    public Task DismissAsync(NotificationItemViewModel item)
        => RunAsync(item, () => _mirror.DismissAsync(item.PeerId, item.Key), "dismiss");

    /// <summary>Phone Link's "Clear all": dismisses every listed notification on the phone.</summary>
    public async Task ClearAllAsync()
    {
        foreach (NotificationItemViewModel item in Notifications.ToList()) await DismissAsync(item);
    }

    public bool HasNotifications => Notifications.Count > 0;

    /// <summary>Empties the Earlier group of the shown phone. Nothing is sent to the phone.</summary>
    public void ClearHistory()
    {
        if (_peerId is not null) _mirror.ClearHistory(_peerId);
    }

    /// <summary>"Copy code": the code alone onto this PC's clipboard, not sent back to the phones (ADR-063).</summary>
    public async Task CopyCodeAsync(NotificationItemViewModel item)
    {
        if (!item.HasCode || _copyText is null) return;
        item.StatusText = string.Empty;
        bool copied;
        try { copied = await _copyText(item.Code); }
        catch (Exception error)
        {
            // The type only: the code is user data.
            VeyroTrace.Warn($"Copying a notification code failed: {error.GetType().Name}");
            copied = false;
        }
        if (!copied)
        {
            item.StatusText = "Could not copy the code.";
            return;
        }
        item.CodeCopied = true;
        await Task.Delay(CopiedFeedback);
        item.CodeCopied = false;
    }

    /// <summary>The feed's quick action: pop-ups off (Feed only) or back on (Show) for the item's app.</summary>
    public async Task TogglePopupsAsync(NotificationItemViewModel item)
    {
        if (_rules is null || !item.CanSetRules) return;
        NotificationAppMode next = item.Mode == NotificationAppMode.FeedOnly ? NotificationAppMode.Show : NotificationAppMode.FeedOnly;
        try
        {
            await _rules.SetModeAsync(item.Package!, next, item.AppName);
            item.NoteText = next == NotificationAppMode.FeedOnly
                ? $"Pop-ups off for {item.AppName}. Change it in Settings > Notifications."
                : $"Pop-ups on for {item.AppName}.";
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Changing pop-ups failed: {error.GetType().Name}");
        }
    }

    public Task InvokeActionAsync(NotificationActionItem action)
        => RunAsync(action.Owner, () => _mirror.InvokeActionAsync(action.Owner.PeerId, action.Owner.Key, action.Index), "action");

    public async Task SendReplyAsync(NotificationItemViewModel item)
    {
        string text = item.ReplyText;
        if (string.IsNullOrWhiteSpace(text)) return;
        if (await RunAsync(item, () => _mirror.ReplyAsync(item.PeerId, item.Key, text), "reply"))
        {
            item.ReplyText = string.Empty;
            item.IsReplyOpen = false;
        }
    }

    private async Task<bool> RunAsync(NotificationItemViewModel item, Func<Task> request, string name)
    {
        item.StatusText = string.Empty;
        try
        {
            await request();
            return true;
        }
        catch (ArgumentException)
        {
            item.StatusText = $"Reply must be 1 to {NotificationMirrorService.MaxReplyLength} characters.";
        }
        catch (Exception error)
        {
            VeyroTrace.Error($"Notification {name} failed", error);
            item.StatusText = "Could not reach the phone.";
        }
        return false;
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }

    private void RaiseCounts()
    {
        OnPropertyChanged(nameof(HasNoNotifications));
        OnPropertyChanged(nameof(HasNotifications));
        OnPropertyChanged(nameof(HasOnlyHistory));
        OnPropertyChanged(nameof(HasHistory));
    }

    private void Sync()
    {
        SyncList(Notifications, _peerId is null ? [] : _mirror.GetNotifications(_peerId), isHistory: false);
        SyncList(History, _peerId is null ? [] : _mirror.GetHistory(_peerId), isHistory: true);
        RefreshModes();
    }

    /// <summary>Keyed sync so an in-progress reply survives unrelated updates.</summary>
    private static void SyncList(ObservableCollection<NotificationItemViewModel> target, IReadOnlyList<MirroredNotification> latest, bool isHistory)
    {
        var keys = latest.Select(n => n.Key).ToHashSet(StringComparer.Ordinal);
        for (int i = target.Count - 1; i >= 0; i--)
        {
            if (!keys.Contains(target[i].Key)) target.RemoveAt(i);
        }
        for (int i = 0; i < latest.Count; i++)
        {
            int existing = IndexOf(target, latest[i].Key);
            if (existing < 0)
            {
                target.Insert(i, new NotificationItemViewModel(latest[i], isHistory));
                continue;
            }
            target[existing].Update(latest[i]);
            if (existing != i) target.Move(existing, i);
        }
    }

    private void RefreshModes()
    {
        foreach (NotificationItemViewModel item in Notifications.Concat(History))
            item.Mode = _rules?.ModeFor(item.Package) ?? NotificationAppMode.Show;
    }

    private static int IndexOf(ObservableCollection<NotificationItemViewModel> target, string key)
    {
        for (int i = 0; i < target.Count; i++)
        {
            if (target[i].Key == key) return i;
        }
        return -1;
    }
}

public sealed record NotificationActionItem(NotificationItemViewModel Owner, int Index, string Label);

public sealed class NotificationItemViewModel : BaseViewModel
{
    private MirroredNotification _model;
    private bool _isReplyOpen;
    private string _replyText = string.Empty;
    private string _statusText = string.Empty;
    private string _noteText = string.Empty;
    private bool _codeCopied;
    private NotificationAppMode _mode;

    private string? _iconSource;
    private Microsoft.UI.Xaml.Media.ImageSource? _icon;

    public NotificationItemViewModel(MirroredNotification model, bool isHistory = false)
    {
        _model = model;
        IsHistory = isHistory;
        Actions = BuildActions(model);
        _ = UpdateIconAsync(model.IconPngBase64);
    }

    /// <summary>The app icon the phone sent (at most 16 KiB PNG), or null: then a generic glyph is shown.</summary>
    public Microsoft.UI.Xaml.Media.ImageSource? Icon => _icon;
    public bool HasIcon => _icon is not null;
    public bool HasNoIcon => _icon is null;

    private async Task UpdateIconAsync(string? base64)
    {
        if (base64 == _iconSource) return;
        _iconSource = base64;
        Microsoft.UI.Xaml.Media.ImageSource? image = null;
        if (base64 is not null)
        {
            try
            {
                byte[] bytes = Convert.FromBase64String(base64);
                var bitmap = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage { DecodePixelWidth = 48 };
                using var stream = new MemoryStream(bytes);
                await bitmap.SetSourceAsync(stream.AsRandomAccessStream());
                image = bitmap;
            }
            catch (Exception)
            {
                // Untrusted bytes: anything that does not decode is simply not shown.
                image = null;
            }
        }
        if (base64 != _iconSource) return;
        _icon = image;
        OnPropertyChanged(nameof(Icon));
        OnPropertyChanged(nameof(HasIcon));
        OnPropertyChanged(nameof(HasNoIcon));
    }

    public string PeerId => _model.PeerId;
    public string Key => _model.Key;
    public string AppName => _model.AppName;
    public string Title => _model.Title;
    public string Text => _model.Body;
    public bool HasTitle => !string.IsNullOrWhiteSpace(_model.Title);
    public bool HasText => !string.IsNullOrWhiteSpace(_model.Body);

    /// <summary>In the Earlier group: gone from the phone, so no reply, actions or dismiss.</summary>
    public bool IsHistory { get; }
    public bool IsLive => !IsHistory;
    public bool CanReply => !IsHistory && _model.CanReply;
    public IReadOnlyList<NotificationActionItem> Actions { get; private set; }
    public bool HasActions => Actions.Count > 0;

    /// <summary>The one-time code, empty when there is none (ADR-063). Shown in the text already; never logged.</summary>
    public string Code => _model.Code ?? string.Empty;
    public bool HasCode => !string.IsNullOrEmpty(_model.Code);

    public bool CodeCopied
    {
        get => _codeCopied;
        set
        {
            if (!SetProperty(ref _codeCopied, value)) return;
            OnPropertyChanged(nameof(CopyCodeLabel));
            OnPropertyChanged(nameof(CopyCodeGlyph));
        }
    }

    public string CopyCodeLabel => _codeCopied ? "Copied" : "Copy code";
    public string CopyCodeGlyph => _codeCopied ? "\uE73E" : "\uE8C8";

    public string? Package => _model.Package;

    /// <summary>Per-app rules need the phone's package name; without one there is no quick action.</summary>
    public bool CanSetRules => NotificationAppRules.IsValidPackage(_model.Package);

    public NotificationAppMode Mode
    {
        get => _mode;
        set
        {
            if (SetProperty(ref _mode, value)) OnPropertyChanged(nameof(PopupsMenuText));
        }
    }

    public string PopupsMenuText => _mode == NotificationAppMode.FeedOnly
        ? $"Turn on pop-ups for {AppName}"
        : $"Turn off pop-ups for {AppName}";

    public string TimeText
    {
        get
        {
            DateTimeOffset local = _model.Timestamp.ToLocalTime();
            return local.Date == DateTimeOffset.Now.Date
                ? local.ToString("t", CultureInfo.CurrentCulture)
                : local.ToString("MMM d", CultureInfo.CurrentCulture);
        }
    }

    public bool IsReplyOpen
    {
        get => _isReplyOpen;
        set => SetProperty(ref _isReplyOpen, value);
    }

    public string ReplyText
    {
        get => _replyText;
        set => SetProperty(ref _replyText, value ?? string.Empty);
    }

    public string StatusText
    {
        get => _statusText;
        set
        {
            if (SetProperty(ref _statusText, value)) OnPropertyChanged(nameof(HasStatus));
        }
    }

    public bool HasStatus => !string.IsNullOrEmpty(_statusText);

    /// <summary>A neutral note under the card, such as "Pop-ups off for Chat".</summary>
    public string NoteText
    {
        get => _noteText;
        set
        {
            if (SetProperty(ref _noteText, value)) OnPropertyChanged(nameof(HasNote));
        }
    }

    public bool HasNote => !string.IsNullOrEmpty(_noteText);

    public void Update(MirroredNotification model)
    {
        _model = model;
        Actions = BuildActions(model);
        _ = UpdateIconAsync(model.IconPngBase64);
        if (!CanReply) IsReplyOpen = false;
        foreach (string name in new[]
                 {
                     nameof(AppName), nameof(Title), nameof(Text), nameof(HasTitle), nameof(HasText),
                     nameof(CanReply), nameof(Actions), nameof(HasActions), nameof(TimeText),
                     nameof(Code), nameof(HasCode), nameof(Package), nameof(CanSetRules), nameof(PopupsMenuText)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private IReadOnlyList<NotificationActionItem> BuildActions(MirroredNotification model)
        => IsHistory ? [] : model.Actions.Take(3).Select(action => new NotificationActionItem(this, action.Index, action.Label)).ToList();
}
