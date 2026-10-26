using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Veyro.Core.Diagnostics;

namespace Veyro.Platforms.Windows.Services;

/// <summary>
/// Mirrors newly posted phone notifications as Windows App SDK app notifications (toasts) and
/// routes toast buttons and the inline reply back to <see cref="INotificationMirrorService"/>.
/// Notification contents are user data: never logged here.
/// </summary>
public sealed class NotificationToastService : INotificationToastService
{
    private const string ReplyInputId = "reply";
    private const int MaxActionButtons = 3;
    private const int MaxTitleLength = 200;
    private const int MaxBodyLength = 1000;
    private const int MaxButtonLabelLength = 40;
    private static readonly byte[] PngSignature = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];

    private readonly INotificationMirrorService _mirror;
    private readonly string _fallbackDataDirectory;
    private (string Directory, string? UriPrefix)? _iconLocation;
    private bool _registered;

    public NotificationToastService(INotificationMirrorService mirror, string fallbackDataDirectory)
    {
        _mirror = mirror;
        _fallbackDataDirectory = fallbackDataDirectory;
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

            if (CacheIcon(notification) is { } logo)
                builder.SetAppLogoOverride(logo, AppNotificationImageCrop.Circle);

            if (notification.CanReply)
            {
                builder.AddTextBox(ReplyInputId, "Type a reply", "Reply");
                builder.AddButton(new AppNotificationButton("Send")
                    .AddArgument("action", "reply")
                    .AddArgument("peer", peer)
                    .AddArgument("key", key)
                    .SetInputId(ReplyInputId));
            }

            foreach (NotificationActionInfo action in notification.Actions.Take(MaxActionButtons))
            {
                builder.AddButton(new AppNotificationButton(Clip(action.Label, MaxButtonLabelLength))
                    .AddArgument("action", "action")
                    .AddArgument("peer", peer)
                    .AddArgument("key", key)
                    .AddArgument("index", action.Index.ToString(CultureInfo.InvariantCulture)));
            }

            AppNotificationManager.Default.Show(builder.BuildNotification());
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Showing mirrored notification failed", error);
        }
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

    private void OnNotificationInvoked(AppNotificationManager sender, AppNotificationActivatedEventArgs args)
    {
        IDictionary<string, string> arguments = args.Arguments;
        if (!arguments.TryGetValue("action", out string? action)) return;
        if (action == "open")
        {
            App.MainWindow?.DispatcherQueue.TryEnqueue(App.ShowMainWindow);
            return;
        }

        if (!arguments.TryGetValue("peer", out string? peer) ||
            !arguments.TryGetValue("key", out string? encodedKey) ||
            DecodeArgument(encodedKey) is not { } key)
            return;

        switch (action)
        {
            case "reply" when args.UserInput.TryGetValue(ReplyInputId, out string? text):
                _ = RunAsync(() => _mirror.ReplyAsync(peer, key, text), "reply");
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
