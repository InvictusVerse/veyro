using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Calls;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Messaging;
using Veyro.Core.Models;
using Xunit;

namespace Veyro.Tests;

/// <summary>Drives protocol/testvectors/link-sms-calls.json (ADR-027).</summary>
public sealed class SmsCallsVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-sms-calls.json")));
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

        Type expected = type switch
        {
            "smsThreadsReq" => typeof(PeerMessage.SmsThreadsRequest),
            "smsThreads" => typeof(PeerMessage.SmsThreads),
            "smsMessagesReq" => typeof(PeerMessage.SmsMessagesRequest),
            "smsMessages" => typeof(PeerMessage.SmsMessages),
            "smsNew" => typeof(PeerMessage.SmsNew),
            "smsSend" => typeof(PeerMessage.SmsSend),
            "smsSendResult" => typeof(PeerMessage.SmsSendResult),
            "simList" => typeof(PeerMessage.SimList),
            "callState" => typeof(PeerMessage.CallState),
            "callAction" => typeof(PeerMessage.CallAction),
            "callLogReq" => typeof(PeerMessage.CallLogRequest),
            "callLog" => typeof(PeerMessage.CallLog),
            _ => throw new InvalidOperationException($"Unexpected vector type {type}")
        };
        PeerMessage? decoded = PeerMessageJson.Decode<PeerMessage>(json);
        Assert.NotNull(decoded);
        Assert.IsType(expected, decoded);

        string reEncoded = PeerMessageJson.Encode(decoded);
        Assert.True(
            JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)),
            $"Re-encoded JSON differs for vector {index}: {reEncoded}");
    }
}

public sealed class PhoneAddressTests
{
    [Theory]
    [InlineData("+1 (555) 010-0100", "+15550100100")]
    [InlineData("555.0100", "5550100")]
    [InlineData("[555] 0100", "5550100")]
    [InlineData("*#06#", "*#06#")]
    [InlineData("123", "123")]
    [InlineData("12345678901234567890123456789012", "12345678901234567890123456789012")]
    [InlineData("+12345678901234567890123456789012", "+12345678901234567890123456789012")]
    [InlineData("12", null)]
    [InlineData("1-2", null)]
    [InlineData("123456789012345678901234567890123", null)]
    [InlineData("+", null)]
    [InlineData("++123", null)]
    [InlineData("12+3", null)]
    [InlineData("12a45", null)]
    [InlineData("555/0100", null)]
    [InlineData("", null)]
    [InlineData("   ", null)]
    [InlineData(null, null)]
    public void Normalize_FollowsSpec(string? raw, string? expected)
        => Assert.Equal(expected, PhoneAddress.Normalize(raw));

    [Theory]
    [InlineData(0, false)]
    [InlineData(1, true)]
    [InlineData(10, true)]
    [InlineData(11, false)]
    public void AddressCount_Is1To10(int count, bool valid)
    {
        var addresses = Enumerable.Range(0, count).Select(i => $"555010{i:D2}").ToList();
        Assert.Equal(valid, PhoneAddress.TryNormalizeAll(addresses, out _));
    }

    [Fact]
    public void OneInvalidAddress_RejectsAll()
        => Assert.False(PhoneAddress.TryNormalizeAll(["5550100", "x"], out _));

    [Theory]
    [InlineData(0, false)]
    [InlineData(1, true)]
    [InlineData(2000, true)]
    [InlineData(2001, false)]
    public void Body_Is1To2000(int length, bool valid)
        => Assert.Equal(valid, PhoneAddress.IsValidBody(new string('a', length)));

    [Theory]
    [InlineData("+15550100100", "5550100100", true)]
    [InlineData("+15550100100", "(555) 010-0100", true)]
    [InlineData("5550100", "5550100", true)]
    [InlineData("100", "0100", false)]
    [InlineData("5550100", "5550101", false)]
    public void SameNumber_MatchesReformattedNumbers(string a, string b, bool same)
        => Assert.Equal(same, PhoneAddress.SameNumber(a, b));
}

public sealed class MessagingServiceTests
{
    private const string Phone = "PHONE";
    private const string Sam = "+15550100";

    private static (FakePeerSessions Sessions, ManualTimeProvider Time, MessagingService Service, List<MessagingChange> Changes) Create(
        int capabilities = PeerCapabilities.Messaging)
    {
        var sessions = new FakePeerSessions();
        var time = new ManualTimeProvider();
        sessions.Connect(Phone, capabilities);
        var service = new MessagingService(sessions, time);
        var changes = new List<MessagingChange>();
        service.Changed += changes.Add;
        return (sessions, time, service, changes);
    }

    private static SmsThread Thread(long id, long ts, string address = Sam, int unread = 0)
        => new(id, [address], ["Name"], "old", ts, unread);

    private static SmsMessage Msg(long id, long threadId, long ts, bool outgoing = false, string body = "hi", string? status = null)
        => new(id, threadId, Sam, body, ts, outgoing, Read: outgoing, status);

    private static async Task LoadThreadsAsync(FakePeerSessions sessions, MessagingService service, params SmsThread[] threads)
    {
        Task<IReadOnlyList<SmsThread>> request = service.RequestThreadsAsync(Phone);
        sessions.Receive(Phone, new PeerMessage.SmsThreads(threads));
        await request;
    }

    private static async Task LoadPageAsync(FakePeerSessions sessions, MessagingService service, long threadId, bool more, params SmsMessage[] messages)
    {
        Task<Conversation> load = service.LoadOlderAsync(Phone, threadId);
        sessions.Receive(Phone, new PeerMessage.SmsMessages(threadId, messages, more));
        await load;
    }

    [Theory]
    [InlineData(new string[0], "hi")]
    [InlineData(new[] { "12" }, "hi")]
    [InlineData(new[] { "5550100", "abc" }, "hi")]
    [InlineData(new[] { "5550100" }, "")]
    public async Task Send_InvalidInput_ThrowsAndSendsNothing(string[] addresses, string body)
    {
        var (sessions, _, service, _) = Create();
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendAsync(Phone, addresses, body));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendAsync(Phone, ["5550100"], new string('a', 2001)));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendAsync(Phone, Enumerable.Repeat("5550100", 11).ToList(), "hi"));
        Assert.Empty(sessions.Sent);
        Assert.Empty(service.GetThreads(Phone));
    }

    [Fact]
    public async Task Send_RequiresCapabilityAndKnownSim()
    {
        var (sessions, _, service, _) = Create(PeerCapabilities.NotificationMirror);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendAsync(Phone, [Sam], "hi"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.RequestThreadsAsync(Phone));

        sessions.Connect(Phone, PeerCapabilities.Messaging);
        sessions.Receive(Phone, new PeerMessage.SimList([new SimInfo(1, "A", 0), new SimInfo(2, "B", 1)]));
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendAsync(Phone, [Sam], "hi", sim: 3));
        await service.SendAsync(Phone, [Sam], "hi", sim: 2);
        Assert.Equal(2, Assert.Single(sessions.SentOf<PeerMessage.SmsSend>(Phone)).Sim);
    }

    [Fact]
    public async Task Send_NormalizesAddresses_AndShowsPendingMessage()
    {
        var (sessions, _, service, _) = Create();
        await LoadThreadsAsync(sessions, service, Thread(12, 100, "+15550100"));

        ConversationMessage pending = await service.SendAsync(Phone, ["+1 555-0100"], "Sure, 1pm");

        PeerMessage.SmsSend sent = Assert.Single(sessions.SentOf<PeerMessage.SmsSend>(Phone));
        Assert.Equal(["+15550100"], sent.Addresses);
        Assert.Equal("Sure, 1pm", sent.Body);
        Assert.Equal(pending.ClientId, sent.ClientId);
        Assert.Equal(SmsDeliveryState.Pending, pending.State);
        Assert.Equal(12, pending.ThreadId); // matched the existing thread
        SmsThread top = service.GetThreads(Phone)[0];
        Assert.Equal((12L, "Sure, 1pm"), (top.ThreadId, top.Snippet));
        Assert.Equal(pending, Assert.Single(service.GetConversation(Phone, 12)!.Messages));
    }

    [Fact]
    public async Task Pending_ResolvesSent_OrFailedWithReason()
    {
        var (sessions, _, service, _) = Create();
        ConversationMessage a = await service.SendAsync(Phone, [Sam], "a");
        ConversationMessage b = await service.SendAsync(Phone, [Sam], "b");

        sessions.Receive(Phone, new PeerMessage.SmsSendResult(a.ClientId!, true));
        sessions.Receive(Phone, new PeerMessage.SmsSendResult(b.ClientId!, false, "no-service"));
        sessions.Receive(Phone, new PeerMessage.SmsSendResult("unknown", true)); // ignored

        var messages = service.GetConversation(Phone, a.ThreadId)!.Messages;
        Assert.Equal(SmsDeliveryState.Sent, messages.Single(m => m.Key == a.Key).State);
        ConversationMessage failed = messages.Single(m => m.Key == b.Key);
        Assert.Equal((SmsDeliveryState.Failed, "no-service"), (failed.State, failed.Error));
    }

    [Fact]
    public async Task Pending_TimesOutAfter75s_LateResultStillCorrects()
    {
        var (sessions, time, service, _) = Create();
        ConversationMessage message = await service.SendAsync(Phone, [Sam], "a");

        time.Advance(TimeSpan.FromSeconds(74));
        Assert.Equal(SmsDeliveryState.Pending, service.GetConversation(Phone, message.ThreadId)!.Messages[0].State);
        time.Advance(TimeSpan.FromSeconds(1));
        ConversationMessage timedOut = service.GetConversation(Phone, message.ThreadId)!.Messages[0];
        Assert.Equal((SmsDeliveryState.Failed, MessagingService.ErrorTimeout), (timedOut.State, timedOut.Error));

        sessions.Receive(Phone, new PeerMessage.SmsSendResult(message.ClientId!, true));
        Assert.Equal(SmsDeliveryState.Sent, service.GetConversation(Phone, message.ThreadId)!.Messages[0].State);
    }

    [Fact]
    public async Task Retry_ResendsFailedMessageUnderNewClientId()
    {
        var (sessions, _, service, _) = Create();
        ConversationMessage message = await service.SendAsync(Phone, [Sam], "again");
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.RetryAsync(Phone, message.ClientId!));
        sessions.Receive(Phone, new PeerMessage.SmsSendResult(message.ClientId!, false, "radio"));

        ConversationMessage retried = await service.RetryAsync(Phone, message.ClientId!);

        var sends = sessions.SentOf<PeerMessage.SmsSend>(Phone);
        Assert.Equal(2, sends.Count);
        Assert.NotEqual(sends[0].ClientId, sends[1].ClientId);
        Assert.Equal("again", sends[1].Body);
        Assert.Equal(retried, Assert.Single(service.GetConversation(Phone, retried.ThreadId)!.Messages));
    }

    [Fact]
    public async Task SmsNew_MergesIntoThreadList()
    {
        var (sessions, _, service, changes) = Create();
        await LoadThreadsAsync(sessions, service, Thread(2, 200, "+15550102"), Thread(1, 100, "+15550101", unread: 2));
        changes.Clear();

        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(901, 1, 300, body: "Lunch?")));
        SmsThread top = service.GetThreads(Phone)[0];
        Assert.Equal((1L, "Lunch?", 300L, 3), (top.ThreadId, top.Snippet, top.Ts, top.Unread));
        Assert.Equal(["Name"], top.Names!);
        var incoming = Assert.Single(changes, c => c.Kind == MessagingChangeKind.Incoming);
        Assert.Equal(901, incoming.Message!.Id);

        // A duplicate frame neither bumps unread nor alerts again.
        changes.Clear();
        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(901, 1, 300, body: "Lunch?")));
        Assert.Equal(3, service.GetThread(Phone, 1)!.Unread);
        Assert.DoesNotContain(changes, c => c.Kind == MessagingChangeKind.Incoming);

        // Outgoing (sent on the phone): moves to top, no unread, no alert.
        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(902, 2, 400, outgoing: true, body: "ok", status: "sent")));
        top = service.GetThreads(Phone)[0];
        Assert.Equal((2L, "ok", 0), (top.ThreadId, top.Snippet, top.Unread));
        Assert.DoesNotContain(changes, c => c.Kind == MessagingChangeKind.Incoming);

        // Unknown thread: created from the thread hint, or from the message alone.
        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(903, 3, 500), new SmsThread(3, ["+15550103"], ["Lee"], "x", 500, 0)));
        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(904, 4, 600)));
        Assert.Equal([4L, 3L, 2L, 1L], service.GetThreads(Phone).Select(t => t.ThreadId));
        Assert.Equal(1, service.GetThread(Phone, 3)!.Unread);
        Assert.Equal([Sam], service.GetThread(Phone, 4)!.Addresses);

        service.MarkSeen(Phone, 1);
        Assert.Equal(0, service.GetThread(Phone, 1)!.Unread);
    }

    [Fact]
    public async Task SmsNew_MergesIntoLoadedThreadOnly()
    {
        var (sessions, _, service, _) = Create();
        await LoadPageAsync(sessions, service, 1, false, Msg(10, 1, 100));

        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(11, 1, 200)));
        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(20, 2, 200)));

        Assert.Equal([10L, 11L], service.GetConversation(Phone, 1)!.Messages.Select(m => m.Id!.Value));
        Assert.Null(service.GetConversation(Phone, 2));
        // Incoming page messages count as seen: no unread bump when smsNew repeats one.
        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(10, 1, 100)));
        Assert.Equal(1, service.GetThread(Phone, 1)!.Unread);
    }

    [Fact]
    public async Task SmsNew_EchoOfPcSend_AdoptsPendingMessageWithoutDuplicate()
    {
        var (sessions, _, service, _) = Create();
        await LoadThreadsAsync(sessions, service, Thread(12, 100));
        await LoadPageAsync(sessions, service, 12, false, Msg(901, 12, 100));
        ConversationMessage pending = await service.SendAsync(Phone, [Sam], "Sure, 1pm");

        sessions.Receive(Phone, new PeerMessage.SmsNew(Msg(902, 12, 150, outgoing: true, body: "Sure, 1pm", status: "sent")));
        sessions.Receive(Phone, new PeerMessage.SmsSendResult(pending.ClientId!, true));

        var messages = service.GetConversation(Phone, 12)!.Messages;
        Assert.Equal(2, messages.Count);
        ConversationMessage adopted = messages[1];
        Assert.Equal((pending.Key, 902L, SmsDeliveryState.Sent), (adopted.Key, adopted.Id!.Value, adopted.State));
    }

    [Fact]
    public async Task SendToNewNumber_DraftThreadIsReplacedByPhoneThread()
    {
        var (sessions, _, service, changes) = Create();
        await LoadThreadsAsync(sessions, service, Thread(12, 100));
        ConversationMessage pending = await service.SendAsync(Phone, ["555 0199"], "hello");
        Assert.True(pending.ThreadId < 0);
        Assert.Equal(pending.ThreadId, service.GetThreads(Phone)[0].ThreadId);
        changes.Clear();

        var echo = new SmsMessage(950, 77, "+15550199", "hello", 200, true, true, "sent");
        sessions.Receive(Phone, new PeerMessage.SmsNew(echo, new SmsThread(77, ["+15550199"], null, "hello", 200, 0)));

        Assert.Equal([77L, 12L], service.GetThreads(Phone).Select(t => t.ThreadId));
        Assert.Null(service.GetConversation(Phone, pending.ThreadId));
        Conversation real = service.GetConversation(Phone, 77)!;
        Assert.Equal((pending.Key, 950L), (real.Messages[0].Key, real.Messages[0].Id!.Value));
        Assert.True(real.HasFirstPage && real.More); // older history may exist on the phone
        Assert.Contains(changes, c => c.Kind == MessagingChangeKind.Conversation && c.ThreadId == 77 && c.PreviousThreadId == pending.ThreadId);
    }

    [Fact]
    public async Task Pagination_UsesOldestLoadedTs_AndStopsWhenNoMore()
    {
        var (sessions, _, service, _) = Create();

        Task<Conversation> first = service.LoadOlderAsync(Phone, 12);
        Assert.Equal(new PeerMessage.SmsMessagesRequest(12, null, 50), sessions.SentOf<PeerMessage.SmsMessagesRequest>(Phone)[0]);
        sessions.Receive(Phone, new PeerMessage.SmsMessages(12, [Msg(3, 12, 300), Msg(2, 12, 200)], More: true));
        Conversation page1 = await first;
        Assert.Equal([200L, 300L], page1.Messages.Select(m => m.Ts));
        Assert.True(page1.More);

        Task<Conversation> second = service.LoadOlderAsync(Phone, 12);
        Task<Conversation> coalesced = service.LoadOlderAsync(Phone, 12);
        Assert.Equal(new PeerMessage.SmsMessagesRequest(12, 200, 50), sessions.SentOf<PeerMessage.SmsMessagesRequest>(Phone)[1]);
        Assert.Equal(2, sessions.SentOf<PeerMessage.SmsMessagesRequest>(Phone).Count);
        sessions.Receive(Phone, new PeerMessage.SmsMessages(12, [Msg(1, 12, 100), Msg(2, 12, 200)], More: false));
        Conversation page2 = await second;
        await coalesced;
        Assert.Equal([100L, 200L, 300L], page2.Messages.Select(m => m.Ts));
        Assert.False(page2.More);

        await service.LoadOlderAsync(Phone, 12);
        Assert.Equal(2, sessions.SentOf<PeerMessage.SmsMessagesRequest>(Phone).Count);
    }

    [Fact]
    public async Task Requests_TimeOutAfter15s()
    {
        var (sessions, time, service, _) = Create();
        Task<IReadOnlyList<SmsThread>> threads = service.RequestThreadsAsync(Phone, limit: 999);
        Task<Conversation> page = service.LoadOlderAsync(Phone, 5);
        Assert.Equal(200, sessions.SentOf<PeerMessage.SmsThreadsRequest>(Phone)[0].Limit);

        time.Advance(TimeSpan.FromSeconds(14));
        Assert.False(threads.IsCompleted || page.IsCompleted);
        time.Advance(TimeSpan.FromSeconds(1));
        await Assert.ThrowsAsync<TimeoutException>(() => threads);
        await Assert.ThrowsAsync<TimeoutException>(() => page);

        // A fresh request goes out after a timeout.
        _ = service.RequestThreadsAsync(Phone);
        Assert.Equal(2, sessions.SentOf<PeerMessage.SmsThreadsRequest>(Phone).Count);
    }

    [Fact]
    public async Task SessionClose_ClearsEverything()
    {
        var (sessions, time, service, changes) = Create();
        sessions.Receive(Phone, new PeerMessage.SimList([new SimInfo(1, "A")]));
        await LoadThreadsAsync(sessions, service, Thread(12, 100));
        await LoadPageAsync(sessions, service, 12, true, Msg(1, 12, 100));
        ConversationMessage pending = await service.SendAsync(Phone, [Sam], "x");
        Task<Conversation> outstanding = service.LoadOlderAsync(Phone, 12);

        sessions.Disconnect(Phone);

        Assert.Empty(service.GetSims(Phone));
        Assert.Empty(service.GetThreads(Phone));
        Assert.False(service.HasThreadList(Phone));
        Assert.Null(service.GetConversation(Phone, 12));
        await Assert.ThrowsAsync<InvalidOperationException>(() => outstanding);
        Assert.Equal(MessagingChangeKind.Cleared, changes[^1].Kind);

        changes.Clear();
        time.Advance(TimeSpan.FromSeconds(80)); // the send timer was cancelled
        Assert.Empty(changes);
        Assert.False(service.IsAvailable(Phone));
        Assert.NotNull(pending);
    }
}

public sealed class CallServiceTests
{
    private const string Phone = "PHONE";

    private static (FakePeerSessions Sessions, ManualTimeProvider Time, CallService Service, List<CallChange> Changes) Create(
        int capabilities = PeerCapabilities.Calls)
    {
        var sessions = new FakePeerSessions();
        var time = new ManualTimeProvider();
        sessions.Connect(Phone, capabilities);
        var service = new CallService(sessions, time);
        var changes = new List<CallChange>();
        service.Changed += changes.Add;
        return (sessions, time, service, changes);
    }

    [Fact]
    public void State_RingingActiveEnded()
    {
        var (sessions, _, service, changes) = Create();

        sessions.Receive(Phone, new PeerMessage.CallState("k-77", "ringing", "in", "+15550100", "Sam"));
        PhoneCall ringing = service.GetCurrentCall(Phone)!;
        Assert.True(ringing.IsIncomingRinging);

        // A later frame of the same call keeps fields it omits.
        sessions.Receive(Phone, new PeerMessage.CallState("k-77", "active"));
        PhoneCall active = service.GetCurrentCall(Phone)!;
        Assert.Equal(("active", "in", "+15550100", "Sam"), (active.State, active.Direction, active.Number, active.Name));

        sessions.Receive(Phone, new PeerMessage.CallState("other", "ended"));
        Assert.NotNull(service.GetCurrentCall(Phone));
        sessions.Receive(Phone, new PeerMessage.CallState("k-77", "ended"));
        Assert.Null(service.GetCurrentCall(Phone));

        Assert.Equal(["ringing", "active", "ended", "ended"], changes.Where(c => c.Kind == CallChangeKind.State).Select(c => c.Call!.State));
    }

    [Fact]
    public void State_InvalidFramesIgnored()
    {
        var (sessions, _, service, changes) = Create();
        sessions.Receive(Phone, new PeerMessage.CallState("k", "holding"));
        sessions.Receive(Phone, new PeerMessage.CallState("", "ringing"));
        sessions.Receive(Phone, new PeerMessage.CallState(new string('k', 129), "ringing"));
        Assert.Null(service.GetCurrentCall(Phone));
        Assert.Empty(changes);

        sessions.Receive(Phone, new PeerMessage.CallState("k", "ringing", "sideways"));
        Assert.Null(service.GetCurrentCall(Phone)!.Direction);
    }

    [Fact]
    public async Task Actions_AttachCurrentCallId()
    {
        var (sessions, _, service, _) = Create();
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendActionAsync(Phone, CallService.ActionDecline));

        sessions.Receive(Phone, new PeerMessage.CallState("k-77", "ringing", "in"));
        await service.SendActionAsync(Phone, CallService.ActionSilence);
        await service.SendActionAsync(Phone, CallService.ActionAnswer, number: "ignored");
        await service.SendActionAsync(Phone, CallService.ActionEnd);
        await Assert.ThrowsAsync<ArgumentException>(() => service.SendActionAsync(Phone, "hold"));

        Assert.Equal(
            [new PeerMessage.CallAction("silence", "k-77"), new PeerMessage.CallAction("answer", "k-77"), new PeerMessage.CallAction("end", "k-77")],
            sessions.SentOf<PeerMessage.CallAction>(Phone));
    }

    [Theory]
    [InlineData("+1 (555) 010-0100", "+15550100100")]
    [InlineData("*#06#", "*#06#")]
    [InlineData("12", null)]
    [InlineData("555-CALL", null)]
    [InlineData(null, null)]
    public async Task Dial_ValidatesLikeSmsAddresses(string? number, string? expected)
    {
        var (sessions, _, service, _) = Create();
        if (expected is null)
        {
            await Assert.ThrowsAsync<ArgumentException>(() => service.SendActionAsync(Phone, CallService.ActionDial, number));
            Assert.Empty(sessions.Sent);
            return;
        }
        await service.SendActionAsync(Phone, CallService.ActionDial, number);
        Assert.Equal(new PeerMessage.CallAction("dial", null, expected), Assert.Single(sessions.SentOf<PeerMessage.CallAction>(Phone)));
    }

    [Fact]
    public async Task Actions_RequireCapability()
    {
        var (sessions, _, service, _) = Create(PeerCapabilities.Messaging);
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.SendActionAsync(Phone, CallService.ActionDial, "5550100"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.RequestCallLogAsync(Phone));
        Assert.Empty(sessions.Sent);
    }

    [Fact]
    public async Task CallLog_RequestResponse_TimeoutAndClearOnClose()
    {
        var (sessions, time, service, changes) = Create();
        Task<IReadOnlyList<CallLogEntry>> request = service.RequestCallLogAsync(Phone, limit: 9999);
        Assert.Equal(500, Assert.Single(sessions.SentOf<PeerMessage.CallLogRequest>(Phone)).Limit);
        sessions.Receive(Phone, new PeerMessage.CallLog([
            new CallLogEntry(1, "+15550100", Ts: 100, DurationSec: -4, Type: "weird"),
            new CallLogEntry(2, "+15550101", "Sam", 200, 42, "missed")
        ]));
        IReadOnlyList<CallLogEntry> log = await request;
        Assert.Equal([2L, 1L], log.Select(e => e.Id));
        Assert.Equal(("in", 0L), (log[1].Type, log[1].DurationSec));
        Assert.Equal(log, service.GetCallLog(Phone));

        Task<IReadOnlyList<CallLogEntry>> late = service.RequestCallLogAsync(Phone);
        time.Advance(TimeSpan.FromSeconds(15));
        await Assert.ThrowsAsync<TimeoutException>(() => late);

        sessions.Receive(Phone, new PeerMessage.CallState("k", "ringing", "in"));
        sessions.Disconnect(Phone);
        Assert.Empty(service.GetCallLog(Phone));
        Assert.Null(service.GetCurrentCall(Phone));
        CallChange cleared = changes[^1];
        Assert.Equal((CallChangeKind.Cleared, "k"), (cleared.Kind, cleared.Call!.CallId));
    }
}

/// <summary>ADR-027 on a real loopback session.</summary>
[Collection(LinkServerCollection.Name)]
public sealed class SmsCallsSessionTests
{
    [Fact]
    public async Task HelloAckAdvertisesMessagingAndCalls_AndThreadsRoundTrip()
    {
        string root = Path.Combine(AppContext.BaseDirectory, "veyro-sms-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        try
        {
            byte[] pcPublicKey = RandomNumberGenerator.GetBytes(32);
            byte[] phonePublicKey = RandomNumberGenerator.GetBytes(32);
            string pcId = DeviceId.FromPublicKey(pcPublicKey).Value;
            string phoneId = DeviceId.FromPublicKey(phonePublicKey).Value;
            byte[] secret = RandomNumberGenerator.GetBytes(32);
            var trustStore = new FileTrustStore(root, new byte[32]);
            await trustStore.UpsertAsync(new TrustedPeer(
                phoneId, "Phone", PlatformType.Android,
                Convert.ToBase64String(phonePublicKey), Convert.ToBase64String(secret),
                AutoAcceptTransfers: false, DateTimeOffset.UtcNow));

            var registry = new PeerSessionRegistry();
            using var messaging = new MessagingService(registry);
            using var calls = new CallService(registry);
            var capable = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            registry.CapabilitiesChanged += peer =>
            {
                if ((registry.GetPeerCapabilities(peer) & PeerCapabilities.Messaging) != 0) capable.TrySetResult();
            };
            var ringing = new TaskCompletionSource<PhoneCall>(TaskCreationOptions.RunContinuationsAsynchronously);
            calls.Changed += change => { if (change.Call is { IsRinging: true } call) ringing.TrySetResult(call); };

            await using var server = new LinkServer(
                pcId, "Test PC", trustStore, Path.Combine(root, "received"), pcPublicKey,
                _ => Task.FromResult(false), sessions: registry);
            await server.StartAsync();

            using var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{server.Port}/veyro"), ct);
            await socket.SendAsync(Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreamble(phoneId, "Phone", "android"))),
                WebSocketMessageType.Text, true, ct);
            byte[] buffer = new byte[4096];
            WebSocketReceiveResult ackFrame = await socket.ReceiveAsync(buffer, ct);
            Assert.Equal(LinkPreambleAck.ModeSession,
                PeerMessageJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, ackFrame.Count))?.Mode);

            await using var phone = new SecureLinkSession(socket, phoneId, secret, encrypt: true, expectedPeerId: pcId);
            const int family = PeerCapabilities.Messaging | PeerCapabilities.Calls;
            await phone.SendAsync(new PeerMessage.Hello(phoneId, "Phone", "android", Capabilities: PeerCapabilities.FileTransfer | family), ct);
            var helloAck = Assert.IsType<PeerMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(family, helloAck.Capabilities & family);
            await capable.Task.WaitAsync(ct);
            Assert.True(messaging.IsAvailable(phoneId));
            Assert.True(calls.IsAvailable(phoneId));

            // PC -> phone smsThreadsReq; phone -> PC smsThreads completes the request.
            Task<IReadOnlyList<SmsThread>> request = messaging.RequestThreadsAsync(phoneId);
            Assert.Equal(new PeerMessage.SmsThreadsRequest(50), (await phone.ReceiveAsync(ct))!.Envelope.Payload);
            await phone.SendAsync(new PeerMessage.SmsThreads([new SmsThread(12, ["+15550100"], ["Sam"], "Lunch?", 1793011200000, 1)]), ct);
            SmsThread thread = Assert.Single(await request.WaitAsync(ct));
            Assert.Equal((12L, 1), (thread.ThreadId, thread.Unread));

            // callState is routed on the same session.
            await phone.SendAsync(new PeerMessage.CallState("k-77", "ringing", "in", "+15550100"), ct);
            Assert.Equal("k-77", (await ringing.Task.WaitAsync(ct)).CallId);

            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", ct);
            await server.StopAsync();
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}
