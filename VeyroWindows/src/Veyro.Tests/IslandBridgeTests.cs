using System.Text;
using Veyro.Core.Handoff;
using Xunit;

namespace Veyro.Tests;

/// <summary>ADR-076: the Islune island bridge text format (VIB1). Islune parses these exact bytes.</summary>
public sealed class IslandBridgeTests
{
    private static byte[] Bytes(string text) => Encoding.UTF8.GetBytes(text);

    [Fact]
    public void Requests_round_trip_and_match_the_documented_bytes()
    {
        var status = new IslandRequest(IslandOp.Status, null, null, []);
        Assert.Equal("VIB1 status *", Encoding.UTF8.GetString(IslandBridgeCodec.EncodeRequest(status)));
        Assert.Equal(IslandOp.Status, IslandBridgeCodec.DecodeRequest(Bytes("VIB1 status *")).Op);

        var text = IslandBridgeCodec.DecodeRequest(Bytes("VIB1 text AAAA-1111\nhello\nworld"));
        Assert.Equal(IslandOp.Text, text.Op);
        Assert.Equal("AAAA-1111", text.DeviceId);
        Assert.Equal("hello\nworld", text.Text);

        var files = IslandBridgeCodec.DecodeRequest(Bytes("VIB1 files *\nC:\\a b.txt\n\\\\server\\share\\c.jpg"));
        Assert.Null(files.DeviceId);
        Assert.Equal([@"C:\a b.txt", @"\\server\share\c.jpg"], files.Paths);
        Assert.Equal("VIB1 files *\nC:\\a b.txt\n\\\\server\\share\\c.jpg",
            Encoding.UTF8.GetString(IslandBridgeCodec.EncodeRequest(files)));
    }

    [Theory]
    [InlineData("")]
    [InlineData("VIB2 status *")]
    [InlineData("VIB1 status")]
    [InlineData("VIB1 status *\nextra")]
    [InlineData("VIB1 delete *")]
    [InlineData("VIB1 text *")]
    [InlineData("VIB1 text *\n")]
    [InlineData("VIB1 files *\nrelative.txt")]
    [InlineData("VIB1 files *\nC:\\ok.txt\n")]
    [InlineData("VIB1 files bad/id\nC:\\ok.txt")]
    public void Malformed_requests_are_refused(string payload)
    {
        Assert.Throws<FormatException>(() => IslandBridgeCodec.DecodeRequest(Bytes(payload)));
    }

    [Fact]
    public void Invalid_utf8_is_refused()
    {
        Assert.Throws<FormatException>(() => IslandBridgeCodec.DecodeRequest([0x56, 0x49, 0x42, 0x31, 0x20, 0xFF]));
    }

    [Fact]
    public void Status_carries_devices_and_transfer_without_control_characters()
    {
        var status = new IslandStatus(
            [new IslandDevice("AAAA-1111", "Redmi\tK50i\n", true), new IslandDevice("BBBB-2222", "Tab", false)],
            new IslandTransfer(false, "Transferring", 425, "Redmi"));
        string wire = Encoding.UTF8.GetString(IslandBridgeCodec.EncodeStatus(status));
        Assert.Equal("VIB1 status\ndevice\tAAAA-1111\t1\tRedmi K50i\ndevice\tBBBB-2222\t0\tTab\ntransfer\tout\tTransferring\t425\tRedmi\n", wire);

        IslandStatus back = IslandBridgeCodec.DecodeStatus(Bytes(wire));
        Assert.Equal(2, back.Devices.Count);
        Assert.True(back.Devices[0].Connected);
        Assert.Equal(425, back.Transfer!.Permille);

        Assert.Empty(IslandBridgeCodec.DecodeStatus(Bytes("VIB1 status\n")).Devices);
        Assert.Throws<FormatException>(() => IslandBridgeCodec.DecodeStatus(Bytes("VIB1 status\ndevice\tx\t2\tA\n")));
    }

    [Fact]
    public void Results_are_one_bounded_line()
    {
        var result = IslandBridgeCodec.DecodeResult(IslandBridgeCodec.EncodeResult(new IslandResult(false, "No phone\nconnected" + new string('x', 400))));
        Assert.False(result.Ok);
        Assert.StartsWith("No phone connected", result.Message);
        Assert.True(result.Message.Length <= IslandBridgeCodec.MaxMessageChars);
        Assert.True(IslandBridgeCodec.DecodeResult(Bytes("VIB1 result ok\nSent.")).Ok);
        Assert.Throws<FormatException>(() => IslandBridgeCodec.DecodeResult(Bytes("VIB1 result maybe")));
    }
}
