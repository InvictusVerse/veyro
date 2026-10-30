using System.Globalization;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels.Settings;

/// <summary>
/// Settings > Notifications (ADR-063): every phone app that sent a notification, with Show, Feed
/// only or Hide. One list for every paired phone. Labels and package names come from the phone.
/// </summary>
public sealed class NotificationRulesViewModel : BaseViewModel
{
    private readonly INotificationAppRules _rules;
    private readonly DispatcherQueue? _dispatcher;

    public NotificationRulesViewModel(INotificationAppRules rules)
    {
        _rules = rules;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        Apps.CollectionChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(HasApps));
            OnPropertyChanged(nameof(HasNoApps));
        };
        _rules.Changed += () => Post(Sync);
        Sync();
    }

    public ObservableCollection<NotificationAppRuleItem> Apps { get; } = [];
    public bool HasApps => Apps.Count > 0;
    public bool HasNoApps => Apps.Count == 0;

    public string HistoryHelp => NotificationsViewModel.HistoryHelpText;

    /// <summary>Sorted by name; existing rows are kept so an open selector is not reset.</summary>
    private void Sync()
    {
        var existing = Apps.ToDictionary(item => item.Package, StringComparer.Ordinal);
        List<NotificationAppRule> latest = _rules.Apps
            .OrderBy(app => app.Label, StringComparer.Create(CultureInfo.CurrentCulture, ignoreCase: true))
            .ThenBy(app => app.Package, StringComparer.Ordinal)
            .ToList();
        if (latest.Select(app => app.Package).SequenceEqual(Apps.Select(item => item.Package), StringComparer.Ordinal))
        {
            for (int i = 0; i < latest.Count; i++) Apps[i].Update(latest[i]);
            return;
        }
        Apps.Clear();
        foreach (NotificationAppRule app in latest)
        {
            if (existing.TryGetValue(app.Package, out NotificationAppRuleItem? item)) item.Update(app);
            else item = new NotificationAppRuleItem(app, _rules);
            Apps.Add(item);
        }
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}

/// <summary>One app row: the selector's index is the <see cref="NotificationAppMode"/> value.</summary>
public sealed class NotificationAppRuleItem : BaseViewModel
{
    private readonly INotificationAppRules _rules;
    private string _label;
    private int _modeIndex;

    public NotificationAppRuleItem(NotificationAppRule rule, INotificationAppRules rules)
    {
        _rules = rules;
        Package = rule.Package;
        _label = rule.Label;
        _modeIndex = (int)rule.Mode;
    }

    public string Package { get; }

    public string Label => _label;

    /// <summary>0 Show, 1 Feed only, 2 Hide. Changing it saves at once.</summary>
    public int ModeIndex
    {
        get => _modeIndex;
        set
        {
            if (value is < 0 or > (int)NotificationAppMode.Hide || !SetProperty(ref _modeIndex, value)) return;
            _ = SaveAsync((NotificationAppMode)value);
        }
    }

    internal void Update(NotificationAppRule rule)
    {
        if (_label != rule.Label)
        {
            _label = rule.Label;
            OnPropertyChanged(nameof(Label));
        }
        if (_modeIndex != (int)rule.Mode)
        {
            _modeIndex = (int)rule.Mode;
            OnPropertyChanged(nameof(ModeIndex));
        }
    }

    private async Task SaveAsync(NotificationAppMode mode)
    {
        try { await _rules.SetModeAsync(Package, mode, _label); }
        catch (Exception error) { VeyroTrace.Warn($"Saving a notification rule failed: {error.GetType().Name}"); }
    }
}
