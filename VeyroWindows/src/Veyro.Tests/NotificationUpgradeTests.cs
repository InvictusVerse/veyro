using Veyro.Core.Clipboard;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Notifications;
using Xunit;

namespace Veyro.Tests;

/// <summary>ADR-063: one-time codes in mirrored notifications.</summary>
public sealed class OneTimeCodeDetectorTests
{
    [Theory]
    // English, in the shapes services send.
    [InlineData(null, "Your verification code is 482913", "482913")]
    [InlineData(null, "482913 is your Google verification code.", "482913")]
    [InlineData(null, "G-482913 is your Google verification code.", "482913")]
    [InlineData("Instagram", "Use 739201 to verify your account", "739201")]
    [InlineData(null, "Your WhatsApp code: 123-456. Don't share this code with others", "123456")]
    [InlineData(null, "Your login code is 123 456", "123456")]
    [InlineData(null, "Your sign-in code: 90210", "90210")]
    [InlineData(null, "Your one-time password is 12345678", "12345678")]
    [InlineData(null, "Your Microsoft account security code is 1234567", "1234567")]
    [InlineData(null, "Your PIN is 4821", "4821")]
    [InlineData(null, "Password: 7720", "7720")]
    [InlineData(null, "OTP:482913", "482913")]
    [InlineData(null, "Your OTP is 482913 valid for 10 min", "482913")]
    [InlineData(null, "Your code is 4821. Expires at 10:30.", "4821")]
    [InlineData(null, "Your code: 4821\nSent 30 Oct 2026", "4821")]
    [InlineData(null, "Your Uber code: 4821. Reply STOP to 22395 to unsubscribe", "4821")]
    [InlineData(null, "Your verification code is 482913. If you did not request it, call 1-800-555-0199.", "482913")]
    [InlineData("Sam", "Sam: the door code is 4471", "4471")]
    // The keyword in the title, the code in the body; an SMS short code as the title is ignored.
    [InlineData("Verification code", "582044", "582044")]
    [InlineData("22395", "Your Uber code: 4821", "4821")]
    // Banks: amounts, masked cards and "ending 1234" are skipped.
    [InlineData(null, "OTP for your transaction of Rs 2,500.00 at AMAZON on card XX1234 is 384910. Valid for 10 mins. Do not share.", "384910")]
    [InlineData(null, "Dear customer, 482913 is the OTP for txn of INR 5000 on card ending 1234.", "482913")]
    // Strong wording wins over words that would otherwise mean "no code".
    [InlineData(null, "Your order verification code is 482913", "482913")]
    // Letters and digits.
    [InlineData(null, "Your verification code is K7F2P9", "K7F2P9")]
    [InlineData(null, "Use code A1B2C3D4 to sign in", "A1B2C3D4")]
    // Other languages.
    [InlineData(null, "Tu código de verificación es 482913", "482913")]
    [InlineData(null, "Seu código de verificação é 482913", "482913")]
    [InlineData(null, "Votre code de vérification est 482913", "482913")]
    [InlineData(null, "Ihr Bestätigungscode lautet 482913", "482913")]
    [InlineData(null, "Il tuo codice di verifica è 482913", "482913")]
    [InlineData(null, "Uw verificatiecode is 482913", "482913")]
    [InlineData(null, "Twój kod weryfikacyjny to 482913", "482913")]
    [InlineData(null, "Код подтверждения: 4821", "4821")]
    [InlineData(null, "Doğrulama kodunuz: 482913", "482913")]
    [InlineData(null, "Kode verifikasi Anda adalah 482913", "482913")]
    [InlineData(null, "Mã xác minh của bạn là 482913", "482913")]
    [InlineData(null, "Din kode er 4821", "4821")]
    [InlineData(null, "您的验证码是123456，5分钟内有效。", "123456")]
    [InlineData(null, "[Web발신] 인증번호 [482913]를 입력해주세요", "482913")]
    // Other digit forms come back as ASCII: full width, Arabic-Indic, Persian, Devanagari.
    [InlineData(null, "認証コード：４８２９１３", "482913")]
    [InlineData(null, "رمز التحقق الخاص بك هو ١٢٣٤٥٦", "123456")]
    [InlineData(null, "کد تایید شما: ۴۸۲۹۱۳", "482913")]
    [InlineData(null, "आपका ओटीपी ४८२९१३ है", "482913")]
    public void Finds_TheCode(string? title, string text, string expected)
    {
        Assert.Equal(expected, OneTimeCodeDetector.Find(title, text));
    }

    [Theory]
    // No keyword.
    [InlineData(null, "Happy birthday! See you at 1234 Main Street")]
    [InlineData(null, "Your order #48213 has shipped")]
    [InlineData("+15550100", "Hi, are we still on for dinner?")]
    [InlineData(null, "New login from Chrome at 1530")]
    // Phone numbers.
    [InlineData(null, "Call me at 555-1234 about the code")]
    [InlineData(null, "Your verification code was sent to +1 555 010 0199")]
    [InlineData(null, "Call 0800 123 4567 for your PIN")]
    [InlineData(null, "Call 5551234 for your code")]
    [InlineData(null, "Your code (555) 123-4567")]
    // Dates and times.
    [InlineData(null, "Your code expires on 2026-10-30")]
    [InlineData(null, "Verification code expires at 10:30")]
    [InlineData(null, "Your PIN was changed on 30/10/2026")]
    [InlineData(null, "Your password expires in October 2026")]
    [InlineData(null, "Code valid until 20261231")]
    [InlineData(null, "PIN 12:34")]
    [InlineData(null, "Meeting at 1530 hrs, bring the code")]
    [InlineData(null, "The code review is at 1400h")]
    // Amounts.
    [InlineData(null, "Payment of $1500 received. Your code is ready.")]
    [InlineData(null, "Price: 4999 INR, code applied")]
    [InlineData(null, "Your OTP for €2500 payment")]
    [InlineData(null, "OTP 2500€")]
    [InlineData(null, "Verification code: 2,500")]
    [InlineData(null, "Your code is 1.234")]
    // Too short, too long, masked, in a link.
    [InlineData(null, "Your code is 123")]
    [InlineData(null, "Your code is 123456789")]
    [InlineData(null, "Your account number 123456789012 has a new code")]
    [InlineData(null, "Your verification code is 1234 5678 9012")]
    [InlineData(null, "OTP will be sent to the number ending in 1234")]
    [InlineData(null, "Card XX1234: your verification code is pending")]
    [InlineData(null, "Reset your password at https://example.com/reset?id=482913")]
    [InlineData(null, "Your verification link is abc123def")]
    // Promotions, tracking, addresses, and the sender's number as the title.
    [InlineData(null, "Use promo code SAVE2024 for 20% off")]
    [InlineData(null, "Track your package with code 1Z999AA10123456784")]
    [InlineData(null, "Your zip code is 94105")]
    [InlineData(null, "Order 12345 confirmed, your pickup code is ready")]
    [InlineData("22395", "Your code is ready")]
    // Empty.
    [InlineData(null, null)]
    [InlineData("", "")]
    public void Finds_NoCode(string? title, string? text)
    {
        Assert.Null(OneTimeCodeDetector.Find(title, text));
    }

    [Fact]
    public void A_CodeFarFromItsKeyword_IsNotTaken()
    {
        string filler = string.Join(" ", Enumerable.Repeat("lorem", 30));
        Assert.Null(OneTimeCodeDetector.Find($"Your code is below. {filler} 482913"));
        Assert.Equal("482913", OneTimeCodeDetector.Find($"Your code is 482913. {filler}"));
    }

    [Fact]
    public void TheNearestCandidate_Wins()
    {
        Assert.Equal("4821", OneTimeCodeDetector.Find("Use 4821 as your PIN. Your parcel 7788 arrives soon"));
        Assert.Equal("5521", OneTimeCodeDetector.Find("Parcel 7788 arrives today. Your PIN: 5521"));
        // Equally near: digits beat letters and digits.
        Assert.Equal("482913", OneTimeCodeDetector.Find("K7F2P9 code 482913"));
    }

    [Theory]
    [InlineData("1234", true)]
    [InlineData("12345678", true)]
    [InlineData("K7F2P9", true)]
    [InlineData("A1B2C3D4", true)]
    [InlineData("123", false)]
    [InlineData("123456789", false)]
    [InlineData("ABCDEF", false)]
    [InlineData("k7f2p9", false)]
    [InlineData("12 34", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void IsCode_AcceptsOnlyWhatFindReturns(string? value, bool expected)
    {
        Assert.Equal(expected, OneTimeCodeDetector.IsCode(value));
    }
}

/// <summary>In-memory settings: writes are counted, nothing touches the disk.</summary>
internal sealed class MemorySettingsStore : ISettingsStore
{
    public AppSettings Current { get; private set; } = new("PC", true, "D:\\x");
    public int Writes { get; private set; }
    public event Action<AppSettings>? SettingsChanged;

    public Task UpdateAsync(Func<AppSettings, AppSettings> transform)
    {
        Current = transform(Current);
        Writes++;
        SettingsChanged?.Invoke(Current);
        return Task.CompletedTask;
    }
}

/// <summary>ADR-063: Show, Feed only and Hide per phone app, saved in settings.</summary>
public sealed class NotificationAppRulesTests
{
    [Fact]
    public async Task SeenApps_AreListedAsShow_AndChoicesPersist()
    {
        var settings = new MemorySettingsStore();
        var rules = new NotificationAppRules(settings);
        int changes = 0;
        rules.Changed += () => changes++;

        rules.NoteSeen("com.example.chat", "Chat");
        rules.NoteSeen("com.example.chat", "Chat");
        rules.NoteSeen("com.example.bank", " Bank ");
        Assert.Equal(2, changes);
        Assert.Equal(NotificationAppMode.Show, rules.ModeFor("com.example.chat"));
        Assert.Equal(["Chat", "Bank"], rules.Apps.Select(a => a.Label));

        await rules.SetModeAsync("com.example.chat", NotificationAppMode.FeedOnly);
        await rules.SetModeAsync("com.example.bank", NotificationAppMode.Hide);
        Assert.Equal(4, changes);

        var reloaded = new NotificationAppRules(settings);
        Assert.Equal(NotificationAppMode.FeedOnly, reloaded.ModeFor("com.example.chat"));
        Assert.Equal(NotificationAppMode.Hide, reloaded.ModeFor("com.example.bank"));
        Assert.Equal(NotificationAppMode.Show, reloaded.ModeFor("com.example.other"));
        Assert.Equal(NotificationAppMode.Show, reloaded.ModeFor(null));
    }

    [Fact]
    public async Task UnchangedChoice_IsNotWrittenAgain_AndALabelChangeIs()
    {
        var settings = new MemorySettingsStore();
        var rules = new NotificationAppRules(settings);
        rules.NoteSeen("com.example.chat", "Chat");
        await rules.SetModeAsync("com.example.chat", NotificationAppMode.FeedOnly);
        int writes = settings.Writes;

        await rules.SetModeAsync("com.example.chat", NotificationAppMode.FeedOnly);
        rules.NoteSeen("com.example.chat", "Chat");
        Assert.Equal(writes, settings.Writes);

        rules.NoteSeen("com.example.chat", "Chat Plus");
        Assert.Equal("Chat Plus", Assert.Single(rules.Apps).Label);
        Assert.Equal(NotificationAppMode.FeedOnly, rules.ModeFor("com.example.chat"));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("com.example/../x")]
    [InlineData("com example")]
    [InlineData("com.ex\u00E4mple")]
    public async Task InvalidPackages_AreNotListed_NorAccepted(string? package)
    {
        var settings = new MemorySettingsStore();
        var rules = new NotificationAppRules(settings);
        rules.NoteSeen(package, "App");
        Assert.Empty(rules.Apps);
        Assert.False(NotificationAppRules.IsValidPackage(package));
        if (package is not null)
            await Assert.ThrowsAsync<ArgumentException>(() => rules.SetModeAsync(package, NotificationAppMode.Hide));
    }

    [Fact]
    public void LongLabels_AreCut_AndAnEmptyLabelIsThePackage()
    {
        var rules = new NotificationAppRules(new MemorySettingsStore());
        rules.NoteSeen("com.example.long", new string('x', 500));
        rules.NoteSeen("com.example.nolabel", "  ");
        Assert.Equal(NotificationAppRules.MaxLabelLength, rules.Apps[0].Label.Length);
        Assert.Equal("com.example.nolabel", rules.Apps[1].Label);
        Assert.False(NotificationAppRules.IsValidPackage(new string('a', NotificationAppRules.MaxPackageLength + 1)));
    }

    [Fact]
    public async Task AtTheCap_TheOldestAppWithoutAChoiceMakesRoom()
    {
        var rules = new NotificationAppRules(new MemorySettingsStore());
        await rules.SetModeAsync("com.example.keep", NotificationAppMode.Hide);
        for (int i = 1; i < NotificationAppRules.MaxApps; i++) rules.NoteSeen($"com.example.app{i}", null);
        Assert.Equal(NotificationAppRules.MaxApps, rules.Apps.Count);

        rules.NoteSeen("com.example.newest", "Newest");

        Assert.Equal(NotificationAppRules.MaxApps, rules.Apps.Count);
        Assert.Equal(NotificationAppMode.Hide, rules.ModeFor("com.example.keep"));
        Assert.DoesNotContain(rules.Apps, a => a.Package == "com.example.app1");
        Assert.Contains(rules.Apps, a => a.Package == "com.example.newest");
    }

    [Fact]
    public async Task SettingsFile_RoundTripsTheRules_AndToleratesUnknownValues()
    {
        string dir = Path.Combine(AppContext.BaseDirectory, "veyro-rules-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new FileSettingsStore(dir);
            var rules = new NotificationAppRules(store);
            rules.NoteSeen("com.example.chat", "Chat");
            await rules.SetModeAsync("com.example.chat", NotificationAppMode.FeedOnly);

            Assert.Equal(NotificationAppMode.FeedOnly, new NotificationAppRules(new FileSettingsStore(dir)).ModeFor("com.example.chat"));

            // A value from a newer build, a bad package and a missing one: the rest still loads.
            File.WriteAllText(Path.Combine(dir, "settings.json"),
                """{"DeviceName":"PC","IsDiscoverable":true,"DownloadDirectory":"D:\\x","TrayModeChosen":true,"NotificationApps":[{"Package":"com.example.a","Label":"A","Mode":7},{"Package":"bad package","Label":"B","Mode":2},{"Label":"C","Mode":2},{"Package":"com.example.d","Label":"D","Mode":2}]}""");
            var loaded = new NotificationAppRules(new FileSettingsStore(dir));
            Assert.Equal(["com.example.a", "com.example.d"], loaded.Apps.Select(a => a.Package));
            Assert.Equal(NotificationAppMode.Show, loaded.ModeFor("com.example.a"));
            Assert.Equal(NotificationAppMode.Hide, loaded.ModeFor("com.example.d"));
        }
        finally
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
        }
    }
}

/// <summary>ADR-063: rules applied in the mirror service, codes on the model, and the 24-hour history.</summary>
public sealed class NotificationMirrorUpgradeTests
{
    private const string Phone = "PHONE";
    private const string ChatPackage = "com.example.chat";
    private const string BankPackage = "com.example.bank";

    private sealed class Rig : IDisposable
    {
        public FakePeerSessions Sessions { get; } = new();
        public MemorySettingsStore Settings { get; } = new();
        public ManualTimeProvider Time { get; } = new();
        public NotificationAppRules Rules { get; }
        public NotificationMirrorService Mirror { get; }
        public List<NotificationChange> Changes { get; } = [];

        public Rig()
        {
            Sessions.Connect(Phone, PeerCapabilities.NotificationMirror);
            Rules = new NotificationAppRules(Settings);
            Mirror = new NotificationMirrorService(Sessions, Rules, Time);
            Mirror.Changed += Changes.Add;
        }

        public void Post(string key, string pkg = ChatPackage, string kind = PeerMessage.Notification.KindPosted, string text = "body")
            => Sessions.ReceiveNotification(Phone, new PeerMessage.Notification(
                key, kind, Pkg: pkg, App: pkg == ChatPackage ? "Chat" : "Bank", Title: "Title", Text: text,
                Ts: Time.GetUtcNow().ToUnixTimeMilliseconds()));

        public void Remove(string key) => Sessions.ReceiveNotification(Phone, new PeerMessage.Notification(key, PeerMessage.Notification.KindRemoved));

        public void Dispose() => Mirror.Dispose();
    }

    [Fact]
    public void ShowApp_PostsAlert_AndTheAppIsListed()
    {
        using var rig = new Rig();
        rig.Post("a");

        Assert.Equal(NotificationChangeKind.Posted, Assert.Single(rig.Changes).Kind);
        NotificationAppRule app = Assert.Single(rig.Rules.Apps);
        Assert.Equal((ChatPackage, "Chat", NotificationAppMode.Show), (app.Package, app.Label, app.Mode));
    }

    [Fact]
    public async Task FeedOnlyApp_IsInTheFeed_ButNeverRaisesAPost()
    {
        using var rig = new Rig();
        await rig.Rules.SetModeAsync(ChatPackage, NotificationAppMode.FeedOnly);
        rig.Changes.Clear();

        rig.Post("a");
        rig.Post("b", pkg: BankPackage);

        Assert.Equal(["a", "b"], rig.Mirror.GetNotifications(Phone).Select(n => n.Key).Order());
        NotificationChange chat = rig.Changes.Single(c => c.Key == "a");
        Assert.Equal(NotificationChangeKind.Updated, chat.Kind);
        Assert.Equal(NotificationChangeKind.Posted, rig.Changes.Single(c => c.Key == "b").Kind);
    }

    [Fact]
    public async Task HiddenApp_IsNotKept_NorAnnounced()
    {
        using var rig = new Rig();
        await rig.Rules.SetModeAsync(ChatPackage, NotificationAppMode.Hide);
        rig.Changes.Clear();

        rig.Post("a");
        rig.Post("a", kind: PeerMessage.Notification.KindActive);
        rig.Remove("a");

        Assert.Empty(rig.Mirror.GetNotifications(Phone));
        Assert.Empty(rig.Mirror.GetHistory(Phone));
        Assert.Empty(rig.Changes);
    }

    [Fact]
    public async Task TurningAnAppToHide_RemovesItsLiveAndEarlierNotifications()
    {
        using var rig = new Rig();
        rig.Post("a");
        rig.Post("b");
        rig.Post("c", pkg: BankPackage);
        rig.Remove("b");
        rig.Changes.Clear();

        await rig.Rules.SetModeAsync(ChatPackage, NotificationAppMode.Hide);

        Assert.Equal(["c"], rig.Mirror.GetNotifications(Phone).Select(n => n.Key));
        Assert.Empty(rig.Mirror.GetHistory(Phone));
        // Removed lets the toast service take the app's toasts down.
        Assert.Contains(rig.Changes, c => c.Kind == NotificationChangeKind.Removed && c.Key == "a");
        Assert.Contains(rig.Changes, c => c.Kind == NotificationChangeKind.History);
    }

    [Fact]
    public void CodeIsFoundOnTheModel()
    {
        using var rig = new Rig();
        rig.Post("otp", pkg: BankPackage, text: "Your verification code is 482913");
        rig.Post("plain", text: "See you at 7");

        Assert.Equal("482913", rig.Mirror.GetNotifications(Phone).Single(n => n.Key == "otp").Code);
        Assert.Null(rig.Mirror.GetNotifications(Phone).Single(n => n.Key == "plain").Code);
    }

    [Fact]
    public void Removed_MovesToHistory_NewestRemovalFirst()
    {
        using var rig = new Rig();
        rig.Post("a");
        rig.Post("b");
        rig.Remove("a");
        rig.Time.Advance(TimeSpan.FromMinutes(1));
        rig.Remove("b");

        Assert.Empty(rig.Mirror.GetNotifications(Phone));
        IReadOnlyList<MirroredNotification> history = rig.Mirror.GetHistory(Phone);
        Assert.Equal(["b", "a"], history.Select(n => n.Key));
        Assert.Equal(rig.Time.GetUtcNow(), history[0].RemovedAt);
        Assert.Equal("Title", history[0].Title);
    }

    [Fact]
    public async Task DismissOnThePc_IsSentToThePhone_AndHistoryFollowsThePhonesRemoval()
    {
        using var rig = new Rig();
        rig.Post("a");

        await rig.Mirror.DismissAsync(Phone, "a");
        Assert.Equal(new PeerMessage.NotificationDismissRequest("a"), Assert.Single(rig.Sessions.SentOf<PeerMessage.NotificationDismissRequest>(Phone)));
        // Still live until the phone confirms with "removed" (ADR-025).
        Assert.Single(rig.Mirror.GetNotifications(Phone));
        Assert.Empty(rig.Mirror.GetHistory(Phone));

        rig.Remove("a");
        Assert.Empty(rig.Mirror.GetNotifications(Phone));
        Assert.Equal("a", Assert.Single(rig.Mirror.GetHistory(Phone)).Key);
    }

    [Fact]
    public void AKeyPostedAgain_LeavesHistory()
    {
        using var rig = new Rig();
        rig.Post("a");
        rig.Remove("a");
        rig.Post("a", kind: PeerMessage.Notification.KindActive);

        Assert.Equal("a", Assert.Single(rig.Mirror.GetNotifications(Phone)).Key);
        Assert.Empty(rig.Mirror.GetHistory(Phone));
    }

    [Fact]
    public void SessionClose_ClearsLive_ButKeepsHistory()
    {
        using var rig = new Rig();
        rig.Post("a");
        rig.Post("b");
        rig.Remove("a");

        rig.Sessions.Disconnect(Phone);

        Assert.Empty(rig.Mirror.GetNotifications(Phone));
        Assert.Equal("a", Assert.Single(rig.Mirror.GetHistory(Phone)).Key);
    }

    [Fact]
    public void History_ExpiresAfter24Hours_AndSaysSo()
    {
        using var rig = new Rig();
        rig.Post("a");
        rig.Post("b");
        rig.Remove("a");
        rig.Time.Advance(TimeSpan.FromHours(1));
        rig.Remove("b");
        rig.Changes.Clear();

        rig.Time.Advance(TimeSpan.FromHours(23) - TimeSpan.FromSeconds(1));
        Assert.Equal(2, rig.Mirror.GetHistory(Phone).Count);
        Assert.Empty(rig.Changes);

        rig.Time.Advance(TimeSpan.FromSeconds(1));
        Assert.Equal("b", Assert.Single(rig.Mirror.GetHistory(Phone)).Key);
        Assert.Equal(NotificationChangeKind.History, Assert.Single(rig.Changes).Kind);

        rig.Time.Advance(TimeSpan.FromHours(1));
        Assert.Empty(rig.Mirror.GetHistory(Phone));
        Assert.Equal(2, rig.Changes.Count);
    }

    [Fact]
    public void ClearHistory_EmptiesOnlyThatPhone_AndSendsNothing()
    {
        using var rig = new Rig();
        rig.Sessions.Connect("OTHER", PeerCapabilities.NotificationMirror);
        rig.Post("a");
        rig.Remove("a");
        rig.Sessions.ReceiveNotification("OTHER", new PeerMessage.Notification("x", PeerMessage.Notification.KindPosted, Pkg: ChatPackage, Title: "t"));
        rig.Sessions.ReceiveNotification("OTHER", new PeerMessage.Notification("x", PeerMessage.Notification.KindRemoved));
        rig.Changes.Clear();

        rig.Mirror.ClearHistory(Phone);
        rig.Mirror.ClearHistory(Phone);

        Assert.Empty(rig.Mirror.GetHistory(Phone));
        Assert.Single(rig.Mirror.GetHistory("OTHER"));
        Assert.Equal(NotificationChangeKind.History, Assert.Single(rig.Changes).Kind);
        Assert.Empty(rig.Sessions.Sent);
    }

    [Fact]
    public void History_IsCapped_OldestRemovalDropped()
    {
        using var rig = new Rig();
        for (int i = 0; i <= NotificationMirrorService.MaxHistoryPerPeer; i++)
        {
            rig.Post("k" + i);
            rig.Remove("k" + i);
        }

        IReadOnlyList<MirroredNotification> history = rig.Mirror.GetHistory(Phone);
        Assert.Equal(NotificationMirrorService.MaxHistoryPerPeer, history.Count);
        Assert.Equal("k" + NotificationMirrorService.MaxHistoryPerPeer, history[0].Key);
        Assert.DoesNotContain(history, n => n.Key == "k0");
    }

    [Fact]
    public void WithoutRules_EverythingShows()
    {
        var sessions = new FakePeerSessions();
        using var mirror = new NotificationMirrorService(sessions);
        var changes = new List<NotificationChange>();
        mirror.Changed += changes.Add;

        sessions.ReceiveNotification(Phone, new PeerMessage.Notification("a", PeerMessage.Notification.KindPosted, Pkg: ChatPackage, Title: "t"));

        Assert.Equal(NotificationChangeKind.Posted, Assert.Single(changes).Kind);
    }
}

/// <summary>ADR-063: "Copy code" uses the clipboard sink without echoing the code to the phones.</summary>
public sealed class CopyCodeClipboardTests
{
    private sealed class FakeSink : IClipboardSink
    {
        public List<string> Texts { get; } = [];
        public Task SetTextAsync(string text) { Texts.Add(text); return Task.CompletedTask; }
        public Task SetImageAsync(byte[] image) => Task.CompletedTask;
    }

    [Fact]
    public async Task CopiedCode_IsOnThePcClipboard_NotSentBack_NorStored()
    {
        var sessions = new FakePeerSessions();
        sessions.Connect("PHONE", PeerCapabilities.Clipboard);
        var sink = new FakeSink();
        using var service = new ClipboardService(sessions, () => true, () => false) { Sink = sink };

        Assert.True(await service.CopyTextToPcAsync("482913"));
        // Windows raises a clipboard change for what Veyro just set.
        await service.OnLocalTextCopiedAsync("482913");

        Assert.Equal(["482913"], sink.Texts);
        Assert.Empty(sessions.SentOf<PeerMessage.Clip>("PHONE"));
        Assert.Empty(service.Items);
    }

    [Fact]
    public async Task WithoutAClipboard_NothingIsCopied()
    {
        using var service = new ClipboardService(new FakePeerSessions(), () => true, () => false);
        Assert.False(await service.CopyTextToPcAsync("482913"));
    }
}
