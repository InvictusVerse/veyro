// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Views/Onboarding/WelcomePage.xaml.cs, GPL-3.0.
// Modified for Veyro.
using Microsoft.UI.Xaml.Media.Animation;
using Veyro.Core.Diagnostics;
using Veyro.Services;

namespace Veyro.Views;

/// <summary>
/// First-run flow (ADR-030). The pairing window is open only while the Pair step is visible.
/// </summary>
public sealed partial class OnboardingPage : Page
{
    public OnboardingViewModel ViewModel => VeyroContainer.Instance.OnboardingViewModel;
    private PairingViewModel Pairing => VeyroContainer.Instance.PairingViewModel;

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

    private async void OnboardingPage_Loaded(object sender, RoutedEventArgs e)
    {
        Pairing.Paired += Pairing_Paired;
        SetStep(ViewModel.Step);
        try
        {
            byte[] png = VeyroContainer.Instance.QrCodeRenderer.RenderPng(OnboardingViewModel.AndroidReleasesUrl);
            AppQrImage.Source = await QrCodeRenderer.ToBitmapAsync(png);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Rendering the download QR code failed", error);
        }
    }

    private void SetStep(int step)
    {
        ViewModel.Step = step;
        if (ViewModel.IsPair) Pairing.Start();
        else Pairing.Stop();
    }

    private void Pairing_Paired(TrustedPeer peer)
    {
        ViewModel.ShowPaired(peer);
        SetStep(OnboardingViewModel.SuccessStep);
    }

    private void Next_Click(object sender, RoutedEventArgs e) => SetStep(ViewModel.Step + 1);

    private void Back_Click(object sender, RoutedEventArgs e) => SetStep(ViewModel.Step - 1);

    private async void PairLater_Click(object sender, RoutedEventArgs e) => await FinishAsync(null);

    private async void Finish_Click(object sender, RoutedEventArgs e) => await FinishAsync(null);

    private async void SetUpBluetooth_Click(object sender, RoutedEventArgs e) => await FinishAsync(MainPage.BluetoothSettingsSection);

    private async Task FinishAsync(string? settingsSection)
    {
        Pairing.Stop();
        try { await ViewModel.CompleteAsync(); }
        catch (Exception error) { VeyroTrace.Error("Saving onboarding completion failed", error); }
        Frame.Navigate(typeof(MainPage), settingsSection, new DrillInNavigationTransitionInfo());
        Frame.BackStack.Clear();
    }
}
