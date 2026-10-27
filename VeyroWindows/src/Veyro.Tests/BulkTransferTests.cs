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
                [new InboundTransferItem(0, "nested/file.bin", payload.LongLength)]);
            Task<BulkTransferResult> sender = BulkTransfer.SendAsync(
                "127.0.0.1", ((IPEndPoint)listener.LocalEndpoint).Port, transferId, token, "android-peer",
                [new OutboundTransferItem(0, source, "nested/file.bin")]);

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