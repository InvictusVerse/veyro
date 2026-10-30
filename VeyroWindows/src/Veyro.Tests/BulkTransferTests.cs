using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Veyro.Core.Models;
using Veyro.Core.Transfer;
using Xunit;

namespace Veyro.Tests;

public sealed class BulkTransferTests
{
    [Fact]
    public async Task LoopbackTransfer_IsAuthenticatedVerifiedAndCommitted()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-vtc1-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        string source = Path.Combine(root, "source.bin");
        string destination = Path.Combine(root, "received");
        byte[] payload = new byte[5 * 1024 * 1024 + 123];
        RandomNumberGenerator.Fill(payload);
        await File.WriteAllBytesAsync(source, payload);

        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        const string transferId = "loopback-transfer";
        const string token = "one-time-token";
        try
        {
            Task<BulkTransferResult> receiver = BulkTransfer.ReceiveAsync(
                listener, transferId, token, "android-peer", destination,
                [new InboundTransferItem(0, "nested/file.bin", payload.LongLength)],
                FixedExporter.Default.Deriver(transferId));
            Task<BulkTransferResult> sender = BulkTransfer.SendAsync(
                "127.0.0.1", ((IPEndPoint)listener.LocalEndpoint).Port, transferId, token, "android-peer",
                [new OutboundTransferItem(0, source, "nested/file.bin")],
                FixedExporter.Default.Deriver(transferId));

            BulkTransferResult[] results = await Task.WhenAll(receiver, sender);
            string receivedPath = Path.Combine(destination, "nested", "file.bin");
            Assert.Equal(payload.LongLength, results[0].BytesTransferred);
            Assert.Equal(payload.LongLength, results[1].BytesTransferred);
            Assert.Equal(payload, await File.ReadAllBytesAsync(receivedPath));
            Assert.Equal(Convert.ToHexString(SHA256.HashData(payload)), Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(receivedPath))));
        }
        finally
        {
            listener.Stop();
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    /// <summary>
    /// ADR-058 AC 4: one byte flipped inside a sealed chunk record on the way fails the transfer,
    /// and nothing is committed under the final name (the receive path overlaps file writes).
    /// </summary>
    [Fact]
    public async Task AByteFlippedInsideAChunkRecord_FailsTheTransferAndCommitsNothing()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-vtc3-tamper-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        string source = Path.Combine(root, "source.bin");
        string destination = Path.Combine(root, "received");
        byte[] payload = new byte[9 * 1024 * 1024];
        RandomNumberGenerator.Fill(payload);
        await File.WriteAllBytesAsync(source, payload);

        var listener = new TcpListener(IPAddress.Loopback, 0);
        var relay = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        relay.Start();
        const string transferId = "tamper-transfer";
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        try
        {
            // Sender -> relay -> receiver; the relay flips one byte 6 MiB in (inside the second chunk).
            Task relaying = Task.Run(async () =>
            {
                using TcpClient inbound = await relay.AcceptTcpClientAsync(timeout.Token);
                using var outbound = new TcpClient();
                await outbound.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port, timeout.Token);
                NetworkStream a = inbound.GetStream(), b = outbound.GetStream();
                Task backward = b.CopyToAsync(a, timeout.Token).ContinueWith(_ => { }, TaskScheduler.Default);
                Task forward = Task.Run(async () =>
                {
                    byte[] buffer = new byte[64 * 1024];
                    long position = 0;
                    const long flipAt = 6L * 1024 * 1024;
                    try
                    {
                        int read;
                        while ((read = await a.ReadAsync(buffer, timeout.Token)) > 0)
                        {
                            if (flipAt >= position && flipAt < position + read) buffer[flipAt - position] ^= 0x01;
                            position += read;
                            await b.WriteAsync(buffer.AsMemory(0, read), timeout.Token);
                        }
                    }
                    catch (Exception error) when (error is IOException or OperationCanceledException or ObjectDisposedException) { }
                });
                // Either side closing ends the relay, so the other side sees the close too.
                await Task.WhenAny(forward, backward);
                inbound.Close();
                outbound.Close();
                await Task.WhenAll(forward, backward);
            });

            Task<BulkTransferResult> receiver = BulkTransfer.ReceiveAsync(
                listener, transferId, "token", "android-peer", destination,
                [new InboundTransferItem(0, "file.bin", payload.LongLength)],
                FixedExporter.Default.Deriver(transferId), ct: timeout.Token);
            Task<BulkTransferResult> sender = BulkTransfer.SendAsync(
                "127.0.0.1", ((IPEndPoint)relay.LocalEndpoint).Port, transferId, "token", "android-peer",
                [new OutboundTransferItem(0, source, "file.bin")],
                FixedExporter.Default.Deriver(transferId), ct: timeout.Token);

            await Assert.ThrowsAsync<InvalidDataException>(() => receiver);
            await Assert.ThrowsAnyAsync<Exception>(() => sender);
            Assert.False(File.Exists(Path.Combine(destination, "file.bin")));
            Assert.False(File.Exists(Path.Combine(destination, "file.bin.veyropart")));
            timeout.Cancel();
            try { await relaying; } catch (OperationCanceledException) { }
        }
        finally
        {
            listener.Stop();
            relay.Stop();
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    [Fact]
    public void BuildResume_ReturnsOnlyChunksMissingFromPartFile()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-resume-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string finalPath = Path.Combine(root, "resume.bin");
            File.WriteAllBytes(finalPath + ".veyropart", new byte[(4 * 1024 * 1024) + 17]);
            var resume = BulkTransfer.BuildResume(
                root,
                [new InboundTransferItem(3, "resume.bin", (8 * 1024 * 1024) + 17)]);

            Assert.Equal(new[] { 2 }, resume[3]);
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}