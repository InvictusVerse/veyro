namespace Veyro.ViewModels;

/// <summary>
/// First-run onboarding (ADR-030): Welcome, Get the Android app, Pair, Success. Pairing itself is
/// <see cref="PairingViewModel"/>; this only tracks the step and the completion flag.
/// </summary>
public sealed class OnboardingViewModel(ISettingsStore settings) : BaseViewModel
{
    public const string AndroidReleasesUrl = "https://github.com/InvictusVerse/veyro/releases";
    public const string SourceUrl = "https://github.com/InvictusVerse/veyro";

    public const int WelcomeStep = 0;
    public const int GetAppStep = 1;
    public const int PairStep = 2;
    public const int SuccessStep = 3;
    public const int StepCount = 4;

    private int _step;
    private string _pairedName = string.Empty;
    private string _pairedFingerprint = string.Empty;

    public int Step
    {
        get => _step;
        set
        {
            int clamped = Math.Clamp(value, WelcomeStep, SuccessStep);
            if (!SetProperty(ref _step, clamped)) return;
            OnPropertyChanged(nameof(IsWelcome));
            OnPropertyChanged(nameof(IsGetApp));
            OnPropertyChanged(nameof(IsPair));
            OnPropertyChanged(nameof(IsSuccess));
            OnPropertyChanged(nameof(StepText));
        }
    }

    public bool IsWelcome => _step == WelcomeStep;
    public bool IsGetApp => _step == GetAppStep;
    public bool IsPair => _step == PairStep;
    public bool IsSuccess => _step == SuccessStep;

    public string StepText => _step switch
    {
        WelcomeStep => "Step 1 of 4: Welcome",
        GetAppStep => "Step 2 of 4: Get the Android app",
        PairStep => "Step 3 of 4: Pair your phone",
        _ => "Step 4 of 4: Done"
    };

    public string PairedName
    {
        get => _pairedName;
        private set => SetProperty(ref _pairedName, value);
    }

    public string PairedFingerprint
    {
        get => _pairedFingerprint;
        private set => SetProperty(ref _pairedFingerprint, value);
    }

    /// <summary>Shows the newly paired phone on the Success step.</summary>
    public void ShowPaired(TrustedPeer peer)
    {
        PairedName = peer.DisplayName;
        try { PairedFingerprint = DeviceId.FormatFingerprint(Convert.FromBase64String(peer.PublicKeyBase64)); }
        catch (Exception error) when (error is FormatException or ArgumentException)
        {
            PairedFingerprint = DeviceId.ParseOrNull(peer.DeviceId) is { } id ? DeviceId.FormatFingerprint(id) : peer.DeviceId;
        }
        Step = SuccessStep;
    }

    public Task CompleteAsync() => settings.UpdateAsync(s => s with { OnboardingCompleted = true });
}
