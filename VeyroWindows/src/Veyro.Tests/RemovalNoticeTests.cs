using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// ADR-057: a phone removed on this PC while it was away is told, verified, the next time it
/// dials in; a connected phone is told at once. The "phone" is a raw client speaking the wire.
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class RemovalNoticeTests : IAsyncLifetime
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-removal-" + Guid.NewGuid().ToString("N"));
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));
    private readonly byte[] _pcPublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _phonePublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);
    private string PcId => DeviceId.FromPublicKey(_pcPublicKey).Value;
    private string PhoneId => DeviceId.FromPublicKey(_phonePublicKey).Value;
    private CancellationToken Ct => _timeout.Token;

    private PeerSessionRegistry _registry = null!;
    private FileTrustStore _trustStore = null!;
    private LinkServer _server = null!;

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(_root);
        _trustStore = new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]);
        await _trustStore.UpsertAsync(new TrustedPeer(
            PhoneId, "Phone", PlatformType.Android,
            Convert.ToBase64String(_phonePublicKey), Convert.ToBase64String(_secret),
            AutoAcceptTransfers: false, DateTimeOffset.UtcNow));
        _registry = new PeerSessionRegistry();
        _server = new LinkServer(PcId, "Test PC", _trustStore, Path.Combine(_root, "in"), _pcPublicKey,
            _ => Task.FromResult(false), sessions: _registry)
        {
            UnpairHelloTimeout = TimeSpan.FromSeconds(3),
            UnpairCloseTimeout = TimeSpan.FromSeconds(3)
        };
        await _server.StartAsync();
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _timeout.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    /// <summary>Dials the PC as an Android phone does: preamble with pair = confirm.</summary>
    private async Task<(ClientWebSocket Socket, LinkPreambleAck Ack)> DialAsync()
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        _dialer?.Dispose();
        _dialer = new V3Dialer(PhoneId);
        LinkPreambleAck ack = await _dialer.SendPreambleAsync(socket, "Phone", Ct, pair: LinkPreamble.PairConfirm, pk: _phonePublicKey);
        return (socket, ack);
    }

    private V3Dialer? _dialer;

    /// <summary>This connection's v3 session keys, from the last dial and <paramref name="secret"/>.</summary>
    private LinkKeys Keys(byte[] secret) => _dialer!.SessionKeys(PcId, secret);

    private async Task WaitUntilAsync(Func<bool> condition)
    {
        for (int i = 0; i < 300 && !condition(); i++) await Task.Delay(20, Ct);
        Assert.True(condition());
    }

    [Fact]
    public async Task PhoneRemovedWhileAway_IsToldWhenItDialsIn()
    {
        await _trustStore.RemoveAndNotifyAsync(PhoneId);
        Assert.Null(_trustStore.GetPeer(PhoneId));
        Assert.NotNull(_trustStore.GetPendingUnpairSecret(PhoneId));

        (ClientWebSocket socket, LinkPreambleAck ack) = await DialAsync();
        Assert.Equal(LinkPreambleAck.ModeSession, ack.Mode);
        await using var session = new SecureLinkSession(socket, Keys(_secret));
        await session.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android", PeerCapabilities.FileTransfer), Ct);

        SecureLinkMessage told = (await session.ReceiveAsync(Ct))!;
        Assert.IsType<PeerMessage.Unpair>(told.Envelope.Payload);
        await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "forgot", Ct);

        await WaitUntilAsync(() => _trustStore.GetPendingUnpairSecret(PhoneId) is null);
        Assert.False(_registry.IsConnected(PhoneId));
        Assert.Null(_trustStore.GetPeer(PhoneId));
    }

    [Fact]
    public async Task ADiallerWithTheWrongSecret_IsNotToldAndTheNoticeStays()
    {
        await _trustStore.RemoveAndNotifyAsync(PhoneId);

        (ClientWebSocket socket, LinkPreambleAck ack) = await DialAsync();
        Assert.Equal(LinkPreambleAck.ModeSession, ack.Mode);
        await using var forger = new SecureLinkSession(socket, Keys(RandomNumberGenerator.GetBytes(32)));
        await forger.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android", 0), Ct);

        // The PC drops the connection without sending anything that authenticates.
        await Assert.ThrowsAnyAsync<Exception>(() => forger.ReceiveAsync(Ct));
        Assert.NotNull(_trustStore.GetPendingUnpairSecret(PhoneId));
        Assert.False(_registry.IsConnected(PhoneId));
    }

    [Fact]
    public async Task APhoneThatRemovedThePcToo_DropsTheNotice_AndItsNextDialPairs()
    {
        await _trustStore.RemoveAndNotifyAsync(PhoneId);

        // It holds no secret for the PC: it closes at once, as Android does ("no-secret").
        (ClientWebSocket first, LinkPreambleAck ack) = await DialAsync();
        Assert.Equal(LinkPreambleAck.ModeSession, ack.Mode);
        await first.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "no-secret", Ct);
        await WaitUntilAsync(() => _trustStore.GetPendingUnpairSecret(PhoneId) is null);
        first.Dispose();

        // The next dial takes the normal pairing path (refused here only because the test server
        // has no key agreement configured), not the removal notice.
        (ClientWebSocket second, LinkPreambleAck again) = await DialAsync();
        Assert.NotEqual(LinkPreambleAck.ModeSession, again.Mode);
        second.Dispose();
    }

    [Fact]
    public async Task RemovingAConnectedPhone_TellsItNow_AndKeepsNothing()
    {
        (ClientWebSocket socket, LinkPreambleAck ack) = await DialAsync();
        Assert.Equal(LinkPreambleAck.ModeSession, ack.Mode);
        await using var session = new SecureLinkSession(socket, Keys(_secret));
        await session.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android", PeerCapabilities.FileTransfer), Ct);
        await WaitUntilAsync(() => _registry.IsConnected(PhoneId));

        Task<bool> removal = PeerRemoval.RemoveAsync(_trustStore, _registry, PhoneId);
        while (true)
        {
            SecureLinkMessage message = (await session.ReceiveAsync(Ct))!;
            if (message.Envelope.Payload is PeerMessage.Unpair) break;
        }
        await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "forgot", Ct);

        Assert.True(await removal.WaitAsync(Ct));
        Assert.Null(_trustStore.GetPeer(PhoneId));
        Assert.Null(_trustStore.GetPendingUnpairSecret(PhoneId));
    }

    [Fact]
    public async Task RemovingAPhoneThatIsAway_KeepsItsSecretToTellItLater()
    {
        Assert.False(await PeerRemoval.RemoveAsync(_trustStore, _registry, PhoneId));
        Assert.Null(_trustStore.GetPeer(PhoneId));
        Assert.Equal(_secret, _trustStore.GetPendingUnpairSecret(PhoneId));
    }
}

/// <summary>The removed-phone list on disk (ADR-057).</summary>
public sealed class FileTrustStoreRemovalTests : IDisposable
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-removed-" + Guid.NewGuid().ToString("N"));
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);
    private readonly ManualTimeProvider _time = new();
    private const string Phone = "PHONE-1";

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private FileTrustStore Store() => new(_root, new byte[32], _time);

    private TrustedPeer Peer(byte[]? secret = null) => new(
        Phone, "Phone", PlatformType.Android, Convert.ToBase64String(new byte[32]),
        Convert.ToBase64String(secret ?? _secret), AutoAcceptTransfers: false, DateTimeOffset.UtcNow);

    [Fact]
    public async Task TheKeptSecret_SurvivesAReload_AndPairingAgainClearsIt()
    {
        await Store().UpsertAsync(Peer());
        await Store().RemoveAndNotifyAsync(Phone);
        Assert.Equal(_secret, Store().GetPendingUnpairSecret(Phone));

        await Store().UpsertAsync(Peer(RandomNumberGenerator.GetBytes(32)));
        Assert.Null(Store().GetPendingUnpairSecret(Phone));
        Assert.False(File.Exists(Path.Combine(_root, "removed.json")));
    }

    [Fact]
    public async Task TheKeptSecret_Expires()
    {
        FileTrustStore store = Store();
        await store.UpsertAsync(Peer());
        await store.RemoveAndNotifyAsync(Phone);
        _time.Advance(FileTrustStore.PendingUnpairKeep + TimeSpan.FromMinutes(1));
        Assert.Null(store.GetPendingUnpairSecret(Phone));
        Assert.Null(Store().GetPendingUnpairSecret(Phone));
    }

    [Fact]
    public async Task ATamperedList_IsIgnored_AndTrustIsUntouched()
    {
        FileTrustStore store = Store();
        await store.UpsertAsync(Peer());
        await store.UpsertAsync(Peer() with { DeviceId = "PHONE-2" });
        await store.RemoveAndNotifyAsync(Phone);

        string path = Path.Combine(_root, "removed.json");
        await File.WriteAllTextAsync(path, (await File.ReadAllTextAsync(path)).Replace("PHONE-1", "PHONE-9"));

        FileTrustStore reloaded = Store();
        Assert.Null(reloaded.GetPendingUnpairSecret("PHONE-9"));
        Assert.Null(reloaded.GetPendingUnpairSecret(Phone));
        Assert.Equal("PHONE-2", Assert.Single(reloaded.GetTrustedPeers()).DeviceId);
    }

    [Fact]
    public async Task PlainRemove_KeepsNothing()
    {
        FileTrustStore store = Store();
        await store.UpsertAsync(Peer());
        await store.RemoveAsync(Phone);
        Assert.Null(store.GetPendingUnpairSecret(Phone));
    }
}
