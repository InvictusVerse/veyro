using Microsoft.UI.Xaml.Media.Animation;
using Veyro.Core.Diagnostics;
using Veyro.Services;

namespace Veyro.Views;

/// <summary>
/// First-run flow (ADR-030): welcome, get the Android app, pair, done. The pairing window is
/// open only while the pairing step is on screen.
/// </summary>
public sealed partial class OnboardingPage : Page
{
    public OnboardingPage()
    {
        InitializeComponent();
        Loaded += OnboardingPage_Loaded;
        Unloaded += (_, _) =>
        {
            Pairing.Paired -= Pairing_Paired;
            Pairing.Stop();
        };
    }

    public OnboardingViewModel ViewModel => VeyroContainer.Instance.OnboardingViewModel;

    private PairingViewModel Pairing => VeyroContainer.Instance.PairingViewModel;

    private async void OnboardingPage_Loaded(object sender, RoutedEventArgs e)
    {
        Pairing.Paired += Pairing_Paired;
        GoToStep(ViewModel.Step);

        try
        {
            byte[] png = VeyroContainer.Instance.QrCodeRenderer.RenderPng(OnboardingViewModel.AndroidReleasesUrl);
            DownloadQrPicture.Source = await QrCodeRenderer.ToBitmapAsync(png);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Rendering the download QR code failed", error);
        }
    }

    /// <summary>Shows a step and keeps the pairing window open only for the pairing step.</summary>
    private void GoToStep(int step)
    {
        ViewModel.Step = step;
        if (ViewModel.IsPair) Pairing.Start();
        else Pairing.Stop();
    }

    private void Pairing_Paired(TrustedPeer peer)
    {
        ViewModel.ShowPaired(peer);
        GoToStep(OnboardingViewModel.SuccessStep);
    }

    private void NextStep_Click(object sender, RoutedEventArgs e) => GoToStep(ViewModel.Step + 1);

    private void PreviousStep_Click(object sender, RoutedEventArgs e) => GoToStep(ViewModel.Step - 1);

    // "Pair later" and "Finish" both leave onboarding for the normal start page.
    private async void FinishSetup_Click(object sender, RoutedEventArgs e) => await LeaveOnboardingAsync(settingsSection: null);

    private async void SetUpBluetooth_Click(object sender, RoutedEventArgs e) =>
        await LeaveOnboardingAsync(MainPage.BluetoothSettingsSection);

    private async Task LeaveOnboardingAsync(string? settingsSection)
    {
        Pairing.Stop();
        try
        {
            await ViewModel.CompleteAsync();
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Saving onboarding completion failed", error);
        }

        Frame.Navigate(App.ShellPageType, settingsSection, new DrillInNavigationTransitionInfo());
        Frame.BackStack.Clear();
    }
}
