using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Link;
using Veyro.Core.Media;
using Veyro.Core.Status;
using Xunit;

namespace Veyro.Tests;

/// <summary>In-memory registry: sends are recorded, inbound messages are raised on demand.</summary>
internal sealed class FakePeerSessions : IPeerSessionRegistry
{
    private readonly Dictionary<string, int> _peers = new(StringComparer.OrdinalIgnoreCase);
    public List<(string Peer, LinkMessage Message)> Sent { get; } = [];

    public event Action<string>? SessionOpened;
    public event Action<string>? SessionClosed;
    public event Action<string>? CapabilitiesChanged;
    public event Action<string, LinkMessage.Notification>? NotificationReceived;
    public event Action<string, LinkMessage.MediaState>? MediaStateReceived;
    public event Action<string, LinkMessage.MediaCommand>? MediaCommandReceived;
    public event Action<string, LinkMessage.DeviceStatus>? DeviceStatusReceived;
    public event Action<string, LinkMessage.SimList>? SimListReceived;
    public event Action<string, LinkMessage.SmsThreads>? SmsThreadsReceived;
    public event Action<string, LinkMessage.SmsMessages>? SmsMessagesReceived;
    public event Action<string, LinkMessage.SmsNew>? SmsNewReceived;
    public event Action<string, LinkMessage.SmsSendResult>? SmsSendResultReceived;
    public event Action<string, LinkMessage.CallState>? CallStateReceived;
    public event Action<string, LinkMessage.CallLog>? CallLogReceived;
    public event Action<string, LinkMessage>? PhotosMessageReceived;
    public event Action<string, LinkMessage>? DeviceMessageReceived;

    public void Receive(string peerId, LinkMessage message)
    {
        switch (message)
        {
            case LinkMessage.PcLock or LinkMessage.Wallpaper or LinkMessage.Clip:
                DeviceMessageReceived?.Invoke(peerId, message);
                break;
            case LinkMessage.Photos or LinkMessage.PhotoThumb or LinkMessage.PhotoNew or LinkMessage.PhotoRemoved:
                PhotosMessageReceived?.Invoke(peerId, message);
                break;
            case LinkMessage.SimList m: SimListReceived?.Invoke(peerId, m); break;
            case LinkMessage.SmsThreads m: SmsThreadsReceived?.Invoke(peerId, m); break;
            case LinkMessage.SmsMessages m: SmsMessagesReceived?.Invoke(peerId, m); break;
            case LinkMessage.SmsNew m: SmsNewReceived?.Invoke(peerId, m); break;
            case LinkMessage.SmsSendResult m: SmsSendResultReceived?.Invoke(peerId, m); break;
            case LinkMessage.CallState m: CallStateReceived?.Invoke(peerId, m); break;
            case LinkMessage.CallLog m: CallLogReceived?.Invoke(peerId, m); break;
            default: throw new ArgumentException("Unsupported fake message", nameof(message));
        }
    }

    public bool IsConnected(string peerId) => _peers.ContainsKey(peerId);
    public IReadOnlyList<string> GetConnectedPeers() => _peers.Keys.ToList();
    public int GetPeerCapabilities(string peerId) => _peers.GetValueOrDefault(peerId);

    public Task SendAsync(string peerId, LinkMessage message, CancellationToken ct = default)
    {
        if (!_peers.ContainsKey(peerId)) throw new InvalidOperationException("not connected");
        Sent.Add((peerId, message));
        return Task.CompletedTask;
    }

    public IOutboundTransferChannel OpenOutboundTransfer(string peerId, string transferId)
        => throw new NotSupportedException("Outbound transfers are tested against the real registry.");

    public void Connect(string peerId, int capabilities)
    {
        _peers[peerId] = 0;
        SessionOpened?.Invoke(peerId);
        _peers[peerId] = capabilities;
        CapabilitiesChanged?.Invoke(peerId);
    }

    public void Disconnect(string peerId)
    {
        _peers.Remove(peerId);
        SessionClosed?.Invoke(peerId);
    }

    public void ReceiveNotification(string peerId, LinkMessage.Notification message) => NotificationReceived?.Invoke(peerId, message);
    public void ReceiveMediaState(string peerId, LinkMessage.MediaState message) => MediaStateReceived?.Invoke(peerId, message);
    public void ReceiveMediaCommand(string peerId, LinkMessage.MediaCommand message) => MediaCommandReceived?.Invoke(peerId, message);
    public void ReceiveDeviceStatus(string peerId, LinkMessage.DeviceStatus message) => DeviceStatusReceived?.Invoke(peerId, message);

    public List<T> SentOf<T>(string peerId) where T : LinkMessage
        => Sent.Where(entry => entry.Peer == peerId).Select(entry => entry.Message).OfType<T>().ToList();
}

/// <summary>Deterministic clock: timers fire only from <see cref="Advance"/>.</summary>
internal sealed class ManualTimeProvider : TimeProvider
{
    private readonly List<ManualTimer> _timers = [];
    private DateTimeOffset _now = new(2026, 10, 26, 12, 0, 0, TimeSpan.Zero);

    public override DateTimeOffset GetUtcNow() => _now;

    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    {
        var timer = new ManualTimer(callback, state, _now + dueTime);
        _timers.Add(timer);
        return timer;
    }

    public void Advance(TimeSpan by)
    {
        _now += by;
        foreach (ManualTimer timer in _timers.ToList())
        {
            if (!timer.Disposed && !timer.Fired && timer.Due <= _now)
            {
                timer.Fired = true;
                timer.Callback(timer.State);
            }
        }
    }

    private sealed class ManualTimer(TimerCallback callback, object? state, DateTimeOffset due) : ITimer
    {
        public TimerCallback Callback { get; } = callback;
        public object? State { get; } = state;
        public DateTimeOffset Due { get; } = due;
        public bool Fired { get; set; }
        public bool Disposed { get; private set; }
        public bool Change(TimeSpan dueTime, TimeSpan period) => throw new NotSupportedException();
        public void Dispose() => Disposed = true;
        public ValueTask DisposeAsync()
        {
            Disposed = true;
            return ValueTask.CompletedTask;
        }
    }
}

internal sealed class FakeMediaSource : IPcMediaSource
{
    public LinkMessage.MediaState Current { get; private set; } = MediaSnapshots.Empty;
    public List<LinkMessage.MediaCommand> Executed { get; } = [];
    public event Action? Changed;

    public void Set(LinkMessage.MediaState state)
    {
        Current = state;
        Changed?.Invoke();
    }

    public Task ExecuteAsync(LinkMessage.MediaCommand command, CancellationToken ct = default)
    {
        Executed.Add(command);
        return Task.CompletedTask;
    }
}

internal sealed class FakeBatterySource : IPcBatterySource
{
    public PcBatteryReading? Current { get; private set; }
    public event Action? Changed;

    public void Set(PcBatteryReading? reading)
    {
        Current = reading;
        Changed?.Invoke();
    }
}

/// <summary>Drives protocol/testvectors/link-media-status.json (ADR-026).</summary>
public sealed class MediaStatusVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-media-status.json")));
    }

    public static TheoryData<int> VectorIndices()
    {
        var data = new TheoryData<int>();
        using var doc = LoadVectors();
        int count = doc.RootElement.GetProperty("vectors").GetArrayLength();
        for (int i = 0; i < count; i++) data.Add(i);
        return data;
    }

    [Theory]
    [MemberData(nameof(VectorIndices))]
    public void Vector_DecodesToExpectedTypeAndReEncodesEquivalently(int index)
    {
        using var doc = LoadVectors();
        JsonElement vector = doc.RootElement.GetProperty("vectors")[index];
        string type = vector.GetProperty("type").GetString()!;
        string json = vector.GetProperty("json").GetString()!;

        LinkMessage? decoded = LinkJson.Decode<LinkMessage>(json);
        Type expected = type switch
        {
            "mediaState" => typeof(LinkMessage.MediaState),
            "mediaCmd" => typeof(LinkMessage.MediaCommand),
            "devStatus" => typeof(LinkMessage.DeviceStatus),
            "devControl" => typeof(LinkMessage.DeviceControl),
            _ => throw new InvalidOperationException($"Unexpected vector type {type}")
        };
        Assert.NotNull(decoded);
        Assert.IsType(expected, decoded);

        string reEncoded = LinkJson.Encode(decoded);
        Assert.True(
            JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)),
            $"Re-encoded JSON differs for vector {index}: {reEncoded}");
    }
}

public sealed class PhoneMediaServiceTests
{
    private const string Phone = "PHONE";

    private static LinkMessage.MediaState State(params MediaSessionInfo[] sessions) => new(sessions, 40, false);

    private static MediaSessionInfo Session(string id, long? duration = 200_000)
        => new(id, App: "Music", Title: "Song", Playing: true, PositionMs: 1000, DurationMs: duration, Ts: 1, CanPrev: true, CanNext: true, CanSeek: true);

    [Fact]
    public async Task SessionActions_RequireIdFromLatestSnapshot()
    {
        var sessions = new FakePeerSessions();
        sessions.Connect(Phone, LinkCapabilities.MediaControl);
        using var service = new PhoneMediaService(sessions);

        await Assert.ThrowsAsync<ArgumentException>(() => service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Toggle, "s1"));
        sessions.ReceiveMediaState(Phone, State(Session("s1")));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Next, "nope"));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Play));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendCommandAsync(Phone, "rewind", "s1"));
        Assert.Empty(sessions.Sent);

        await service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Toggle, "s1");
        await service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Seek, "s1", 999_999);

        Assert.Equal(
            [new LinkMessage.MediaCommand("toggle", "s1"), new LinkMessage.MediaCommand("seek", "s1", 200_000)],
            sessions.SentOf<LinkMessage.MediaCommand>(Phone));
    }

    [Fact]
    public async Task Volume_IsClampedAndIgnoresId_MuteIsBinary()
    {
        var sessions = new FakePeerSessions();
        sessions.Connect(Phone, LinkCapabilities.MediaControl);
        using var service = new PhoneMediaService(sessions);

        await service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Volume, "whatever", 140);
        await service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Volume, null, -5);
        await service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Mute, null, 7);
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Volume));

        Assert.Equal(
            [new LinkMessage.MediaCommand("volume", null, 100), new LinkMessage.MediaCommand("volume", null, 0), new LinkMessage.MediaCommand("mute", null, 1)],
            sessions.SentOf<LinkMessage.MediaCommand>(Phone));
    }

    [Fact]
    public async Task Commands_RequireLiveSessionWithCapability()
    {
        var sessions = new FakePeerSessions();
        sessions.Connect(Phone, LinkCapabilities.NotificationMirror);
        using var service = new PhoneMediaService(sessions);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Volume, null, 10));
        sessions.Disconnect(Phone);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendCommandAsync(Phone, LinkMessage.MediaCommand.Volume, null, 10));
        Assert.Empty(sessions.Sent);
    }

    [Fact]
    public void State_IsSanitizedPerPeer_AndClearedOnSessionClose()
    {
        var sessions = new FakePeerSessions();
        sessions.Connect(Phone, LinkCapabilities.MediaControl);
        sessions.Connect("OTHER", LinkCapabilities.MediaControl);
        using var service = new PhoneMediaService(sessions);
        var changed = new List<string>();
        service.Changed += changed.Add;

        sessions.ReceiveMediaState(Phone, new LinkMessage.MediaState(
            [Session("s1"), Session("s1"), Session(""), Session("s2") with { Art = new string('A', MediaSnapshots.MaxArtBase64Length + 4) }],
            Volume: 250));
        sessions.ReceiveMediaState("OTHER", State(Session("o1")));

        PhoneMediaSnapshot snapshot = service.GetState(Phone)!;
        Assert.Equal(["s1", "s2"], snapshot.State.Sessions.Select(s => s.Id));
        Assert.Null(snapshot.State.Sessions[1].Art);
        Assert.Equal(100, snapshot.State.Volume);

        sessions.Disconnect(Phone);
        Assert.Null(service.GetState(Phone));
        Assert.NotNull(service.GetState("OTHER"));
        Assert.Equal([Phone, "OTHER", Phone], changed);
    }

    [Fact]
    public void Extrapolation_AdvancesOnlyWhilePlaying_AndCapsAtDuration()
    {
        var playing = new MediaSessionInfo("s", Playing: true, PositionMs: 10_000, DurationMs: 20_000, Ts: 1_000_000);
        Assert.Equal(13_000, MediaSnapshots.ExtrapolatePositionMs(playing, 1_003_000, 1_000_000));
        Assert.Equal(20_000, MediaSnapshots.ExtrapolatePositionMs(playing, 1_900_000, 1_000_000));
        Assert.Equal(10_000, MediaSnapshots.ExtrapolatePositionMs(playing with { Playing = false }, 1_003_000, 1_000_000));
        // Sender clock far ahead of ours: fall back to receive time.
        Assert.Equal(12_000, MediaSnapshots.ExtrapolatePositionMs(playing with { Ts = 9_000_000 }, 1_002_000, 1_000_000));
    }
}

public sealed class PcMediaPublisherTests
{
    private const string Phone = "PHONE";

    private static LinkMessage.MediaState State(string title, string id = "app") => new(
        [new MediaSessionInfo(id, App: "Player", Title: title, Playing: true, PositionMs: 0, DurationMs: 60_000, Ts: 1, CanSeek: true)],
        50, false);

    [Fact]
    public void Connect_SendsSnapshotImmediately_OnlyToCapablePeers()
    {
        var time = new ManualTimeProvider();
        var sessions = new FakePeerSessions();
        var source = new FakeMediaSource();
        source.Set(State("a"));
        using var publisher = new PcMediaPublisher(sessions, source, time);

        sessions.Connect(Phone, LinkCapabilities.MediaControl);
        sessions.Connect("LEGACY", LinkCapabilities.FileTransfer);

        Assert.Same(source.Current, Assert.Single(sessions.SentOf<LinkMessage.MediaState>(Phone)));
        Assert.Empty(sessions.SentOf<LinkMessage.MediaState>("LEGACY"));
    }

    [Fact]
    public void Changes_AreCoalescedToOnePer500ms_TrailingSendCarriesNewest()
    {
        var time = new ManualTimeProvider();
        var sessions = new FakePeerSessions();
        var source = new FakeMediaSource();
        using var publisher = new PcMediaPublisher(sessions, source, time);
        sessions.Connect(Phone, LinkCapabilities.MediaControl); // send #1 at t=0

        time.Advance(TimeSpan.FromMilliseconds(100));
        source.Set(State("b"));
        source.Set(State("c"));
        time.Advance(TimeSpan.FromMilliseconds(200));
        source.Set(State("d"));
        Assert.Single(sessions.SentOf<LinkMessage.MediaState>(Phone));

        time.Advance(TimeSpan.FromMilliseconds(199)); // t=499
        Assert.Single(sessions.SentOf<LinkMessage.MediaState>(Phone));
        time.Advance(TimeSpan.FromMilliseconds(1)); // t=500
        var sent = sessions.SentOf<LinkMessage.MediaState>(Phone);
        Assert.Equal(2, sent.Count);
        Assert.Equal("d", sent[1].Sessions[0].Title);

        // Quiet for longer than the interval: the next change goes out at once.
        time.Advance(TimeSpan.FromMilliseconds(600));
        source.Set(State("e"));
        Assert.Equal(3, sessions.SentOf<LinkMessage.MediaState>(Phone).Count);
    }

    [Fact]
    public void SessionClose_CancelsPendingSend()
    {
        var time = new ManualTimeProvider();
        var sessions = new FakePeerSessions();
        var source = new FakeMediaSource();
        using var publisher = new PcMediaPublisher(sessions, source, time);
        sessions.Connect(Phone, LinkCapabilities.MediaControl);
        source.Set(State("b"));
        sessions.Disconnect(Phone);
        time.Advance(TimeSpan.FromSeconds(1));
        Assert.Single(sessions.Sent);
    }

    [Fact]
    public void Commands_UnknownIdIgnored_ValuesClamped()
    {
        var sessions = new FakePeerSessions();
        var source = new FakeMediaSource();
        source.Set(State("a", id: "Spotify.exe"));
        using var publisher = new PcMediaPublisher(sessions, source, new ManualTimeProvider());
        sessions.Connect(Phone, LinkCapabilities.MediaControl);

        sessions.ReceiveMediaCommand(Phone, new("toggle", "Chrome"));
        sessions.ReceiveMediaCommand(Phone, new("next"));
        sessions.ReceiveMediaCommand(Phone, new("rewind", "Spotify.exe"));
        sessions.ReceiveMediaCommand(Phone, new("seek", "Spotify.exe"));
        sessions.ReceiveMediaCommand(Phone, new("toggle", "Spotify.exe"));
        sessions.ReceiveMediaCommand(Phone, new("seek", "Spotify.exe", 90_000));
        sessions.ReceiveMediaCommand(Phone, new("volume", "ignored", 400));
        sessions.ReceiveMediaCommand(Phone, new("mute", null, 0));

        Assert.Equal(
            [
                new LinkMessage.MediaCommand("toggle", "Spotify.exe"),
                new LinkMessage.MediaCommand("seek", "Spotify.exe", 60_000),
                new LinkMessage.MediaCommand("volume", null, 100),
                new LinkMessage.MediaCommand("mute", null, 0)
            ],
            source.Executed);
    }
}

public sealed class DeviceStatusServiceTests
{
    private const string Phone = "PHONE";

    [Fact]
    public void PcBattery_SentOnConnect_ThenOnlyOnChange()
    {
        var sessions = new FakePeerSessions();
        var battery = new FakeBatterySource();
        battery.Set(new PcBatteryReading(80, false));
        using var service = new DeviceStatusService(sessions, battery);

        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);
        sessions.Connect("LEGACY", LinkCapabilities.FileTransfer);
        battery.Set(new PcBatteryReading(80, false)); // no change
        battery.Set(new PcBatteryReading(79, false)); // 1 point
        battery.Set(new PcBatteryReading(79, true));  // charging flip

        Assert.Equal(
            [new LinkMessage.DeviceStatus(80, false), new LinkMessage.DeviceStatus(79, false), new LinkMessage.DeviceStatus(79, true)],
            sessions.SentOf<LinkMessage.DeviceStatus>(Phone));
        Assert.Empty(sessions.SentOf<LinkMessage.DeviceStatus>("LEGACY"));
    }

    [Fact]
    public void NoBattery_SendsNothing_AndReconnectResends()
    {
        var sessions = new FakePeerSessions();
        var battery = new FakeBatterySource();
        using var service = new DeviceStatusService(sessions, battery);
        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);
        Assert.Empty(sessions.Sent);

        battery.Set(new PcBatteryReading(50, true));
        sessions.Disconnect(Phone);
        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);
        Assert.Equal(2, sessions.SentOf<LinkMessage.DeviceStatus>(Phone).Count);
    }

    [Fact]
    public void PhoneStatus_IsPerPeer_SanitizedAndClearedOnClose()
    {
        var sessions = new FakePeerSessions();
        using var service = new DeviceStatusService(sessions, null);
        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);
        sessions.Connect("OTHER", LinkCapabilities.DeviceStatus);

        sessions.ReceiveDeviceStatus(Phone, new LinkMessage.DeviceStatus(82, true, "vibrate", false));
        sessions.ReceiveDeviceStatus("OTHER", new LinkMessage.DeviceStatus(140, null, "loud", true));

        Assert.Equal(new LinkMessage.DeviceStatus(82, true, "vibrate", false), service.GetPhoneStatus(Phone));
        Assert.Equal(new LinkMessage.DeviceStatus(null, null, null, true), service.GetPhoneStatus("OTHER"));

        sessions.Disconnect(Phone);
        Assert.Null(service.GetPhoneStatus(Phone));
        Assert.NotNull(service.GetPhoneStatus("OTHER"));
    }

    [Fact]
    public async Task SendControl_ValidatesAndRequiresCapability()
    {
        var sessions = new FakePeerSessions();
        using var service = new DeviceStatusService(sessions, null);
        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);

        await service.SendControlAsync(Phone, ring: true);
        await service.SendControlAsync(Phone, ringer: "silent", dnd: true);
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendControlAsync(Phone));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendControlAsync(Phone, ringer: "loud"));

        sessions.Connect("LEGACY", LinkCapabilities.FileTransfer);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendControlAsync("LEGACY", ring: true));

        Assert.Equal(
            [new LinkMessage.DeviceControl(Ring: true), new LinkMessage.DeviceControl(null, "silent", true)],
            sessions.SentOf<LinkMessage.DeviceControl>(Phone));
    }
}
