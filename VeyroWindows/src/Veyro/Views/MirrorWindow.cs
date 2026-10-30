using System.Runtime.InteropServices;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml.Media;
using Veyro.Core.Diagnostics;
using Veyro.Services;
using Veyro.UserControls;

namespace Veyro.Views;

/// <summary>
/// One phone's mirrored screen (ADR-066). A normal top-level window titled
/// "&lt;phone&gt; - Veyro mirror" so OBS Studio's Window Capture can find it; it is never excluded
/// from capture. Minimising it pauses the phone's encoder; closing it stops the session.
/// </summary>
public sealed class MirrorWindow : Window
{
    private const int WidthDip = 420;
    private const int HeightDip = 860;

    private readonly MirrorView _view;
    private readonly ISettingsStore _settings;
    private readonly OverlappedPresenter _windowed;
    private bool _minimised;

    /// <summary>Full screen on the window's display; Esc, double-click or the top button leave it.</summary>
    private void SetFullScreen(bool on)
    {
        try
        {
            if (on) AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
            else AppWindow.SetPresenter(_windowed);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Full screen not changed: {error.GetType().Name}");
        }
        _view.FullScreen = AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen;
    }

    private void OnSettingsChanged(AppSettings settings) =>
        DispatcherQueue.TryEnqueue(_view.RefreshQuiet);

    public MirrorWindow(string peerId, string phoneName, string pcId, IPeerSessionRegistry sessions, ISettingsStore settings,
        Func<PeerMessage.StreamCaps?> caps, IAppThemeModeService theme)
    {
        PeerId = peerId;
        Title = $"{phoneName} - Veyro mirror";
        SystemBackdrop = new MicaBackdrop { Kind = MicaKind.Base };
        _view = new MirrorView(settings);
        _view.SetWindowTitle(Title);
        Content = _view;
        Controller = new MirrorController(peerId, phoneName, pcId, sessions, settings, caps, _view.PlayerElement);
        _view.Attach(Controller);
        _view.CloseRequested += Close;
        _view.FullScreenRequested += SetFullScreen;
        _settings = settings;
        settings.SettingsChanged += OnSettingsChanged;

        OverlappedPresenter presenter = AppWindow.Presenter as OverlappedPresenter ?? OverlappedPresenter.Create();
        presenter.IsResizable = true;
        presenter.IsMaximizable = true;
        presenter.IsMinimizable = true;
        AppWindow.SetPresenter(presenter);
        _windowed = presenter;
        AppWindow.Resize(InitialSize());
        AppWindow.Changed += OnAppWindowChanged;
        theme.ApplyTheme(this, theme.CurrentTheme);
        Closed += OnClosed;
    }

    public string PeerId { get; }

    public MirrorController Controller { get; }

    /// <summary>A phone-shaped window at the screen's scale, no taller than the work area.</summary>
    private global::Windows.Graphics.SizeInt32 InitialSize()
    {
        double scale = 1;
        try
        {
            uint dpi = GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this));
            if (dpi > 0) scale = dpi / 96.0;
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
        }
        int width = (int)(WidthDip * scale);
        int height = (int)(HeightDip * scale);
        DisplayArea? area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary);
        if (area is not null)
        {
            height = Math.Min(height, area.WorkArea.Height - (int)(48 * scale));
            width = Math.Min(width, area.WorkArea.Width - (int)(48 * scale));
        }
        return new global::Windows.Graphics.SizeInt32(Math.Max(320, width), Math.Max(480, height));
    }

    private void OnAppWindowChanged(AppWindow sender, AppWindowChangedEventArgs args)
    {
        if (!args.DidPresenterChange && !args.DidSizeChange) return;
        bool minimised = sender.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized };
        if (minimised == _minimised) return;
        _minimised = minimised;
        _ = Controller.SetPausedAsync(minimised);
    }

    private void OnClosed(object sender, WindowEventArgs args)
    {
        Closed -= OnClosed;
        AppWindow.Changed -= OnAppWindowChanged;
        _view.CloseRequested -= Close;
        _view.FullScreenRequested -= SetFullScreen;
        _settings.SettingsChanged -= OnSettingsChanged;
        _view.Detach();
        Controller.Dispose();
        VeyroTrace.Info("Mirror window closed");
    }

    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern uint GetDpiForWindow(IntPtr hwnd);
}
