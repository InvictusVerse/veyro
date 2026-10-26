using System.Text;
using Veyro.Core.Data;
using Veyro.Core.Handoff;
using Xunit;

namespace Veyro.Tests;

/// <summary>ADR-039: Share sheet and Explorer menu hand-off encoding, target rules and menu model.</summary>
public sealed class HandoffTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "veyro-handoff-" + Guid.NewGuid().ToString("N"));

    private static readonly SendCandidate PhoneA = new("AAAA-1111", "Pixel", IsConnected: true);
    private static readonly SendCandidate PhoneB = new("BBBB-2222", "Galaxy", IsConnected: false);
    private static readonly SendCandidate PhoneC = new("CCCC-3333", "Nothing", IsConnected: true);

    public HandoffTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        foreach (string file in Directory.EnumerateFiles(_dir)) File.Delete(file);
        Directory.Delete(_dir);
    }

    // ── Codec ────────────────────────────────────────────────────────────────────

    [Fact]
    public void Request_round_trips_every_kind()
    {
        var requests = new[]
        {
            new HandoffRequest(HandoffKind.Share, [@"C:\Users\me\Pictures\a b.jpg", @"\\server\share\c.txt"]),
            new HandoffRequest(HandoffKind.ContextMenu, [@"D:\x\y.zip"], HandoffTargetKind.Device, "AAAA-1111"),
            new HandoffRequest(HandoffKind.ContextMenu, [@"D:\x\y.zip"], HandoffTargetKind.AllConnected),
            new HandoffRequest(HandoffKind.Pair, [])
        };
        foreach (HandoffRequest request in requests)
        {
            HandoffRequest decoded = HandoffCodec.DecodeRequest(HandoffCodec.EncodeRequest(request));
            Assert.Equal(request.Kind, decoded.Kind);
            Assert.Equal(request.Target, decoded.Target);
            Assert.Equal(request.DeviceId, decoded.DeviceId);
            Assert.Equal(request.Paths, decoded.Paths);
        }
    }

    [Fact]
    public void Reply_round_trips_and_long_messages_are_cut()
    {
        HandoffReply reply = HandoffCodec.DecodeReply(HandoffCodec.EncodeReply(new HandoffReply(HandoffOutcome.PartlySent, "Sent to 1 of 2 phones.")));
        Assert.Equal(HandoffOutcome.PartlySent, reply.Outcome);
        Assert.Equal("Sent to 1 of 2 phones.", reply.Message);

        HandoffReply cut = HandoffCodec.DecodeReply(HandoffCodec.EncodeReply(new HandoffReply(HandoffOutcome.Failed, new string('x', 5000))));
        Assert.Equal(HandoffCodec.MaxMessageLength, cut.Message!.Length);
    }

    [Theory]
    [InlineData("""{"V":2,"Kind":1,"Paths":["C:\\a.txt"],"Target":0}""")] // future version
    [InlineData("""{"V":1,"Kind":9,"Paths":["C:\\a.txt"],"Target":0}""")] // unknown kind
    [InlineData("""{"V":1,"Kind":1,"Paths":["C:\\a.txt"],"Target":7}""")] // unknown target
    [InlineData("""{"V":1,"Kind":1,"Paths":[],"Target":0}""")] // no files
    [InlineData("""{"V":1,"Kind":1,"Paths":["relative\\a.txt"],"Target":0}""")] // not absolute
    [InlineData("""{"V":1,"Kind":1,"Paths":["C:a.txt"],"Target":0}""")] // drive-relative
    [InlineData("""{"V":1,"Kind":1,"Paths":["C:\\a\u0000.txt"],"Target":0}""")] // NUL
    [InlineData("""{"V":1,"Kind":2,"Paths":["C:\\a.txt"],"Target":1}""")] // device target without id
    [InlineData("""{"V":1,"Kind":2,"Paths":["C:\\a.txt"],"Target":1,"DeviceId":"..\\x"}""")] // malformed id
    [InlineData("""{"V":1,"Kind":2,"Paths":["C:\\a.txt"],"Target":2,"DeviceId":"AAAA"}""")] // id without device target
    [InlineData("""{"V":1,"Kind":3,"Paths":["C:\\a.txt"],"Target":0}""")] // pairing with files
    [InlineData("""not json""")]
    [InlineData("""null""")]
    public void Malformed_requests_are_rejected(string json)
    {
        Assert.Throws<FormatException>(() => HandoffCodec.DecodeRequest(Encoding.UTF8.GetBytes(json)));
    }

    [Fact]
    public void Encoding_refuses_what_decoding_would_reject()
    {
        Assert.Throws<FormatException>(() => HandoffCodec.EncodeRequest(new HandoffRequest(HandoffKind.Share, ["a.txt"])));
    }

    [Fact]
    public async Task Frames_round_trip_and_bad_lengths_are_rejected()
    {
        byte[] payload = HandoffCodec.EncodeRequest(new HandoffRequest(HandoffKind.Share, [@"C:\a.txt"]));
        using var stream = new MemoryStream();
        await HandoffCodec.WriteFrameAsync(stream, payload, CancellationToken.None);
        stream.Position = 0;
        Assert.Equal(payload, await HandoffCodec.ReadFrameAsync(stream, CancellationToken.None));

        // Peer went away mid-frame.
        await Assert.ThrowsAnyAsync<EndOfStreamException>(() => HandoffCodec.ReadFrameAsync(new MemoryStream([5, 0, 0, 0, 1]), CancellationToken.None));
        // Oversized length is refused before allocating.
        byte[] huge = BitConverter.GetBytes(HandoffCodec.MaxFrameBytes + 1);
        await Assert.ThrowsAsync<FormatException>(() => HandoffCodec.ReadFrameAsync(new MemoryStream(huge), CancellationToken.None));
    }

    // ── Target selection ────────────────────────────────────────────────────────

    [Fact]
    public void Share_with_one_connected_phone_sends_straight_when_the_setting_is_on()
    {
        SendPlan plan = SendTargetSelector.Plan([PhoneA, PhoneB], null, HandoffTargetKind.Choose, null, sendStraightToOnlyConnected: true);
        Assert.Equal(new[] { "AAAA-1111" }, Assert.IsType<SendPlan.SendNow>(plan).DeviceIds);
    }

    [Fact]
    public void Share_with_one_connected_phone_asks_when_the_setting_is_off()
    {
        SendPlan plan = SendTargetSelector.Plan([PhoneA, PhoneB], null, HandoffTargetKind.Choose, null, sendStraightToOnlyConnected: false);
        Assert.Equal("AAAA-1111", Assert.IsType<SendPlan.Choose>(plan).PreselectedDeviceId);
    }

    [Fact]
    public void Share_with_two_connected_phones_asks_and_preselects_the_connected_phone_on_screen()
    {
        SendPlan plan = SendTargetSelector.Plan([PhoneA, PhoneB, PhoneC], "cccc-3333", HandoffTargetKind.Choose, null, true);
        Assert.Equal("CCCC-3333", Assert.IsType<SendPlan.Choose>(plan).PreselectedDeviceId);
    }

    [Fact]
    public void Preselection_prefers_a_connected_phone_over_a_disconnected_one_on_screen()
    {
        Assert.Equal("AAAA-1111", SendTargetSelector.Preselect([PhoneB, PhoneA], "BBBB-2222"));
        Assert.Equal("BBBB-2222", SendTargetSelector.Preselect([PhoneB with { DeviceId = "X" }, PhoneB], "BBBB-2222"));
        Assert.Equal("X", SendTargetSelector.Preselect([PhoneB with { DeviceId = "X" }, PhoneB], null));
        Assert.Null(SendTargetSelector.Preselect([], null));
    }

    [Fact]
    public void Share_with_nothing_connected_asks()
    {
        SendPlan plan = SendTargetSelector.Plan([PhoneB], null, HandoffTargetKind.Choose, null, true);
        Assert.Equal("BBBB-2222", Assert.IsType<SendPlan.Choose>(plan).PreselectedDeviceId);
    }

    [Fact]
    public void Nothing_paired_offers_pairing()
    {
        Assert.IsType<SendPlan.NoPhonePaired>(SendTargetSelector.Plan([], null, HandoffTargetKind.Choose, null, true));
        Assert.IsType<SendPlan.NoPhonePaired>(SendTargetSelector.Plan([], null, HandoffTargetKind.AllConnected, null, true));
        Assert.IsType<SendPlan.Unavailable>(SendTargetSelector.Plan([], null, HandoffTargetKind.Device, "AAAA-1111", true));
    }

    [Fact]
    public void All_connected_sends_to_every_connected_phone_in_order()
    {
        SendPlan plan = SendTargetSelector.Plan([PhoneA, PhoneB, PhoneC], null, HandoffTargetKind.AllConnected, null, false);
        Assert.Equal(new[] { "AAAA-1111", "CCCC-3333" }, Assert.IsType<SendPlan.SendNow>(plan).DeviceIds);

        SendPlan none = SendTargetSelector.Plan([PhoneB], null, HandoffTargetKind.AllConnected, null, false);
        Assert.Equal(SendTargetSelector.NothingConnectedMessage, Assert.IsType<SendPlan.Unavailable>(none).Message);
    }

    [Fact]
    public void A_named_phone_must_still_be_paired_but_need_not_be_connected()
    {
        Assert.Equal(new[] { "BBBB-2222" }, Assert.IsType<SendPlan.SendNow>(
            SendTargetSelector.Plan([PhoneA, PhoneB], null, HandoffTargetKind.Device, "bbbb-2222", true)).DeviceIds);
        Assert.Equal(SendTargetSelector.NotPairedMessage, Assert.IsType<SendPlan.Unavailable>(
            SendTargetSelector.Plan([PhoneA], null, HandoffTargetKind.Device, "ZZZZ-9999", true)).Message);
    }

    [Fact]
    public void Chooser_answers_resolve_to_paired_ids_only()
    {
        Assert.Equal(new[] { "AAAA-1111", "CCCC-3333" }, SendTargetSelector.ResolveChoice([PhoneA, PhoneB, PhoneC], null, allConnected: true));
        Assert.Equal(new[] { "BBBB-2222" }, SendTargetSelector.ResolveChoice([PhoneA, PhoneB], "BBBB-2222", false));
        Assert.Empty(SendTargetSelector.ResolveChoice([PhoneA], "ZZZZ-9999", false));
        Assert.Empty(SendTargetSelector.ResolveChoice([PhoneB], null, true));
    }

    // ── Explorer menu ──────────────────────────────────────────────────────────

    [Fact]
    public void Menu_lists_each_phone_marks_connected_and_ends_with_all_connected()
    {
        IReadOnlyList<ExplorerMenuEntry> entries = ExplorerMenu.Build([PhoneA, PhoneB]);
        Assert.Equal(new[] { "Pixel (connected)", "Galaxy", ExplorerMenu.AllConnectedTitle }, entries.Select(entry => entry.Title));
        Assert.True(entries[^1].IsEnabled);

        IReadOnlyList<ExplorerMenuEntry> offline = ExplorerMenu.Build([PhoneB]);
        Assert.False(offline[^1].IsEnabled);
    }

    [Fact]
    public void Menu_without_phones_offers_pairing_only()
    {
        ExplorerMenuEntry entry = Assert.Single(ExplorerMenu.Build([]));
        Assert.Equal(ExplorerMenuEntryKind.Pair, entry.Kind);
        Assert.Equal(ExplorerMenu.PairTitle, entry.Title);
        HandoffRequest request = ExplorerMenu.ToRequest(entry, [@"C:\a.txt"])!;
        Assert.Equal(HandoffKind.Pair, request.Kind);
        Assert.Empty(request.Paths);
    }

    [Fact]
    public void Menu_entries_produce_the_matching_hand_off()
    {
        IReadOnlyList<ExplorerMenuEntry> entries = ExplorerMenu.Build([PhoneA, PhoneB]);
        HandoffRequest toPhone = ExplorerMenu.ToRequest(entries[1], [@"C:\a.txt"])!;
        Assert.Equal((HandoffKind.ContextMenu, HandoffTargetKind.Device, "BBBB-2222"), (toPhone.Kind, toPhone.Target, toPhone.DeviceId));

        HandoffRequest toAll = ExplorerMenu.ToRequest(entries[2], [@"C:\a.txt"])!;
        Assert.Equal((HandoffTargetKind.AllConnected, (string?)null), (toAll.Target, toAll.DeviceId));

        // Survives the pipe.
        Assert.Equal("BBBB-2222", HandoffCodec.DecodeRequest(HandoffCodec.EncodeRequest(toPhone)).DeviceId);

        Assert.Null(ExplorerMenu.ToRequest(entries[1], [])); // nothing selected
        Assert.Null(ExplorerMenu.ToRequest(ExplorerMenu.Build([PhoneB])[^1], [@"C:\a.txt"])); // disabled entry
    }

    [Fact]
    public async Task Menu_is_hidden_by_default_and_when_the_setting_is_off_or_unreadable()
    {
        string settings = Path.Combine(_dir, "settings.json");
        Assert.False(ExplorerMenu.ReadEnabled(settings)); // no file

        var store = new FileSettingsStore(_dir);
        Assert.False(store.Current.ShowInExplorerContextMenu);
        Assert.True(store.Current.SendSharedFilesStraightToConnectedPhone);
        await store.UpdateAsync(s => s with { DeviceName = "PC" });
        Assert.False(ExplorerMenu.ReadEnabled(settings)); // written with the default

        await store.UpdateAsync(s => s with { ShowInExplorerContextMenu = true });
        Assert.True(ExplorerMenu.ReadEnabled(settings));

        await File.WriteAllTextAsync(settings, "{ not json");
        Assert.False(ExplorerMenu.ReadEnabled(settings));
        await File.WriteAllTextAsync(settings, """{"ShowInExplorerContextMenu":"yes"}""");
        Assert.False(ExplorerMenu.ReadEnabled(settings));
    }

    [Fact]
    public void Older_settings_files_get_the_new_defaults()
    {
        File.WriteAllText(Path.Combine(_dir, "settings.json"),
            """{"DeviceName":"PC","IsDiscoverable":true,"DownloadDirectory":"D:\\x","TrayModeChosen":true}""");
        AppSettings loaded = new FileSettingsStore(_dir).Current;
        Assert.True(loaded.SendSharedFilesStraightToConnectedPhone);
        Assert.False(loaded.ShowInExplorerContextMenu);
    }

    [Fact]
    public void Menu_labels_come_from_the_trust_file_without_secrets_and_never_connected()
    {
        string trust = Path.Combine(_dir, "trust.json");
        Assert.Empty(ExplorerMenu.ReadPairedForLabels(trust));

        string payload = """
            [{"DeviceId":"BBBB-2222","DisplayName":"Galaxy","Platform":0,"PublicKeyBase64":"k","PairingSecretBase64":"s","AutoAcceptTransfers":true,"PairedAt":"2026-02-01T00:00:00+00:00"},
             {"DeviceId":"AAAA-1111","DisplayName":" ","Platform":0,"PublicKeyBase64":"k","PairingSecretBase64":"s","AutoAcceptTransfers":true,"PairedAt":"2026-01-01T00:00:00+00:00"},
             {"DeviceId":"..\\bad","DisplayName":"Bad","Platform":0,"PublicKeyBase64":"k","PairingSecretBase64":"s","AutoAcceptTransfers":true,"PairedAt":"2026-01-01T00:00:00+00:00"}]
            """;
        File.WriteAllText(trust, System.Text.Json.JsonSerializer.Serialize(new { PayloadJson = payload, HmacHex = "00" }));

        IReadOnlyList<SendCandidate> phones = ExplorerMenu.ReadPairedForLabels(trust);
        Assert.Equal(new[] { "AAAA-1111", "BBBB-2222" }, phones.Select(phone => phone.DeviceId)); // pairing order
        Assert.Equal("Unnamed device", phones[0].DisplayName);
        Assert.All(phones, phone => Assert.False(phone.IsConnected));

        File.WriteAllText(trust, "garbage");
        Assert.Empty(ExplorerMenu.ReadPairedForLabels(trust));
    }
}
