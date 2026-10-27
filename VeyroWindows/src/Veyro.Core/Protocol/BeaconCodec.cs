using System.Buffers.Binary;

namespace Veyro.Core.Protocol;

/// <summary>
/// The UDP beacon frame (04-PROTOCOL.md section 3.2). Mirrors com.veyro.core.protocol.BeaconCodec.
///
/// <code>
/// offset size  field
/// 0      4     magic   = "VEYR"
/// 4      1     version = 1
/// 5      1     type    = 0x01 QUERY | 0x02 ANNOUNCE
/// 6      2     payloadLength (u16 BE)
/// 8      n     payload = PresenceCodec bytes
/// </code>
///
/// This is the fallback that makes hotspot scenarios work: several access points and hotspot
/// drivers drop multicast while forwarding directed broadcast, and some do the reverse, which is
/// why both are sent and why mDNS alone is not enough.
///
/// The decoder parses packets anything on the network can send, so it is total: no exceptions,
/// no partial results, and the declared length is never trusted against the actual buffer size.
/// </summary>
public static class BeaconCodec
{
    public const int HeaderBytes = 8;
    public const int MaxFrameBytes = HeaderBytes + PresenceCodec.MaxEncodedBytes;

    public enum BeaconType : byte
    {
        /// <summary>"Is anyone there?" Answered with a <b>unicast</b> ANNOUNCE, never a broadcast.</summary>
        Query = 0x01,

        /// <summary>"I am here."</summary>
        Announce = 0x02
    }

    public enum BeaconRejectReason
    {
        TooShort, BadMagic, BadVersion, BadType, LengthMismatch, TooLarge, BadPayload
    }

    public abstract record BeaconResult
    {
        public sealed record Ok(BeaconType Type, PresenceRecord Record) : BeaconResult;

        /// <summary>A query carries no useful payload, so it decodes to its own case.</summary>
        public sealed record Query(PresenceRecord? Record) : BeaconResult;

        public sealed record Invalid(BeaconRejectReason Reason, string Detail) : BeaconResult;
    }

    public static byte[] Encode(BeaconType type, PresenceRecord record)
    {
        byte[] payload = PresenceCodec.EncodeToBytes(record);
        if (payload.Length > PresenceCodec.MaxEncodedBytes)
            throw new ArgumentException(
                $"payload of {payload.Length} bytes exceeds the beacon limit", nameof(record));

        byte[] o = new byte[HeaderBytes + payload.Length];
        ProtocolConstants.UdpMagic.CopyTo(o.AsSpan(0, 4));
        o[4] = (byte)ProtocolConstants.VlpVersion;
        o[5] = (byte)type;
        BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(6, 2), (ushort)payload.Length);
        payload.CopyTo(o.AsSpan(HeaderBytes));
        return o;
    }

    /// <summary>A query with no payload: the smallest legal frame.</summary>
    public static byte[] EncodeBareQuery()
    {
        byte[] o = new byte[HeaderBytes];
        ProtocolConstants.UdpMagic.CopyTo(o.AsSpan(0, 4));
        o[4] = (byte)ProtocolConstants.VlpVersion;
        o[5] = (byte)BeaconType.Query;
        return o; // length stays 0
    }

    /// <param name="bytes">the datagram buffer</param>
    /// <param name="length">
    /// how many bytes were actually received. Passed separately because a UDP receive hands back
    /// a fixed-size buffer with a separate length; using the buffer size would read uninitialised
    /// tail as payload.
    /// </param>
    public static BeaconResult Decode(byte[] bytes, int length = -1)
    {
        if (length < 0) length = bytes.Length;

        if (length < HeaderBytes)
            return new BeaconResult.Invalid(BeaconRejectReason.TooShort, $"{length} bytes");
        if (length > MaxFrameBytes)
            return new BeaconResult.Invalid(BeaconRejectReason.TooLarge, $"{length} bytes");

        // Almost every packet on a busy multicast group is something else entirely, so this is
        // the normal path, not an error worth logging.
        if (!bytes.AsSpan(0, 4).SequenceEqual(ProtocolConstants.UdpMagic))
            return new BeaconResult.Invalid(BeaconRejectReason.BadMagic, "not a Veyro beacon");

        if (bytes[4] != ProtocolConstants.VlpVersion)
            return new BeaconResult.Invalid(BeaconRejectReason.BadVersion, $"version {bytes[4]}");

        if (!Enum.IsDefined(typeof(BeaconType), bytes[5]))
            return new BeaconResult.Invalid(BeaconRejectReason.BadType, $"type {bytes[5]}");
        var type = (BeaconType)bytes[5];

        int declared = BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(6, 2));

        // Never trust a declared length against the real buffer. This is the classic parser bug,
        // and in a network-facing decoder it is a read past the end of received data.
        if (HeaderBytes + declared != length)
            return new BeaconResult.Invalid(
                BeaconRejectReason.LengthMismatch,
                $"declared {declared}, frame carries {length - HeaderBytes}");

        if (declared == 0)
        {
            // A bare query with no payload is legal: it says "announce yourselves", nothing more.
            return type == BeaconType.Query
                ? new BeaconResult.Query(null)
                : new BeaconResult.Invalid(BeaconRejectReason.BadPayload, "announce with no payload");
        }

        var decoded = PresenceCodec.DecodeFromBytes(bytes.AsSpan(HeaderBytes, declared));
        return decoded switch
        {
            PresenceResult.Ok ok => type == BeaconType.Announce
                ? new BeaconResult.Ok(type, ok.Record)
                : new BeaconResult.Query(ok.Record),
            PresenceResult.Invalid bad => new BeaconResult.Invalid(
                BeaconRejectReason.BadPayload, $"{bad.Reason}: {bad.Detail}"),
            _ => new BeaconResult.Invalid(BeaconRejectReason.BadPayload, "unknown")
        };
    }
}
