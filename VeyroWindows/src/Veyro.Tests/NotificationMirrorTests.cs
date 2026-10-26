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

        LinkMessage? decoded = LinkJson.Decode<LinkMessage>(json);
        Type expected = type switch
        {
            "notif" => typeof(LinkMessage.Notification),
            "notifAction" => typeof(LinkMessage.NotificationActionRequest),
            "notifReply" => typeof(LinkMessage.NotificationReplyRequest),
            "notifDismiss" => typeof(LinkMessage.NotificationDismissRequest),
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

public sealed class NotificationMirrorServiceTests
{
    private const string Peer = "PHONE";

    private sealed class FakeSessions : IPeerSessionRegistry
    {
        public bool Connected { get; set; } = true;
        public int Capabilities { get; set; } = LinkCapabilities.NotificationMirror;
        public List<LinkMessage> Sent { get; } = [];

        public event Action<string>? SessionOpened;
        public event Action<string>? SessionClosed;
        public event Action<string, LinkMessage.Notification>? NotificationReceived;
#pragma warning disable CS0067 // ADR-026 members, unused by notification tests
        public event Action<string>? CapabilitiesChanged;
        public event Action<string, LinkMessage.MediaState>? MediaStateReceived;
        public event Action<string, LinkMessage.MediaCommand>? MediaCommandReceived;
        public event Action<string, LinkMessage.DeviceStatus>? DeviceStatusReceived;
#pragma warning restore CS0067

        public bool IsConnected(string peerId) => Connected;
        public IReadOnlyList<string> GetConnectedPeers() => Connected ? [Peer] : [];
        public int GetPeerCapabilities(string peerId) => Connected ? Capabilities : 0;

        public Task SendAsync(string peerId, LinkMessage message, CancellationToken ct = default)
        {
            Sent.Add(message);
            return Task.CompletedTask;
        }

        public void Receive(LinkMessage.Notification notification) => NotificationReceived?.Invoke(Peer, notification);
        public void Open() => SessionOpened?.Invoke(Peer);
        public void Close() => SessionClosed?.Invoke(Peer);
    }

    private static LinkMessage.Notification Posted(string key, string title, long ts, string kind = LinkMessage.Notification.KindPosted)
        => new(key, kind, Pkg: "com.example.chat", App: "Chat", Title: title, Text: "body", Ts: ts, CanReply: true);

    [Fact]
    public void PostedAndActive_UpsertByKey_NewestFirst()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        var changes = new List<NotificationChange>();
        service.Changed += changes.Add;

        sessions.Receive(Posted("a", "first", 1000));
        sessions.Receive(Posted("b", "second", 2000, LinkMessage.Notification.KindActive));
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

        sessions.Receive(new LinkMessage.Notification("a", LinkMessage.Notification.KindRemoved));
        sessions.Receive(new LinkMessage.Notification("missing", LinkMessage.Notification.KindRemoved));

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
        sessions.Receive(new LinkMessage.Notification("a", "future-kind"));
        sessions.Receive(new LinkMessage.Notification("", LinkMessage.Notification.KindPosted));
        Assert.Empty(service.GetNotifications(Peer));
    }

    [Fact]
    public void MessagingStyle_BodyUsesLastMessageLines()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);
        sessions.Receive(new LinkMessage.Notification(
            "a", LinkMessage.Notification.KindPosted, Text: "ignored",
            Messages: [new("Sam", "one"), new("Sam", "two"), new(null, "three"), new("Sam", "four")]));

        Assert.Equal("Sam: two\nthree\nSam: four", service.GetNotifications(Peer)[0].Body);
    }

    [Fact]
    public async Task Reply_IsTrimmedAndSent()
    {
        var sessions = new FakeSessions();
        using var service = new NotificationMirrorService(sessions);

        await service.ReplyAsync(Peer, "k", "  Sure, 1pm \n");

        var request = Assert.IsType<LinkMessage.NotificationReplyRequest>(Assert.Single(sessions.Sent));
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

        Assert.Equal(new LinkMessage.NotificationActionRequest("k", 1), sessions.Sent[0]);
        Assert.Equal(new LinkMessage.NotificationDismissRequest("k"), sessions.Sent[1]);
        Assert.Equal(2, sessions.Sent.Count);
    }

    [Fact]
    public async Task Requests_RequireLiveSessionWithCapability()
    {
        var sessions = new FakeSessions { Capabilities = LinkCapabilities.FileTransfer };
        using var service = new NotificationMirrorService(sessions);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.DismissAsync(Peer, "k"));

        sessions.Connected = false;
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.DismissAsync(Peer, "k"));
        Assert.Empty(sessions.Sent);
    }
}
