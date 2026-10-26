// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Dialogs/ConnectionRequestDialog.xaml.cs and
// src/Sefirah/ViewModels/Dialogs/ConnectionRequestViewModel.cs, GPL-3.0.
// Modified for Veyro.
namespace Veyro.Dialogs;

/// <summary>
/// Legacy pairing prompt, shown only when no pairing window is open (04-PROTOCOL.md 4.2). The
/// user accepts and types the code on the phone. Declines by itself when the time runs out.
/// The code is shown, never logged.
/// </summary>
public sealed partial class ConnectionRequestDialog : ContentDialog
{
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(60);

    private readonly DispatcherQueueTimer _timer;
    private readonly DateTimeOffset _deadline;

    public ConnectionRequestDialog(string peerName, string fingerprint, string sixDigitCode, TimeSpan? timeout = null)
    {
        InitializeComponent();
        PeerNameText.Text = string.IsNullOrWhiteSpace(peerName) ? "Unnamed phone" : peerName;
        FingerprintText.Text = fingerprint;
        PinCodeText.Text = sixDigitCode.Length == 6 ? $"{sixDigitCode[..3]} {sixDigitCode[3..]}" : sixDigitCode;

        _deadline = DateTimeOffset.UtcNow + (timeout ?? DefaultTimeout);
        _timer = DispatcherQueue.GetForCurrentThread().CreateTimer();
        _timer.Interval = TimeSpan.FromSeconds(1);
        _timer.Tick += (_, _) => UpdateCountdown();
        Opened += (_, _) => _timer.Start();
        Closed += (_, _) => _timer.Stop();
        UpdateCountdown();
    }

    private void UpdateCountdown()
    {
        TimeSpan left = _deadline - DateTimeOffset.UtcNow;
        if (left <= TimeSpan.Zero)
        {
            _timer.Stop();
            // Closing without a button press is a decline.
            Hide();
            return;
        }
        TimeoutText.Text = $"Declines automatically in {(int)Math.Ceiling(left.TotalSeconds)} s";
    }
}
