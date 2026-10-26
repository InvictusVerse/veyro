using System.Globalization;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// SMS conversations of the active phone (ADR-027). Talks only to <see cref="IMessagingService"/>,
/// never to sockets. Everything shown here lives in memory and is gone when the session closes.
/// Message bodies, numbers, names and SIM labels are never logged.
/// </summary>
public sealed class MessagesViewModel : BaseViewModel
{
    public const string EnableHint =
        "On the phone, open Veyro and turn on \"Messages and calls on PC\", then allow the SMS, contacts and phone permissions it asks for.";

    private readonly IMessagingService _messaging;
    private readonly IDeviceManager _deviceManager;
    private readonly DispatcherQueue? _dispatcher;
    private string? _peerId;
    private IReadOnlyList<SmsThread> _allThreads = [];
    private long? _selectedThreadId;
    private IReadOnlyList<string>? _draftAddresses;
    private string _searchText = string.Empty;
    private string _composeText = string.Empty;
    private string _statusText = string.Empty;
    private SimItem? _selectedSim;
    private bool _isActive;
    private bool _isLoadingThreads;
    private bool _isLoadingOlder;

    public MessagesViewModel(IMessagingService messaging, IDeviceManager deviceManager)
    {
        _messaging = messaging;
        _deviceManager = deviceManager;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;

        Threads.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoThreads));
        _messaging.Changed += change => Post(() => OnChanged(change));
        _deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            if (string.Equals(device?.DeviceId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
            _peerId = device?.DeviceId;
            _selectedThreadId = null;
            _draftAddresses = null;
            SyncAll();
            EnsureLoaded();
        });
        SyncAll();
    }

    /// <summary>Raised when something outside the page (a toast) asks to show a conversation.</summary>
    public event Action? OpenRequested;

    public ObservableCollection<SmsThreadItemViewModel> Threads { get; } = [];
    public ObservableCollection<SmsMessageItemViewModel> Messages { get; } = [];
    public ObservableCollection<SimItem> Sims { get; } = [];

    public bool IsAvailable => _peerId is not null && _messaging.IsAvailable(_peerId);
    public bool IsUnavailable => !IsAvailable;

    public string UnavailableText => _peerId is null
        ? "Pair a phone to see its messages here."
        : "Connect the phone to see its messages. " + EnableHint;

    public bool IsLoadingThreads => _isLoadingThreads;
    public bool HasNoThreads => IsAvailable && !_isLoadingThreads && Threads.Count == 0;

    public string EmptyThreadsText => _searchText.Length > 0
        ? "No conversations match your search."
        : "No conversations to show. " + EnableHint;

    public string SearchText
    {
        get => _searchText;
        set
        {
            if (!SetProperty(ref _searchText, (value ?? string.Empty).Trim())) return;
            OnPropertyChanged(nameof(EmptyThreadsText));
            ApplyThreadFilter();
        }
    }

    public SmsThreadItemViewModel? SelectedThread
        => _selectedThreadId is { } id ? Threads.FirstOrDefault(t => t.ThreadId == id) : null;

    public bool HasConversation => _selectedThreadId is not null || _draftAddresses is not null;
    public bool HasNoConversation => !HasConversation && IsAvailable;

    public string ConversationTitle
    {
        get
        {
            if (_draftAddresses is { } draft) return string.Join(", ", draft);
            SmsThread? thread = SelectedThreadModel();
            return thread is null ? string.Empty : SmsThreadItemViewModel.TitleOf(thread);
        }
    }

    public string ConversationSubtitle
    {
        get
        {
            if (_draftAddresses is not null) return "New message";
            SmsThread? thread = SelectedThreadModel();
            if (thread is null) return string.Empty;
            string numbers = string.Join(", ", thread.Addresses);
            return numbers == ConversationTitle ? string.Empty : numbers;
        }
    }

    public bool HasConversationSubtitle => !string.IsNullOrEmpty(ConversationSubtitle);

    public bool CanLoadOlder
    {
        get
        {
            if (_isLoadingOlder || _peerId is null || _selectedThreadId is not > 0) return false;
            return _messaging.GetConversation(_peerId, _selectedThreadId.Value) is { HasFirstPage: true, More: true };
        }
    }

    public bool IsLoadingOlder => _isLoadingOlder;

    public bool HasMultipleSims => Sims.Count > 1;

    public SimItem? SelectedSim
    {
        get => _selectedSim;
        set => SetProperty(ref _selectedSim, value);
    }

    public string ComposeText
    {
        get => _composeText;
        set
        {
            if (SetProperty(ref _composeText, value ?? string.Empty))
            {
                OnPropertyChanged(nameof(CanSend));
                OnPropertyChanged(nameof(ComposeCounterText));
            }
        }
    }

    public int MaxBodyLength => PhoneAddress.MaxBodyLength;
    public string ComposeCounterText => $"{_composeText.Length}/{PhoneAddress.MaxBodyLength}";

    public bool CanSend => IsAvailable && HasConversation && !string.IsNullOrWhiteSpace(_composeText) &&
                           PhoneAddress.IsValidBody(_composeText);

    public string StatusText
    {
        get => _statusText;
        private set
        {
            if (SetProperty(ref _statusText, value)) OnPropertyChanged(nameof(HasStatus));
        }
    }

    public bool HasStatus => !string.IsNullOrEmpty(_statusText);

    /// <summary>The page is on screen: fetch what is missing.</summary>
    public void Activate()
    {
        _isActive = true;
        SyncAll();
        EnsureLoaded();
    }

    public void Deactivate() => _isActive = false;

    public async Task RefreshAsync()
    {
        if (_peerId is not { } peer || !IsAvailable) return;
        SetLoadingThreads(true);
        StatusText = string.Empty;
        try { await _messaging.RequestThreadsAsync(peer); }
        catch (TimeoutException) { StatusText = "The phone did not answer. Try again."; }
        catch (Exception error)
        {
            VeyroTrace.Error("Loading SMS threads failed", error);
            StatusText = "Could not load conversations from the phone.";
        }
        finally { SetLoadingThreads(false); }
    }

    public Task SelectThreadAsync(SmsThreadItemViewModel item)
    {
        _selectedThreadId = item.ThreadId;
        _draftAddresses = null;
        StatusText = string.Empty;
        SyncConversation();
        RaiseConversationProperties();
        MarkSelectedSeen();
        return LoadFirstPageIfNeededAsync();
    }

    /// <summary>Starts a conversation with a typed number. Returns an error to show, or null.</summary>
    public string? StartNewMessage(string number)
    {
        if (PhoneAddress.Normalize(number) is not { } normalized) return "Enter a valid phone number.";
        SmsThread? existing = _allThreads.FirstOrDefault(t => t.Addresses.Count == 1 && PhoneAddress.SameNumber(t.Addresses[0], normalized));
        if (existing is not null)
        {
            SearchText = string.Empty;
            _selectedThreadId = existing.ThreadId;
            _draftAddresses = null;
        }
        else
        {
            _selectedThreadId = null;
            _draftAddresses = [normalized];
        }
        StatusText = string.Empty;
        SyncConversation();
        RaiseConversationProperties();
        _ = LoadFirstPageIfNeededAsync();
        return null;
    }

    public async Task LoadOlderAsync()
    {
        if (_peerId is not { } peer || _selectedThreadId is not > 0 || _isLoadingOlder) return;
        long threadId = _selectedThreadId.Value;
        SetLoadingOlder(true);
        try { await _messaging.LoadOlderAsync(peer, threadId); }
        catch (TimeoutException) { StatusText = "The phone did not answer. Try again."; }
        catch (Exception error)
        {
            VeyroTrace.Error("Loading SMS page failed", error);
            StatusText = "Could not load messages from the phone.";
        }
        finally { SetLoadingOlder(false); }
    }

    public async Task SendAsync()
    {
        if (!CanSend || _peerId is not { } peer) return;
        IReadOnlyList<string>? addresses = _draftAddresses ?? SelectedThreadModel()?.Addresses;
        if (addresses is null || addresses.Count == 0) return;
        string body = _composeText;
        ComposeText = string.Empty;
        StatusText = string.Empty;
        try
        {
            ConversationMessage message = await _messaging.SendAsync(peer, addresses, body, _selectedSim?.Id);
            _selectedThreadId = message.ThreadId;
            _draftAddresses = null;
            SyncAll();
        }
        catch (ArgumentException)
        {
            ComposeText = body;
            StatusText = $"Messages need 1 to {PhoneAddress.MaxAddresses} valid numbers and 1 to {PhoneAddress.MaxBodyLength} characters.";
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Sending SMS failed", error);
            StatusText = "Could not reach the phone.";
        }
    }

    public async Task RetryAsync(SmsMessageItemViewModel item)
    {
        if (_peerId is not { } peer || item.ClientId is not { } clientId) return;
        StatusText = string.Empty;
        try { await _messaging.RetryAsync(peer, clientId); }
        catch (Exception error)
        {
            VeyroTrace.Error("Retrying SMS failed", error);
            StatusText = "Could not reach the phone.";
        }
    }

    /// <summary>From a toast: show this phone's thread. Safe to call from any thread.</summary>
    public void RequestOpenThread(string peerId, long threadId) => Post(() =>
    {
        if (!string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase) &&
            _deviceManager.PairedDevices.FirstOrDefault(d => string.Equals(d.DeviceId, peerId, StringComparison.OrdinalIgnoreCase)) is { } device)
        {
            _deviceManager.ActiveDevice = device;
            _peerId = device.DeviceId;
        }
        if (!string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
        SearchText = string.Empty;
        _selectedThreadId = threadId;
        _draftAddresses = null;
        SyncAll();
        OpenRequested?.Invoke();
        MarkSelectedSeen();
        _ = LoadFirstPageIfNeededAsync();
    });

    private void OnChanged(MessagingChange change)
    {
        if (!string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
        switch (change.Kind)
        {
            case MessagingChangeKind.Availability:
                SyncAll();
                EnsureLoaded();
                break;
            case MessagingChangeKind.Cleared:
                if (_selectedThreadId is < 0) _selectedThreadId = null;
                SyncAll();
                break;
            case MessagingChangeKind.Sims:
                SyncSims();
                break;
            case MessagingChangeKind.Threads:
                SyncThreads();
                RaiseConversationProperties();
                break;
            case MessagingChangeKind.Conversation:
                if (change.PreviousThreadId is { } previous && previous == _selectedThreadId) _selectedThreadId = change.ThreadId;
                if (change.ThreadId == _selectedThreadId)
                {
                    SyncConversation();
                    RaiseConversationProperties();
                    if (_isActive) MarkSelectedSeen();
                }
                break;
        }
    }

    private void EnsureLoaded()
    {
        if (!_isActive || _peerId is not { } peer || !IsAvailable) return;
        if (!_messaging.HasThreadList(peer) && !_isLoadingThreads) _ = RefreshAsync();
        _ = LoadFirstPageIfNeededAsync();
    }

    private async Task LoadFirstPageIfNeededAsync()
    {
        if (_peerId is not { } peer || _selectedThreadId is not > 0 || !IsAvailable) return;
        if (_messaging.GetConversation(peer, _selectedThreadId.Value) is { HasFirstPage: true }) return;
        await LoadOlderAsync();
    }

    private void MarkSelectedSeen()
    {
        if (_peerId is { } peer && _selectedThreadId is { } id) _messaging.MarkSeen(peer, id);
    }

    private SmsThread? SelectedThreadModel()
        => _selectedThreadId is { } id ? _allThreads.FirstOrDefault(t => t.ThreadId == id) : null;

    private void SyncAll()
    {
        SyncSims();
        SyncThreads();
        SyncConversation();
        RaiseConversationProperties();
        foreach (string name in new[] { nameof(IsAvailable), nameof(IsUnavailable), nameof(UnavailableText), nameof(HasNoThreads), nameof(CanSend) })
            OnPropertyChanged(name);
    }

    private void SyncSims()
    {
        IReadOnlyList<SimInfo> sims = _peerId is null ? [] : _messaging.GetSims(_peerId);
        int? selected = _selectedSim?.Id;
        Sims.Clear();
        foreach (SimInfo sim in sims)
        {
            string label = string.IsNullOrWhiteSpace(sim.Label) ? $"SIM {(sim.Slot ?? 0) + 1}" : sim.Label;
            Sims.Add(new SimItem(sim.Id, label));
        }
        SelectedSim = Sims.FirstOrDefault(s => s.Id == selected);
        OnPropertyChanged(nameof(HasMultipleSims));
    }

    private void SyncThreads()
    {
        _allThreads = _peerId is null ? [] : _messaging.GetThreads(_peerId);
        ApplyThreadFilter();
        OnPropertyChanged(nameof(UnreadCount));
        OnPropertyChanged(nameof(HasUnread));
    }

    /// <summary>Unread messages across the phone's known threads, for the Messages tab badge (Phone Link).</summary>
    public int UnreadCount => _allThreads.Sum(thread => Math.Max(0, thread.Unread));
    public bool HasUnread => UnreadCount > 0;

    private void ApplyThreadFilter()
    {
        List<SmsThread> visible = _allThreads.Where(Matches).ToList();
        var ids = visible.Select(t => t.ThreadId).ToHashSet();
        for (int i = Threads.Count - 1; i >= 0; i--)
        {
            if (!ids.Contains(Threads[i].ThreadId)) Threads.RemoveAt(i);
        }
        for (int i = 0; i < visible.Count; i++)
        {
            int existing = IndexOfThread(visible[i].ThreadId);
            if (existing < 0)
            {
                Threads.Insert(i, new SmsThreadItemViewModel(visible[i]));
                continue;
            }
            Threads[existing].Update(visible[i]);
            if (existing != i) Threads.Move(existing, i);
        }
        OnPropertyChanged(nameof(SelectedThread));
        OnPropertyChanged(nameof(HasNoThreads));
    }

    private bool Matches(SmsThread thread)
    {
        if (_searchText.Length == 0) return true;
        if (SmsThreadItemViewModel.TitleOf(thread).Contains(_searchText, StringComparison.CurrentCultureIgnoreCase)) return true;
        if (thread.Snippet?.Contains(_searchText, StringComparison.CurrentCultureIgnoreCase) == true) return true;
        string digits = new(_searchText.Where(char.IsAsciiDigit).ToArray());
        return digits.Length >= 3 && thread.Addresses.Any(a => new string(a.Where(char.IsAsciiDigit).ToArray()).Contains(digits, StringComparison.Ordinal));
    }

    private int IndexOfThread(long threadId)
    {
        for (int i = 0; i < Threads.Count; i++)
        {
            if (Threads[i].ThreadId == threadId) return i;
        }
        return -1;
    }

    private void SyncConversation()
    {
        IReadOnlyList<ConversationMessage> latest = _peerId is not null && _selectedThreadId is { } id
            ? _messaging.GetConversation(_peerId, id)?.Messages ?? []
            : [];
        bool group = (SelectedThreadModel()?.Addresses.Count ?? 1) > 1;
        var keys = latest.Select(m => m.Key).ToHashSet(StringComparer.Ordinal);
        for (int i = Messages.Count - 1; i >= 0; i--)
        {
            if (!keys.Contains(Messages[i].Key)) Messages.RemoveAt(i);
        }
        for (int i = 0; i < latest.Count; i++)
        {
            int existing = IndexOfMessage(latest[i].Key);
            if (existing < 0)
            {
                Messages.Insert(i, new SmsMessageItemViewModel(latest[i], group));
                continue;
            }
            Messages[existing].Update(latest[i]);
            if (existing != i) Messages.Move(existing, i);
        }
    }

    private int IndexOfMessage(string key)
    {
        for (int i = 0; i < Messages.Count; i++)
        {
            if (Messages[i].Key == key) return i;
        }
        return -1;
    }

    private void RaiseConversationProperties()
    {
        foreach (string name in new[]
                 {
                     nameof(SelectedThread), nameof(HasConversation), nameof(HasNoConversation), nameof(ConversationTitle),
                     nameof(ConversationSubtitle), nameof(HasConversationSubtitle), nameof(CanLoadOlder), nameof(CanSend)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private void SetLoadingThreads(bool value)
    {
        _isLoadingThreads = value;
        OnPropertyChanged(nameof(IsLoadingThreads));
        OnPropertyChanged(nameof(HasNoThreads));
    }

    private void SetLoadingOlder(bool value)
    {
        _isLoadingOlder = value;
        OnPropertyChanged(nameof(IsLoadingOlder));
        OnPropertyChanged(nameof(CanLoadOlder));
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }

    internal static string FormatTime(long epochMs)
    {
        if (epochMs <= 0) return string.Empty;
        DateTimeOffset local;
        try { local = DateTimeOffset.FromUnixTimeMilliseconds(epochMs).ToLocalTime(); }
        catch (ArgumentOutOfRangeException) { return string.Empty; }
        DateTimeOffset now = DateTimeOffset.Now;
        if (local.Date == now.Date) return local.ToString("t", CultureInfo.CurrentCulture);
        if (local.Year == now.Year) return local.ToString("MMM d", CultureInfo.CurrentCulture);
        return local.ToString("d", CultureInfo.CurrentCulture);
    }
}

public sealed record SimItem(int Id, string Label);

public sealed class SmsThreadItemViewModel : BaseViewModel
{
    private SmsThread _model;

    public SmsThreadItemViewModel(SmsThread model) => _model = model;

    public long ThreadId => _model.ThreadId;
    public string Title => TitleOf(_model);
    public string Snippet => _model.Snippet ?? string.Empty;
    public string TimeText => MessagesViewModel.FormatTime(_model.Ts);
    public bool HasUnread => _model.Unread > 0;
    public string UnreadText => _model.Unread > 99 ? "99+" : _model.Unread.ToString(CultureInfo.CurrentCulture);
    public string Initial => Title.FirstOrDefault(char.IsLetterOrDigit) is var c && c != default ? char.ToUpper(c, CultureInfo.CurrentCulture).ToString() : "#";

    public void Update(SmsThread model)
    {
        if (model == _model) return;
        _model = model;
        foreach (string name in new[] { nameof(Title), nameof(Snippet), nameof(TimeText), nameof(HasUnread), nameof(UnreadText), nameof(Initial) })
            OnPropertyChanged(name);
    }

    /// <summary>Contact names where known, numbers otherwise.</summary>
    public static string TitleOf(SmsThread thread)
    {
        var parts = thread.Addresses.Select((address, i) =>
            thread.Names is { } names && i < names.Count && !string.IsNullOrWhiteSpace(names[i]) ? names[i]! : address);
        string title = string.Join(", ", parts);
        return title.Length == 0 ? "Unknown" : title;
    }
}

public sealed class SmsMessageItemViewModel : BaseViewModel
{
    private ConversationMessage _model;
    private readonly bool _group;

    public SmsMessageItemViewModel(ConversationMessage model, bool group)
    {
        _model = model;
        _group = group;
    }

    public string Key => _model.Key;
    public string? ClientId => _model.ClientId;
    public bool IsOutgoing => _model.Outgoing;
    public string Body => _model.Body;
    public string TimeText => MessagesViewModel.FormatTime(_model.Ts);
    public string SenderText => _model.Address;
    public bool HasSender => _group && !_model.Outgoing;
    public bool IsFailed => _model.State == SmsDeliveryState.Failed;
    public bool CanRetry => IsFailed && _model.ClientId is not null;

    public string StatusText => _model.State switch
    {
        SmsDeliveryState.Pending => "Sending...",
        SmsDeliveryState.Failed => FailureText(_model.Error),
        _ => TimeText
    };

    public void Update(ConversationMessage model)
    {
        if (model == _model) return;
        _model = model;
        foreach (string name in new[] { nameof(Body), nameof(TimeText), nameof(SenderText), nameof(IsFailed), nameof(CanRetry), nameof(StatusText) })
            OnPropertyChanged(name);
    }

    private static string FailureText(string? error) => error switch
    {
        "permission" => "Not sent: Veyro on the phone lacks SMS permission",
        "disabled" => "Not sent: \"Messages and calls on PC\" is off on the phone",
        "invalid" => "Not sent: invalid number or message",
        "radio" => "Not sent: the phone's radio is off",
        "no-service" => "Not sent: no mobile service",
        MessagingService.ErrorTimeout => "Not confirmed: the phone did not answer",
        _ => "Not sent"
    };
}
