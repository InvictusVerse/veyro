using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Link;
using Veyro.Core.Status;
using Xunit;

namespace Veyro.Tests;

/// <summary>Drives protocol/testvectors/link-status-wallpaper.json (ADR-038).</summary>
public sealed class StatusWallpaperVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-status-wallpaper.json")));
    }

    public static TheoryData<int> VectorIndices()
    {
        var data = new TheoryData<int>();
        using var doc = LoadVectors();
        int count = doc.RootElement.GetProperty("vectors").GetArrayLength();
        for (int i = 0; i < count; i++) data.Add(i);
        return data;
    }

    [Theory]
    [MemberData(nameof(VectorIndices))]
    public void Vector_DecodesToExpectedTypeAndReEncodesEquivalently(int index)
    {
        using var doc = LoadVectors();
        JsonElement vector = doc.RootElement.GetProperty("vectors")[index];
        string type = vector.GetProperty("type").GetString()!;
        string json = vector.GetProperty("json").GetString()!;

        LinkMessage? decoded = LinkJson.Decode<LinkMessage>(json);
        Type expected = type switch
        {
            "devStatus" => typeof(LinkMessage.DeviceStatus),
            "pcLock" => typeof(LinkMessage.PcLock),
            "wallpaper" => typeof(LinkMessage.Wallpaper),
            _ => throw new InvalidOperationException($"Unexpected vector type {type}")
        };
        Assert.NotNull(decoded);
        Assert.IsType(expected, decoded);

        string reEncoded = LinkJson.Encode(decoded);
        Assert.True(
            JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)),
            $"Re-encoded JSON differs for vector {index}: {reEncoded}");
        Assert.Equal(decoded, LinkJson.Decode<LinkMessage>(reEncoded));
    }

    [Fact]
    public void FullDeviceStatusVector_CarriesEveryRadioField()
    {
        using var doc = LoadVectors();
        string json = doc.RootElement.GetProperty("vectors")[0].GetProperty("json").GetString()!;
        var status = Assert.IsType<LinkMessage.DeviceStatus>(LinkJson.Decode<LinkMessage>(json));
        Assert.Equal(new LinkMessage.DeviceStatus(57, false, "normal", false, 3, 2, "Carrier A", true, false), status);
    }
}

public sealed class WallpaperEncodingTests
{
    /// <summary>A stand-in JPEG encoder whose output grows with pixels and quality.</summary>
    private static Func<int, int, int, CancellationToken, Task<byte[]>> FakeEncoder(
        double bytesPerPixelAtFullQuality, List<(int W, int H, int Q)> calls)
        => (w, h, q, _) =>
        {
            calls.Add((w, h, q));
            // Capped at one byte over the limit: the encoder only compares the length, and the
            // uncapped size (hundreds of MB) ran this machine out of memory.
            long size = Math.Min((long)(w * h * bytesPerPixelAtFullQuality * q / 100.0), WallpaperEncoding.MaxBytes + 1L);
            byte[] jpeg = new byte[Math.Max(size, 3)];
            jpeg[0] = 0xFF;
            jpeg[1] = 0xD8;
            jpeg[2] = 0xFF;
            return Task.FromResult(jpeg);
        };

    [Fact]
    public void FitWithin_KeepsAspectAndCapsTheLongestSide()
    {
        Assert.Equal((720, 405), WallpaperEncoding.FitWithin(3840, 2160, 720));
        Assert.Equal((405, 720), WallpaperEncoding.FitWithin(2160, 3840, 720));
        Assert.Equal((640, 480), WallpaperEncoding.FitWithin(640, 480, 720));
        Assert.Throws<ArgumentOutOfRangeException>(() => WallpaperEncoding.FitWithin(0, 10, 720));
    }

    [Fact]
    public async Task Encoder_LowersQualityUntilTheResultFits()
    {
        var calls = new List<(int W, int H, int Q)>();
        // 720x405 at q85 = ~99 KB, q75 = ~87 KB: the second attempt fits.
        byte[]? jpeg = await WallpaperEncoding.EncodeUnderCapAsync(3840, 2160, FakeEncoder(0.4, calls));

        Assert.NotNull(jpeg);
        Assert.True(jpeg!.Length <= WallpaperEncoding.MaxBytes);
        Assert.Equal([(720, 405, 85), (720, 405, 75)], calls);
    }

    [Fact]
    public async Task Encoder_ShrinksWhenNoQualityFits_AndNeverExceedsTheCaps()
    {
        var calls = new List<(int W, int H, int Q)>();
        byte[]? jpeg = await WallpaperEncoding.EncodeUnderCapAsync(3840, 2160, FakeEncoder(2.0, calls));

        Assert.NotNull(jpeg);
        Assert.True(jpeg!.Length <= WallpaperEncoding.MaxBytes);
        Assert.All(calls, call => Assert.True(Math.Max(call.W, call.H) <= WallpaperEncoding.MaxSide));
        Assert.True(calls[^1].W < 720);
    }

    [Fact]
    public async Task Encoder_GivesUpBelowTheMinimumSide()
    {
        var calls = new List<(int W, int H, int Q)>();
        Assert.Null(await WallpaperEncoding.EncodeUnderCapAsync(1920, 1080, FakeEncoder(1000.0, calls)));
        Assert.Equal(WallpaperEncoding.MinSide, Math.Max(calls[^1].W, calls[^1].H));
    }

    [Fact]
    public void DecodeIncoming_AcceptsOnlySmallJpegs()
    {
        Assert.NotNull(WallpaperEncoding.DecodeIncoming("/9j/4AAQSkZJRg=="));
        Assert.Null(WallpaperEncoding.DecodeIncoming(null));
        Assert.Null(WallpaperEncoding.DecodeIncoming("not base64!"));
        Assert.Null(WallpaperEncoding.DecodeIncoming(Convert.ToBase64String("PNG..."u8.ToArray())));
        byte[] big = new byte[WallpaperEncoding.MaxBytes + 1];
        big[0] = 0xFF; big[1] = 0xD8; big[2] = 0xFF;
        Assert.Null(WallpaperEncoding.DecodeIncoming(Convert.ToBase64String(big)));
    }
}

internal sealed class FakeWallpaperSource : IPcWallpaperSource
{
    public byte[]? Jpeg { get; set; } = [0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3];
    public int Reads;
    public event Action? Changed;

    public Task<byte[]?> ReadJpegAsync(CancellationToken ct = default)
    {
        Reads++;
        return Task.FromResult(Jpeg);
    }

    public void Change(byte[]? jpeg)
    {
        Jpeg = jpeg;
        Changed?.Invoke();
    }
}

public sealed class WallpaperServiceTests
{
    private const string Phone = "PHONE";

    [Fact]
    public void PcWallpaper_SentOnConnectToCapablePeers_EncodedOnce_AndResentOnChange()
    {
        var sessions = new FakePeerSessions();
        var source = new FakeWallpaperSource();
        using var service = new WallpaperService(sessions, source);

        sessions.Connect(Phone, LinkCapabilities.Wallpaper);
        sessions.Connect("OTHER", LinkCapabilities.Wallpaper | LinkCapabilities.DeviceStatus);
        sessions.Connect("LEGACY", LinkCapabilities.DeviceStatus);

        Assert.Equal(Convert.ToBase64String(source.Jpeg!), Assert.Single(sessions.SentOf<LinkMessage.Wallpaper>(Phone)).Jpeg);
        Assert.Single(sessions.SentOf<LinkMessage.Wallpaper>("OTHER"));
        Assert.Empty(sessions.SentOf<LinkMessage.Wallpaper>("LEGACY"));
        Assert.Equal(1, source.Reads);

        source.Change(null); // wallpaper removed: "none available"
        Assert.Null(sessions.SentOf<LinkMessage.Wallpaper>(Phone)[^1].Jpeg);
        Assert.Equal(2, sessions.SentOf<LinkMessage.Wallpaper>(Phone).Count);
    }

    [Fact]
    public void PhoneWallpaper_IsValidated_PerPeer_AndClearedOnClose()
    {
        var sessions = new FakePeerSessions();
        using var service = new WallpaperService(sessions, null);
        var changed = new List<string>();
        service.Changed += changed.Add;
        sessions.Connect(Phone, LinkCapabilities.Wallpaper);

        sessions.Receive(Phone, new LinkMessage.Wallpaper("/9j/4AAQSkZJRg=="));
        Assert.Equal(0xFF, service.GetPhoneWallpaper(Phone)![0]);

        sessions.Receive(Phone, new LinkMessage.Wallpaper("garbage"));
        Assert.Null(service.GetPhoneWallpaper(Phone));

        sessions.Receive(Phone, new LinkMessage.Wallpaper("/9j/4AAQSkZJRg=="));
        sessions.Disconnect(Phone);
        Assert.Null(service.GetPhoneWallpaper(Phone));
        Assert.Equal([Phone, Phone, Phone, Phone], changed);
        Assert.Empty(sessions.Sent); // no source: the PC sends nothing
    }
}

public sealed class ExtendedDeviceStatusTests
{
    private const string Phone = "PHONE";

    private sealed class FakeNetwork : IPcNetworkSource
    {
        public int? WifiLevel { get; private set; }
        public event Action? Changed;

        public void Set(int? level)
        {
            WifiLevel = level;
            Changed?.Invoke();
        }
    }

    [Fact]
    public void PhoneRadioFields_AreKept_AndOutOfRangeValuesDropped()
    {
        var sessions = new FakePeerSessions();
        using var service = new DeviceStatusService(sessions, null);
        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);

        sessions.ReceiveDeviceStatus(Phone, new LinkMessage.DeviceStatus(57, false, "normal", false, 3, 2, "  Carrier A ", true, false));
        Assert.Equal(new LinkMessage.DeviceStatus(57, false, "normal", false, 3, 2, "Carrier A", true, false), service.GetPhoneStatus(Phone));

        sessions.ReceiveDeviceStatus(Phone, new LinkMessage.DeviceStatus(Wifi: -1, Cell: -1));
        Assert.Equal(new LinkMessage.DeviceStatus(Wifi: -1, Cell: -1), service.GetPhoneStatus(Phone));

        sessions.ReceiveDeviceStatus(Phone, new LinkMessage.DeviceStatus(Wifi: 9, Cell: -2, Carrier: new string('x', 200)));
        LinkMessage.DeviceStatus sanitized = service.GetPhoneStatus(Phone)!;
        Assert.Null(sanitized.Wifi);
        Assert.Null(sanitized.Cell);
        Assert.Equal(DeviceStatusService.MaxCarrierLength, sanitized.Carrier!.Length);
    }

    [Fact]
    public void PcWifi_IsPublished_WithOrWithoutABattery()
    {
        var sessions = new FakePeerSessions();
        var network = new FakeNetwork();
        network.Set(3);
        using var service = new DeviceStatusService(sessions, null, network);

        sessions.Connect(Phone, LinkCapabilities.DeviceStatus);
        network.Set(3); // unchanged
        network.Set(4);
        network.Set(7); // out of range: unknown, nothing left to send

        Assert.Equal(
            [new LinkMessage.DeviceStatus(Wifi: 3), new LinkMessage.DeviceStatus(Wifi: 4)],
            sessions.SentOf<LinkMessage.DeviceStatus>(Phone));
    }
}
