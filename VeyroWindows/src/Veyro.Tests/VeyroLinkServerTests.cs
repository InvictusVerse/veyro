using Veyro.Core.Crypto;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Transfer;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Xunit;

namespace Veyro.Tests;

public sealed class VeyroLinkServerTests
{
    [Fact]
    public async Task ServerStartsWithoutHttpUrlReservation()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-server-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var trustStore = new FileTrustStore(root, new byte[32]);
            await using var server = new VeyroLinkServer(
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
            await using var server = new VeyroLinkServer(
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
            var notified = new TaskCompletionSource<(string Peer, LinkMessage.Notification Message)>(TaskCreationOptions.RunContinuationsAsynchronously);
            var closed = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
            registry.NotificationReceived += (peer, message) => notified.TrySetResult((peer, message));
            registry.SessionClosed += peer => closed.TrySetResult(peer);

            string received = Path.Combine(root, "received");
            await using var server = new VeyroLinkServer(
                pcId, "Test PC", trustStore, received, pcPublicKey,
                _ => Task.FromResult(false), sessions: registry);
            await server.StartAsync();

            using var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{server.Port}/veyro"), ct);
            await socket.SendAsync(Encoding.UTF8.GetBytes(LinkJson.Encode(new LinkPreamble(phoneId, "Phone", "android"))),
                WebSocketMessageType.Text, true, ct);
            byte[] buffer = new byte[4096];
            WebSocketReceiveResult ackFrame = await socket.ReceiveAsync(buffer, ct);
            LinkPreambleAck? ack = LinkJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, ackFrame.Count));
            Assert.Equal(LinkPreambleAck.ModeSession, ack?.Mode);

            await using var phone = new SecureLinkSession(socket, phoneId, secret, encrypt: true, expectedPeerId: pcId);

            // Hello -> HelloAck advertising notification mirroring.
            await phone.SendAsync(new LinkMessage.Hello(phoneId, "Phone", "android",
                Capabilities: LinkCapabilities.FileTransfer | LinkCapabilities.NotificationMirror), ct);
            var helloAck = Assert.IsType<LinkMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.NotEqual(0, helloAck.Capabilities & LinkCapabilities.NotificationMirror);
            Assert.True(registry.IsConnected(phoneId));
            Assert.NotEqual(0, registry.GetPeerCapabilities(phoneId) & LinkCapabilities.NotificationMirror);

            // Phone -> PC notification reaches the handler; PC -> phone request reaches the phone.
            const string key = "0|com.example.chat|7|null|10123";
            await phone.SendAsync(new LinkMessage.Notification(key, LinkMessage.Notification.KindPosted, Title: "t"), ct);
            var delivered = await notified.Task.WaitAsync(ct);
            Assert.Equal(phoneId, delivered.Peer);
            Assert.Equal(key, delivered.Message.Key);
            await registry.SendAsync(phoneId, new LinkMessage.NotificationDismissRequest(key), ct);
            Assert.Equal(new LinkMessage.NotificationDismissRequest(key), (await phone.ReceiveAsync(ct))!.Envelope.Payload);

            // Heartbeat still answered.
            await phone.SendAsync(new LinkMessage.Ping("n1"), ct);
            Assert.Equal(new LinkMessage.Pong("n1"), (await phone.ReceiveAsync(ct))!.Envelope.Payload);

            // A transfer on the same session still works.
            string source = Path.Combine(root, "source.bin");
            byte[] payload = RandomNumberGenerator.GetBytes(64 * 1024 + 7);
            await File.WriteAllBytesAsync(source, payload, ct);
            const string transferId = "session-transfer";
            await phone.SendAsync(new LinkMessage.TransferOffer(
                transferId, [new LinkItem(0, "hello.bin", payload.Length, 0)], payload.Length, "Phone", ["LAN_TCP"]), ct);
            var decision = Assert.IsType<LinkMessage.TransferDecision>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.True(decision.Accept);
            await Vtc1BulkTransfer.SendAsync(
                decision.TcpHost!, decision.TcpPort!.Value, transferId, decision.AuthToken!, phoneId,
                [new OutboundTransferItem(0, source, "hello.bin")], resume: decision.Resume, ct: ct);
            var end = Assert.IsType<LinkMessage.TransferEnd>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.True(end.Completed);
            Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(received, "hello.bin"), ct));

            // An authenticated frame of a type this build does not know is skipped, not fatal.
            byte[] sessionKey = FrameCrypto.SessionKeyFromBytes(secret);
            async Task SendRawAsync(long seq, string payloadJson)
            {
                string envelope = $"{{\"v\":1,\"msgId\":\"m{seq}\",\"from\":\"{phoneId}\",\"seq\":{seq},\"ts\":1,\"payload\":{payloadJson}}}";
                SealedFrame frame = FrameCrypto.Seal(envelope, sessionKey, encrypt: true, phoneId);
                string wire = LinkJson.Encode(new LinkWireFrame
                {
                    From = frame.FromDeviceId,
                    Nonce = Convert.ToBase64String(frame.Nonce),
                    Ciphertext = Convert.ToBase64String(frame.Body),
                    Mac = Convert.ToBase64String(frame.Mac)
                });
                await socket.SendAsync(Encoding.UTF8.GetBytes(wire), WebSocketMessageType.Text, true, ct);
            }
            await SendRawAsync(10_000, "{\"t\":\"futureFeature\",\"x\":1}");
            await SendRawAsync(10_001, "{\"t\":\"ping\",\"nonce\":\"n2\"}");
            Assert.Equal(new LinkMessage.Pong("n2"), (await phone.ReceiveAsync(ct))!.Envelope.Payload);

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
            pcMedia.Set(new LinkMessage.MediaState(
                [new MediaSessionInfo("Player.exe", App: "Player", Title: "t", Playing: true, PositionMs: 0, DurationMs: 1000, Ts: 1, CanSeek: true)],
                30, false));
            var battery = new FakeBatterySource();
            battery.Set(new Veyro.Core.Status.PcBatteryReading(64, true));
            using var publisher = new Veyro.Core.Media.PcMediaPublisher(registry, pcMedia);
            using var status = new Veyro.Core.Status.DeviceStatusService(registry, battery);
            var phoneState = new TaskCompletionSource<LinkMessage.MediaState>(TaskCreationOptions.RunContinuationsAsynchronously);
            registry.MediaStateReceived += (_, state) => phoneState.TrySetResult(state);

            await using var server = new VeyroLinkServer(
                pcId, "Test PC", trustStore, Path.Combine(root, "received"), pcPublicKey,
                _ => Task.FromResult(false), sessions: registry);
            await server.StartAsync();

            using var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{server.Port}/veyro"), ct);
            await socket.SendAsync(Encoding.UTF8.GetBytes(LinkJson.Encode(new LinkPreamble(phoneId, "Phone", "android"))),
                WebSocketMessageType.Text, true, ct);
            byte[] buffer = new byte[4096];
            WebSocketReceiveResult ackFrame = await socket.ReceiveAsync(buffer, ct);
            Assert.Equal(LinkPreambleAck.ModeSession,
                LinkJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, ackFrame.Count))?.Mode);

            await using var phone = new SecureLinkSession(socket, phoneId, secret, encrypt: true, expectedPeerId: pcId);
            const int companion = LinkCapabilities.NotificationMirror | LinkCapabilities.MediaControl | LinkCapabilities.DeviceStatus;
            await phone.SendAsync(new LinkMessage.Hello(phoneId, "Phone", "android",
                Capabilities: LinkCapabilities.FileTransfer | companion), ct);

            var helloAck = Assert.IsType<LinkMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(companion, helloAck.Capabilities & companion);

            // On-connect snapshots, in either order.
            var pushed = new List<LinkMessage>
            {
                (await phone.ReceiveAsync(ct))!.Envelope.Payload,
                (await phone.ReceiveAsync(ct))!.Envelope.Payload
            };
            var pcState = Assert.Single(pushed.OfType<LinkMessage.MediaState>());
            Assert.Equal("Player.exe", Assert.Single(pcState.Sessions).Id);
            Assert.Equal(30, pcState.Volume);
            Assert.Equal(new LinkMessage.DeviceStatus(64, true), Assert.Single(pushed.OfType<LinkMessage.DeviceStatus>()));

            // Phone -> PC mediaState reaches the registry handlers.
            await phone.SendAsync(new LinkMessage.MediaState(
                [new MediaSessionInfo("s1", Title: "phone song", Playing: true, PositionMs: 5, Ts: 2)], 40, false), ct);
            var delivered = await phoneState.Task.WaitAsync(ct);
            Assert.Equal("s1", Assert.Single(delivered.Sessions).Id);
            Assert.Equal(40, delivered.Volume);

            // Phone -> PC mediaCmd is executed against the PC snapshot; a ping after it proves
            // the loop processed the command first.
            await phone.SendAsync(new LinkMessage.MediaCommand(LinkMessage.MediaCommand.Toggle, "Player.exe"), ct);
            await phone.SendAsync(new LinkMessage.Ping("n1"), ct);
            Assert.Equal(new LinkMessage.Pong("n1"), (await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(new LinkMessage.MediaCommand("toggle", "Player.exe"), Assert.Single(pcMedia.Executed));

            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", ct);
            await server.StopAsync();
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}