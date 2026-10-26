// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Features/CallFeature.cs, GPL-3.0.
// Modified for Veyro.
using Veyro.Core.Diagnostics;
using Veyro.Views;

namespace Veyro.Services;

/// <summary>
/// Opens the in-call window for Bluetooth calls and closes it when they end (ADR-029). Which call
/// is shown follows <see cref="CallPresentation.ShowsWindow"/>: an active (non-held) call first.
/// A window the user closed stays closed for that call.
/// </summary>
public sealed class CallWindowService : IDisposable
{
    private readonly IBluetoothCallingService _bluetooth;
    private readonly ICallService _calls;
    private readonly IAppThemeModeService _theme;
    private readonly DispatcherQueue? _dispatcher;
    private readonly List<InCallViewModel> _sessions = [];
    private readonly HashSet<string> _dismissed = new(StringComparer.Ordinal);
    private CallWindow? _window;
    private bool _closing;

    public CallWindowService(IBluetoothCallingService bluetooth, ICallService calls, IAppThemeModeService theme)
    {
        _bluetooth = bluetooth;
        _calls = calls;
        _theme = theme;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _bluetooth.CallAdded += OnCallAdded;
        _bluetooth.Changed += OnChanged;
        _calls.Changed += OnWifiChanged;
    }

    public CallWindowViewModel WindowViewModel { get; } = new();

    public void Dispose()
    {
        _bluetooth.CallAdded -= OnCallAdded;
        _bluetooth.Changed -= OnChanged;
        _calls.Changed -= OnWifiChanged;
        foreach (InCallViewModel session in _sessions) session.Dispose();
        _sessions.Clear();
        CloseWindow();
    }

    private void OnCallAdded(IBluetoothCall call) => Post(() =>
    {
        if (_sessions.Any(s => s.CallId == call.CallId)) return;
        var session = new InCallViewModel(call, _calls, _bluetooth.LinkedPeerId, _dispatcher);
        session.StateChanged += Evaluate;
        _sessions.Add(session);
        Evaluate();
    });

    private void OnChanged() => Post(Evaluate);

    private void OnWifiChanged(CallChange change)
    {
        if (change.Kind is CallChangeKind.Availability or CallChangeKind.Cleared) Post(Evaluate);
    }

    private void Evaluate()
    {
        HashSet<string> live = new(_bluetooth.Calls.Select(c => c.CallId), StringComparer.Ordinal);
        foreach (InCallViewModel ended in _sessions.Where(s => CallPresentation.IsOver(s.Status) || !live.Contains(s.CallId)).ToList())
        {
            ended.StateChanged -= Evaluate;
            ended.Dispose();
            _sessions.Remove(ended);
            _dismissed.Remove(ended.CallId);
        }

        string? peer = _bluetooth.LinkedPeerId;
        bool wifi = peer is not null && _calls.IsAvailable(peer);
        InCallViewModel? shown = _sessions
            .Where(s => CallPresentation.ShowsWindow(s.Status, wifi))
            .OrderBy(s => s.Status == BluetoothCallStatus.Held)
            .FirstOrDefault();
        WindowViewModel.Call = shown;

        if (shown is null || _dismissed.Contains(shown.CallId))
        {
            if (shown is null) CloseWindow();
            return;
        }
        if (_window is not null) return;
        try
        {
            _window = new CallWindow(WindowViewModel, _theme);
            _window.Closed += OnWindowClosed;
            _window.Activate();
            VeyroTrace.Info("Call window opened");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Opening call window failed", error);
            _window = null;
        }
    }

    private void OnWindowClosed(object sender, WindowEventArgs args)
    {
        if (_window is { } window) window.Closed -= OnWindowClosed;
        _window = null;
        // Closed by the user while a call is still up: do not pop it open again for this call.
        if (!_closing && WindowViewModel.Call is { } call) _dismissed.Add(call.CallId);
    }

    private void CloseWindow()
    {
        if (_window is not { } window) return;
        _closing = true;
        try { window.Close(); }
        catch (Exception error) { VeyroTrace.Error("Closing call window failed", error); }
        finally { _closing = false; }
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}
