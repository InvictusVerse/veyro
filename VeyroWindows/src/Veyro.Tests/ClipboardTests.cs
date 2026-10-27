using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Clipboard;
using Veyro.Core.Link;
using Xunit;

namespace Veyro.Tests;

/// <summary>Drives protocol/testvectors/link-clipboard.json (ADR-043). The Android suite asserts the same file.</summary>
public sealed class ClipboardVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-clipboard.json")));
    }

    [Fact]
    public void EveryVector_DecodesToClipAndReEncodesEquivalently()
    {
        using var doc = LoadVectors();
        var vectors = doc.RootElement.GetProperty("vectors").EnumerateArray().ToList();
        Assert.Equal(3, vectors.Count);
        foreach (JsonElement vector in vectors)
        {
            string json = vector.GetProperty("json").GetString()!;
            var clip = Assert.IsType<LinkMessage.Clip>(LinkJson.Decode<LinkMessage>(json));
            string reEncoded = LinkJson.Encode<LinkMessage>(clip);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)), $"Re-encoded JSON differs: {reEncoded}");
        }
    }

    [Fact]
    public void Vectors_CarryTheExpectedValues()
    {
        using var doc = LoadVectors();
        var clips = doc.RootElement.GetProperty("vectors").EnumerateArray()
            .Select(v => Assert.IsType<LinkMessage.Clip>(LinkJson.Decode<LinkMessage>(v.GetProperty("json").GetString()!)))
            .ToList();
        Assert.Equal(new LinkMessage.Clip("c-1", "text", Text: "https://youtu.be/dQw4w9WgXcQ", Apply: true, Ts: 1793011200000), clips[0]);
        Assert.False(clips[1].Apply);
        Assert.Equal("image", clips[2].Kind);
        Assert.Equal("iVBORw0KGgo=", clips[2].Image);
        Assert.Null(clips[2].Text);
    }

    [Fact]
    public void ThePcAdvertisesClipboardAt0x0008()
    {
        Assert.Equal(0x0008, LinkCapabilities.Clipboard);
    }
}

public sealed class ClipboardServiceTests
{
    private const string Phone = "PHONE";
    private static readonly byte[] Png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];

    private sealed class FakeSink : IClipboardSink
    {
        public List<string> Texts { get; } = [];
        public List<byte[]> Images { get; } = [];
        public Task SetTextAsync(string text) { Texts.Add(text); return Task.CompletedTask; }
        public Task SetImageAsync(byte[] image) { Images.Add(image); return Task.CompletedTask; }
    }

    private sealed class FakeNotifier : IClipboardNotifier
    {
        public List<ClipItem> Applied { get; } = [];
        public List<ClipItem> Stored { get; } = [];
        public void ShowApplied(string peerId, ClipItem item) => Applied.Add(item);
        public void ShowStored(string peerId, ClipItem item) => Stored.Add(item);
    }

    private sealed class Rig
    {
        public FakePeerSessions Sessions { get; } = new();
        public FakeSink Sink { get; } = new();
        public FakeNotifier Notifier { get; } = new();
        public bool SendEnabled { get; set; } = true;
        public bool ImagesEnabled { get; set; }
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UnixEpoch;
        public ClipboardService Service { get; }

        public Rig()
        {
            Service = new ClipboardService(Sessions, () => SendEnabled, () => ImagesEnabled, () => Now)
            {
                Sink = Sink,
                Notifier = Notifier,
            };
            Sessions.Connect(Phone, LinkCapabilities.Clipboard);
        }

        public List<LinkMessage.Clip> SentClips => Sessions.SentOf<LinkMessage.Clip>(Phone);
    }

    [Fact]
    public async Task SharedText_IsPutOnTheClipboard_StoredAndToasted()
    {
        var rig = new Rig();
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip("1", "text", Text: "https://youtu.be/x", Apply: true));

        Assert.Equal(["https://youtu.be/x"], rig.Sink.Texts);
        Assert.Single(rig.Notifier.Applied);
        Assert.Empty(rig.Notifier.Stored);
        Assert.Equal(new Uri("https://youtu.be/x"), rig.Service.Items.Single().Link);
    }

    [Fact]
    public async Task SentClipboard_IsStoredOnly()
    {
        var rig = new Rig();
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip("1", "text", Text: "note", Apply: false));

        Assert.Empty(rig.Sink.Texts);
        Assert.Single(rig.Notifier.Stored);
        Assert.Equal("note", rig.Service.Items.Single().Text);
    }

    [Fact]
    public async Task AppliedText_IsNotEchoedBack_ButANewCopyIs()
    {
        var rig = new Rig();
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip("1", "text", Text: "a\nb", Apply: true));
        // Windows may hand the same text back with CRLF line endings.
        await rig.Service.OnLocalTextCopiedAsync("a\r\nb");
        Assert.Empty(rig.SentClips);

        await rig.Service.OnLocalTextCopiedAsync("something else");
        var sent = Assert.Single(rig.SentClips);
        Assert.False(sent.Apply);
        Assert.Equal("text", sent.Kind);
    }

    [Fact]
    public async Task DuplicateChangeEvents_SendOnce_ButALaterCopySendsAgain()
    {
        var rig = new Rig();
        await rig.Service.OnLocalTextCopiedAsync("hello");
        await rig.Service.OnLocalTextCopiedAsync("hello");
        Assert.Single(rig.SentClips);

        rig.Now += TimeSpan.FromSeconds(5);
        await rig.Service.OnLocalTextCopiedAsync("hello");
        Assert.Equal(2, rig.SentClips.Count);
    }

    [Fact]
    public async Task Sending_RespectsTheSettings_AndTheCapability()
    {
        var rig = new Rig { SendEnabled = false };
        await rig.Service.OnLocalTextCopiedAsync("off");
        Assert.Empty(rig.SentClips);

        rig.SendEnabled = true;
        await rig.Service.OnLocalImageCopiedAsync(Png);
        Assert.Empty(rig.SentClips); // images are off by default

        rig.ImagesEnabled = true;
        await rig.Service.OnLocalImageCopiedAsync(Png);
        Assert.Equal("image", Assert.Single(rig.SentClips).Kind);

        rig.Sessions.Connect("OLD", 0);
        rig.Now += TimeSpan.FromSeconds(5);
        await rig.Service.OnLocalTextCopiedAsync("only to capable phones");
        Assert.Empty(rig.Sessions.SentOf<LinkMessage.Clip>("OLD"));
    }

    [Fact]
    public async Task AnImageChangeRightAfterVeyroSetOne_IsTakenAsTheEcho()
    {
        var rig = new Rig { ImagesEnabled = true };
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip("1", "image", Image: Convert.ToBase64String(Png), Apply: true));
        Assert.Single(rig.Sink.Images);

        byte[] reEncoded = [0x89, 0x50, 0x4E, 0x47, 1, 2, 3];
        await rig.Service.OnLocalImageCopiedAsync(reEncoded);
        Assert.Empty(rig.SentClips);

        rig.Now += TimeSpan.FromSeconds(5);
        await rig.Service.OnLocalImageCopiedAsync(reEncoded);
        Assert.Single(rig.SentClips);
    }

    [Theory]
    [InlineData("", "text", "x", null)]
    [InlineData("1", "text", "", null)]
    [InlineData("1", "file", "x", null)]
    [InlineData("1", "image", null, "PHN2Zy8+")] // "<svg/>"
    [InlineData("1", "image", null, "%%% not base64")]
    public async Task InvalidItems_AreDropped(string id, string kind, string? text, string? image)
    {
        var rig = new Rig();
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip(id, kind, text, image, Apply: true));
        Assert.Empty(rig.Service.Items);
        Assert.Empty(rig.Sink.Texts);
        Assert.Empty(rig.Notifier.Applied);
    }

    [Fact]
    public async Task OversizedText_IsDropped()
    {
        var rig = new Rig();
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip("1", "text", Text: new string('x', LinkMessage.Clip.MaxTextChars + 1)));
        Assert.Empty(rig.Service.Items);
    }

    [Fact]
    public async Task History_KeepsTheNewest50_DropsRepeatedIds_AndRemoveAndClearWork()
    {
        var rig = new Rig();
        for (int i = 0; i < 60; i++)
            await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip($"id{i}", "text", Text: $"t{i}"));
        await rig.Service.HandleIncomingAsync(Phone, new LinkMessage.Clip("id59", "text", Text: "t59"));

        Assert.Equal(ClipboardService.MaxItems, rig.Service.Items.Count);
        Assert.Equal("id59", rig.Service.Items[0].Id);
        Assert.Equal(60, rig.Notifier.Stored.Count);

        rig.Service.Remove("id59");
        Assert.Equal("id58", rig.Service.Items[0].Id);
        rig.Service.Clear();
        Assert.Empty(rig.Service.Items);
    }

    [Theory]
    [InlineData("watch https://youtu.be/abc now", "https://youtu.be/abc")]
    [InlineData("(see https://example.com/a?b=1).", "https://example.com/a?b=1")]
    [InlineData("HTTP://EXAMPLE.COM", "http://example.com/")]
    [InlineData("no link here", null)]
    [InlineData("javascript:alert(1)", null)]
    [InlineData("ftp://example.com/file", null)]
    [InlineData(null, null)]
    public void FindLink_OffersOnlyHttpLinks(string? text, string? expected)
    {
        Uri? link = ClipboardService.FindLink(text);
        Assert.Equal(expected, link?.ToString());
    }

    [Fact]
    public void ImageCheck_AcceptsOnlyPngOrJpegWithinTheCap()
    {
        Assert.True(ClipboardService.IsAcceptableImage(Png));
        Assert.True(ClipboardService.IsAcceptableImage([0xFF, 0xD8, 0, 0]));
        Assert.False(ClipboardService.IsAcceptableImage(null));
        Assert.False(ClipboardService.IsAcceptableImage("GIF89a"u8.ToArray()));
        byte[] huge = new byte[LinkMessage.Clip.MaxImageBytes + 1];
        Png.CopyTo(huge, 0);
        Assert.False(ClipboardService.IsAcceptableImage(huge));
    }
}
