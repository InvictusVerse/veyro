namespace Veyro.UserControls;

/// <summary>The call card inside <see cref="Views.CallWindow"/>. All behaviour lives in <see cref="InCallViewModel"/>.</summary>
public sealed partial class CallView : UserControl
{
    public CallView(CallWindowViewModel viewModel)
    {
        ViewModel = viewModel;
        InitializeComponent();
    }

    public CallWindowViewModel ViewModel { get; }

    private void Answer_Click(object sender, RoutedEventArgs e) => Act(call => call.AnswerAsync());
    private void Decline_Click(object sender, RoutedEventArgs e) => Act(call => call.DeclineAsync());
    private void End_Click(object sender, RoutedEventArgs e) => Act(call => call.EndAsync());
    private void Mute_Click(object sender, RoutedEventArgs e) => Act(call => call.ToggleMuteAsync());
    private void Hold_Click(object sender, RoutedEventArgs e) => Act(call => call.ToggleHoldAsync());
    private void Audio_Click(object sender, RoutedEventArgs e) => Act(call => call.SwitchAudioAsync());

    /// <summary>Runs an action on the call being shown. The view model reports failures itself.</summary>
    private async void Act(Func<InCallViewModel, Task> action)
    {
        if (ViewModel.Call is { } call) await action(call);
    }
}
