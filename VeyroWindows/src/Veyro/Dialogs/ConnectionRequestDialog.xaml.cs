// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Dialogs/ConnectionRequestDialog.xaml.cs and
// src/Sefirah/ViewModels/Dialogs/ConnectionRequestViewModel.cs, GPL-3.0.
// Modified for Veyro.
using Microsoft.UI.Xaml.Automation;

namespace Veyro.Dialogs;

/// <summary>
/// Confirm-code pairing prompt (04-PROTOCOL.md 4.3, ADR-031): the phone's name, its fingerprint
/// and the 6-digit code both screens show, with Accept / Decline. Declines by itself at the
/// deadline and closes when the phone cancels. The code is shown, never logged.
/// </summary>
public sealed partial class ConnectionRequestDialog : ContentDialog
{
    private readonly DispatcherQueueTimer _timer;
    private readonly DateTimeOffset _deadline;
    private readonly TimeSpan _window;
    private readonly CancellationTokenRegistration _abandonedRegistration;

    public ConnectionRequestDialog(
        string peerName,
        string fingerprint,
        string sixDigitCode,
        DateTimeOffset deadline,
        CancellationToken abandoned = default)
    {
        InitializeComponent();
        PeerNameText.Text = string.IsNullOrWhiteSpace(peerName) ? "Unnamed phone" : peerName;
        FingerprintText.Text = fingerprint;
        CodeText.Text = sixDigitCode.Length == 6 ? $"{sixDigitCode[..3]} {sixDigitCode[3..]}" : sixDigitCode;
        // Read digit by digit, so a screen reader does not say "seventy-seven thousand".
        AutomationProperties.SetName(CodeText, "Pairing code " + string.Join(' ', sixDigitCode.ToCharArray()));

        _deadline = deadline;
        _window = deadline - DateTimeOffset.UtcNow;
        _timer = DispatcherQueue.GetForCurrentThread().CreateTimer();
        _timer.Interval = TimeSpan.FromSeconds(1);
        _timer.Tick += (_, _) => UpdateCountdown();
        Opened += (_, _) => _timer.Start();
        Closed += (_, _) =>
        {
            _timer.Stop();
            _abandonedRegistration.Dispose();
        };

        // The phone cancelled or went away: the request no longer exists, so take it off screen.
        DispatcherQueue queue = DispatcherQueue.GetForCurrentThread();
        _abandonedRegistration = abandoned.Register(() => queue.TryEnqueue(Hide));
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
        int seconds = (int)Math.Ceiling(left.TotalSeconds);
        TimeoutText.Text = $"Declines automatically in {seconds} s";
        CountdownRing.Value = _window <= TimeSpan.Zero ? 0 : Math.Clamp(left / _window * 100, 0, 100);
    }
}
