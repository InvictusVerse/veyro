using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
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

    /// <summary>Relays one TCP connection to a target port and keeps every byte in each direction.</summary>
    private sealed class CaptureRelay
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly MemoryStream _forward = new();
        private readonly MemoryStream _backward = new();

        public CaptureRelay(int targetPort, CancellationToken ct)
        {
            _listener.Start();
            Running = RunAsync(targetPort, ct);
        }

        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;
        public Task Running { get; }

        /// <summary>What the dialler (the VTC3 sender) wrote.</summary>
        public byte[] Forward { get { lock (_forward) return _forward.ToArray(); } }

        /// <summary>What the listener (the VTC3 receiver) wrote back.</summary>
        public byte[] Backward { get { lock (_backward) return _backward.ToArray(); } }

        private async Task RunAsync(int targetPort, CancellationToken ct)
        {
            try
            {
                using TcpClient inbound = await _listener.AcceptTcpClientAsync(ct);
                using var outbound = new TcpClient();
                await outbound.ConnectAsync(IPAddress.Loopback, targetPort, ct);
                await Task.WhenAll(
                    PumpAsync(inbound.Client, outbound.Client, _forward, ct),
                    PumpAsync(outbound.Client, inbound.Client, _backward, ct));
            }
            finally
            {
                _listener.Stop();
            }
        }

        private static async Task PumpAsync(Socket from, Socket to, MemoryStream capture, CancellationToken ct)
        {
            byte[] buffer = new byte[64 * 1024];
            try
            {
                int read;
                while ((read = await from.ReceiveAsync(buffer, SocketFlags.None, ct)) > 0)
                {
                    lock (capture) capture.Write(buffer, 0, read);
                    int sent = 0;
                    while (sent < read) sent += await to.SendAsync(buffer.AsMemory(sent, read - sent), SocketFlags.None, ct);
                }
            }
            catch (Exception error) when (error is SocketException or OperationCanceledException or ObjectDisposedException) { }
            try { to.Shutdown(SocketShutdown.Send); } catch (Exception error) when (error is SocketException or ObjectDisposedException) { }
        }
    }

    private static async Task<(BulkTransferResult Received, CaptureRelay Relay)> TransferThroughRelayAsync(
        string transferId, string token, string source, string destination, long size,
        IReadOnlyDictionary<int, IReadOnlyList<int>>? resume, CancellationToken ct)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var relay = new CaptureRelay(((IPEndPoint)listener.LocalEndpoint).Port, ct);
            Task<BulkTransferResult> receiver = BulkTransfer.ReceiveAsync(
                listener, transferId, token, "android-peer", destination,
                [new InboundTransferItem(0, "file.bin", size)],
                FixedExporter.Default.Deriver(transferId), ct: ct);
            Task<BulkTransferResult> sender = BulkTransfer.SendAsync(
                "127.0.0.1", relay.Port, transferId, token, "android-peer",
                [new OutboundTransferItem(0, source, "file.bin")],
                FixedExporter.Default.Deriver(transferId), resume: resume, ct: ct);
            BulkTransferResult[] results = await Task.WhenAll(receiver, sender);
            await relay.Running.WaitAsync(ct);
            return (results[0], relay);
        }
        finally
        {
            listener.Stop();
        }
    }

    /// <summary>
    /// ADR-058 AC 15 (review issue 10): file bytes never cross the data socket in the clear. A
    /// file made of a known 64-byte pattern goes through a relay that keeps every byte in both
    /// directions; neither direction contains the pattern, the token or the sender id, and the
    /// received file has the right SHA-256.
    /// </summary>
    [Fact]
    public async Task FileBytesAndTheTokenNeverCrossTheDataSocketInTheClear()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-vtc3-capture-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        try
        {
            byte[] pattern = Encoding.ASCII.GetBytes("VEYRO-AC15-PLAINTEXT-PATTERN-0123456789-ABCDEFGHIJKLMNOPQRSTUVWX");
            Assert.Equal(64, pattern.Length);
            byte[] payload = new byte[5 * 1024 * 1024 + 321];
            for (int i = 0; i < payload.Length; i++) payload[i] = pattern[i % pattern.Length];
            string source = Path.Combine(root, "source.bin");
            await File.WriteAllBytesAsync(source, payload);
            const string token = "ac15-one-time-token";

            var (received, relay) = await TransferThroughRelayAsync(
                "capture-transfer", token, source, Path.Combine(root, "received"), payload.LongLength, null, timeout.Token);

            Assert.Equal(payload.LongLength, received.BytesTransferred);
            Assert.Equal(SHA256.HashData(payload), SHA256.HashData(await File.ReadAllBytesAsync(Path.Combine(root, "received", "file.bin"))));
            byte[] forward = relay.Forward, backward = relay.Backward;
            Assert.True(forward.Length > payload.Length, "the file crossed this socket");
            Assert.True(backward.Length > 0, "the receiver answered on this socket");
            foreach (byte[] wire in new[] { forward, backward })
            {
                Assert.Equal(-1, wire.AsSpan().IndexOf(pattern.AsSpan()));
                Assert.Equal(-1, wire.AsSpan().IndexOf(Encoding.ASCII.GetBytes(token).AsSpan()));
                Assert.Equal(-1, wire.AsSpan().IndexOf("android-peer"u8));
            }
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    /// <summary>The stream-open salt and the first sealed record (header, then ciphertext and tag) of one direction.</summary>
    private static (byte[] Salt, byte[] Header, byte[] Sealed) FirstSealedRecord(byte[] wire, string transferId)
    {
        int n = Encoding.UTF8.GetByteCount(transferId);
        int openLength = 19 + n + 16;
        Assert.Equal(0x09, wire[4]);
        int headerLength = 19 + n;
        int ctLength = BinaryPrimitives.ReadInt32BigEndian(wire.AsSpan(openLength + 15 + n, 4));
        return (wire[(19 + n)..openLength],
                wire[openLength..(openLength + headerLength)],
                wire[(openLength + headerLength)..(openLength + headerLength + ctLength)]);
    }

    /// <summary>
    /// ADR-058 AC 16 (review issue 10): reopening the data socket for the same transfer id in the
    /// same session (a second dial as after a route or host fallback, then a resume) uses new keys.
    /// The first sealed record of each direction has the same header (type, transfer id, seq 0,
    /// length) and the same plaintext in every attempt, so the same nonce; its ciphertext differs,
    /// so the key differs and no (key, nonce) pair repeats.
    /// </summary>
    [Fact]
    public async Task ReopeningTheDataSocketForOneTransferUsesNewKeysEachTime()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-vtc3-reopen-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        const string transferId = "reopen-transfer";
        try
        {
            byte[] payload = new byte[BulkTransfer.DefaultChunkSize + 1000];
            RandomNumberGenerator.Fill(payload);
            string source = Path.Combine(root, "source.bin");
            await File.WriteAllBytesAsync(source, payload);

            var attempts = new List<CaptureRelay>();
            foreach (string name in new[] { "first", "fallback" })
            {
                var (_, relay) = await TransferThroughRelayAsync(transferId, "token", source, Path.Combine(root, name), payload.LongLength, null, timeout.Token);
                attempts.Add(relay);
            }
            // Resume: the receiver already holds chunk 0 and asks only for chunk 1.
            string resumeRoot = Path.Combine(root, "resume");
            Directory.CreateDirectory(resumeRoot);
            await File.WriteAllBytesAsync(Path.Combine(resumeRoot, "file.bin.veyropart"), payload[..BulkTransfer.DefaultChunkSize]);
            var resume = BulkTransfer.BuildResume(resumeRoot, [new InboundTransferItem(0, "file.bin", payload.LongLength)]);
            Assert.Equal(new[] { 1 }, resume[0]);
            var (resumed, resumedRelay) = await TransferThroughRelayAsync(transferId, "token", source, resumeRoot, payload.LongLength, resume, timeout.Token);
            attempts.Add(resumedRelay);
            Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(resumeRoot, "file.bin")));

            int n = Encoding.UTF8.GetByteCount(transferId);
            foreach (Func<CaptureRelay, byte[]> direction in new Func<CaptureRelay, byte[]>[] { r => r.Forward, r => r.Backward })
            {
                var records = attempts.Select(relay => FirstSealedRecord(direction(relay), transferId)).ToList();
                foreach (var record in records) Assert.All(record.Header[(7 + n)..(15 + n)], b => Assert.Equal(0, b));
                for (int i = 0; i < records.Count; i++)
                {
                    for (int j = i + 1; j < records.Count; j++)
                    {
                        Assert.NotEqual(records[i].Salt, records[j].Salt);
                        Assert.Equal(records[i].Header, records[j].Header);
                        Assert.NotEqual(records[i].Sealed, records[j].Sealed);
                    }
                }
            }
        }
        finally
        {
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