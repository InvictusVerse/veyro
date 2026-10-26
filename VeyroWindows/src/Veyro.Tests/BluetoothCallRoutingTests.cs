using Veyro.Core.Calls;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Xunit;

namespace Veyro.Tests;

/// <summary>Scriptable Bluetooth hands-free line.</summary>
internal sealed class FakeBluetoothLine : IBluetoothCallLine
{
    public event Action? Changed;
    public BluetoothLineStatus Status { get; set; } = BluetoothLineStatus.Connected;
    public string? LinkedPeerId { get; set; }
    public bool HasIncomingCall { get; set; }
    public bool AnswerSucceeds { get; set; } = true;
    public bool RejectSucceeds { get; set; } = true;
    public int Answers { get; private set; }
    public int Rejects { get; private set; }

    public Task<bool> AnswerIncomingAsync()
    {
        Answers++;
        return Task.FromResult(HasIncomingCall && AnswerSucceeds);
    }

    public Task<bool> RejectIncomingAsync()
    {
        Rejects++;
        return Task.FromResult(HasIncomingCall && RejectSucceeds);
    }

    public void Ring()
    {
        HasIncomingCall = true;
        Changed?.Invoke();
    }
}

/// <summary>ADR-029: answer and decline route selection between Bluetooth and Wi-Fi.</summary>
public sealed class CallRouterTests
{
    private const string Phone = "PHONE";

    private static (FakePeerSessions Sessions, FakeBluetoothLine Line, ManualTimeProvider Time, CallRouter Router, CallService Calls) Create(
        string? linkedPeer = Phone, bool ringing = true)
    {
        var sessions = new FakePeerSessions();
        sessions.Connect(Phone, LinkCapabilities.Calls);
        var calls = new CallService(sessions);
        if (ringing) sessions.Receive(Phone, new LinkMessage.CallState("k-1", "ringing", "in"));
        var line = new FakeBluetoothLine { LinkedPeerId = linkedPeer };
        var time = new ManualTimeProvider();
        return (sessions, line, time, new CallRouter(calls, line, time), calls);
    }

    [Theory]
    [InlineData(BluetoothLineStatus.Connected, Phone, Phone, CallAnswerRoute.Bluetooth)]
    [InlineData(BluetoothLineStatus.Connected, "phone", Phone, CallAnswerRoute.Bluetooth)]
    [InlineData(BluetoothLineStatus.Connected, "OTHER", Phone, CallAnswerRoute.WiFi)]
    [InlineData(BluetoothLineStatus.Connected, null, Phone, CallAnswerRoute.WiFi)]
    [InlineData(BluetoothLineStatus.Connected, Phone, null, CallAnswerRoute.WiFi)]
    [InlineData(BluetoothLineStatus.Connecting, Phone, Phone, CallAnswerRoute.WiFi)]
    [InlineData(BluetoothLineStatus.Disconnected, Phone, Phone, CallAnswerRoute.WiFi)]
    [InlineData(BluetoothLineStatus.NotSupported, Phone, Phone, CallAnswerRoute.WiFi)]
    public void PreferredRoute_NeedsConnectedLineToSamePhone(BluetoothLineStatus status, string? linked, string? peer, CallAnswerRoute expected)
    {
        var (_, line, _, router, _) = Create(linked);
        line.Status = status;
        Assert.Equal(expected, router.PreferredAnswerRoute(peer));
    }

    [Fact]
    public async Task Answer_OverBluetooth_SendsNothingOnWifi()
    {
        var (sessions, line, _, router, _) = Create();
        line.HasIncomingCall = true;
        Assert.Equal(CallAnswerRoute.Bluetooth, await router.AnswerAsync(Phone));
        Assert.Equal(1, line.Answers);
        Assert.Empty(sessions.SentOf<LinkMessage.CallAction>(Phone));
    }

    [Fact]
    public async Task Answer_WaitsForLateHandsFreeRing()
    {
        var (sessions, line, _, router, _) = Create();
        Task<CallAnswerRoute> answer = router.AnswerAsync(Phone);
        Assert.False(answer.IsCompleted);
        line.Ring();
        Assert.Equal(CallAnswerRoute.Bluetooth, await answer);
        Assert.Empty(sessions.SentOf<LinkMessage.CallAction>(Phone));
    }

    [Fact]
    public async Task Answer_NoHandsFreeRing_FallsBackToWifiAfterWait()
    {
        var (sessions, line, time, router, _) = Create();
        Task<CallAnswerRoute> answer = router.AnswerAsync(Phone);
        Assert.False(answer.IsCompleted);
        time.Advance(CallRouter.IncomingWait);
        Assert.Equal(CallAnswerRoute.WiFi, await answer);
        Assert.Equal(0, line.Answers);
        Assert.Equal(new LinkMessage.CallAction("answer", "k-1"), Assert.Single(sessions.SentOf<LinkMessage.CallAction>(Phone)));
    }

    [Fact]
    public async Task Answer_BluetoothFailure_FallsBackToWifi()
    {
        var (sessions, line, _, router, _) = Create();
        line.HasIncomingCall = true;
        line.AnswerSucceeds = false;
        Assert.Equal(CallAnswerRoute.WiFi, await router.AnswerAsync(Phone));
        Assert.Single(sessions.SentOf<LinkMessage.CallAction>(Phone));
    }

    [Fact]
    public async Task Answer_OtherPhoneLinked_UsesWifiWithoutTouchingBluetooth()
    {
        var (sessions, line, _, router, _) = Create(linkedPeer: "OTHER");
        line.HasIncomingCall = true;
        Assert.Equal(CallAnswerRoute.WiFi, await router.AnswerAsync(Phone));
        Assert.Equal(0, line.Answers);
        Assert.Single(sessions.SentOf<LinkMessage.CallAction>(Phone));
    }

    [Fact]
    public async Task Decline_PrefersWifi_ThenBluetooth_ElseThrows()
    {
        var (sessions, line, _, router, _) = Create();
        line.HasIncomingCall = true;
        Assert.Equal(CallAnswerRoute.WiFi, await router.DeclineAsync(Phone));
        Assert.Equal(0, line.Rejects);
        Assert.Equal(new LinkMessage.CallAction("decline", "k-1"), Assert.Single(sessions.SentOf<LinkMessage.CallAction>(Phone)));

        // Wi-Fi down: the hands-free line still lets the PC reject the call.
        sessions.Disconnect(Phone);
        Assert.Equal(CallAnswerRoute.Bluetooth, await router.DeclineAsync(Phone));
        Assert.Equal(1, line.Rejects);

        line.HasIncomingCall = false;
        await Assert.ThrowsAsync<InvalidOperationException>(() => router.DeclineAsync(Phone));
    }
}

public sealed class CallPresentationTests
{
    [Theory]
    [InlineData(BluetoothCallStatus.Talking, true, true)]
    [InlineData(BluetoothCallStatus.Held, true, true)]
    [InlineData(BluetoothCallStatus.Dialing, true, true)]
    [InlineData(BluetoothCallStatus.Incoming, true, false)]
    [InlineData(BluetoothCallStatus.Incoming, false, true)]
    [InlineData(BluetoothCallStatus.Ended, false, false)]
    [InlineData(BluetoothCallStatus.Lost, true, false)]
    public void ShowsWindow_IncomingOnlyWhenWifiCannotAlert(BluetoothCallStatus status, bool wifi, bool expected)
        => Assert.Equal(expected, CallPresentation.ShowsWindow(status, wifi));

    [Fact]
    public void Title_PrefersWifiNameThenBluetoothThenNumbers()
    {
        var wifi = new PhoneCall("P", "k", "active", "in", "+15550100", "Sam");
        Assert.Equal("Sam", CallPresentation.Title(wifi, "Samuel", "+15550199"));
        Assert.Equal("+15550100", CallPresentation.Subtitle(wifi, "Samuel", "+15550199"));

        var wifiNoName = wifi with { Name = null };
        Assert.Equal("Samuel", CallPresentation.Title(wifiNoName, "Samuel", "+15550199"));
        Assert.Equal("+15550100", CallPresentation.Subtitle(wifiNoName, "Samuel", "+15550199"));

        Assert.Equal("+15550199", CallPresentation.Title(null, " ", "+15550199"));
        Assert.Null(CallPresentation.Subtitle(null, null, "+15550199"));
        Assert.Equal(CallPresentation.UnknownCaller, CallPresentation.Title(null, null, null));
    }

    [Fact]
    public void Duration_AndStatusText()
    {
        Assert.Equal("0:07", CallPresentation.FormatDuration(TimeSpan.FromSeconds(7)));
        Assert.Equal("12:05", CallPresentation.FormatDuration(TimeSpan.FromSeconds(725)));
        Assert.Equal("1:02:03", CallPresentation.FormatDuration(new TimeSpan(1, 2, 3)));
        Assert.Equal("0:42", CallPresentation.StatusText(BluetoothCallStatus.Talking, TimeSpan.FromSeconds(42)));
        Assert.Equal("On hold", CallPresentation.StatusText(BluetoothCallStatus.Held, TimeSpan.FromSeconds(42)));
    }

    [Fact]
    public void Settings_WithoutBluetoothFields_LoadWithNulls()
    {
        // Under the test output folder (on the repo drive), not %TEMP%.
        string dir = Path.Combine(AppContext.BaseDirectory, "veyro-bt-settings-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            File.WriteAllText(Path.Combine(dir, "settings.json"),
                """{"DeviceName":"PC","IsDiscoverable":true,"DownloadDirectory":"D:\\x","AutoStartOnLogin":false,"MinimizeToTrayOnClose":true}""");
            AppSettings loaded = new FileSettingsStore(dir).Current;
            Assert.Equal("PC", loaded.DeviceName);
            Assert.Null(loaded.BluetoothCallTransportId);
            Assert.Null(loaded.BluetoothCallPeerId);
        }
        finally
        {
            File.Delete(Path.Combine(dir, "settings.json"));
            Directory.Delete(dir);
        }
    }
}
