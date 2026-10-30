using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Clipboard;
using Veyro.Core.Data;
using Veyro.Core.Devices;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Status;
using Xunit;

namespace Veyro.Tests;

/// <summary>Drives protocol/testvectors/link-device-info-controls.json (ADR-059, ADR-060).</summary>
public sealed class DeviceInfoControlsVectorTests
{
    private static JsonDocument Load()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-device-info-controls.json")));
    }

    public static TheoryData<int> VectorIndices()
    {
        var data = new TheoryData<int>();
        using var doc = Load();
        int count = doc.RootElement.GetProperty("vectors").GetArrayLength();
        for (int i = 0; i < count; i++) data.Add(i);
        return data;
    }

    [Theory]
    [MemberData(nameof(VectorIndices))]
    public void Vector_DecodesToExpectedTypeAndReEncodesEquivalently(int index)
    {
        using var doc = Load();
        JsonElement vector = doc.RootElement.GetProperty("vectors")[index];
        string json = vector.GetProperty("json").GetString()!;
        PeerMessage? decoded = PeerMessageJson.Decode<PeerMessage>(json);
        Type expected = vector.GetProperty("type").GetString() switch
        {
            "deviceInfo" => typeof(PeerMessage.DeviceInfo),
            "devStatus" => typeof(PeerMessage.DeviceStatus),
            "devControl" => typeof(PeerMessage.DeviceControl),
            var other => throw new InvalidOperationException($"Unexpected vector type {other}")
        };
        Assert.IsType(expected, decoded);
        string reEncoded = PeerMessageJson.Encode(decoded);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)), $"Vector {index} re-encoded as {reEncoded}");
        Assert.Equal(decoded, PeerMessageJson.Decode<PeerMessage>(reEncoded));
    }

    [Fact]
    public void Vectors_CarryTheExpectedValues()
    {
        using var doc = Load();
        PeerMessage?[] decoded = doc.RootElement.GetProperty("vectors").EnumerateArray()
            .Select(v => PeerMessageJson.Decode<PeerMessage>(v.GetProperty("json").GetString()!)).ToArray();
        Assert.Equal(7, decoded.Length);
        Assert.Equal(new PeerMessage.DeviceInfo("Pixel 9", false), decoded[1]);
        var status = Assert.IsType<PeerMessage.DeviceStatus>(decoded[3]);
        Assert.Equal((false, 40, 75, true), (status.Torch!.Value, status.MediaVolume!.Value, status.Brightness!.Value, status.AutoBrightness!.Value));
        Assert.Equal(new PhoneControlState("ok", "ok", "permission"), status.Controls);
        Assert.Equal(new PeerMessage.DeviceControl(MediaVolume: 55, Brightness: 30, AutoBrightness: false), decoded[6]);
    }

    [Fact]
    public void NameRule_MatchesTheSharedCases()
    {
        using var doc = Load();
        JsonElement names = doc.RootElement.GetProperty("names");
        foreach (JsonElement pair in names.GetProperty("accept").EnumerateArray())
            Assert.Equal(pair[1].GetString(), DeviceNames.Clean(pair[0].GetString()));
        int rejected = 0;
        foreach (JsonElement input in names.GetProperty("reject").EnumerateArray())
        {
            Assert.Null(DeviceNames.Clean(input.GetString()));
            rejected++;
        }
        Assert.True(rejected >= 8);
        Assert.Null(DeviceNames.Clean(null));
    }

    [Fact]
    public void CapabilityBits_AreTheAssignedOnes_AndAdvertised()
    {
        Assert.Equal(0x40000, PeerCapabilities.PhoneControls);
        Assert.Equal(0x200000, PeerCapabilities.DeviceInfo);
        Assert.NotEqual(0, LinkServer.LocalCapabilities & PeerCapabilities.PhoneControls);
        Assert.NotEqual(0, LinkServer.LocalCapabilities & PeerCapabilities.DeviceInfo);
    }
}

/// <summary>In-memory trust store for the name tests.</summary>
internal sealed class MemoryTrustStore : ITrustStore
{
    private readonly List<TrustedPeer> _peers = [];
    public int Writes { get; private set; }

    public IReadOnlyList<TrustedPeer> GetTrustedPeers() => _peers.ToList();
    public TrustedPeer? GetPeer(string deviceId) => _peers.FirstOrDefault(p => string.Equals(p.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));

    public Task UpsertAsync(TrustedPeer peer)
    {
        _peers.RemoveAll(p => string.Equals(p.DeviceId, peer.DeviceId, StringComparison.OrdinalIgnoreCase));
        _peers.Add(peer);
        Writes++;
        return Task.CompletedTask;
    }

    public Task RemoveAsync(string deviceId)
    {
        _peers.RemoveAll(p => string.Equals(p.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
        return Task.CompletedTask;
    }
}

/// <summary>ADR-059: live device names on the PC.</summary>
public sealed class DeviceNameServiceTests
{
    private const string Phone = "PHONE";

    private sealed class Rig : IDisposable
    {
        public FakePeerSessions Sessions { get; } = new();
        public MemoryTrustStore Trust { get; } = new();
        public ManualTimeProvider Time { get; } = new();
        public string LocalName { get; set; } = "Office PC";
        public List<string> Renamed { get; } = [];
        public DeviceNameService Service { get; }

        public Rig()
        {
            Trust.UpsertAsync(new TrustedPeer(Phone, "Pixel", PlatformType.Android, "", "", false, DateTimeOffset.UnixEpoch)).Wait();
            Service = new DeviceNameService(Sessions, Trust, () => LocalName, Time);
            Service.PeerNameChanged += Renamed.Add;
        }

        public List<string?> Told(string peer) => Sessions.SentOf<PeerMessage.DeviceInfo>(peer).Select(m => m.Name).ToList();

        public void Dispose() => Service.Dispose();
    }

    [Fact]
    public void Connect_TellsACapablePhoneThePcName_AndNotAnOlderOne()
    {
        using var rig = new Rig();
        rig.Sessions.Connect(Phone, PeerCapabilities.DeviceInfo | PeerCapabilities.FileTransfer);
        rig.Sessions.Connect("OLD", PeerCapabilities.FileTransfer);
        Assert.Equal(["Office PC"], rig.Told(Phone));
        Assert.Empty(rig.Sessions.SentOf<PeerMessage.DeviceInfo>("OLD"));
    }

    [Fact]
    public void Rename_ReachesEveryConnectedPhoneOnce_AfterTyping()
    {
        using var rig = new Rig();
        rig.Sessions.Connect(Phone, PeerCapabilities.DeviceInfo);
        rig.Sessions.Connect("SECOND", PeerCapabilities.DeviceInfo);

        // The Settings box saves on every keystroke: only the final name goes out.
        foreach (string typed in new[] { "S", "St", "Study", "Study PC" })
        {
            rig.LocalName = typed;
            rig.Service.LocalNameChanged();
            rig.Time.Advance(TimeSpan.FromMilliseconds(200));
        }
        Assert.Equal(["Office PC"], rig.Told(Phone));
        rig.Time.Advance(DeviceNameService.RenameDebounce);
        Assert.Equal(["Office PC", "Study PC"], rig.Told(Phone));
        Assert.Equal(["Office PC", "Study PC"], rig.Told("SECOND"));

        // Nothing changed: nothing resent.
        rig.Service.LocalNameChanged();
        rig.Time.Advance(DeviceNameService.RenameDebounce);
        Assert.Equal(2, rig.Told(Phone).Count);
    }

    [Fact]
    public void Reconnect_TellsTheCurrentNameAgain()
    {
        using var rig = new Rig();
        rig.Sessions.Connect(Phone, PeerCapabilities.DeviceInfo);
        rig.Sessions.Disconnect(Phone);
        rig.LocalName = "Renamed while away";
        rig.Sessions.Connect(Phone, PeerCapabilities.DeviceInfo);
        Assert.Equal(["Office PC", "Renamed while away"], rig.Told(Phone));
    }

    [Fact]
    public async Task PhoneName_IsStoredWhenItChanges_KeepsTheNickname_AndRaisesTheEvent()
    {
        using var rig = new Rig();
        await rig.Trust.UpsertAsync(rig.Trust.GetPeer(Phone)! with { Nickname = "Work phone" });
        int writes = rig.Trust.Writes;

        await rig.Service.StoreAsync(Phone, "  Pixel 9 ");
        await rig.Service.StoreAsync(Phone, "Pixel 9");
        TrustedPeer stored = rig.Trust.GetPeer(Phone)!;
        Assert.Equal("Pixel 9", stored.DisplayName);
        Assert.Equal("Work phone", stored.Nickname);
        Assert.Equal("Work phone", stored.ShownName);
        Assert.Equal(writes + 1, rig.Trust.Writes);
        Assert.Equal([Phone], rig.Renamed);
    }

    [Fact]
    public async Task InvalidOrUnknown_ChangesNothing()
    {
        using var rig = new Rig();
        await rig.Service.StoreAsync(Phone, "Bell\u0007");
        await rig.Service.StoreAsync(Phone, new string('A', 65));
        await rig.Service.StoreAsync(Phone, "   ");
        await rig.Service.StoreAsync("STRANGER", "Someone");
        Assert.Equal("Pixel", rig.Trust.GetPeer(Phone)!.DisplayName);
        Assert.Null(rig.Trust.GetPeer("STRANGER"));
        Assert.Empty(rig.Renamed);
    }

    [Fact]
    public async Task DeviceInfoMessage_IsStored()
    {
        using var rig = new Rig();
        rig.Sessions.Connect(Phone, PeerCapabilities.DeviceInfo);
        rig.Sessions.Receive(Phone, new PeerMessage.DeviceInfo("Pixel Fold", ClipReceive: true));
        await Task.Delay(50);
        Assert.Equal("Pixel Fold", rig.Trust.GetPeer(Phone)!.DisplayName);
        // A deviceInfo with no name (only the clipboard switch) leaves the name alone.
        rig.Sessions.Receive(Phone, new PeerMessage.DeviceInfo(ClipReceive: false));
        await Task.Delay(50);
        Assert.Equal("Pixel Fold", rig.Trust.GetPeer(Phone)!.DisplayName);
    }
}

/// <summary>ADR-059: the nickname in the Windows trust record and the switcher.</summary>
public sealed class TrustNicknameTests : IDisposable
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-nickname-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private static TrustedPeer Peer(string name, string? nickname = null)
        => new("PHONE", name, PlatformType.Android, "", Convert.ToBase64String(new byte[32]), true, DateTimeOffset.UnixEpoch, Nickname: nickname);

    [Fact]
    public async Task Nickname_SurvivesAReload_AndAnAnnouncedRename()
    {
        var store = new FileTrustStore(_root, new byte[32]);
        await store.UpsertAsync(Peer("Pixel", nickname: "Work phone"));
        Assert.True(await ((ITrustStore)store).SetAnnouncedNameAsync("PHONE", "Pixel 9"));
        Assert.False(await ((ITrustStore)store).SetAnnouncedNameAsync("PHONE", "Pixel 9"));

        TrustedPeer reloaded = new FileTrustStore(_root, new byte[32]).GetPeer("PHONE")!;
        Assert.Equal("Pixel 9", reloaded.DisplayName);
        Assert.Equal("Work phone", reloaded.Nickname);
        Assert.True(reloaded.AutoAcceptTransfers);
    }

    [Fact]
    public async Task ARecordWithoutNickname_StillLoads()
    {
        var store = new FileTrustStore(_root, new byte[32]);
        await store.UpsertAsync(Peer("Pixel"));
        string file = Path.Combine(_root, "trust.json");
        Assert.DoesNotContain("ShownName", File.ReadAllText(file));
        TrustedPeer reloaded = new FileTrustStore(_root, new byte[32]).GetPeer("PHONE")!;
        Assert.Null(reloaded.Nickname);
        Assert.Equal("Pixel", reloaded.ShownName);
    }

    [Fact]
    public void Switcher_ShowsTheNicknameFirst()
    {
        var entries = PairedDeviceDirectory.Build([Peer("Pixel", "Work phone"), Peer("Galaxy") with { DeviceId = "OTHER" }], _ => false);
        Assert.Equal(["Work phone", "Galaxy"], entries.Select(e => e.DisplayName));
    }
}

/// <summary>ADR-059: the PC follows a phone's "Auto-fetch clipboard" switch, live.</summary>
public sealed class ClipboardReceivePreferenceTests
{
    private const string Phone = "PHONE";
    private const string Other = "OTHER";

    private sealed class Rig
    {
        public FakePeerSessions Sessions { get; } = new();
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UnixEpoch;
        public bool ImagesEnabled { get; set; }
        public List<string> Changed { get; } = [];
        public ClipboardService Service { get; }
        private int _copy;

        private sealed class NullSink : IClipboardSink
        {
            public Task SetTextAsync(string text) => Task.CompletedTask;
            public Task SetImageAsync(byte[] image) => Task.CompletedTask;
        }

        public Rig()
        {
            Service = new ClipboardService(Sessions, () => true, () => ImagesEnabled, () => Now) { Sink = new NullSink() };
            Service.ReceivePreferenceChanged += Changed.Add;
            Sessions.Connect(Phone, PeerCapabilities.Clipboard);
            Sessions.Connect(Other, PeerCapabilities.Clipboard);
        }

        /// <summary>A new copy on the PC, later than the last (the repeat guard is 2 s).</summary>
        public Task CopyAsync(string text)
        {
            Now += TimeSpan.FromSeconds(5 + _copy++);
            return Service.OnLocalTextCopiedAsync(text);
        }

        public Task SayAsync(bool receive) => Service.SetReceive(Phone, receive);

        public List<string?> Got(string peer) => Sessions.SentOf<PeerMessage.Clip>(peer).Select(c => c.Text).ToList();
    }

    [Fact]
    public async Task Off_SkipsOnlyThatPhone_On_ResumesWithTheNextCopy()
    {
        var rig = new Rig();
        await rig.SayAsync(false);
        Assert.True(rig.Service.IsReceiveOff(Phone));
        await rig.CopyAsync("one");
        Assert.Empty(rig.Got(Phone));
        Assert.Equal(["one"], rig.Got(Other));

        await rig.SayAsync(true);
        await rig.CopyAsync("two");
        Assert.Equal(["one", "two"], rig.Got(Phone).Where(t => t is not null).ToList());
        Assert.Equal([Phone, Phone], rig.Changed);
    }

    [Fact]
    public async Task TurningBackOn_SendsTheMissedLatestCopyOnce()
    {
        var rig = new Rig();
        await rig.SayAsync(false);
        await rig.CopyAsync("first");
        await rig.CopyAsync("latest");
        rig.Now += TimeSpan.FromMinutes(9);

        await rig.SayAsync(true);
        Assert.Equal(["latest"], rig.Got(Phone));

        // Toggling again, or the same "on" repeated on reconnect, sends nothing more.
        await rig.SayAsync(true);
        await rig.SayAsync(false);
        await rig.SayAsync(true);
        Assert.Equal(["latest"], rig.Got(Phone));
        Assert.Equal(["first", "latest"], rig.Got(Other));
    }

    [Fact]
    public async Task CatchUp_OnlyWithinTenMinutes()
    {
        var rig = new Rig();
        await rig.SayAsync(false);
        await rig.CopyAsync("old");
        rig.Now += ClipboardService.CatchUpWindow + TimeSpan.FromSeconds(1);
        await rig.SayAsync(true);
        Assert.Empty(rig.Got(Phone));
    }

    [Fact]
    public async Task NoCatchUp_ForACopyMadeWhileItWasOn_OrSentAlready()
    {
        var rig = new Rig();
        await rig.CopyAsync("seen");
        await rig.SayAsync(false);
        await rig.SayAsync(true);
        Assert.Equal(["seen"], rig.Got(Phone));
    }

    [Fact]
    public async Task NoCatchUp_AfterThePcClipboardTookSomethingFromAPhone()
    {
        var rig = new Rig();
        await rig.SayAsync(false);
        await rig.CopyAsync("copied here");
        await rig.Service.HandleIncomingAsync(Other, new PeerMessage.Clip("x", "text", Text: "from a phone", Apply: true));
        await rig.SayAsync(true);
        Assert.Empty(rig.Got(Phone));
    }

    [Fact]
    public async Task CatchUp_RespectsTheImageSetting()
    {
        var rig = new Rig { ImagesEnabled = true };
        await rig.SayAsync(false);
        rig.Now += TimeSpan.FromSeconds(10);
        await rig.Service.OnLocalImageCopiedAsync([0x89, 0x50, 0x4E, 0x47, 1, 2]);
        rig.ImagesEnabled = false;
        await rig.SayAsync(true);
        Assert.Empty(rig.Sessions.SentOf<PeerMessage.Clip>(Phone));
    }

    [Fact]
    public async Task Announcement_ArrivesAsDeviceInfo_AndTheNoteNamesThePhones()
    {
        var rig = new Rig();
        rig.Sessions.Receive(Phone, new PeerMessage.DeviceInfo("Pixel", ClipReceive: false));
        await Task.Delay(20);
        Assert.Equal([Phone], rig.Service.PeersWithReceiveOff);
        Assert.Equal("Auto-fetch is off on Pixel. What you copy here is not sent there until it is turned back on.",
            ClipboardService.DescribeReceiveOff(["Pixel"]));
        Assert.StartsWith("Auto-fetch is off on Pixel, Galaxy and Fold.", ClipboardService.DescribeReceiveOff(["Pixel", "Galaxy", "Fold"]));
        Assert.Null(ClipboardService.DescribeReceiveOff([]));
    }
}

/// <summary>ADR-060: flashlight, volume and brightness in the PC's device status service.</summary>
public sealed class PhoneControlsServiceTests
{
    private const string Phone = "PHONE";
    private const int Caps = PeerCapabilities.DeviceStatus | PeerCapabilities.PhoneControls;

    [Fact]
    public async Task SendPhoneControl_ValidatesRanges_AndNeedsTheBit()
    {
        var sessions = new FakePeerSessions();
        using var service = new DeviceStatusService(sessions, null);
        sessions.Connect(Phone, Caps);
        sessions.Connect("OLD", PeerCapabilities.DeviceStatus);

        Assert.True(service.CanUsePhoneControls(Phone));
        Assert.False(service.CanUsePhoneControls("OLD"));
        await service.SendPhoneControlAsync(Phone, torch: true);
        await service.SendPhoneControlAsync(Phone, mediaVolume: 0, brightness: 100, autoBrightness: false);
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendPhoneControlAsync(Phone));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => service.SendPhoneControlAsync(Phone, mediaVolume: 101));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => service.SendPhoneControlAsync(Phone, brightness: -1));
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendPhoneControlAsync("OLD", torch: true));
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendPhoneControlAsync("NOBODY", torch: true));

        Assert.Equal(
            [new PeerMessage.DeviceControl(Torch: true), new PeerMessage.DeviceControl(MediaVolume: 0, Brightness: 100, AutoBrightness: false)],
            sessions.SentOf<PeerMessage.DeviceControl>(Phone));
        Assert.Empty(sessions.SentOf<PeerMessage.DeviceControl>("OLD"));
    }

    [Fact]
    public void ReportedLevels_AreSanitized()
    {
        var sessions = new FakePeerSessions();
        using var service = new DeviceStatusService(sessions, null);
        sessions.Connect(Phone, Caps);
        sessions.ReceiveDeviceStatus(Phone, new PeerMessage.DeviceStatus(
            Torch: true, MediaVolume: 140, Brightness: 50, AutoBrightness: false,
            Controls: new PhoneControlState("ok", "loud", "permission")));
        var status = service.GetPhoneStatus(Phone)!;
        Assert.True(status.Torch);
        Assert.Null(status.MediaVolume);
        Assert.Equal(50, status.Brightness);
        Assert.Equal(new PhoneControlState("ok", null, "permission"), status.Controls);
    }

    [Fact]
    public void Reasons_SayWhyAControlIsOff()
    {
        var ok = new PeerMessage.DeviceStatus(Controls: new PhoneControlState("ok", "off", "permission"));
        Assert.Null(PhoneControlsView.Unavailable(true, true, ok, PhoneControlKind.Torch));
        Assert.Contains("Let PCs control flashlight, volume and brightness", PhoneControlsView.Unavailable(true, true, ok, PhoneControlKind.Volume));
        Assert.Contains("Allow brightness changes", PhoneControlsView.Unavailable(true, true, ok, PhoneControlKind.Brightness));
        Assert.Equal("The phone is not connected.", PhoneControlsView.Unavailable(false, false, ok, PhoneControlKind.Torch));
        Assert.Contains("Update Veyro on the phone", PhoneControlsView.Unavailable(true, false, null, PhoneControlKind.Torch));
        Assert.Equal("The phone has not reported this yet.", PhoneControlsView.Unavailable(true, true, null, PhoneControlKind.Volume));
        var gone = new PeerMessage.DeviceStatus(Controls: new PhoneControlState("unsupported", "unsupported", "busy"));
        Assert.Equal("This phone has no flashlight.", PhoneControlsView.Unavailable(true, true, gone, PhoneControlKind.Torch));
        Assert.NotNull(PhoneControlsView.Unavailable(true, true, gone, PhoneControlKind.Brightness));
    }

    [Fact]
    public void HeldLevel_ShowsTheUsersPickUntilConfirmedOrTwoSecondsPass()
    {
        var time = new ManualTimeProvider();
        var level = new HeldLevel(time);
        Assert.Equal(40, level.Shown(40));
        level.Request(70);
        Assert.Equal(70, level.Shown(40));
        Assert.Equal(70, level.Shown(70));
        Assert.Equal(45, level.Shown(45));

        level.Request(90);
        time.Advance(HeldLevel.HoldFor + TimeSpan.FromMilliseconds(1));
        Assert.Equal(45, level.Shown(45));
    }

    [Fact]
    public async Task LatestValueSender_SendsFirstAndLast_OneInFlight()
    {
        var sent = new List<int>();
        var gate = new TaskCompletionSource();
        var sender = new LatestValueSender<int>(async value =>
        {
            sent.Add(value);
            if (sent.Count == 1) await gate.Task;
        });

        Task first = sender.Post(10);
        _ = sender.Post(20);
        _ = sender.Post(30);
        _ = sender.Post(40);
        Assert.Equal([10], sent);
        gate.SetResult();
        await first;
        Assert.Equal([10, 40], sent);

        await sender.Post(50);
        Assert.Equal([10, 40, 50], sent);
    }
}

/// <summary>ADR-059 end to end: the PC stores a phone's own name from its hello and its deviceInfo.</summary>
[Collection(LinkServerCollection.Name)]
public sealed class DeviceNameLinkTests : IAsyncLifetime
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-names-" + Guid.NewGuid().ToString("N"));
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(30));
    private readonly byte[] _pcPublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _phonePublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);
    private string PcId => DeviceId.FromPublicKey(_pcPublicKey).Value;
    private string PhoneId => DeviceId.FromPublicKey(_phonePublicKey).Value;
    private CancellationToken Ct => _timeout.Token;
    private FileTrustStore _trust = null!;
    private PeerSessionRegistry _registry = null!;
    private LinkServer _server = null!;
    private DeviceNameService _names = null!;
    private readonly ManualTimeProvider _time = new();
    private string _pcName = "Test PC";

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(_root);
        _trust = new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]);
        await _trust.UpsertAsync(new TrustedPeer(PhoneId, "Old phone name", PlatformType.Android,
            Convert.ToBase64String(_phonePublicKey), Convert.ToBase64String(_secret), false, DateTimeOffset.UtcNow, Nickname: "Mine"));
        _registry = new PeerSessionRegistry();
        _server = new LinkServer(PcId, _pcName, _trust, Path.Combine(_root, "in"), _pcPublicKey, _ => Task.FromResult(false), sessions: _registry);
        _names = new DeviceNameService(_registry, _trust, () => _pcName, _time);
        await _server.StartAsync();
    }

    public async Task DisposeAsync()
    {
        _names.Dispose();
        await _server.DisposeAsync();
        _timeout.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private async Task<T> WaitForAsync<T>(Func<T?> read) where T : class
    {
        for (int i = 0; i < 200; i++)
        {
            if (read() is { } value) return value;
            await Task.Delay(25, Ct);
        }
        throw new TimeoutException();
    }

    [Fact]
    public async Task HelloAndDeviceInfo_UpdateTheStoredName_TheNicknameStays_AndThePcSaysItsOwnName()
    {
        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        await socket.SendAsync(Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreamble(PhoneId, "Renamed phone", "android"))),
            WebSocketMessageType.Text, true, Ct);
        byte[] buffer = new byte[4096];
        await socket.ReceiveAsync(buffer, Ct);
        await using var phone = new SessionTransferTests.FakePhone(socket,
            new SecureLinkSession(socket, PhoneId, _secret, encrypt: true, expectedPeerId: PcId));

        // Renamed while away: the hello carries the new name.
        await phone.Session.SendAsync(new PeerMessage.Hello(PhoneId, "Renamed phone", "android",
            PeerCapabilities.DeviceStatus | PeerCapabilities.DeviceInfo), Ct);
        var ack = await phone.ReceiveAsync<PeerMessage.HelloAck>(Ct);
        Assert.Equal("Test PC", ack.DeviceName);
        Assert.NotEqual(0, ack.Capabilities & PeerCapabilities.DeviceInfo);
        Assert.Equal("Test PC", (await phone.ReceiveAsync<PeerMessage.DeviceInfo>(Ct)).Name);
        await WaitForAsync(() => _trust.GetPeer(PhoneId)?.DisplayName == "Renamed phone" ? "ok" : null);

        // Renamed live, then an invalid name, which changes nothing.
        await phone.Session.SendAsync(new PeerMessage.DeviceInfo("Pixel 9"), Ct);
        await WaitForAsync(() => _trust.GetPeer(PhoneId)?.DisplayName == "Pixel 9" ? "ok" : null);
        await phone.Session.SendAsync(new PeerMessage.DeviceInfo("Two\nlines"), Ct);
        await phone.Session.SendAsync(new PeerMessage.Ping("n"), Ct);
        await phone.ReceiveAsync<PeerMessage.Pong>(Ct);
        await Task.Delay(100, Ct);
        TrustedPeer stored = _trust.GetPeer(PhoneId)!;
        Assert.Equal("Pixel 9", stored.DisplayName);
        Assert.Equal("Mine", stored.Nickname);

        // This PC renamed in Settings: the live session hears it.
        _pcName = "Study PC";
        _server.SetLocalName(_pcName);
        _names.LocalNameChanged();
        _time.Advance(DeviceNameService.RenameDebounce);
        Assert.Equal("Study PC", (await phone.ReceiveAsync<PeerMessage.DeviceInfo>(Ct)).Name);
    }
}
