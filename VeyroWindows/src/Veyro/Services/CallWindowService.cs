using Veyro.Core.Diagnostics;
using Veyro.Views;

namespace Veyro.Services;

/// <summary>
/// Owns the in-call window for Bluetooth calls (ADR-029). It keeps one <see cref="InCallViewModel"/>
/// per live call, opens the window while one of them should be shown and closes it when none is.
/// <see cref="CallPresentation.ShowsWindow"/> decides which calls qualify, and a call that is not
/// on hold goes before one that is. When the user closes the window during a call, it stays closed
/// for that call. Must be created on the UI thread.
/// </summary>
public sealed class CallWindowService : IDisposable
{
    private readonly IBluetoothCallingService _bluetooth;
    private readonly ICallService _phoneCalls;
    private readonly IAppThemeModeService _theme;
    private readonly DispatcherQueue? _uiThread;
    private readonly Dictionary<string, InCallViewModel> _sessions = new(StringComparer.Ordinal);
    private readonly HashSet<string> _closedByUser = new(StringComparer.Ordinal);
    private CallWindow? _window;
    private bool _closingFromCode;

    public CallWindowService(IBluetoothCallingService bluetooth, ICallService phoneCalls, IAppThemeModeService theme)
    {
        _bluetooth = bluetooth;
        _phoneCalls = phoneCalls;
        _theme = theme;
        _uiThread = DispatcherQueue.GetForCurrentThread();

        _bluetooth.CallAdded += OnCallAdded;
        _bluetooth.Changed += OnLineChanged;
        _phoneCalls.Changed += OnPhoneCallsChanged;
    }

    /// <summary>The call shown in the window. Null while no window is wanted.</summary>
    public CallWindowViewModel WindowViewModel { get; } = new();

    public void Dispose()
    {
        _bluetooth.CallAdded -= OnCallAdded;
        _bluetooth.Changed -= OnLineChanged;
        _phoneCalls.Changed -= OnPhoneCallsChanged;
        foreach (InCallViewModel session in _sessions.Values) session.Dispose();
        _sessions.Clear();
        CloseWindow();
    }

    private void OnCallAdded(IBluetoothCall call) => RunOnUiThread(() =>
    {
        if (_sessions.ContainsKey(call.CallId)) return;
        var session = new InCallViewModel(call, _phoneCalls, _bluetooth.LinkedPeerId, _uiThread);
        session.StateChanged += Reconcile;
        _sessions.Add(call.CallId, session);
        Reconcile();
    });

    private void OnLineChanged() => RunOnUiThread(Reconcile);

    private void OnPhoneCallsChanged(CallChange change)
    {
        // Whether the phone is reachable over Wi-Fi decides if a ringing call gets this window.
        if (change.Kind is CallChangeKind.Availability or CallChangeKind.Cleared) RunOnUiThread(Reconcile);
    }

    /// <summary>Drops finished calls, picks the call to show and opens or closes the window to match.</summary>
    private void Reconcile()
    {
        DropFinishedSessions();

        string? peer = _bluetooth.LinkedPeerId;
        bool phoneOnWifi = peer is not null && _phoneCalls.IsAvailable(peer);
        InCallViewModel? toShow = _sessions.Values
            .Where(session => CallPresentation.ShowsWindow(session.Status, phoneOnWifi))
            .OrderBy(session => session.Status == BluetoothCallStatus.Held)
            .FirstOrDefault();
        WindowViewModel.Call = toShow;

        if (toShow is null)
        {
            CloseWindow();
            return;
        }
        if (_window is null && !_closedByUser.Contains(toShow.CallId)) OpenWindow();
    }

    private void DropFinishedSessions()
    {
        HashSet<string> liveIds = _bluetooth.Calls.Select(call => call.CallId).ToHashSet(StringComparer.Ordinal);
        foreach ((string id, InCallViewModel session) in _sessions.ToArray())
        {
            if (!CallPresentation.IsOver(session.Status) && liveIds.Contains(id)) continue;
            session.StateChanged -= Reconcile;
            session.Dispose();
            _sessions.Remove(id);
            _closedByUser.Remove(id);
        }
    }

    private void OpenWindow()
    {
        try
        {
            var window = new CallWindow(WindowViewModel, _theme);
            window.Closed += OnWindowClosed;
            _window = window;
            window.Activate();
            VeyroTrace.Info("Call window opened");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("The call window could not be opened", error);
            _window = null;
        }
    }

    private void OnWindowClosed(object sender, WindowEventArgs args)
    {
        if (sender is CallWindow window) window.Closed -= OnWindowClosed;
        _window = null;
        // Closed by the user in the middle of a call: leave it closed for the rest of that call.
        if (!_closingFromCode && WindowViewModel.Call is { } call) _closedByUser.Add(call.CallId);
    }

    private void CloseWindow()
    {
        if (_window is not { } window) return;
        _closingFromCode = true;
        try { window.Close(); }
        catch (Exception error) { VeyroTrace.Error("The call window could not be closed", error); }
        finally { _closingFromCode = false; }
    }

    private void RunOnUiThread(Action action)
    {
        if (_uiThread is null || _uiThread.HasThreadAccess) action();
        else _uiThread.TryEnqueue(() => action());
    }
}
