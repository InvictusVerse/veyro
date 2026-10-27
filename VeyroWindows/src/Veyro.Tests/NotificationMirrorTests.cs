using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Link;
using Veyro.Core.Notifications;
using Xunit;

namespace Veyro.Tests;

/// <summary>Drives protocol/testvectors/link-notification.json (ADR-025).</summary>
public sealed class NotificationVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-notification.json")));
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

        PeerMessage? decoded = PeerMessageJson.Decode<PeerMessage>(json);
        Type expected = type switch
        {
            "notif" => typeof(PeerMessage.Notification),
            "notifAction" => typeof(PeerMessage.NotificationActionRequest),
            "notifReply" => typeof(PeerMessage.NotificationReplyRequest),
            "notifDismiss" => typeof(PeerMessage.NotificationDismissRequest),
            _ => throw new InvalidOperationException($"Unexpected vector type {type}")
        };
        Assert.NotNull(decoded);
        Assert.IsType(expected, decoded);

        string reEncoded = PeerMessageJson.Encode(decoded);
        Assert.True(
            JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)),
            $"Re-encoded JSON differs for vector {index}: {reEncoded}");
    }
}

public sealed class NotificationMirrorServiceTests
{
    private const string Peer = "PHONE";

    private sealed class FakeSessions : IPeerSessionRegistry
    {
        public bool Connected { get; set; } = true;
        public int Capabilities { get; set; } = PeerCapabilities.NotificationMirror;
        public List<PeerMessage> Sent { get; } = [];

        public event Action<string>? SessionOpened;
        public event Action<string>? SessionClosed;
        public event Action<string, PeerMessage.Notification>? NotificationReceived;
#pragma warning disable CS0067 // ADR-026 members, unused by notification tests
        public event Action<string>? CapabilitiesChanged;
        public event Action<string, PeerMessage.MediaState>? MediaStateReceived;
        public event Action<string, PeerMessage.MediaCommand>? MediaCommandReceived;
        public event Action<string, PeerMessage.DeviceStatus>? DeviceStatusReceived;
        public event Action<string, PeerMessage.SimList>? SimListReceived;
        public event Action<string, PeerMessage.SmsThreads>? SmsThreadsReceived;
        public event Action<string, PeerMessage.SmsMessages>? SmsMessagesReceived;
        public event Action<string, PeerMessage.SmsNew>? SmsNewReceived;
        public event Action<string, PeerMessage.SmsSendResult>? SmsSendResultReceived;
        public event Action<string, PeerMessage.CallState>? CallStateReceived;
        public event Action<string, PeerMessage.CallLog>? CallLogReceived;
        public event Action<string, PeerMessage>? PhotosMessageReceived;
        public event Action<string, PeerMessage>? DeviceMessageReceived;
#pragma warning restore CS0067

        public bool IsConnected(string peerId) => Connected;
        public IReadOnlyList<string> GetConnectedPeers() => Connected ? [Peer] : [];
        public int GetPeerCapabilities(string peerId) => Connected ? Capabilities : 0;

        public Task SendAsync(string peerId, PeerMessage message, CancellationToken ct = default)
        {
            Sent.Add(message);
            return Task.CompletedTask;
        }

        public IOutboundTransferChannel OpenOutboundTransfer(string peerId, string transferId)
            => throw new NotSupportedException();

        public void Receive(PeerMessage.Notification notification) => NotificationReceived?.Invoke(Peer, notification);
        public void Open() => SessionOpened?.Invoke(Peer);
        public void Close() => SessionClosed?.Invoke(Peer);
    }

    private static PeerMessage.Notification Posted(string key, string title, long ts, string kind = PeerMessage.Notification.KindPosted)
        => new(key, kind, Pkg: "com.example.chat", App: "Chat", Title: title, Text: "body", Ts: ts, CanReply: true);

    [Fact]
    public void PostedAndActive_UpsertByKey_NewestFirst()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        var changes = new List<NotificationChange>();
        service.Changed += changes.Add;

        sessions.Receive(Posted("a", "first", 1000));
        sessions.Receive(Posted("b", "second", 2000, PeerMessage.Notification.KindActive));
        sessions.Receive(Posted("a", "first-updated", 3000));

        var list = service.GetNotifications(Peer);
        Assert.Equal(["a", "b"], list.Select(n => n.Key));
        Assert.Equal("first-updated", list[0].Title);
        Assert.True(list[0].CanReply);
        Assert.Equal(
            [NotificationChangeKind.Posted, NotificationChangeKind.Updated, NotificationChangeKind.Posted],
            changes.Select(c => c.Kind));
    }

    [Fact]
    public void Removed_DeletesOnlyThatKey_AndUnknownKeyIsSilent()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        sessions.Receive(Posted("a", "A", 1000));
        sessions.Receive(Posted("b", "B", 2000));
        var changes = new List<NotificationChange>();
        service.Changed += changes.Add;

        sessions.Receive(new PeerMessage.Notification("a", PeerMessage.Notification.KindRemoved));
        sessions.Receive(new PeerMessage.Notification("missing", PeerMessage.Notification.KindRemoved));

        Assert.Equal(["b"], service.GetNotifications(Peer).Select(n => n.Key));
        NotificationChange change = Assert.Single(changes);
        Assert.Equal(NotificationChangeKind.Removed, change.Kind);
        Assert.Equal("a", change.Key);
    }

    [Fact]
    public void SessionClose_ClearsPeerList()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        sessions.Receive(Posted("a", "A", 1000));
        var changes = new List<NotificationChange>();
        service.Changed += changes.Add;

        sessions.Close();

        Assert.Empty(service.GetNotifications(Peer));
        Assert.Equal(NotificationChangeKind.Cleared, Assert.Single(changes).Kind);
    }

    [Fact]
    public void UnknownKindAndEmptyKey_AreIgnored()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        sessions.Receive(new PeerMessage.Notification("a", "future-kind"));
        sessions.Receive(new PeerMessage.Notification("", PeerMessage.Notification.KindPosted));
        Assert.Empty(service.GetNotifications(Peer));
    }

    [Fact]
    public void MessagingStyle_BodyUsesLastMessageLines()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        sessions.Receive(new PeerMessage.Notification(
            "a", PeerMessage.Notification.KindPosted, Text: "ignored",
            Messages: [new("Sam", "one"), new("Sam", "two"), new(null, "three"), new("Sam", "four")]));

        Assert.Equal("Sam: two\nthree\nSam: four", service.GetNotifications(Peer)[0].Body);
    }

    [Fact]
    public async Task Reply_IsTrimmedAndSent()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);

        await service.ReplyAsync(Peer, "k", "  Sure, 1pm \n");

        var request = Assert.IsType<PeerMessage.NotificationReplyRequest>(Assert.Single(sessions.Sent));
        Assert.Equal("k", request.Key);
        Assert.Equal("Sure, 1pm", request.Text);
    }

    [Fact]
    public async Task Reply_RejectsEmptyAndOversizeText()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);

        await Assert.ThrowsAsync<ArgumentException>(() => service.ReplyAsync(Peer, "k", "   "));
        await Assert.ThrowsAsync<ArgumentException>(() => service.ReplyAsync(Peer, "k", new string('x', NotificationMirrorService.MaxReplyLength + 1)));
        await Assert.ThrowsAsync<ArgumentException>(() => service.ReplyAsync(Peer, "", "hi"));
        Assert.Empty(sessions.Sent);

        await service.ReplyAsync(Peer, "k", new string('x', NotificationMirrorService.MaxReplyLength));
        Assert.Single(sessions.Sent);
    }

    [Fact]
    public async Task ActionAndDismiss_SendRequests()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);

        await service.InvokeActionAsync(Peer, "k", 1);
        await service.DismissAsync(Peer, "k");
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => service.InvokeActionAsync(Peer, "k", -1));

        Assert.Equal(new PeerMessage.NotificationActionRequest("k", 1), sessions.Sent[0]);
        Assert.Equal(new PeerMessage.NotificationDismissRequest("k"), sessions.Sent[1]);
        Assert.Equal(2, sessions.Sent.Count);
    }

    [Fact]
    public async Task Requests_RequireLiveSessionWithCapability()
    {
        var sessions = new FakeSessions { Capabilities = PeerCapabilities.FileTransfer };
        using var service = new NotificationMirrorService(sessions);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.DismissAsync(Peer, "k"));

        sessions.Connected = false;
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.DismissAsync(Peer, "k"));
        Assert.Empty(sessions.Sent);
    }
}
