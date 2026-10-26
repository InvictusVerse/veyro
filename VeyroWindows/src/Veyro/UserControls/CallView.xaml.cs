// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/UserControls/CallView.xaml.cs, GPL-3.0.
// Modified for Veyro.
namespace Veyro.UserControls;

public sealed partial class CallView : UserControl
{
    public CallWindowViewModel ViewModel { get; }

    public CallView(CallWindowViewModel viewModel)
    {
        ViewModel = viewModel;
        InitializeComponent();
    }

    private async void Answer_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Call is { } call) await call.AnswerAsync();
    }

    private async void Decline_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Call is { } call) await call.DeclineAsync();
    }

    private async void End_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Call is { } call) await call.EndAsync();
    }

    private async void Mute_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Call is { } call) await call.ToggleMuteAsync();
    }

    private async void Hold_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Call is { } call) await call.ToggleHoldAsync();
    }

    private async void Audio_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Call is { } call) await call.SwitchAudioAsync();
    }
}
