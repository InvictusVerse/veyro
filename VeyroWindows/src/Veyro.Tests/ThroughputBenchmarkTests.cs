using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;
using Xunit;
using Xunit.Abstractions;

namespace Veyro.Tests;

/// <summary>
/// NFR-3 (ADR-058): timed loopback bulk transfer and ChaCha20-Poly1305 seal/open throughput, in
/// MB/s. No pass/fail threshold: the numbers depend on the machine. Each result is written to the
/// test output and, when VEYRO_BENCH_OUT names a file, appended to it as a VEYRO-BENCH line.
/// </summary>
public sealed class ThroughputBenchmarkTests(ITestOutputHelper output)
{
    private void Report(string line)
    {
        output.WriteLine(line);
        if (Environment.GetEnvironmentVariable("VEYRO_BENCH_OUT") is { Length: > 0 } path)
            File.AppendAllText(path, line + Environment.NewLine);
    }

    [Fact]
    public async Task TimedLoopbackTransfer_AtTheDefaultChunkSize()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-bench-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            const int size = 256 * 1024 * 1024;
            string source = Path.Combine(root, "source.bin");
            byte[] payload = new byte[size];
            RandomNumberGenerator.Fill(payload);
            await File.WriteAllBytesAsync(source, payload);
            payload = [];

            TimeSpan best = TimeSpan.MaxValue;
            for (int run = 0; run < 4; run++)
            {
                string transferId = "bench-" + run;
                string destination = Path.Combine(root, "received-" + run);
                var listener = new TcpListener(IPAddress.Loopback, 0);
                listener.Start();
                try
                {
                    var watch = Stopwatch.StartNew();
                    Task<BulkTransferResult> receiver = BulkTransfer.ReceiveAsync(
                        listener, transferId, "token", "android-peer", destination,
                        [new InboundTransferItem(0, "bench.bin", size)],
                        FixedExporter.Default.Deriver(transferId));
                    Task<BulkTransferResult> sender = BulkTransfer.SendAsync(
                        "127.0.0.1", ((IPEndPoint)listener.LocalEndpoint).Port, transferId, "token", "android-peer",
                        [new OutboundTransferItem(0, source, "bench.bin")],
                        FixedExporter.Default.Deriver(transferId));
                    BulkTransferResult[] results = await Task.WhenAll(receiver, sender);
                    watch.Stop();
                    Assert.Equal(size, results[0].BytesTransferred);
                    // Run 0 warms the JIT and the file cache and is not counted.
                    if (run > 0 && watch.Elapsed < best) best = watch.Elapsed;
                }
                finally
                {
                    listener.Stop();
                    try { Directory.Delete(destination, recursive: true); } catch { }
                }
            }
            Report(string.Format(CultureInfo.InvariantCulture, "VEYRO-BENCH dotnet transfer 256MiB chunk={0}KiB {1:F1} MB/s",
                BulkTransfer.DefaultChunkSize / 1024, size / (1024.0 * 1024.0) / best.TotalSeconds));
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    [Theory]
    [InlineData(512 * 1024)]
    [InlineData(4 * 1024 * 1024)]
    public void AeadSealAndOpenThroughput(int frameSize)
    {
        if (!ChaCha20Poly1305.IsSupported) return;
        byte[] key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        byte[] aad = new byte[64];
        byte[] plain = new byte[frameSize];
        RandomNumberGenerator.Fill(plain);
        byte[] sealedFrame = new byte[frameSize + AeadStreamFramer.TagSize];
        byte[] opened = new byte[frameSize];
        int frames = (int)(256L * 1024 * 1024 / frameSize);

        using (var warmS = new AeadStreamFramer(key, 1, FramerRole.Seal))
        using (var warmO = new AeadStreamFramer(key, 1, FramerRole.Open))
        {
            for (int i = 0; i < 8; i++)
            {
                warmS.Seal(aad, plain, sealedFrame);
                warmO.Open(aad, sealedFrame, opened);
            }
        }

        using var sealer = new AeadStreamFramer(key, 1, FramerRole.Seal);
        using var opener = new AeadStreamFramer(key, 1, FramerRole.Open);
        long sealTicks = 0, openTicks = 0;
        for (int i = 0; i < frames; i++)
        {
            long t0 = Stopwatch.GetTimestamp();
            sealer.Seal(aad, plain, sealedFrame);
            long t1 = Stopwatch.GetTimestamp();
            opener.Open(aad, sealedFrame, opened);
            long t2 = Stopwatch.GetTimestamp();
            sealTicks += t1 - t0;
            openTicks += t2 - t1;
        }
        Assert.Equal(plain, opened);
        double mb = (double)frames * frameSize / (1024.0 * 1024.0);
        Report(string.Format(CultureInfo.InvariantCulture, "VEYRO-BENCH dotnet aead frame={0}KiB seal={1:F1} MB/s open={2:F1} MB/s",
            frameSize / 1024, mb / ((double)sealTicks / Stopwatch.Frequency), mb / ((double)openTicks / Stopwatch.Frequency)));
    }
}
