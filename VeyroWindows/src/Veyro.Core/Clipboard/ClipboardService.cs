using System.Text.RegularExpressions;
using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Clipboard;

/// <summary>One item in the PC's virtual clipboard (ADR-043). In memory only.</summary>
public sealed record ClipItem(
    string Id,
    string PeerId,
    string Kind,
    string? Text,
    byte[]? Image,
    DateTimeOffset At)
{
    /// <summary>First http(s) link in the text, for an Open button. Null when there is none.</summary>
    public Uri? Link => ClipboardService.FindLink(Text);
}

/// <summary>Writes to the Windows clipboard. Implemented in the app (WinRT, UI thread).</summary>
public interface IClipboardSink
{
    Task SetTextAsync(string text);
    Task SetImageAsync(byte[] image);

    /// <summary>
    /// Like <see cref="SetTextAsync"/>, but kept out of the system's clipboard history and cloud
    /// sync where the platform allows it: a one-time code (ADR-063).
    /// </summary>
    Task SetPrivateTextAsync(string text) => SetTextAsync(text);
}

/// <summary>Toasts for clipboard events. Implemented by the app's toast service.</summary>
public interface IClipboardNotifier
{
    /// <summary>A phone shared text: it is on the PC clipboard now.</summary>
    void ShowApplied(string peerId, ClipItem item);

    /// <summary>A phone sent its clipboard: it is in the virtual clipboard.</summary>
    void ShowStored(string peerId, ClipItem item);
}

/// <summary>
/// Clipboard between this PC and its phones (ADR-043, 04-PROTOCOL.md 6.5).
///  - Phone shares text / a link (apply = true): put on the PC clipboard, toast (Open for links).
///  - Phone sends its clipboard (apply = false): kept in the virtual clipboard, toast.
///  - The PC copies something: sent to every connected capable phone (setting, default on);
///    images only when the image setting is on. What this service itself just put on the
///    clipboard is not echoed back.
/// History: newest first, at most <see cref="MaxItems"/>, in memory only.
/// Clipboard contents are user data and are never logged.
/// </summary>
public sealed partial class ClipboardService : IDisposable
{
    public const int MaxItems = 50;

    private readonly IPeerSessionRegistry _sessions;
    private readonly Func<bool> _sendEnabled;
    private readonly Func<bool> _imagesEnabled;
    private readonly object _gate = new();
    private readonly List<ClipItem> _items = new();
    private readonly HashSet<string> _seenIds = new(StringComparer.Ordinal);
    private static readonly TimeSpan EchoWindow = TimeSpan.FromSeconds(2);

    private readonly Func<DateTimeOffset> _clock;
    private string? _lastAppliedFingerprint;
    private DateTimeOffset? _lastImageAppliedAt;
    private string? _lastSentFingerprint;
    private DateTimeOffset _lastSentAt;

    /// <summary>
    /// ADR-059: a copy made while a phone had "Auto-fetch clipboard" off is still sent to it when
    /// it turns the switch back on, if it is the latest copy and no older than this.
    /// </summary>
    public static readonly TimeSpan CatchUpWindow = TimeSpan.FromMinutes(10);

    // ADR-059, all under _gate. Phones that said "auto-fetch off" (default on until a phone says
    // otherwise; kept across its reconnects, each session re-announces it).
    private readonly HashSet<string> _receiveOff = new(StringComparer.OrdinalIgnoreCase);
    // Per phone, the id of the copy it was not sent because its switch was off.
    private readonly Dictionary<string, string> _missed = new(StringComparer.OrdinalIgnoreCase);
    // The latest copy this PC sent out (or held back), for the catch-up.
    private PeerMessage.Clip? _latestCopy;
    private DateTimeOffset _latestCopyAt;

    public IClipboardSink? Sink { get; set; }
    public IClipboardNotifier? Notifier { get; set; }

    /// <summary>The virtual clipboard changed. Raised on a background thread.</summary>
    public event Action? Changed;

    /// <summary>A phone turned "Auto-fetch clipboard" on or off (argument: device id). Background thread.</summary>
    public event Action<string>? ReceivePreferenceChanged;

    /// <summary>The phone said its "Auto-fetch clipboard" is off: this PC does not send it its copies.</summary>
    public bool IsReceiveOff(string peerId)
    {
        lock (_gate) return _receiveOff.Contains(peerId);
    }

    /// <summary>Every phone whose "Auto-fetch clipboard" is off, as last announced.</summary>
    public IReadOnlyList<string> PeersWithReceiveOff
    {
        get { lock (_gate) return _receiveOff.ToList(); }
    }

    /// <summary>The PC's note for phones with auto-fetch off, or null when there are none.</summary>
    public static string? DescribeReceiveOff(IReadOnlyList<string> phoneNames)
    {
        if (phoneNames.Count == 0) return null;
        string names = phoneNames.Count == 1
            ? phoneNames[0]
            : string.Join(", ", phoneNames.Take(phoneNames.Count - 1)) + " and " + phoneNames[^1];
        return $"Auto-fetch is off on {names}. What you copy here is not sent there until it is turned back on.";
    }

    public ClipboardService(
        IPeerSessionRegistry sessions,
        Func<bool> sendEnabled,
        Func<bool> imagesEnabled,
        Func<DateTimeOffset>? clock = null)
    {
        _sessions = sessions;
        _sendEnabled = sendEnabled;
        _imagesEnabled = imagesEnabled;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        _sessions.DeviceMessageReceived += OnDeviceMessage;
    }

    public IReadOnlyList<ClipItem> Items
    {
        get { lock (_gate) return _items.ToList(); }
    }

    public void Remove(string id)
    {
        lock (_gate) _items.RemoveAll(i => i.Id == id);
        Changed?.Invoke();
    }

    public void Clear()
    {
        lock (_gate) _items.Clear();
        Changed?.Invoke();
    }

    /// <summary>Copy an item from the virtual clipboard onto the PC clipboard.</summary>
    public async Task CopyToPcAsync(ClipItem item)
    {
        if (Sink is not { } sink) return;
        RememberApplied(item.Text, item.Image);
        if (item.Image is { } image) await sink.SetImageAsync(image).ConfigureAwait(false);
        else if (item.Text is { } text) await sink.SetTextAsync(text).ConfigureAwait(false);
    }

    /// <summary>
    /// ADR-073: puts an image (a photo or scan taken on the phone) on the PC clipboard without
    /// sending it back to the phones and without adding it to the virtual clipboard.
    /// </summary>
    public async Task<bool> CopyImageToPcAsync(byte[] image)
    {
        if (Sink is not { } sink || image.Length == 0) return false;
        RememberApplied(null, image);
        await sink.SetImageAsync(image).ConfigureAwait(false);
        return true;
    }

    /// <summary>
    /// Puts text on the PC clipboard without sending it to the phones, without adding it to the
    /// virtual clipboard, and outside clipboard history: "Copy code" on a mirrored notification
    /// (ADR-063). False when the clipboard is not available. The text is user data: never logged.
    /// </summary>
    public async Task<bool> CopyTextToPcAsync(string text)
    {
        if (Sink is not { } sink || string.IsNullOrEmpty(text)) return false;
        RememberApplied(text, null);
        await sink.SetPrivateTextAsync(text).ConfigureAwait(false);
        return true;
    }

    /// <summary>The PC clipboard changed (text). Sends it to the phones unless it is our own echo.</summary>
    public Task OnLocalTextCopiedAsync(string text)
    {
        if (!_sendEnabled() || string.IsNullOrEmpty(text) || text.Length > PeerMessage.Clip.MaxTextChars) return Task.CompletedTask;
        string fingerprint = Fingerprint(text, null);
        if (IsEcho(fingerprint) || IsRepeat(fingerprint)) return Task.CompletedTask;
        return BroadcastAsync(new PeerMessage.Clip(NewId(), PeerMessage.Clip.KindText, Text: text, Ts: Now()));
    }

    /// <summary>
    /// ADR-074: Home's "Send clipboard": the user sends the PC's current text on purpose, so the
    /// "send copies to phones" setting and the repeat filter do not apply; a phone whose own
    /// clipboard switch is off still gets nothing. False when there is nothing to send or no phone.
    /// </summary>
    public async Task<bool> SendTextNowAsync(string text)
    {
        if (string.IsNullOrEmpty(text) || text.Length > PeerMessage.Clip.MaxTextChars) return false;
        bool any;
        lock (_gate)
        {
            any = _sessions.GetConnectedPeers().Any(p =>
                (_sessions.GetPeerCapabilities(p) & PeerCapabilities.Clipboard) != 0 && !_receiveOff.Contains(p));
        }
        if (!any) return false;
        await BroadcastAsync(new PeerMessage.Clip(NewId(), PeerMessage.Clip.KindText, Text: text, Ts: Now())).ConfigureAwait(false);
        return true;
    }

    /// <summary>The PC clipboard changed (image, already PNG within the cap).</summary>
    public Task OnLocalImageCopiedAsync(byte[] png)
    {
        if (!_sendEnabled() || !_imagesEnabled() || !IsAcceptableImage(png)) return Task.CompletedTask;
        string fingerprint = Fingerprint(null, png);
        // Windows re-encodes a bitmap it was given, so an image echo rarely matches byte for byte:
        // any image change right after Veyro set one is taken as that echo.
        if (IsEcho(fingerprint) || IsRecentImageApply() || IsRepeat(fingerprint)) return Task.CompletedTask;
        return BroadcastAsync(new PeerMessage.Clip(NewId(), PeerMessage.Clip.KindImage, Image: Convert.ToBase64String(png), Ts: Now()));
    }

    /// <summary>
    /// Sends one of this PC's own copies (always apply = false) to every capable phone, except a
    /// phone whose "Auto-fetch clipboard" is off (ADR-059): that one is remembered as having
    /// missed it, for <see cref="SetReceive"/>.
    /// </summary>
    private async Task BroadcastAsync(PeerMessage.Clip clip)
    {
        var targets = new List<string>();
        lock (_gate)
        {
            _latestCopy = clip;
            _latestCopyAt = _clock();
            foreach (string peerId in _sessions.GetConnectedPeers())
            {
                if ((_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.Clipboard) == 0) continue;
                if (_receiveOff.Contains(peerId)) _missed[peerId] = clip.Id;
                else targets.Add(peerId);
            }
        }
        foreach (string peerId in targets)
        {
            try { await _sessions.SendAsync(peerId, clip).ConfigureAwait(false); }
            catch (Exception error) { VeyroTrace.Warn($"Clipboard not sent: {error.GetType().Name}"); }
        }
    }

    private void OnDeviceMessage(string peerId, PeerMessage message)
    {
        switch (message)
        {
            case PeerMessage.Clip clip:
                _ = HandleIncomingAsync(peerId, clip);
                break;
            case PeerMessage.DeviceInfo { ClipReceive: { } receive }:
                _ = SetReceive(peerId, receive);
                break;
        }
    }

    /// <summary>
    /// ADR-059: the phone announced its "Auto-fetch clipboard" switch. Off: its copies stop at once.
    /// On again: copies resume, and the latest copy it missed while off is sent once, if it is
    /// still the latest, within <see cref="CatchUpWindow"/>, and still allowed (send setting, image
    /// setting). A repeated announcement changes nothing, so rapid toggling never sends twice.
    /// </summary>
    public Task SetReceive(string peerId, bool receive)
    {
        PeerMessage.Clip? catchUp = null;
        bool changed;
        lock (_gate)
        {
            changed = receive ? _receiveOff.Remove(peerId) : _receiveOff.Add(peerId);
            if (receive && _missed.Remove(peerId, out string? missedId) &&
                _latestCopy is { } latest && latest.Id == missedId &&
                _clock() - _latestCopyAt <= CatchUpWindow &&
                _sendEnabled() && (latest.Kind != PeerMessage.Clip.KindImage || _imagesEnabled()))
            {
                catchUp = latest;
            }
        }
        if (changed)
        {
            try { ReceivePreferenceChanged?.Invoke(peerId); }
            catch (Exception error) { VeyroTrace.Error("ReceivePreferenceChanged handler failed", error); }
        }
        if (catchUp is null || !_sessions.IsConnected(peerId) ||
            (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.Clipboard) == 0)
            return Task.CompletedTask;
        return SendCatchUpAsync(peerId, catchUp);
    }

    private async Task SendCatchUpAsync(string peerId, PeerMessage.Clip clip)
    {
        try { await _sessions.SendAsync(peerId, clip).ConfigureAwait(false); }
        catch (Exception error) { VeyroTrace.Warn($"Clipboard catch-up not sent: {error.GetType().Name}"); }
    }

    /// <summary>Incoming clip from a phone. Untrusted input: validated and size-capped.</summary>
    public async Task HandleIncomingAsync(string peerId, PeerMessage.Clip clip)
    {
        ClipItem? item = Validate(peerId, clip);
        if (item is null) return;
        lock (_gate)
        {
            if (!_seenIds.Add(peerId + "|" + clip.Id)) return;
            if (_seenIds.Count > 4 * MaxItems) _seenIds.Clear();
            _items.Insert(0, item);
            if (_items.Count > MaxItems) _items.RemoveRange(MaxItems, _items.Count - MaxItems);
        }
        Changed?.Invoke();

        if (clip.Apply)
        {
            try { await CopyToPcAsync(item).ConfigureAwait(false); }
            catch (Exception error) { VeyroTrace.Warn($"Setting the clipboard failed: {error.GetType().Name}"); }
            Notifier?.ShowApplied(peerId, item);
        }
        else
        {
            Notifier?.ShowStored(peerId, item);
        }
    }

    private static ClipItem? Validate(string peerId, PeerMessage.Clip clip)
    {
        if (string.IsNullOrEmpty(clip.Id) || clip.Id.Length > 64) return null;
        DateTimeOffset at = DateTimeOffset.Now;
        switch (clip.Kind)
        {
            case PeerMessage.Clip.KindText when !string.IsNullOrEmpty(clip.Text) && clip.Text.Length <= PeerMessage.Clip.MaxTextChars:
                return new ClipItem(clip.Id, peerId, clip.Kind, clip.Text, null, at);
            case PeerMessage.Clip.KindImage when clip.Image is { Length: > 0 } b64 && b64.Length <= (PeerMessage.Clip.MaxImageBytes + 2) / 3 * 4:
                byte[] bytes;
                try { bytes = Convert.FromBase64String(b64); }
                catch (FormatException) { return null; }
                return IsAcceptableImage(bytes) ? new ClipItem(clip.Id, peerId, clip.Kind, null, bytes, at) : null;
            default:
                return null;
        }
    }

    /// <summary>PNG or JPEG by magic bytes, within the size cap.</summary>
    public static bool IsAcceptableImage(byte[]? bytes)
    {
        if (bytes is null || bytes.Length < 4 || bytes.Length > PeerMessage.Clip.MaxImageBytes) return false;
        bool png = bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
        bool jpeg = bytes[0] == 0xFF && bytes[1] == 0xD8;
        return png || jpeg;
    }

    /// <summary>First http(s) URL in the text, or null. Only http(s) is ever offered to open.</summary>
    public static Uri? FindLink(string? text)
    {
        if (string.IsNullOrEmpty(text)) return null;
        Match match = LinkPattern().Match(text);
        return match.Success && Uri.TryCreate(match.Value.TrimEnd('.', ',', ')', ']', '"', '\''), UriKind.Absolute, out Uri? uri) &&
               (uri.Scheme == Uri.UriSchemeHttps || uri.Scheme == Uri.UriSchemeHttp)
            ? uri
            : null;
    }

    [GeneratedRegex(@"https?://[^\s<>""]+", RegexOptions.IgnoreCase)]
    private static partial Regex LinkPattern();

    private void RememberApplied(string? text, byte[]? image)
    {
        lock (_gate)
        {
            _lastAppliedFingerprint = Fingerprint(text, image);
            _lastImageAppliedAt = image is null ? null : _clock();
            // ADR-059: the PC clipboard now holds something from a phone, so the last copy is no
            // longer what is on it and must not be caught up to a phone later.
            _latestCopy = null;
            _missed.Clear();
        }
    }

    private bool IsRecentImageApply()
    {
        lock (_gate)
        {
            if (_lastImageAppliedAt is not { } at || _clock() - at > EchoWindow) return false;
            _lastImageAppliedAt = null;
            return true;
        }
    }

    /// <summary>
    /// Some apps raise several clipboard changes for one copy; the same content within
    /// <see cref="EchoWindow"/> is sent once. Copying it again later is sent again.
    /// </summary>
    private bool IsRepeat(string fingerprint)
    {
        lock (_gate)
        {
            DateTimeOffset now = _clock();
            bool repeat = _lastSentFingerprint == fingerprint && now - _lastSentAt <= EchoWindow;
            _lastSentFingerprint = fingerprint;
            _lastSentAt = now;
            return repeat;
        }
    }

    private bool IsEcho(string fingerprint)
    {
        lock (_gate)
        {
            if (_lastAppliedFingerprint != fingerprint) return false;
            _lastAppliedFingerprint = null;
            return true;
        }
    }

    private static string Fingerprint(string? text, byte[]? image)
    {
        // Line endings normalised: the clipboard may hand back "\r\n" for text that was set with "\n".
        byte[] data = image ?? System.Text.Encoding.UTF8.GetBytes((text ?? string.Empty).Replace("\r\n", "\n"));
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(data));
    }

    private static string NewId() => Guid.NewGuid().ToString("N")[..16];
    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public void Dispose() => _sessions.DeviceMessageReceived -= OnDeviceMessage;
}
