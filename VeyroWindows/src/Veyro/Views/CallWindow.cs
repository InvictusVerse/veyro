// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Views/WindowViews/CallWindow.xaml.cs, GPL-3.0.
// Modified for Veyro.
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml.Media;
using Veyro.UserControls;

namespace Veyro.Views;

/// <summary>Small always-on-top window for a Bluetooth call with audio on this PC (ADR-029).</summary>
public sealed class CallWindow : Window
{
    private const int WindowWidth = 380;
    private const int WindowHeight = 210;

    public CallWindow(CallWindowViewModel viewModel, IAppThemeModeService theme)
    {
        Title = "Veyro call";
        SystemBackdrop = new MicaBackdrop { Kind = MicaKind.Base };
        Content = new CallView(viewModel);

        OverlappedPresenter presenter = AppWindow.Presenter as OverlappedPresenter ?? OverlappedPresenter.Create();
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.IsMinimizable = true;
        presenter.IsAlwaysOnTop = true;
        AppWindow.SetPresenter(presenter);
        AppWindow.Resize(new global::Windows.Graphics.SizeInt32(WindowWidth, WindowHeight));
        theme.ApplyTheme(this, theme.CurrentTheme);
    }
}
