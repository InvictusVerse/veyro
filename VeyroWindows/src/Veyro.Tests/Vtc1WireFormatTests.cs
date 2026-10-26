using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Byte-layout tests for the VTC1 wire format.
///
/// These assert EXACT bytes against hand-computed expected values rather than round-tripping
/// the codec against itself. A round-trip test passes even when the layout is wrong, which is
/// precisely how the first version of this codec shipped with a missing transferId and seq in
/// the header. Any change here is a wire-format change (see 04-PROTOCOL.md).
/// </summary>
public sealed class Vtc1WireFormatTests
{
    [Fact]
    public async Task TransferEnd_ProducesExactKotlinByteLayout()
    {
        var frame = new TcpFrame.TransferEnd(Seq: 1, TransferId: "tx1", Completed: true);
        byte[] actual = await TcpFrameCodec.EncodeAsync(frame);

        // magic | type | tidLen | "tx1" | seq=1          | payLen=1 | completed
        const string expected =
            "56544331" + "07" + "0003" + "747831" + "0000000000000001" + "00000001" + "01";

        Assert.Equal(expected, Convert.ToHexString(actual).ToLowerInvariant());
        Assert.Equal(23, actual.Length);
    }

    [Fact]
    public async Task Chunk_ProducesExactKotlinByteLayout()
    {
        var frame = new TcpFrame.Chunk(
            Seq: 2, TransferId: "t", ItemIndex: 0, ChunkIndex: 1, Data: [0xAA, 0xBB]);
        byte[] actual = await TcpFrameCodec.EncodeAsync(frame);

        // magic | type | tidLen | "t" | seq=2 | payLen=14 | item=0 | chunk=1 | dataLen=2 | data
        const string expected =
            "56544331" + "04" + "0001" + "74" + "0000000000000002" + "0000000e" +
            "00000000" + "00000001" + "00000002" + "aabb";

        Assert.Equal(expected, Convert.ToHexString(actual).ToLowerInvariant());
    }

    [Fact]
    public async Task ItemBegin_PayloadIsIndexSizeChunkCountChunkSize()
    {
        var frame = new TcpFrame.ItemBegin(
            Seq: 7, TransferId: "ab", ItemIndex: 3, SizeBytes: 1024, ChunkCount: 2, ChunkSize: 512);
        byte[] actual = await TcpFrameCodec.EncodeAsync(frame);

        const string expected =
            "56544331" + "03" + "0002" + "6162" + "0000000000000007" + "00000014" +
            "00000003" + "0000000000000400" + "00000002" + "00000200";

        Assert.Equal(expected, Convert.ToHexString(actual).ToLowerInvariant());
    }

    [Fact]
    public async Task Auth_PayloadUsesU16LengthsAndRouteByte()
    {
        var frame = new TcpFrame.Auth(
            Seq: 1, TransferId: "x", AuthToken: "tok", SenderDeviceId: "dev",
            Route: TransferRoute.LanTcp);
        byte[] actual = await TcpFrameCodec.EncodeAsync(frame);

        // payload: u16 tokenLen | "tok" | u16 devIdLen | "dev" | route=0x02 (LAN_TCP)
        const string expected =
            "56544331" + "01" + "0001" + "78" + "0000000000000001" + "0000000b" +
            "0003" + "746f6b" + "0003" + "646576" + "02";

        Assert.Equal(expected, Convert.ToHexString(actual).ToLowerInvariant());
    }

    [Fact]
    public async Task Auth_WifiDirectRouteUsesByte01()
    {
        var frame = new TcpFrame.Auth(1, "x", "t", "d", TransferRoute.WifiDirect);
        byte[] actual = await TcpFrameCodec.EncodeAsync(frame);
        Assert.Equal(0x01, actual[^1]);
    }

    [Fact]
    public async Task AllFrameTypes_RoundTripPreservingSeqAndTransferId()
    {
        TcpFrame[] frames =
        [
            new TcpFrame.Auth(1, "tx-1", "token", "DEVICEID", TransferRoute.LanTcp),
            new TcpFrame.AuthAck(2, "tx-1", true),
            new TcpFrame.AuthAck(3, "tx-1", false, "bad token"),
            new TcpFrame.ItemBegin(4, "tx-1", 0, 4096, 8, 512),
            new TcpFrame.Chunk(5, "tx-1", 0, 0, Encoding.UTF8.GetBytes("hello world")),
            new TcpFrame.ItemEnd(6, "tx-1", 0, new string('a', 64)),
            new TcpFrame.ItemResult(7, "tx-1", 0, true),
            new TcpFrame.ItemResult(8, "tx-1", 1, false, "HASH_MISMATCH"),
            new TcpFrame.TransferEnd(9, "tx-1", true),
            new TcpFrame.Cancel(10, "tx-1", "user cancelled")
        ];

        using var ms = new MemoryStream();
        foreach (var f in frames)
            await TcpFrameCodec.WriteFrameAsync(ms, f, autoFlush: false);

        ms.Position = 0;
        var decoded = new List<TcpFrame>();
        while (await TcpFrameCodec.ReadFrameOrNullAsync(ms) is { } read)
            decoded.Add(read);

        Assert.Equal(frames.Length, decoded.Count);
        for (int i = 0; i < frames.Length; i++)
        {
            Assert.Equal(frames[i].Seq, decoded[i].Seq);
            Assert.Equal(frames[i].TransferId, decoded[i].TransferId);
            Assert.Equal(frames[i].GetType(), decoded[i].GetType());
        }

        Assert.Equal("hello world", Encoding.UTF8.GetString(((TcpFrame.Chunk)decoded[4]).Data));
        Assert.Equal("bad token", ((TcpFrame.AuthAck)decoded[2]).Reason);
        Assert.Null(((TcpFrame.AuthAck)decoded[1]).Reason);
        Assert.Equal("HASH_MISMATCH", ((TcpFrame.ItemResult)decoded[7]).ErrorMessage);
        Assert.Equal("user cancelled", ((TcpFrame.Cancel)decoded[9]).Reason);
    }

    [Fact]
    public async Task CleanEofAtFrameBoundary_ReturnsNull()
    {
        using var ms = new MemoryStream();
        Assert.Null(await TcpFrameCodec.ReadFrameOrNullAsync(ms));
    }

    [Fact]
    public async Task TruncatedMidFrame_ThrowsEndOfStream()
    {
        byte[] full = await TcpFrameCodec.EncodeAsync(new TcpFrame.TransferEnd(1, "tx", true));
        using var ms = new MemoryStream(full[..(full.Length - 1)]);
        await Assert.ThrowsAsync<EndOfStreamException>(
            async () => await TcpFrameCodec.ReadFrameOrNullAsync(ms));
    }

    [Fact]
    public async Task BadMagic_IsRejected()
    {
        byte[] full = await TcpFrameCodec.EncodeAsync(new TcpFrame.TransferEnd(1, "tx", true));
        full[0] = 0x00;
        using var ms = new MemoryStream(full);
        await Assert.ThrowsAsync<InvalidDataException>(
            async () => await TcpFrameCodec.ReadFrameOrNullAsync(ms));
    }

    [Fact]
    public async Task EmptyTransferId_IsRejected()
    {
        await Assert.ThrowsAsync<ArgumentException>(
            async () => await TcpFrameCodec.EncodeAsync(new TcpFrame.TransferEnd(1, "", true)));
    }

    [Fact]
    public async Task OversizedTransferId_IsRejected()
    {
        string tooLong = new('a', TcpFrameCodec.MaxTransferIdLength + 1);
        await Assert.ThrowsAsync<ArgumentException>(
            async () => await TcpFrameCodec.EncodeAsync(new TcpFrame.TransferEnd(1, tooLong, true)));
    }

    [Fact]
    public async Task ChunkDoesNotAutoFlushByDefault_ControlFramesDo()
    {
        var counting = new FlushCountingStream();
        await TcpFrameCodec.WriteFrameAsync(counting, new TcpFrame.Chunk(1, "t", 0, 0, [1, 2, 3]));
        Assert.Equal(0, counting.Flushes);

        await TcpFrameCodec.WriteFrameAsync(counting, new TcpFrame.TransferEnd(2, "t", true));
        Assert.Equal(1, counting.Flushes);
    }

    private sealed class FlushCountingStream : MemoryStream
    {
        public int Flushes { get; private set; }

        public override Task FlushAsync(CancellationToken cancellationToken)
        {
            Flushes++;
            return base.FlushAsync(cancellationToken);
        }
    }
}
