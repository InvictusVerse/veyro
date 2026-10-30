using Veyro.Core.Crypto;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Classes that start a real listener share one collection, so they never run in parallel: the
/// listener picks its port by probing 5150-5169, and two parallel probes can pick the same one.
/// </summary>
[CollectionDefinition(Name)]
public sealed class LinkServerCollection
{
    public const string Name = "LinkServer";
}

[Collection(LinkServerCollection.Name)]
public sealed class LinkServerTests
{
    [Fact]
    public async Task ServerStartsWithoutHttpUrlReservation()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-server-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var trustStore = new FileTrustStore(root, new byte[32]);
            await using var server = new LinkServer(
                "00000000000000000000000000",
                "Test PC",
                trustStore,
                root,
                new byte[32],
                _ => Task.FromResult(false));

            await server.StartAsync();

            Assert.True(server.IsRunning);
            Assert.InRange(server.Port, 1, 65535);
            await server.StopAsync();
            Assert.False(server.IsRunning);
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    [Fact]
    public async Task ServerAcceptsWebSocketUpgradeOnVeyroPath()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-ws-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            await using var server = new LinkServer(
                "00000000000000000000000000", "Test PC", new FileTrustStore(root, new byte[32]),
                root, new byte[32], _ => Task.FromResult(false));
            await server.StartAsync();
            using var client = new ClientWebSocket();
            await client.ConnectAsync(new Uri($"ws://127.0.0.1:{server.Port}/veyro"), CancellationToken.None);
            Assert.Equal(WebSocketState.Open, client.State);
            await client.CloseAsync(WebSocketCloseStatus.NormalClosure, "test", CancellationToken.None);
            await server.StopAsync();
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    /// <summary>
    /// ADR-025: one authenticated session carries Hello/HelloAck, companion messages in both
    /// directions, and a later file transfer, without reconnecting.
    /// </summary>
    [Fact]
    public async Task PersistentSession_CarriesHelloNotificationAndTransfer()
    {
        // Under the test output folder (on the repo drive), not %TEMP%.
        string root = Path.Combine(AppContext.BaseDirectory, "veyro-session-" + Guid.NewGuid().ToString("N"));
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
                AutoAcceptTransfers: true, DateTimeOffset.UtcNow));

            var registry = new PeerSessionRegistry();
            var notified = new TaskCompletionSource<(string Peer, PeerMessage.Notification Message)>(TaskCreationOptions.RunContinuationsAsynchronously);
            var closed = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
            registry.NotificationReceived += (peer, message) => notified.TrySetResult((peer, message));
            registry.SessionClosed += peer => closed.TrySetResult(peer);

            string received = Path.Combine(root, "received");
            await using var server = new LinkServer(
                pcId, "Test PC", trustStore, received, pcPublicKey,
                _ => Task.FromResult(false), sessions: registry);
            await server.StartAsync();

            using var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{server.Port}/veyro"), ct);
            var v3dial = new V3Dialer(phoneId);
            LinkPreambleAck v3ack = await v3dial.SendPreambleAsync(socket, "Phone", ct);
            Assert.Equal(LinkPreambleAck.ModeSession, v3ack.Mode);

            await using var phone = new SecureLinkSession(socket, v3dial.SessionKeys(pcId, secret));

            // Hello -> HelloAck advertising notification mirroring.
            await phone.SendAsync(new PeerMessage.Hello(phoneId, "Phone", "android",
                Capabilities: PeerCapabilities.FileTransfer | PeerCapabilities.NotificationMirror), ct);
            var helloAck = Assert.IsType<PeerMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.NotEqual(0, helloAck.Capabilities & PeerCapabilities.NotificationMirror);
            Assert.True(registry.IsConnected(phoneId));
            Assert.NotEqual(0, registry.GetPeerCapabilities(phoneId) & PeerCapabilities.NotificationMirror);

            // Phone -> PC notification reaches the handler; PC -> phone request reaches the phone.
            const string key = "0|com.example.chat|7|null|10123";
            await phone.SendAsync(new PeerMessage.Notification(key, PeerMessage.Notification.KindPosted, Title: "t"), ct);
            var delivered = await notified.Task.WaitAsync(ct);
            Assert.Equal(phoneId, delivered.Peer);
            Assert.Equal(key, delivered.Message.Key);
            await registry.SendAsync(phoneId, new PeerMessage.NotificationDismissRequest(key), ct);
            Assert.Equal(new PeerMessage.NotificationDismissRequest(key), (await phone.ReceiveAsync(ct))!.Envelope.Payload);

            // Heartbeat still answered.
            await phone.SendAsync(new PeerMessage.Ping("n1"), ct);
            Assert.Equal(new PeerMessage.Pong("n1"), (await phone.ReceiveAsync(ct))!.Envelope.Payload);

            // A transfer on the same session still works.
            string source = Path.Combine(root, "source.bin");
            byte[] payload = RandomNumberGenerator.GetBytes(64 * 1024 + 7);
            await File.WriteAllBytesAsync(source, payload, ct);
            const string transferId = "session-transfer";
            await phone.SendAsync(new PeerMessage.TransferOffer(
                transferId, [new LinkItem(0, "hello.bin", payload.Length, 0)], payload.Length, "Phone", ["LAN_TCP"]), ct);
            var decision = Assert.IsType<PeerMessage.TransferDecision>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.True(decision.Accept);
            await BulkTransfer.SendAsync(
                decision.TcpHost!, decision.TcpPort!.Value, transferId, decision.AuthToken!, phoneId,
                [new OutboundTransferItem(0, source, "hello.bin")], Vtc3.KeyDeriver(phone.ExportKey, transferId),
                resume: decision.Resume, ct: ct);
            // ADR-036: the receiver reports each item on the session too (Android's sender waits for it).
            var itemResult = Assert.IsType<PeerMessage.ItemResult>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(new PeerMessage.ItemResult(transferId, 0, true), itemResult);
            var end = Assert.IsType<PeerMessage.TransferEnd>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.True(end.Completed);
            Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(received, "hello.bin"), ct));

            // An authenticated frame of a type this build does not know is skipped, not fatal.
            // It is sealed with the phone's own send key and the next counter, as a newer build would.
            Task SendRawAsync(string payloadJson) => phone.SendRawEnvelopeForTestAsync(seq =>
                $"{{\"v\":{LinkProtocol.Version},\"msgId\":\"m{seq}\",\"from\":\"{phoneId}\",\"seq\":{seq},\"ts\":1,\"payload\":{payloadJson}}}", ct);
            await SendRawAsync("{\"t\":\"futureFeature\",\"x\":1}");
            await SendRawAsync("{\"t\":\"ping\",\"nonce\":\"n2\"}");
            Assert.Equal(new PeerMessage.Pong("n2"), (await phone.ReceiveAsync(ct))!.Envelope.Payload);

            // Peer close ends the session cleanly and is reported.
            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", ct);
            Assert.Equal(phoneId, await closed.Task.WaitAsync(ct));
            Assert.False(registry.IsConnected(phoneId));
            await server.StopAsync();
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    /// <summary>
    /// ADR-026: HelloAck advertises all three companion bits; the PC's on-connect mediaState and
    /// devStatus follow it; the phone's mediaState and mediaCmd are routed on the same session.
    /// </summary>
    [Fact]
    public async Task PersistentSession_CarriesMediaAndDeviceStatus()
    {
        string root = Path.Combine(AppContext.BaseDirectory, "veyro-media-" + Guid.NewGuid().ToString("N"));
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
            var pcMedia = new FakeMediaSource();
            pcMedia.Set(new PeerMessage.MediaState(
                [new MediaSessionInfo("Player.exe", App: "Player", Title: "t", Playing: true, PositionMs: 0, DurationMs: 1000, Ts: 1, CanSeek: true)],
                30, false));
            var battery = new FakeBatterySource();
            battery.Set(new Veyro.Core.Status.PcBatteryReading(64, true));
            using var publisher = new Veyro.Core.Media.PcMediaPublisher(registry, pcMedia);
            using var status = new Veyro.Core.Status.DeviceStatusService(registry, battery);
            var phoneState = new TaskCompletionSource<PeerMessage.MediaState>(TaskCreationOptions.RunContinuationsAsynchronously);
            registry.MediaStateReceived += (_, state) => phoneState.TrySetResult(state);

            await using var server = new LinkServer(
                pcId, "Test PC", trustStore, Path.Combine(root, "received"), pcPublicKey,
                _ => Task.FromResult(false), sessions: registry);
            await server.StartAsync();

            using var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{server.Port}/veyro"), ct);
            var v3dial = new V3Dialer(phoneId);
            LinkPreambleAck v3ack = await v3dial.SendPreambleAsync(socket, "Phone", ct);
            Assert.Equal(LinkPreambleAck.ModeSession, v3ack.Mode);

            await using var phone = new SecureLinkSession(socket, v3dial.SessionKeys(pcId, secret));
            const int companion = PeerCapabilities.NotificationMirror | PeerCapabilities.MediaControl | PeerCapabilities.DeviceStatus;
            await phone.SendAsync(new PeerMessage.Hello(phoneId, "Phone", "android",
                Capabilities: PeerCapabilities.FileTransfer | companion), ct);

            var helloAck = Assert.IsType<PeerMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(companion, helloAck.Capabilities & companion);

            // On-connect snapshots, in either order.
            var pushed = new List<PeerMessage>
            {
                (await phone.ReceiveAsync(ct))!.Envelope.Payload,
                (await phone.ReceiveAsync(ct))!.Envelope.Payload
            };
            var pcState = Assert.Single(pushed.OfType<PeerMessage.MediaState>());
            Assert.Equal("Player.exe", Assert.Single(pcState.Sessions).Id);
            Assert.Equal(30, pcState.Volume);
            Assert.Equal(new PeerMessage.DeviceStatus(64, true), Assert.Single(pushed.OfType<PeerMessage.DeviceStatus>()));

            // Phone -> PC mediaState reaches the registry handlers.
            await phone.SendAsync(new PeerMessage.MediaState(
                [new MediaSessionInfo("s1", Title: "phone song", Playing: true, PositionMs: 5, Ts: 2)], 40, false), ct);
            var delivered = await phoneState.Task.WaitAsync(ct);
            Assert.Equal("s1", Assert.Single(delivered.Sessions).Id);
            Assert.Equal(40, delivered.Volume);

            // Phone -> PC mediaCmd is executed against the PC snapshot; a ping after it proves
            // the loop processed the command first.
            await phone.SendAsync(new PeerMessage.MediaCommand(PeerMessage.MediaCommand.Toggle, "Player.exe"), ct);
            await phone.SendAsync(new PeerMessage.Ping("n1"), ct);
            Assert.Equal(new PeerMessage.Pong("n1"), (await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(new PeerMessage.MediaCommand("toggle", "Player.exe"), Assert.Single(pcMedia.Executed));

            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", ct);
            await server.StopAsync();
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}