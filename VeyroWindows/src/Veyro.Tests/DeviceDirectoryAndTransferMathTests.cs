using Veyro.Core.Devices;
using Veyro.Core.Models;
using Veyro.Core.Transfer;
using Xunit;

namespace Veyro.Tests;

/// <summary>Sidebar device switcher rules and transfer banner math (Windows UI real-data pass).</summary>
public sealed class DeviceDirectoryAndTransferMathTests
{
    private static readonly DateTimeOffset T0 = new(2026, 10, 1, 12, 0, 0, TimeSpan.Zero);

    private static TrustedPeer Peer(string id, string name, int minutes)
        => new(id, name, PlatformType.Android, "AAAA", "BBBB", false, T0.AddMinutes(minutes));

    private static IReadOnlyList<PairedDeviceEntry> Devices(params (string Id, bool Connected)[] items)
        => items.Select(item => new PairedDeviceEntry(item.Id, item.Id, PlatformType.Android, "AAAA", item.Connected)).ToList();

    [Fact]
    public void Build_UsesTrustOrder_DeduplicatesAndReadsLiveConnection()
    {
        var trusted = new[] { Peer("B", "Pixel", 2), Peer("A", "Galaxy", 1), Peer("b", "Pixel renamed", 3) };

        var list = PairedDeviceDirectory.Build(trusted, id => id == "A");

        Assert.Equal(["A", "b"], list.Select(d => d.DeviceId));
        Assert.True(list[0].IsConnected);
        Assert.False(list[1].IsConnected);
        Assert.Equal("Pixel renamed", list[1].DisplayName);
    }

    [Fact]
    public void Build_NamesBlankDevicesWithoutInventingAModel()
    {
        var list = PairedDeviceDirectory.Build([Peer("A", "  ", 0)], _ => false);
        Assert.Equal(PairedDeviceDirectory.UnnamedDevice, list[0].DisplayName);
    }

    [Fact]
    public void Build_NoTrustedPeers_IsEmpty()
        => Assert.Empty(PairedDeviceDirectory.Build([], _ => true));

    [Fact]
    public void SelectAfterReload_KeepsCurrent_ThenPrefersConnected_ThenFirst()
    {
        var devices = Devices(("A", false), ("B", true));
        Assert.Equal("A", PairedDeviceDirectory.SelectAfterReload(devices, "a"));
        Assert.Equal("B", PairedDeviceDirectory.SelectAfterReload(devices, "gone"));
        Assert.Equal("A", PairedDeviceDirectory.SelectAfterReload(Devices(("A", false), ("C", false)), null));
        Assert.Null(PairedDeviceDirectory.SelectAfterReload([], "A"));
    }

    [Fact]
    public void SelectAfterSessionOpened_FollowsConnectingPhoneOnlyWhenShownPhoneIsOffline()
    {
        Assert.Equal("B", PairedDeviceDirectory.SelectAfterSessionOpened(Devices(("A", false), ("B", true)), "A", "B"));
        Assert.Equal("A", PairedDeviceDirectory.SelectAfterSessionOpened(Devices(("A", true), ("B", true)), "A", "B"));
        Assert.Equal("B", PairedDeviceDirectory.SelectAfterSessionOpened(Devices(("A", false), ("B", true)), null, "B"));
        // An unknown peer (not in the trust list) never becomes the shown device.
        Assert.Equal("A", PairedDeviceDirectory.SelectAfterSessionOpened(Devices(("A", false)), "A", "X"));
    }

    [Fact]
    public void Step_CyclesBothWays()
    {
        var devices = Devices(("A", false), ("B", false), ("C", false));
        Assert.Equal("B", PairedDeviceDirectory.Step(devices, "A", -120));
        Assert.Equal("C", PairedDeviceDirectory.Step(devices, "A", 120));
        Assert.Equal("A", PairedDeviceDirectory.Step(devices, "C", -120));
        Assert.Equal("A", PairedDeviceDirectory.Step(devices, "missing", 120));
        Assert.Null(PairedDeviceDirectory.Step([], "A", 120));
    }

    [Fact]
    public void AverageRate_IsUnknownUntilMeasurable()
    {
        Assert.Null(TransferProgressMath.AverageBytesPerSecond(0, TimeSpan.FromSeconds(10)));
        Assert.Null(TransferProgressMath.AverageBytesPerSecond(1000, TimeSpan.FromMilliseconds(100)));
        Assert.Equal(500, TransferProgressMath.AverageBytesPerSecond(1000, TimeSpan.FromSeconds(2)));
    }

    [Fact]
    public void EstimateRemaining_NeedsARateAndATotal()
    {
        Assert.Null(TransferProgressMath.EstimateRemaining(10, 100, null));
        Assert.Null(TransferProgressMath.EstimateRemaining(10, 0, 50));
        Assert.Equal(TimeSpan.FromSeconds(2), TransferProgressMath.EstimateRemaining(0, 100, 50));
        Assert.Equal(TimeSpan.Zero, TransferProgressMath.EstimateRemaining(200, 100, 50));
    }

    [Theory]
    [InlineData(0, "0 B")]
    [InlineData(512, "512 B")]
    [InlineData(1536, "1.5 KB")]
    [InlineData(12_900_000, "12.3 MB")]
    [InlineData(157_286_400, "150 MB")]
    [InlineData(-5, "0 B")]
    public void FormatBytes_IsCompact(long bytes, string expected)
        => Assert.Equal(expected, TransferProgressMath.FormatBytes(bytes));

    [Fact]
    public void FormatDuration_PicksTheLargestUnit()
    {
        Assert.Equal("45 s", TransferProgressMath.FormatDuration(TimeSpan.FromSeconds(45)));
        Assert.Equal("3 min 05 s", TransferProgressMath.FormatDuration(TimeSpan.FromSeconds(185)));
        Assert.Equal("1 h 02 min", TransferProgressMath.FormatDuration(TimeSpan.FromMinutes(62)));
    }
}
