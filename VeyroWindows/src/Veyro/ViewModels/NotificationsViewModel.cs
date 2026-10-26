using System.Globalization;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// Mirrored notifications of the active device for the sidebar feed (ADR-025). Talks only to
/// <see cref="INotificationMirrorService"/>; never to sockets.
/// </summary>
public sealed class NotificationsViewModel : BaseViewModel
{
    private readonly INotificationMirrorService _mirror;
    private readonly IDeviceManager _deviceManager;
    private readonly DispatcherQueue? _dispatcher;
    private string? _peerId;

    public ObservableCollection<NotificationItemViewModel> Notifications { get; } = [];
    public bool HasNoNotifications => Notifications.Count == 0;

    /// <summary>Why the feed is empty: offline phones have no mirrored notifications at all.</summary>
    public string EmptyText => _deviceManager.ActiveDevice switch
    {
        null => string.Empty,
        { IsConnected: false } => "Notifications from the phone appear here while it is connected.",
        _ => "No notifications. Turn on notification mirroring in Veyro on the phone if you expect some."
    };

    public NotificationsViewModel(INotificationMirrorService mirror, IDeviceManager deviceManager)
    {
        _mirror = mirror;
        _deviceManager = deviceManager;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;

        Notifications.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoNotifications));
        _mirror.Changed += change => Post(() =>
        {
            if (string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase)) Sync();
        });
        _deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            _peerId = device?.DeviceId;
            Sync();
            OnPropertyChanged(nameof(EmptyText));
        });
        _deviceManager.ConnectionChanged += peerId => Post(() =>
        {
            if (string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase)) OnPropertyChanged(nameof(EmptyText));
        });
        Sync();
    }

    public Task DismissAsync(NotificationItemViewModel item)
        => RunAsync(item, () => _mirror.DismissAsync(item.PeerId, item.Key), "dismiss");

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

    /// <summary>Keyed sync so an in-progress reply survives unrelated updates.</summary>
    private void Sync()
    {
        IReadOnlyList<MirroredNotification> latest = _peerId is null ? [] : _mirror.GetNotifications(_peerId);
        var keys = latest.Select(n => n.Key).ToHashSet(StringComparer.Ordinal);
        for (int i = Notifications.Count - 1; i >= 0; i--)
        {
            if (!keys.Contains(Notifications[i].Key)) Notifications.RemoveAt(i);
        }
        for (int i = 0; i < latest.Count; i++)
        {
            int existing = IndexOf(latest[i].Key);
            if (existing < 0)
            {
                Notifications.Insert(i, new NotificationItemViewModel(latest[i]));
                continue;
            }
            Notifications[existing].Update(latest[i]);
            if (existing != i) Notifications.Move(existing, i);
        }
    }

    private int IndexOf(string key)
    {
        for (int i = 0; i < Notifications.Count; i++)
        {
            if (Notifications[i].Key == key) return i;
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

    private string? _iconSource;
    private Microsoft.UI.Xaml.Media.ImageSource? _icon;

    public NotificationItemViewModel(MirroredNotification model)
    {
        _model = model;
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
    public bool CanReply => _model.CanReply;
    public IReadOnlyList<NotificationActionItem> Actions { get; private set; }
    public bool HasActions => Actions.Count > 0;

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

    public void Update(MirroredNotification model)
    {
        _model = model;
        Actions = BuildActions(model);
        _ = UpdateIconAsync(model.IconPngBase64);
        if (!model.CanReply) IsReplyOpen = false;
        foreach (string name in new[]
                 {
                     nameof(AppName), nameof(Title), nameof(Text), nameof(HasTitle), nameof(HasText),
                     nameof(CanReply), nameof(Actions), nameof(HasActions), nameof(TimeText)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private IReadOnlyList<NotificationActionItem> BuildActions(MirroredNotification model)
        => model.Actions.Take(3).Select(action => new NotificationActionItem(this, action.Index, action.Label)).ToList();
}
