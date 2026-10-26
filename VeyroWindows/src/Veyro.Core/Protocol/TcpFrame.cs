using System.Buffers.Binary;
using System.Text;
using Veyro.Core.Models;

namespace Veyro.Core.Protocol;

/// <summary>
/// VTC1 raw TCP data-plane frames. Mirrors com.veyro.core.protocol.tcp.TcpFrame (ADR-022).
///
/// Every frame carries a monotonic sequence number and the owning transfer id.
/// </summary>
public abstract record TcpFrame(long Seq, string TransferId)
{
    public sealed record Auth(
        long Seq, string TransferId, string AuthToken, string SenderDeviceId, TransferRoute Route)
        : TcpFrame(Seq, TransferId);

    public sealed record AuthAck(
        long Seq, string TransferId, bool Ok, string? Reason = null)
        : TcpFrame(Seq, TransferId);

    public sealed record ItemBegin(
        long Seq, string TransferId, int ItemIndex, long SizeBytes, int ChunkCount, int ChunkSize)
        : TcpFrame(Seq, TransferId);

    public sealed record Chunk(
        long Seq, string TransferId, int ItemIndex, int ChunkIndex, byte[] Data)
        : TcpFrame(Seq, TransferId)
    {
        public bool Equals(Chunk? other)
            => other is not null
               && Seq == other.Seq
               && TransferId == other.TransferId
               && ItemIndex == other.ItemIndex
               && ChunkIndex == other.ChunkIndex
               && Data.AsSpan().SequenceEqual(other.Data);

        public override int GetHashCode()
            => HashCode.Combine(Seq, TransferId, ItemIndex, ChunkIndex, Data.Length);
    }

    public sealed record ItemEnd(
        long Seq, string TransferId, int ItemIndex, string Sha256Hex)
        : TcpFrame(Seq, TransferId);

    public sealed record ItemResult(
        long Seq, string TransferId, int ItemIndex, bool Ok, string? ErrorMessage = null)
        : TcpFrame(Seq, TransferId);

    public sealed record TransferEnd(
        long Seq, string TransferId, bool Completed)
        : TcpFrame(Seq, TransferId);

    public sealed record Cancel(
        long Seq, string TransferId, string Reason)
        : TcpFrame(Seq, TransferId);
}
