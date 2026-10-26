using Veyro.Core.Data;
using Veyro.Core.Link;
using System.Net.WebSockets;
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
}