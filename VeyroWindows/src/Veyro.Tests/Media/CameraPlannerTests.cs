using Veyro.Core.Link;
using Veyro.Core.Mirroring;
using Xunit;

namespace Veyro.Tests.Media;

/// <summary>The PC's camera choices: only what the phone offers (ADR-067).</summary>
public sealed class CameraPlannerTests
{
    private static readonly PeerMessage.StreamCamera Back = new("0", "back",
        [new(3840, 2160, 30), new(1920, 1080, 60), new(1280, 720, 60), new(640, 480, 30)]);
    private static readonly PeerMessage.StreamCamera Front = new("1", "front", [new(1920, 1080, 30)]);

    private static PeerMessage.StreamCaps Caps(params PeerMessage.StreamEncoder[] encoders) =>
        new(false, null, encoders, false, null, true, [Front, Back]);

    private static readonly PcDecoder[] Decoders = [new("h264", true, 4096, 4096), new("h265", true, 8192, 8192)];

    [Fact]
    public void Choose_PrefersTheSavedCamera_ThenBack()
    {
        Assert.Equal("1", CameraPlanner.Choose(Caps(), "1")!.Id);
        Assert.Equal("0", CameraPlanner.Choose(Caps(), "gone")!.Id);
        Assert.Equal("0", CameraPlanner.Choose(Caps(), null)!.Id);
        Assert.Null(CameraPlanner.Choose(new PeerMessage.StreamCaps(), null));
    }

    [Fact]
    public void ChooseSize_IsExact_OrTheLargestNotBigger_OrTheSmallest()
    {
        Assert.Equal(new PeerMessage.StreamSize(1280, 720, 60), CameraPlanner.ChooseSize(Back, null, null));
        Assert.Equal(new PeerMessage.StreamSize(3840, 2160, 30), CameraPlanner.ChooseSize(Back, 3840, 2160));
        Assert.Equal(new PeerMessage.StreamSize(1920, 1080, 30), CameraPlanner.ChooseSize(Front, 3840, 2160));
        Assert.Equal(new PeerMessage.StreamSize(1920, 1080, 30), CameraPlanner.ChooseSize(Front, 320, 240));
    }

    [Fact]
    public void FrameRates_Offer60_OnlyWhereTheCameraReachesIt()
    {
        Assert.Equal([30, 60], CameraPlanner.FrameRates(new(1920, 1080, 60)));
        Assert.Equal([30], CameraPlanner.FrameRates(new(3840, 2160, 30)));
        Assert.Equal([24], CameraPlanner.FrameRates(new(3840, 2160, 24)));
        Assert.Equal("1920 × 1080 (1080p)", CameraPlanner.Label(new(1920, 1080, 60)));
    }

    [Fact]
    public void Plan_UsesHevcFor4K_WhenBothSidesHaveIt_AndKeepsTheSize()
    {
        var caps = Caps(new PeerMessage.StreamEncoder("h264", true, 4096, 2304, 60), new PeerMessage.StreamEncoder("h265", true, 4096, 2304, 60));
        var four = CameraPlanner.Plan(caps, new(3840, 2160, 30), 60, Decoders, hardwareEncode: true)!;
        Assert.Equal(("h265", 3840, 2160, 30, 3840), (four.Codec, four.Width, four.Height, four.Fps, four.MaxLong));
        var hd = CameraPlanner.Plan(caps, new(1280, 720, 60), 60, Decoders, hardwareEncode: true)!;
        Assert.Equal(("h264", 60), (hd.Codec, hd.Fps));
        Assert.Equal((int)(1280 * 720 * 60 * 0.10), hd.Bitrate);

        var onlyAvc = Caps(new PeerMessage.StreamEncoder("h264", true, 4096, 2304, 60));
        Assert.Equal("h264", CameraPlanner.Plan(onlyAvc, new(3840, 2160, 30), 30, Decoders, true)!.Codec);
        Assert.Null(CameraPlanner.Plan(Caps(new PeerMessage.StreamEncoder("h264", true, 1920, 1088, 60)), new(3840, 2160, 30), 30, Decoders, true));
    }

    [Fact]
    public void Clean_DropsBadCameras_AndRejectsBadSizes()
    {
        var caps = new PeerMessage.StreamCaps(false, null, [], false, null, true,
            [new("0", "back", [new(1280, 720, 30)]), new("../1", "back", [new(1280, 720, 30)]), new("2", "side", [new(1280, 720, 30)])]);
        Assert.Equal(["0"], StreamRules.Clean(caps)!.Cameras!.Select(c => c.Id));
        Assert.Empty(StreamRules.Clean(caps with { CameraAllowed = false })!.Cameras!);
        Assert.Null(StreamRules.Clean(caps with { Cameras = [new("0", "back", [new(9000, 720, 30)])] }));
    }

    [Fact]
    public void CameraSession_UsesCameraWords_AndStartsWithTheCameraFields()
    {
        var session = new MirrorSession("Redmi", camera: true);
        var caps = Caps(new PeerMessage.StreamEncoder("h264", true, 4096, 2304, 60));
        var plan = CameraPlanner.Plan(caps, new(1920, 1080, 60), 30, Decoders, true);
        string sid = StreamRules.NewSid();
        var start = session.BeginCamera(sid, caps, plan, "0", "192.168.31.191", 5151)!;
        Assert.Equal(("camera", "0", 1920, 1080, false), (start.Source, start.Camera, start.W!.Value, start.H!.Value, start.Audio));
        session.OnState(new PeerMessage.StreamState(sid, "asking"));
        Assert.Equal("Allow the camera on Redmi", session.Status.Title);
        session.OnStop(new PeerMessage.StreamStop(sid, "declined"));
        Assert.Equal("Camera use was declined on Redmi", session.Status.Title);
        Assert.Equal("Ask again", session.Status.AskAgainLabel);

        var off = new MirrorSession("Redmi", camera: true);
        Assert.Null(off.BeginCamera(sid, caps with { CameraAllowed = false }, plan, "0", "10.0.0.1", 5151));
        Assert.Contains("Let PCs use this phone's camera", off.Status.Detail);
    }
}
