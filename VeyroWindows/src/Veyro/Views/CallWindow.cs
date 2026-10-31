using System.Runtime.InteropServices;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml.Media;
using Veyro.UserControls;

namespace Veyro.Views;

/// <summary>
/// The small window for a Bluetooth call whose audio plays on this PC (ADR-029). It stays above
/// other windows, cannot be resized and shows <see cref="CallView"/>.
/// </summary>
public sealed class CallWindow : Window
{
    /// <summary>Size of the content area in device-independent pixels.</summary>
    private const int ContentWidth = 400;
    private const int ContentHeight = 190;

    public CallWindow(CallWindowViewModel viewModel, IAppThemeModeService theme)
    {
        Title = "Veyro call";
        SystemBackdrop = new MicaBackdrop { Kind = MicaKind.Base };
        Content = new CallView(viewModel);

        OverlappedPresenter presenter = AppWindow.Presenter as OverlappedPresenter ?? OverlappedPresenter.Create();
        presenter.IsAlwaysOnTop = true;
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.IsMinimizable = true;
        AppWindow.SetPresenter(presenter);

        double scale = DisplayScale();
        AppWindow.ResizeClient(new global::Windows.Graphics.SizeInt32(
            (int)Math.Round(ContentWidth * scale), (int)Math.Round(ContentHeight * scale)));

        theme.ApplyTheme(this, theme.CurrentTheme);
    }

    /// <summary>The window's display scale (1.0 at 100%). AppWindow sizes are in physical pixels.</summary>
    private double DisplayScale()
    {
        try
        {
            uint dpi = GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this));
            if (dpi > 0) return dpi / 96.0;
        }
        catch (Exception)
        {
            // Fall back to 100%: a slightly small window is better than none.
        }
        return 1.0;
    }

    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern uint GetDpiForWindow(IntPtr hwnd);
}
