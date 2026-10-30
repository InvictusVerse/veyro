using Veyro.Core.Data;
using Veyro.Core.Diagnostics;

namespace Veyro.Core.Notifications;

/// <summary>What this PC does with one phone app's notifications (ADR-063). Stored as numbers.</summary>
public enum NotificationAppMode
{
    /// <summary>Pop-up (toast) and feed. The default.</summary>
    Show = 0,

    /// <summary>In the feed only, no pop-up.</summary>
    FeedOnly = 1,

    /// <summary>Neither: dropped when it arrives.</summary>
    Hide = 2,
}

/// <summary>A phone app that sent a notification, and the user's choice for it (ADR-063).</summary>
public sealed record NotificationAppRule(string Package, string Label, NotificationAppMode Mode);

/// <summary>Per-app rules for mirrored notifications, one list for every paired phone (ADR-063).</summary>
public interface INotificationAppRules
{
    /// <summary>The list or a choice changed. Raised on the caller's thread.</summary>
    event Action? Changed;

    /// <summary>Every app seen, in the order first seen.</summary>
    IReadOnlyList<NotificationAppRule> Apps { get; }

    /// <summary><see cref="NotificationAppMode.Show"/> for an unknown or missing package.</summary>
    NotificationAppMode ModeFor(string? package);

    /// <summary>Records that <paramref name="package"/> sent a notification, so Settings can list it.</summary>
    void NoteSeen(string? package, string? label);

    Task SetModeAsync(string package, NotificationAppMode mode, string? label = null);
}

/// <summary>
/// Per-app notification rules kept in settings.json (<see cref="AppSettings.NotificationApps"/>),
/// global by Android package name. Holds package names and app labels only, never notification
/// contents. Package and label come from the phone: validated and size-capped here.
/// </summary>
public sealed class NotificationAppRules : INotificationAppRules
{
    public const int MaxApps = 300;
    public const int MaxPackageLength = 255;
    public const int MaxLabelLength = 100;

    private readonly ISettingsStore _settings;
    private readonly object _gate = new();
    private readonly List<NotificationAppRule> _apps = [];
    private readonly SemaphoreSlim _saveLock = new(1, 1);

    public event Action? Changed;

    public NotificationAppRules(ISettingsStore settings)
    {
        _settings = settings;
        foreach (NotificationAppRule? rule in settings.Current.NotificationApps ?? [])
        {
            if (_apps.Count >= MaxApps) break;
            if (rule is null || !IsValidPackage(rule.Package) || IndexOf(rule.Package) >= 0) continue;
            _apps.Add(new NotificationAppRule(rule.Package, CleanLabel(rule.Label, rule.Package), Known(rule.Mode)));
        }
    }

    public IReadOnlyList<NotificationAppRule> Apps
    {
        get { lock (_gate) return _apps.ToList(); }
    }

    public NotificationAppMode ModeFor(string? package)
    {
        if (package is null) return NotificationAppMode.Show;
        lock (_gate)
        {
            int index = IndexOf(package);
            return index < 0 ? NotificationAppMode.Show : _apps[index].Mode;
        }
    }

    public void NoteSeen(string? package, string? label)
    {
        if (!IsValidPackage(package)) return;
        string clean = CleanLabel(label, package!);
        lock (_gate)
        {
            int index = IndexOf(package!);
            if (index >= 0)
            {
                // A notification without a label keeps the one already known.
                if (_apps[index].Label == clean || string.IsNullOrWhiteSpace(label)) return;
                _apps[index] = _apps[index] with { Label = clean };
            }
            else if (!Add(new NotificationAppRule(package!, clean, NotificationAppMode.Show)))
            {
                return;
            }
        }
        Changed?.Invoke();
        _ = SaveAsync();
    }

    public async Task SetModeAsync(string package, NotificationAppMode mode, string? label = null)
    {
        if (!IsValidPackage(package)) throw new ArgumentException("Not an Android package name.", nameof(package));
        mode = Known(mode);
        lock (_gate)
        {
            int index = IndexOf(package);
            if (index >= 0)
            {
                if (_apps[index].Mode == mode) return;
                _apps[index] = _apps[index] with { Mode = mode };
            }
            else if (!Add(new NotificationAppRule(package, CleanLabel(label, package), mode)))
            {
                return;
            }
        }
        Changed?.Invoke();
        await SaveAsync().ConfigureAwait(false);
    }

    /// <summary>Android package names: letters, digits, '_' and '.', at most 255 characters.</summary>
    public static bool IsValidPackage(string? package)
        => !string.IsNullOrEmpty(package) && package.Length <= MaxPackageLength &&
           package.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '.');

    private static string CleanLabel(string? label, string package)
    {
        string trimmed = string.IsNullOrWhiteSpace(label) ? package : label.Trim();
        return trimmed.Length <= MaxLabelLength ? trimmed : trimmed[..MaxLabelLength];
    }

    /// <summary>A value from a newer build or a hand-edited file counts as the default.</summary>
    private static NotificationAppMode Known(NotificationAppMode mode)
        => Enum.IsDefined(mode) ? mode : NotificationAppMode.Show;

    private int IndexOf(string package) => _apps.FindIndex(app => string.Equals(app.Package, package, StringComparison.Ordinal));

    /// <summary>Adds under the lock; at the cap the oldest app with no choice made makes room.</summary>
    private bool Add(NotificationAppRule rule)
    {
        if (_apps.Count >= MaxApps)
        {
            int oldest = _apps.FindIndex(app => app.Mode == NotificationAppMode.Show);
            if (oldest < 0) return false;
            _apps.RemoveAt(oldest);
        }
        _apps.Add(rule);
        return true;
    }

    /// <summary>One write at a time, each with the latest list.</summary>
    private async Task SaveAsync()
    {
        await _saveLock.WaitAsync().ConfigureAwait(false);
        try
        {
            IReadOnlyList<NotificationAppRule> snapshot = Apps;
            await _settings.UpdateAsync(s => s with { NotificationApps = snapshot }).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Saving notification app rules failed: {error.GetType().Name}");
        }
        finally
        {
            _saveLock.Release();
        }
    }
}
