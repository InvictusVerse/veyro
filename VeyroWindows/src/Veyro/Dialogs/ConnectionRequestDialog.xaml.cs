using Microsoft.UI.Xaml.Automation;

namespace Veyro.Dialogs;

/// <summary>
/// Confirm-code pairing prompt (04-PROTOCOL.md 4.3, ADR-031). Shows the phone's name and
/// fingerprint and the six-digit code both screens display, with Accept and Decline. It declines
/// by itself at the deadline and closes when the phone cancels the request. The code is shown on
/// screen only; it is never logged.
/// </summary>
public sealed partial class ConnectionRequestDialog : ContentDialog
{
    private readonly DispatcherQueueTimer _tick;
    private readonly DateTimeOffset _deadline;
    private readonly TimeSpan _totalWindow;
    private readonly CancellationTokenRegistration _cancelHook;

    /// <param name="phoneName">Name the phone announced; blank is shown as "Unnamed phone".</param>
    /// <param name="fingerprint">The phone's fingerprint, for comparison with its own screen.</param>
    /// <param name="code">The six-digit confirmation code.</param>
    /// <param name="deadline">When the request expires and counts as declined.</param>
    /// <param name="abandoned">Cancelled when the phone withdraws, which closes the dialog.</param>
    public ConnectionRequestDialog(
        string phoneName,
        string fingerprint,
        string code,
        DateTimeOffset deadline,
        CancellationToken abandoned = default)
    {
        InitializeComponent();

        PhoneNameBlock.Text = string.IsNullOrWhiteSpace(phoneName) ? "Unnamed phone" : phoneName;
        PhoneFingerprintBlock.Text = fingerprint;
        PairingCodeBlock.Text = code.Length == 6 ? $"{code[..3]} {code[3..]}" : code;
        // Spell the digits out so a screen reader does not read the code as one large number.
        AutomationProperties.SetName(PairingCodeBlock, "Pairing code " + string.Join(' ', code.ToCharArray()));

        _deadline = deadline;
        _totalWindow = deadline - DateTimeOffset.UtcNow;

        DispatcherQueue queue = DispatcherQueue.GetForCurrentThread();
        _tick = queue.CreateTimer();
        _tick.Interval = TimeSpan.FromSeconds(1);
        _tick.Tick += (_, _) => RefreshTimeLeft();

        // The timer runs only while the dialog is open and is stopped when it closes.
        Opened += (_, _) => _tick.Start();
        Closed += (_, _) =>
        {
            _tick.Stop();
            _cancelHook.Dispose();
        };

        // The phone cancelled or went away: the request no longer exists, so remove the prompt.
        _cancelHook = abandoned.Register(() => queue.TryEnqueue(Hide));

        RefreshTimeLeft();
    }

    private void RefreshTimeLeft()
    {
        TimeSpan remaining = _deadline - DateTimeOffset.UtcNow;
        if (remaining <= TimeSpan.Zero)
        {
            _tick.Stop();
            // Closing without pressing a button counts as declining.
            Hide();
            return;
        }

        TimeLeftBlock.Text = $"Declines automatically in {(int)Math.Ceiling(remaining.TotalSeconds)} s";
        TimeLeftBar.Value = _totalWindow <= TimeSpan.Zero ? 0 : Math.Clamp(remaining / _totalWindow * 100, 0, 100);
    }
}
