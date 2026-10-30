using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Protocol;

namespace Veyro.Core.Transfer;

public sealed record OutboundTransferItem(
    int Index,
    string SourcePath,
    string RelativePath);

public sealed record InboundTransferItem(
    int Index,
    string RelativePath,
    long SizeBytes);

public sealed record BulkTransferResult(
    string TransferId,
    long BytesTransferred,
    IReadOnlyList<string> CompletedPaths);

/// <summary>
/// High-throughput VTC3 data-plane implementation (ADR-058): every record is sealed with
/// ChaCha20-Poly1305 under per-attempt keys from the carrying session's exporter
/// (<c>deriveKeys</c>, <see cref="Vtc3.KeyDeriver"/>). Control-plane consent and
/// endpoint negotiation remain outside this class; callers provide the one-time transfer credential.
/// </summary>
public static class BulkTransfer
{
    public const int DefaultChunkSize = 4 * 1024 * 1024;
    private const int SocketBufferSize = 1024 * 1024;
    // Kernel socket buffers with room for a whole sealed 4 MiB record, so the peer's sealing or
    // opening overlaps the transfer instead of each record waiting for the last to drain (NFR-3).
    private const int SocketKernelBufferSize = 8 * 1024 * 1024;
    private const int FlushEveryChunks = 4;

    /// <summary>How long the receiver waits for the accepted sender to open the data socket.</summary>
    public static readonly TimeSpan DefaultAcceptTimeout = TimeSpan.FromSeconds(30);

    /// <summary>Longest silence on the data socket before the receiver gives up.</summary>
    public static readonly TimeSpan DefaultIdleTimeout = TimeSpan.FromSeconds(60);

    public static IReadOnlyDictionary<int, IReadOnlyList<int>> BuildResume(
        string destinationRoot,
        IReadOnlyList<InboundTransferItem> items,
        int chunkSize = DefaultChunkSize)
    {
        var resume = new Dictionary<int, IReadOnlyList<int>>();
        foreach (var item in items)
        {
            if (!RelativePath.IsValid(item.RelativePath)) continue;
            string finalPath = Path.GetFullPath(Path.Combine(destinationRoot, item.RelativePath.Replace('/', Path.DirectorySeparatorChar)));
            string partPath = finalPath + ".veyropart";
            long existing = File.Exists(partPath) ? new FileInfo(partPath).Length : 0;
            int chunkCount = checked((int)((item.SizeBytes + chunkSize - 1) / chunkSize));
            if (existing <= 0) continue;
            var missing = Enumerable.Range(0, chunkCount)
                .Where(index => (long)index * chunkSize >= existing)
                .ToList();
            resume[item.Index] = missing;
        }
        return resume;
    }

    public static async Task<BulkTransferResult> SendAsync(
        string host,
        int port,
        string transferId,
        string authToken,
        string senderDeviceId,
        IReadOnlyList<OutboundTransferItem> items,
        Func<byte[], byte[], byte[]> deriveKeys,
        int chunkSize = DefaultChunkSize,
        IReadOnlyDictionary<int, IReadOnlyList<int>>? resume = null,
        TransferRoute route = TransferRoute.LanTcp,
        IProgress<long>? progress = null,
        CancellationToken ct = default)
    {
        ValidateChunkSize(chunkSize);
        using var client = new TcpClient { NoDelay = true, SendBufferSize = SocketKernelBufferSize, ReceiveBufferSize = SocketKernelBufferSize };
        await client.ConnectAsync(host, port, ct).ConfigureAwait(false);
        await using NetworkStream stream = client.GetStream();

        // Fresh salts both ways, so this attempt's keys are new (04-PROTOCOL.md 7.3).
        using Vtc3Channel channel = await Vtc3.OpenAsSenderAsync(stream, transferId, deriveKeys, ct).ConfigureAwait(false);
        Vtc3Writer writer = channel.Writer;
        await writer.WriteAsync(new TcpFrame.Auth(0, transferId, authToken, senderDeviceId, route), ct: ct).ConfigureAwait(false);
        var authAck = await channel.Reader.ReadOrNullAsync(ct).ConfigureAwait(false) as TcpFrame.AuthAck
            ?? throw new InvalidDataException("VTC3 receiver did not return AuthAck.");
        if (!authAck.Ok)
            throw new UnauthorizedAccessException($"VTC3 authentication rejected: {authAck.Reason ?? "unknown reason"}");

        long transferred = 0;
        var completed = new List<string>(items.Count);
        foreach (var item in items)
        {
            ct.ThrowIfCancellationRequested();
            var fileInfo = new FileInfo(item.SourcePath);
            if (!fileInfo.Exists) throw new FileNotFoundException("Transfer source does not exist.", item.SourcePath);
            if (!RelativePath.IsValid(item.RelativePath)) throw new ArgumentException($"Invalid transfer path: {item.RelativePath}", nameof(items));

            int chunkCount = checked((int)((fileInfo.Length + chunkSize - 1) / chunkSize));
            await writer.WriteAsync(
                new TcpFrame.ItemBegin(0, transferId, item.Index, fileInfo.Length, chunkCount, chunkSize),
                ct: ct).ConfigureAwait(false);

            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using var input = new FileStream(
                item.SourcePath, FileMode.Open, FileAccess.Read, FileShare.Read, SocketBufferSize,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            // Two read buffers: while chunk N is sealed and written on the pool, chunk N+1 is read
            // and hashed here (NFR-3). At most one write is in flight, and it is awaited before
            // the next starts, so records stay in order and a buffer is never reused while sealing.
            byte[][] buffers = [new byte[chunkSize], new byte[chunkSize]];
            int current = 0;
            Task pendingWrite = Task.CompletedTask;
            int chunkIndex = 0;
            int pendingFlushes = 0;
            HashSet<int>? requestedChunks = resume is not null && resume.TryGetValue(item.Index, out var requested)
                ? requested.ToHashSet()
                : null;
            try
            {
                int read;
                while ((read = await input.ReadAsync(buffers[current].AsMemory(0, chunkSize), ct).ConfigureAwait(false)) > 0)
                {
                    byte[] buffer = buffers[current];
                    hash.AppendData(buffer, 0, read);
                    int currentChunk = chunkIndex++;
                    if (requestedChunks is not null && !requestedChunks.Contains(currentChunk))
                    {
                        transferred += read;
                        progress?.Report(transferred);
                        continue;
                    }
                    bool flush = ++pendingFlushes >= FlushEveryChunks;
                    await pendingWrite.ConfigureAwait(false);
                    // Sealed straight from the read buffer into the writer's reused record buffer.
                    int length = read;
                    pendingWrite = Task.Run(() => writer.WriteChunkAsync(item.Index, currentChunk, buffer.AsMemory(0, length), flush, ct), ct);
                    current ^= 1;
                    if (flush) pendingFlushes = 0;
                    transferred += read;
                    progress?.Report(transferred);
                }
                await pendingWrite.ConfigureAwait(false);
            }
            catch
            {
                // Observe the write in flight before the buffers and the channel go away.
                try { await pendingWrite.ConfigureAwait(false); } catch { }
                throw;
            }

            await writer.WriteAsync(
                new TcpFrame.ItemEnd(0, transferId, item.Index, Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant()),
                ct: ct).ConfigureAwait(false);
            var result = await channel.Reader.ReadOrNullAsync(ct).ConfigureAwait(false) as TcpFrame.ItemResult
                ?? throw new InvalidDataException("VTC3 receiver did not return ItemResult.");
            if (!result.Ok) throw new IOException($"VTC3 receiver rejected item {item.Index}: {result.ErrorMessage ?? "unknown error"}");
            completed.Add(item.RelativePath);
        }

        await writer.WriteAsync(new TcpFrame.TransferEnd(0, transferId, true), ct: ct).ConfigureAwait(false);
        return new BulkTransferResult(transferId, transferred, completed);
    }

    public static async Task<BulkTransferResult> ReceiveAsync(
        TcpListener listener,
        string transferId,
        string expectedAuthToken,
        string? expectedSenderDeviceId,
        string destinationRoot,
        IReadOnlyList<InboundTransferItem> items,
        Func<byte[], byte[], byte[]> deriveKeys,
        IProgress<long>? progress = null,
        CancellationToken ct = default,
        Func<int, bool, string?, CancellationToken, Task>? onItemResult = null,
        TimeSpan? acceptTimeout = null,
        TimeSpan? idleTimeout = null)
    {
        TimeSpan idleLimit = idleTimeout ?? DefaultIdleTimeout;
        using TcpClient client = await AcceptAsync(listener, acceptTimeout ?? DefaultAcceptTimeout, ct).ConfigureAwait(false);
        client.NoDelay = true;
        client.SendBufferSize = SocketKernelBufferSize;
        client.ReceiveBufferSize = SocketKernelBufferSize;
        await using NetworkStream stream = client.GetStream();

        Vtc3Channel channel;
        using (var open = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            open.CancelAfter(idleLimit);
            try { channel = await Vtc3.OpenAsReceiverAsync(stream, transferId, deriveKeys, open.Token).ConfigureAwait(false); }
            catch (OperationCanceledException error) when (!ct.IsCancellationRequested)
            {
                throw new TimeoutException("VTC3 sender did not open the stream.", error);
            }
        }
        using Vtc3Channel ownedChannel = channel;
        Vtc3Writer writer = channel.Writer;

        // Bounded per frame: a sender that goes quiet fails the transfer instead of holding the
        // receiver (and the session's one-transfer slot) forever. Hashing between reads is not counted.
        async Task<TcpFrame?> ReadAsync()
        {
            using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
            idle.CancelAfter(idleLimit);
            try { return await channel.Reader.ReadOrNullAsync(idle.Token).ConfigureAwait(false); }
            catch (OperationCanceledException error) when (!ct.IsCancellationRequested)
            {
                throw new TimeoutException("VTC3 sender stopped sending.", error);
            }
        }

        var auth = await ReadAsync().ConfigureAwait(false) as TcpFrame.Auth
            ?? throw new InvalidDataException("VTC3 sender did not send Auth.");
        // authToken stays as defence in depth; the boundary is holding the session's exporter keys.
        if (auth.TransferId != transferId || auth.AuthToken != expectedAuthToken ||
            (expectedSenderDeviceId is not null && auth.SenderDeviceId != expectedSenderDeviceId))
        {
            await writer.WriteAsync(new TcpFrame.AuthAck(0, transferId, false, "UNAUTHORIZED"), ct: ct).ConfigureAwait(false);
            throw new UnauthorizedAccessException("VTC3 authentication rejected.");
        }

        await writer.WriteAsync(new TcpFrame.AuthAck(0, transferId, true), ct: ct).ConfigureAwait(false);
        var itemMap = items.ToDictionary(item => item.Index);
        var completed = new List<string>(items.Count);
        var tempFiles = new Dictionary<int, string>();
        var streams = new Dictionary<int, FileStream>();
        var chunkSizes = new Dictionary<int, (int ChunkSize, long SizeBytes)>();
        long transferred = 0;
        // The file write of chunk N runs while chunk N+1 is read and opened (NFR-3). At most one
        // is in flight; every other frame waits for it, so ItemEnd hashes a complete file.
        Task? pendingWrite = null;

        try
        {
            while (true)
            {
                // The VTC3 reader already rejected (fatally) a record for another transfer, out
                // of order, replayed or tampered with.
                var frame = await ReadAsync().ConfigureAwait(false)
                    ?? throw new EndOfStreamException("VTC3 stream ended before TransferEnd.");

                if (pendingWrite is not null)
                {
                    Task previous = pendingWrite;
                    pendingWrite = null;
                    await previous.ConfigureAwait(false);
                }

                switch (frame)
                {
                    case TcpFrame.ItemBegin begin:
                    {
                        if (!itemMap.TryGetValue(begin.ItemIndex, out var item) || begin.SizeBytes != item.SizeBytes)
                            throw new InvalidDataException($"Unexpected VTC3 item {begin.ItemIndex}.");
                        if (!RelativePath.IsValid(item.RelativePath))
                            throw new InvalidDataException($"Invalid inbound path: {item.RelativePath}");
                        string finalPath = Path.GetFullPath(Path.Combine(destinationRoot, item.RelativePath.Replace('/', Path.DirectorySeparatorChar)));
                        string root = Path.GetFullPath(destinationRoot).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                        if (!finalPath.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                            throw new InvalidDataException("Inbound path escaped destination root.");
                        Directory.CreateDirectory(Path.GetDirectoryName(finalPath)!);
                        string tempPath = finalPath + ".veyropart";
                        tempFiles[item.Index] = tempPath;
                        streams[item.Index] = new FileStream(tempPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None, SocketBufferSize, FileOptions.Asynchronous | FileOptions.RandomAccess);
                        chunkSizes[item.Index] = (begin.ChunkSize, item.SizeBytes);
                        break;
                    }
                    case TcpFrame.Chunk chunk:
                    {
                        if (!streams.TryGetValue(chunk.ItemIndex, out var output) || !chunkSizes.TryGetValue(chunk.ItemIndex, out var itemInfo))
                            throw new InvalidDataException("Chunk arrived before ItemBegin.");
                        long offset = (long)chunk.ChunkIndex * itemInfo.ChunkSize;
                        if (chunk.ChunkIndex < 0 || offset < 0 || offset + chunk.Data.Length > itemInfo.SizeBytes)
                            throw new InvalidDataException("Chunk exceeds the declared item size.");
                        // On the pool: Windows completes a write that extends a file synchronously,
                        // even on an overlapped handle, so awaiting it here would block the reader.
                        // Safe only because chunk.Data is a copy (TcpFrameCodec.DecodePayload), not
                        // a view of the reader's reused buffer.
                        byte[] data = chunk.Data;
                        pendingWrite = Task.Run(async () =>
                        {
                            output.Position = offset;
                            await output.WriteAsync(data, ct).ConfigureAwait(false);
                        }, ct);
                        transferred += chunk.Data.Length;
                        progress?.Report(transferred);
                        break;
                    }
                    case TcpFrame.ItemEnd end:
                    {
                        if (!itemMap.TryGetValue(end.ItemIndex, out var item) || !streams.Remove(end.ItemIndex, out var output) || !chunkSizes.Remove(end.ItemIndex))
                            throw new InvalidDataException("ItemEnd does not match an active item.");
                        await output.FlushAsync(ct).ConfigureAwait(false);
                        await output.DisposeAsync().ConfigureAwait(false);
                        string actualHash;
                        await using (var committedInput = new FileStream(tempFiles[end.ItemIndex], FileMode.Open, FileAccess.Read, FileShare.Read, SocketBufferSize, FileOptions.SequentialScan))
                        {
                            actualHash = Convert.ToHexString(await SHA256.HashDataAsync(committedInput, ct).ConfigureAwait(false)).ToLowerInvariant();
                        }
                        bool ok = actualHash == end.Sha256Hex.ToLowerInvariant() && new FileInfo(tempFiles[end.ItemIndex]).Length == item.SizeBytes;
                        if (ok)
                        {
                            string finalPath = Path.GetFullPath(Path.Combine(destinationRoot, item.RelativePath.Replace('/', Path.DirectorySeparatorChar)));
                            File.Move(tempFiles[end.ItemIndex], finalPath, overwrite: true);
                            completed.Add(item.RelativePath);
                        }
                        string? error = ok ? null : "HASH_MISMATCH";
                        await writer.WriteAsync(new TcpFrame.ItemResult(0, transferId, end.ItemIndex, ok, error), ct: ct).ConfigureAwait(false);
                        // The control-plane itemResult (04-PROTOCOL.md 6): Android's sender waits for it.
                        if (onItemResult is not null) await onItemResult(end.ItemIndex, ok, error, ct).ConfigureAwait(false);
                        if (!ok) throw new InvalidDataException($"SHA-256 verification failed for item {end.ItemIndex}.");
                        break;
                    }
                    case TcpFrame.TransferEnd end when end.Completed:
                        return new BulkTransferResult(transferId, transferred, completed);
                    case TcpFrame.Cancel cancel:
                        throw new OperationCanceledException($"Remote cancelled transfer: {cancel.Reason}", ct);
                    default:
                        throw new InvalidDataException($"Unexpected VTC3 frame: {frame.GetType().Name}");
                }
            }
        }
        finally
        {
            if (pendingWrite is not null)
            {
                try { await pendingWrite.ConfigureAwait(false); } catch { }
            }
            foreach (var output in streams.Values) await output.DisposeAsync().ConfigureAwait(false);
            foreach (var temp in tempFiles.Values) TryDelete(temp);
        }
    }

    private static async Task<TcpClient> AcceptAsync(TcpListener listener, TimeSpan timeout, CancellationToken ct)
    {
        using var accept = CancellationTokenSource.CreateLinkedTokenSource(ct);
        accept.CancelAfter(timeout);
        try { return await listener.AcceptTcpClientAsync(accept.Token).ConfigureAwait(false); }
        catch (OperationCanceledException error) when (!ct.IsCancellationRequested)
        {
            // The sender accepted the decision but never connected (unreachable host, firewall,
            // it failed on its side). Without this bound the session answered "busy" for good.
            throw new TimeoutException("VTC3 sender did not connect.", error);
        }
    }

    private static void ValidateChunkSize(int chunkSize)
    {
        if (chunkSize < 256 * 1024 || chunkSize > 4 * 1024 * 1024)
            throw new ArgumentOutOfRangeException(nameof(chunkSize), "VTC3 chunk size must be 256 KiB..4 MiB.");
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { }
    }
}