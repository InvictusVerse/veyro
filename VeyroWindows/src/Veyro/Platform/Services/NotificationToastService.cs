using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Veyro.Core.Diagnostics;

namespace Veyro.Platform.Services;

/// <summary>
/// Mirrors newly posted phone notifications as Windows App SDK app notifications (toasts) and
/// routes toast buttons and the inline reply back to <see cref="INotificationMirrorService"/>.
/// Also asks about incoming files with Accept / Decline toasts (ADR-036; counts only, no names).
/// Also raises toasts for incoming SMS and ringing calls (ADR-027) and routes their reply and
/// call buttons to <see cref="IMessagingService"/> and <see cref="ICallService"/>.
/// ADR-063: a notification with a one-time code gets "Copy code"; every mirrored notification gets
/// "Turn off pop-ups for &lt;app&gt;" in its "..." menu. Per-app rules are applied by the mirror
/// service before a change reaches this class, so a "Feed only" app never raises a toast here.
/// Notification, message and call contents are user data: never logged here. SMS and call toasts
/// expire on reboot and are removed when the phone's session closes; toast arguments carry ids
/// only, never numbers or text.
/// </summary>
public sealed class NotificationToastService : INotificationToastService
{
    private const string ReplyInputId = "reply";
    private const string SmsReplyInputId = "smsReply";
    private const int MaxActionButtons = 3;
    /// <summary>Windows shows at most 5 buttons and context-menu items on one toast.</summary>
    private const int MaxToastActions = 5;
    private const int MaxMenuLabelLength = 60;
    private static readonly TimeSpan ConfirmationLifetime = TimeSpan.FromSeconds(15);
    private static readonly string ConfirmationTag = Hash("confirm");
    private const int MaxTitleLength = 200;
    private const int MaxBodyLength = 1000;
    private const int MaxButtonLabelLength = 40;
    private static readonly byte[] PngSignature = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];

    private readonly INotificationMirrorService _mirror;
    private readonly IMessagingService _messaging;
    private readonly ICallService _calls;
    private readonly CallRouter _router;
    private readonly Action<string, long> _openThread;
    private readonly Func<string, string?> _phoneName;
    private readonly Action<string> _showPhone;
    private readonly Func<string, Task<bool>>? _copyText;
    private readonly INotificationAppRules? _rules;
    private readonly Func<bool> _quiet;
    private readonly string _fallbackDataDirectory;
    private readonly object _ringingGate = new();
    /// <summary>Ringing calls with a toast, and the caller line it shows.</summary>
    private readonly Dictionary<string, string> _ringingShown = new(StringComparer.Ordinal);
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, TaskCompletionSource<ConsentAnswer?>> _transferPrompts = new(StringComparer.Ordinal);
    private static readonly string TransferGroup = Hash("transfers");
    private (string Directory, string? UriPrefix)? _iconLocation;
    private bool _registered;

    public NotificationToastService(
        INotificationMirrorService mirror,
        IMessagingService messaging,
        ICallService calls,
        CallRouter router,
        Action<string, long> openThread,
        string fallbackDataDirectory,
        Func<string, string?>? phoneName = null,
        Action<string>? showPhone = null,
        Func<string, Task<bool>>? copyText = null,
        INotificationAppRules? rules = null,
        Func<bool>? quiet = null)
    {
        // Do not disturb: phone notifications and SMS stay in Veyro but pop up nothing.
        _quiet = quiet ?? (() => false);
        _mirror = mirror;
        _messaging = messaging;
        _calls = calls;
        _router = router;
        _openThread = openThread;
        _fallbackDataDirectory = fallbackDataDirectory;
        // ADR-040: with several phones a toast says which one it came from, and opening it shows that phone.
        _phoneName = phoneName ?? (_ => null);
        _showPhone = showPhone ?? (_ => { });
        // ADR-063: "Copy code" (through the clipboard service, so it is not sent back to the
        // phones) and "Turn off pop-ups for <app>".
        _copyText = copyText;
        _rules = rules;
    }

    /// <summary>"From Pixel 8" under the toast, so toasts from two phones are told apart. Shown, never logged.</summary>
    private AppNotificationBuilder FromPhone(AppNotificationBuilder builder, string peerId)
    {
        string? name;
        try { name = _phoneName(peerId); }
        catch (Exception) { name = null; }
        return string.IsNullOrWhiteSpace(name) ? builder : builder.SetAttributionText(Clip("From " + name.Trim(), MaxTitleLength));
    }

    public void Initialize()
    {
        if (_registered) return;
        try
        {
            // The handler must be attached before Register (Windows App SDK requirement).
            AppNotificationManager.Default.NotificationInvoked += OnNotificationInvoked;
            AppNotificationManager.Default.Register();
            _registered = true;
            _mirror.Changed += OnMirrorChanged;
            _messaging.Changed += OnMessagingChanged;
            _calls.Changed += OnCallChanged;
            VeyroTrace.Info("App notifications registered");
        }
        catch (Exception error)
        {
            AppNotificationManager.Default.NotificationInvoked -= OnNotificationInvoked;
            VeyroTrace.Error("App notification registration failed", error);
        }
    }

    public void Dispose()
    {
        if (!_registered) return;
        _registered = false;
        _mirror.Changed -= OnMirrorChanged;
        _messaging.Changed -= OnMessagingChanged;
        _calls.Changed -= OnCallChanged;
        AppNotificationManager.Default.NotificationInvoked -= OnNotificationInvoked;
        try { AppNotificationManager.Default.Unregister(); }
        catch (Exception error) { VeyroTrace.Error("App notification unregistration failed", error); }
    }

    private void OnMirrorChanged(NotificationChange change)
    {
        switch (change.Kind)
        {
            // Only fresh posts alert; the "active" snapshot on reconnect must not replay toasts.
            case NotificationChangeKind.Posted when change.Notification is not null:
                Show(change.Notification);
                break;
            case NotificationChangeKind.Removed when change.Key is not null:
                _ = RemoveAsync(change.PeerId, change.Key);
                break;
        }
    }

    private void Show(MirroredNotification notification)
    {
        if (IsQuiet()) return;
        try
        {
            string peer = notification.PeerId;
            string key = EncodeArgument(notification.Key);
            var builder = new AppNotificationBuilder()
                .AddArgument("action", "open")
                .AddArgument("peer", peer)
                .AddArgument("key", key)
                .SetTag(TagFor(notification.Key))
                .SetGroup(GroupFor(peer))
                .AddText(Clip(notification.AppName, MaxTitleLength));
            if (!string.IsNullOrWhiteSpace(notification.Title))
                builder.AddText(Clip(notification.Title, MaxTitleLength));
            if (!string.IsNullOrWhiteSpace(notification.Body))
                builder.AddText(Clip(notification.Body, MaxBodyLength));
            FromPhone(builder, peer);

            if (CacheIcon(notification) is { } logo)
                builder.SetAppLogoOverride(logo, AppNotificationImageCrop.Circle);

            // A toast holds at most 5 buttons and context-menu items together.
            int room = MaxToastActions;
            // ADR-063: the code travels in the button's arguments; it is already in the toast's text.
            if (_copyText is not null && notification.Code is { } code)
            {
                builder.AddButton(new AppNotificationButton("Copy code")
                    .AddArgument("action", "copy-code")
                    .AddArgument("code", code));
                room--;
            }

            bool canMute = _rules is not null && NotificationAppRules.IsValidPackage(notification.Package);
            if (canMute) room--;

            if (notification.CanReply)
            {
                builder.AddTextBox(ReplyInputId, "Type a reply", "Reply");
                builder.AddButton(new AppNotificationButton("Send")
                    .AddArgument("action", "reply")
                    .AddArgument("peer", peer)
                    .AddArgument("key", key)
                    .SetInputId(ReplyInputId));
                room--;
            }

            foreach (NotificationActionInfo action in notification.Actions.Take(Math.Min(MaxActionButtons, room)))
            {
                builder.AddButton(new AppNotificationButton(Clip(action.Label, MaxButtonLabelLength))
                    .AddArgument("action", "action")
                    .AddArgument("peer", peer)
                    .AddArgument("key", key)
                    .AddArgument("index", action.Index.ToString(CultureInfo.InvariantCulture)));
            }

            // ADR-063: in the toast's "..." menu, where Windows keeps its own "turn off" choices.
            if (canMute)
            {
                builder.AddButton(new AppNotificationButton(Clip("Turn off pop-ups for " + notification.AppName, MaxMenuLabelLength))
                    .AddArgument("action", "mute-app")
                    .AddArgument("pkg", notification.Package!)
                    .SetContextMenuPlacement());
            }

            AppNotificationManager.Default.Show(builder.BuildNotification());
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Showing mirrored notification failed", error);
        }
    }

    // ── Copy code and per-app pop-ups (ADR-063) ──────────────────────────────────────

    private async Task CopyCodeAsync(string code)
    {
        bool copied = false;
        try { copied = _copyText is not null && await _copyText(code).ConfigureAwait(false); }
        // The type only: the code is user data.
        catch (Exception error) { VeyroTrace.Warn($"Copying a code from a toast failed: {error.GetType().Name}"); }
        ShowConfirmation(copied ? "Code copied" : "Could not copy the code", null);
    }

    private async Task TurnOffPopupsAsync(string package)
    {
        if (_rules is null) return;
        string label = _rules.Apps.FirstOrDefault(app => app.Package == package)?.Label ?? package;
        try
        {
            await _rules.SetModeAsync(package, NotificationAppMode.FeedOnly).ConfigureAwait(false);
            ShowConfirmation(
                Clip($"Pop-ups off for {label}", MaxTitleLength),
                "Its notifications still appear in Veyro. Change it in Settings > Notifications.");
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Turning off pop-ups failed: {error.GetType().Name}");
        }
    }

    /// <summary>A quiet toast that answers a toast button and leaves the notification centre soon after.</summary>
    private void ShowConfirmation(string title, string? text)
    {
        if (!_registered) return;
        try
        {
            var builder = new AppNotificationBuilder()
                .AddArgument("action", "open")
                .SetTag(ConfirmationTag)
                .SetGroup(StatusGroup)
                .AddText(title)
                .MuteAudio();
            if (text is not null) builder.AddText(text);
            AppNotification notification = builder.BuildNotification();
            notification.Expiration = DateTimeOffset.Now.Add(ConfirmationLifetime);
            notification.ExpiresOnReboot = true;
            AppNotificationManager.Default.Show(notification);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Confirmation toast failed: {error.GetType().Name}");
        }
    }

    // ── SMS and calls (ADR-027) ──────────────────────────────────────────────────────

    private void OnMessagingChanged(MessagingChange change)
    {
        switch (change.Kind)
        {
            case MessagingChangeKind.Incoming when change.Message is not null && change.ThreadId is { } threadId:
                ShowSms(change.PeerId, threadId, change.Message);
                break;
            case MessagingChangeKind.Cleared:
                _ = RemoveGroupAsync(SmsGroupFor(change.PeerId));
                break;
        }
    }

    private bool IsQuiet()
    {
        try { return _quiet(); }
        catch (Exception) { return false; }
    }

    private void ShowSms(string peerId, long threadId, SmsMessage message)
    {
        if (IsQuiet()) return;
        try
        {
            SmsThread? thread = _messaging.GetThread(peerId, threadId);
            string sender = thread is not null ? SmsThreadItemViewModel.TitleOf(thread) : message.Address;
            string thread64 = threadId.ToString(CultureInfo.InvariantCulture);
            var builder = new AppNotificationBuilder()
                .AddArgument("action", "sms-open")
                .AddArgument("peer", peerId)
                .AddArgument("thread", thread64)
                .SetTag(Hash("t|" + thread64))
                .SetGroup(SmsGroupFor(peerId))
                .AddText(Clip(sender, MaxTitleLength))
                .AddText(Clip(message.Body, MaxBodyLength))
                .AddTextBox(SmsReplyInputId, "Type a reply", "Reply")
                .AddButton(new AppNotificationButton("Send")
                    .AddArgument("action", "sms-reply")
                    .AddArgument("peer", peerId)
                    .AddArgument("thread", thread64)
                    .SetInputId(SmsReplyInputId));
            FromPhone(builder, peerId);
            AppNotification notification = builder.BuildNotification();
            notification.ExpiresOnReboot = true;
            AppNotificationManager.Default.Show(notification);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Showing SMS toast failed", error);
        }
    }

    private void OnCallChanged(CallChange change)
    {
        switch (change.Kind)
        {
            case CallChangeKind.State when change.Call is { } call:
                string key = change.PeerId + "|" + call.CallId;
                if (call.IsIncomingRinging)
                {
                    // Android 12+ reports the ring first and the number (and so the contact name)
                    // a moment later in a second callState. The toast is shown again under the same
                    // tag when the caller line changes, which replaces "Unknown caller".
                    string caller = (call.Name ?? "") + "|" + (call.Number ?? "");
                    bool show;
                    lock (_ringingGate)
                    {
                        show = !_ringingShown.TryGetValue(key, out string? shownCaller) || shownCaller != caller;
                        _ringingShown[key] = caller;
                    }
                    if (show) ShowIncomingCall(call);
                }
                else
                {
                    bool shown;
                    lock (_ringingGate) shown = _ringingShown.Remove(key);
                    if (shown) _ = RemoveTagAsync(CallTagFor(call.CallId), CallGroupFor(change.PeerId));
                }
                break;
            case CallChangeKind.Cleared:
                lock (_ringingGate)
                {
                    foreach (string k in _ringingShown.Keys.Where(k => k.StartsWith(change.PeerId + "|", StringComparison.Ordinal)).ToList())
                        _ringingShown.Remove(k);
                }
                _ = RemoveGroupAsync(CallGroupFor(change.PeerId));
                break;
        }
    }

    private void ShowIncomingCall(PhoneCall call)
    {
        try
        {
            string title = call.Name ?? call.Number ?? "Unknown caller";
            // ADR-029: with Bluetooth hands-free up to this phone, Answer takes the call on the PC.
            bool bluetooth = _router.PreferredAnswerRoute(call.PeerId) == CallAnswerRoute.Bluetooth;
            var builder = new AppNotificationBuilder()
                .SetScenario(AppNotificationScenario.IncomingCall)
                .AddArgument("action", "call-open")
                .AddArgument("peer", call.PeerId)
                .SetTag(CallTagFor(call.CallId))
                .SetGroup(CallGroupFor(call.PeerId))
                .AddText(Clip(title, MaxTitleLength))
                .AddText(call.Name is not null && call.Number is not null ? Clip(call.Number, MaxTitleLength) : "Incoming call")
                .AddText(bluetooth ? "Answer to talk on this PC over Bluetooth" : "Audio stays on the phone")
                .AddButton(CallButton(bluetooth ? "Answer on PC" : "Answer (on phone)", "call-answer", call, AppNotificationButtonStyle.Success))
                .AddButton(CallButton("Decline", "call-decline", call, AppNotificationButtonStyle.Critical))
                .AddButton(CallButton("Silence", "call-silence", call, null));
            FromPhone(builder, call.PeerId);
            AppNotification notification = builder.BuildNotification();
            notification.ExpiresOnReboot = true;
            AppNotificationManager.Default.Show(notification);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Showing call toast failed", error);
        }
    }

    private static AppNotificationButton CallButton(string label, string action, PhoneCall call, AppNotificationButtonStyle? style)
    {
        var button = new AppNotificationButton(label)
            .AddArgument("action", action)
            .AddArgument("peer", call.PeerId)
            .AddArgument("call", EncodeArgument(call.CallId));
        if (style is { } s && AppNotificationButton.IsButtonStyleSupported()) button.SetButtonStyle(s);
        return button;
    }

    /// <summary>Applies a toast call button only if that call is still the phone's current call.</summary>
    private void RunCallAction(string peerId, string? encodedCallId, string action)
    {
        if (encodedCallId is null || DecodeArgument(encodedCallId) is not { } callId) return;
        if (_calls.GetCurrentCall(peerId)?.CallId != callId) return;
        // Answer goes over Bluetooth when connected to this phone (audio on the PC), else Wi-Fi.
        Func<Task> request = action == CallService.ActionAnswer
            ? () => _router.AnswerAsync(peerId)
            : () => _calls.SendActionAsync(peerId, action);
        _ = RunAsync(request, "call " + action);
    }

    private void ReplyToThread(string peerId, string? rawThread, string text)
    {
        if (!long.TryParse(rawThread, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out long threadId)) return;
        if (_messaging.GetThread(peerId, threadId) is not { Addresses.Count: > 0 } thread) return;
        _ = RunAsync(() => _messaging.SendAsync(peerId, thread.Addresses, text), "SMS reply");
    }

    private static string SmsGroupFor(string peerId) => Hash("s|" + peerId);

    private static string CallGroupFor(string peerId) => Hash("c|" + peerId);

    private static string CallTagFor(string callId) => Hash("call|" + callId);

    private static async Task RemoveTagAsync(string tag, string group)
    {
        try { await AppNotificationManager.Default.RemoveByTagAndGroupAsync(tag, group); }
        catch (Exception error) { VeyroTrace.Error("Removing toast failed", error); }
    }

    private static async Task RemoveGroupAsync(string group)
    {
        try { await AppNotificationManager.Default.RemoveByGroupAsync(group); }
        catch (Exception error) { VeyroTrace.Error("Removing toasts failed", error); }
    }

    private static async Task RemoveAsync(string peerId, string key)
    {
        try
        {
            await AppNotificationManager.Default.RemoveByTagAndGroupAsync(TagFor(key), GroupFor(peerId));
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Removing mirrored notification failed", error);
        }
    }

    // ── Incoming files (ADR-036) ─────────────────────────────────────────────────────

    public async Task<ConsentAnswer?> ShowTransferRequestAsync(string peerName, int fileCount, long totalBytes, CancellationToken ct)
    {
        if (!_registered) throw new InvalidOperationException("App notifications are not registered.");
        ct.ThrowIfCancellationRequested();
        string id = Guid.NewGuid().ToString("N");
        string tag = Hash("x|" + id);
        var answer = new TaskCompletionSource<ConsentAnswer?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _transferPrompts[id] = answer;
        try
        {
            string files = fileCount == 1 ? "1 file" : $"{fileCount.ToString("N0", CultureInfo.CurrentCulture)} files";
            var builder = new AppNotificationBuilder()
                // Reminder keeps it on screen until answered instead of sliding into the centre.
                .SetScenario(AppNotificationScenario.Reminder)
                .AddArgument("action", "transfer-open")
                .AddArgument("req", id)
                .SetTag(tag)
                .SetGroup(TransferGroup)
                .AddText(Clip($"{peerName} wants to send files", MaxTitleLength))
                .AddText($"{files}, {TransferProgressMath.FormatBytes(totalBytes)}")
                // ADR-038: toasts have no checkbox, so the pre-ticked "Always accept" is the
                // default button and "Only this time" is the unticked choice.
                .AddText("Accept also accepts this phone's files from now on.")
                .AddButton(TransferButton("Accept", "transfer-accept", id, AppNotificationButtonStyle.Success))
                .AddButton(TransferButton("Only this time", "transfer-once", id, null))
                .AddButton(TransferButton("Decline", "transfer-decline", id, null));
            AppNotification notification = builder.BuildNotification();
            notification.ExpiresOnReboot = true;
            AppNotificationManager.Default.Show(notification);
            using (ct.Register(() => answer.TrySetCanceled(ct)))
                return await answer.Task.ConfigureAwait(false);
        }
        finally
        {
            _transferPrompts.TryRemove(id, out _);
            _ = RemoveTagAsync(tag, TransferGroup);
        }
    }

    public void ShowStatus(string title, string text, string? tag = null)
    {
        if (!_registered) return;
        try
        {
            var builder = new AppNotificationBuilder()
                .AddArgument("action", "open")
                .SetGroup(StatusGroup)
                .AddText(Clip(title, MaxTitleLength))
                .AddText(Clip(text, MaxBodyLength));
            if (tag is not null) builder.SetTag(Hash("s|" + tag));
            AppNotificationManager.Default.Show(builder.BuildNotification());
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Status toast failed: {error.GetType().Name}");
        }
    }

    private const string StatusGroup = "veyro-status";

    private static AppNotificationButton TransferButton(string label, string action, string id, AppNotificationButtonStyle? style)
    {
        var button = new AppNotificationButton(label)
            .AddArgument("action", action)
            .AddArgument("req", id);
        if (style is { } s && AppNotificationButton.IsButtonStyleSupported()) button.SetButtonStyle(s);
        return button;
    }

    private void AnswerTransferPrompt(IDictionary<string, string> arguments, ConsentAnswer? answer)
    {
        if (arguments.TryGetValue("req", out string? id) && _transferPrompts.TryGetValue(id, out var prompt))
            prompt.TrySetResult(answer);
    }

    private void OnNotificationInvoked(AppNotificationManager sender, AppNotificationActivatedEventArgs args)
    {
        IDictionary<string, string> arguments = args.Arguments;
        if (!arguments.TryGetValue("action", out string? action)) return;
        switch (action)
        {
            case "transfer-accept":
                AnswerTransferPrompt(arguments, new ConsentAnswer(true, AlwaysAccept: true));
                return;
            case "transfer-once":
                AnswerTransferPrompt(arguments, new ConsentAnswer(true));
                return;
            case "transfer-decline":
                AnswerTransferPrompt(arguments, ConsentAnswer.Declined);
                return;
            case "transfer-open":
                // Bring Veyro forward first, then let the prompt ask again in the window.
                App.MainWindow?.DispatcherQueue.TryEnqueue(() =>
                {
                    App.ShowMainWindow();
                    AnswerTransferPrompt(arguments, null);
                });
                return;
            // ADR-063. Arguments come back from Windows; only a well-formed code or package is used.
            case "copy-code":
                if (arguments.TryGetValue("code", out string? code) && OneTimeCodeDetector.IsCode(code))
                    _ = CopyCodeAsync(code);
                return;
            case "mute-app":
                if (arguments.TryGetValue("pkg", out string? package) && NotificationAppRules.IsValidPackage(package))
                    _ = TurnOffPopupsAsync(package);
                return;
        }
        if (action is "open" or "call-open")
        {
            arguments.TryGetValue("peer", out string? from);
            App.MainWindow?.DispatcherQueue.TryEnqueue(() =>
            {
                App.ShowMainWindow();
                // The pages follow the phone the toast came from, not whichever was shown.
                if (from is not null) _showPhone(from);
            });
            return;
        }

        if (action.StartsWith("sms-", StringComparison.Ordinal) || action.StartsWith("call-", StringComparison.Ordinal))
        {
            if (!arguments.TryGetValue("peer", out string? phone)) return;
            arguments.TryGetValue("thread", out string? rawThread);
            arguments.TryGetValue("call", out string? rawCall);
            switch (action)
            {
                case "sms-open" when long.TryParse(rawThread, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out long threadId):
                    App.MainWindow?.DispatcherQueue.TryEnqueue(() =>
                    {
                        App.ShowMainWindow();
                        _openThread(phone, threadId);
                    });
                    break;
                case "sms-reply" when args.UserInput.TryGetValue(SmsReplyInputId, out string? reply) && PhoneAddress.IsValidBody(reply):
                    ReplyToThread(phone, rawThread, reply);
                    break;
                case "call-answer":
                    RunCallAction(phone, rawCall, CallService.ActionAnswer);
                    break;
                case "call-decline":
                    RunCallAction(phone, rawCall, CallService.ActionDecline);
                    break;
                case "call-silence":
                    RunCallAction(phone, rawCall, CallService.ActionSilence);
                    break;
            }
            return;
        }

        if (!arguments.TryGetValue("peer", out string? peer) ||
            !arguments.TryGetValue("key", out string? encodedKey) ||
            DecodeArgument(encodedKey) is not { } key)
            return;

        switch (action)
        {
            case "reply" when args.UserInput.TryGetValue(ReplyInputId, out string? text) && !string.IsNullOrWhiteSpace(text):
                _ = RunAsync(() => _mirror.ReplyAsync(peer, key, text), "reply");
                break;
            case "reply":
                VeyroTrace.Info("Toast reply ignored: the reply box was empty");
                break;
            case "action" when arguments.TryGetValue("index", out string? rawIndex) &&
                               int.TryParse(rawIndex, NumberStyles.None, CultureInfo.InvariantCulture, out int index):
                _ = RunAsync(() => _mirror.InvokeActionAsync(peer, key, index), "action");
                break;
        }
    }

    private static async Task RunAsync(Func<Task> request, string name)
    {
        try { await request().ConfigureAwait(false); }
        catch (Exception error) { VeyroTrace.Error($"Notification {name} from toast failed", error); }
    }

    /// <summary>
    /// Writes the phone-supplied app icon to a per-app cache file and returns its URI, or null.
    /// The bytes are untrusted: only a PNG within the protocol size limit is accepted.
    /// </summary>
    private Uri? CacheIcon(MirroredNotification notification)
    {
        if (notification.IconPngBase64 is null) return null;
        try
        {
            byte[] png = Convert.FromBase64String(notification.IconPngBase64);
            if (png.Length < PngSignature.Length || !png.AsSpan(0, PngSignature.Length).SequenceEqual(PngSignature))
                return null;

            (string directory, string? uriPrefix) = IconLocation();
            Directory.CreateDirectory(directory);
            string fileName = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(notification.Package ?? notification.AppName)))[..32] + ".png";
            string path = Path.Combine(directory, fileName);
            if (!File.Exists(path) || !File.ReadAllBytes(path).AsSpan().SequenceEqual(png))
            {
                string temp = path + ".tmp";
                File.WriteAllBytes(temp, png);
                File.Move(temp, path, overwrite: true);
            }
            return uriPrefix is null ? new Uri(path) : new Uri(uriPrefix + fileName);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Caching notification icon failed", error);
            return null;
        }
    }

    /// <summary>
    /// Packaged: the package's LocalFolder, addressed with ms-appdata so the toast host can read
    /// it. Unpackaged (development): the Veyro data directory, addressed as a file URI.
    /// </summary>
    private (string Directory, string? UriPrefix) IconLocation()
    {
        if (_iconLocation is { } cached) return cached;
        const string folder = "notification-icons";
        try
        {
            string local = global::Windows.Storage.ApplicationData.Current.LocalFolder.Path;
            _iconLocation = (Path.Combine(local, folder), $"ms-appdata:///local/{folder}/");
        }
        catch (Exception)
        {
            _iconLocation = (Path.Combine(_fallbackDataDirectory, folder), null);
        }
        return _iconLocation.Value;
    }

    /// <summary>Tags and groups are limited to 64 characters; the phone's key can be longer.</summary>
    private static string TagFor(string key) => Hash("k|" + key);

    private static string GroupFor(string peerId) => Hash("p|" + peerId);

    private static string Hash(string value) => Base64Url.Encode(SHA256.HashData(Encoding.UTF8.GetBytes(value)).AsSpan(0, 18));

    /// <summary>Keys are opaque phone strings; base64url keeps them clear of argument separators.</summary>
    private static string EncodeArgument(string value) => Base64Url.Encode(Encoding.UTF8.GetBytes(value));

    private static string? DecodeArgument(string value)
    {
        byte[]? bytes = Base64Url.DecodeOrNull(value);
        if (bytes is null) return null;
        try { return new UTF8Encoding(false, throwOnInvalidBytes: true).GetString(bytes); }
        catch (DecoderFallbackException) { return null; }
    }

    private static string Clip(string value, int max) => value.Length <= max ? value : value[..(max - 1)] + "\u2026";
}
