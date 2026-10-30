using Veyro.Core.Clipboard;
using Veyro.Core.Diagnostics;
using Veyro.Core.Photos;

namespace Veyro.Services;

/// <summary>
/// "Take photo with phone" and "Scan document with phone" on the PC (ADR-073), from the More menu
/// or Ctrl+Alt+C / Ctrl+Alt+D: asks the phone, then puts the image on the clipboard (not sent back
/// to the phones) and says where it was saved. One request at a time; a second press replaces it.
/// </summary>
public sealed class PhoneCaptureActions
{
    private readonly CaptureService _capture;
    private readonly ClipboardService _clipboard;
    private readonly INotificationToastService _toasts;
    private readonly Func<string, string?> _phoneName;
    private readonly Func<string?> _phone;
    private CancellationTokenSource? _current;

    public PhoneCaptureActions(CaptureService capture, ClipboardService clipboard, INotificationToastService toasts,
        Func<string, string?> phoneName, Func<string?> connectedPhone)
    {
        _capture = capture;
        _clipboard = clipboard;
        _toasts = toasts;
        _phoneName = phoneName;
        _phone = connectedPhone;
    }

    public bool CanUse(string? peerId) => peerId is not null && _capture.CanUse(peerId);

    /// <summary>For the shown phone (shortcuts).</summary>
    public Task TakeAsync(bool scan) => _phone() is { } phone ? TakeAsync(phone, scan) : NoPhoneAsync();

    private Task NoPhoneAsync()
    {
        _toasts.ShowStatus("No phone connected", "Connect a phone to take a photo with it.", "capture");
        return Task.CompletedTask;
    }

    public async Task TakeAsync(string peerId, bool scan)
    {
        string phone = _phoneName(peerId) ?? "the phone";
        if (!_capture.CanUse(peerId))
        {
            _toasts.ShowStatus($"{phone} cannot take photos for this PC", "Update Veyro on the phone, or connect it.", "capture");
            return;
        }
        _current?.Cancel();
        var mine = new CancellationTokenSource();
        _current = mine;
        _toasts.ShowStatus(scan ? $"Scan the page with {phone}" : $"Take the photo with {phone}",
            "Tap the notification on the phone.", "capture");
        CaptureOutcome outcome = await _capture.RequestAsync(peerId, scan ? SpeakerCaptureRules.ModeScan : SpeakerCaptureRules.ModePhoto, mine.Token);
        if (!ReferenceEquals(_current, mine)) return;
        _current = null;
        if (outcome.FilePath is not { } path)
        {
            if (outcome.Reason != "cancelled" || !mine.IsCancellationRequested)
                _toasts.ShowStatus(FailureTitle(outcome.Reason, phone, scan), FailureText(outcome.Reason), "capture");
            return;
        }
        bool copied = false;
        try { copied = await _clipboard.CopyImageToPcAsync(await File.ReadAllBytesAsync(path)); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            VeyroTrace.Warn($"Capture not copied: {error.GetType().Name}");
        }
        _toasts.ShowStatus(scan ? $"Scan from {phone}" : $"Photo from {phone}",
            (copied ? "On the clipboard: press Ctrl+V to paste it. " : "") + $"Saved as {Path.GetFileName(path)} in {Path.GetDirectoryName(path)}.",
            "capture");
    }

    private static string FailureTitle(string? reason, string phone, bool scan) => reason switch
    {
        "declined" => $"{phone} declined",
        "cancelled" => scan ? "Scan cancelled" : "Photo cancelled",
        "expired" => $"{phone} did not answer in time",
        "permission" => $"Veyro on {phone} may not use the camera",
        "busy" => $"{phone} is busy with another photo",
        "network" => $"{phone} disconnected",
        _ => scan ? "The scan did not arrive" : "The photo did not arrive",
    };

    private static string FailureText(string? reason) => reason switch
    {
        "declined" => "Ask again whenever you like. \"Take photos and scans for PCs\" must be on in Veyro on the phone.",
        "permission" => "Allow Camera for Veyro in the phone's settings.",
        "expired" => "The request is open for 2 minutes; make sure the phone is unlocked.",
        _ => "Try again.",
    };
}
