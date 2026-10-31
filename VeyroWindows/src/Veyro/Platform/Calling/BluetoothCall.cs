using Veyro.Core.Diagnostics;
using Windows.ApplicationModel.Calls;
using Windows.Foundation;
using WinRtCall = Windows.ApplicationModel.Calls.PhoneCall;

namespace Veyro.Platform.Calling;

/// <summary>
/// One call on the Bluetooth hands-free line (ADR-029), backed by a
/// <c>Windows.ApplicationModel.Calls.PhoneCall</c>. Status, mute and audio route are always read
/// from the Windows object, so they stay current without a local copy. The caller's number and
/// name are read once when the call is opened. They are user data and never logged.
/// </summary>
internal sealed class BluetoothCall : IBluetoothCall
{
    private readonly WinRtCall _native;
    private int _released;

    private BluetoothCall(WinRtCall native, string? number, string? name)
    {
        _native = native;
        Number = number;
        Name = name;
        _native.StatusChanged += OnNativeChanged;
        _native.AudioDeviceChanged += OnNativeChanged;
        _native.IsMutedChanged += OnNativeChanged;
    }

    public string CallId => _native.CallId;
    public string? Number { get; }
    public string? Name { get; }
    public BluetoothCallStatus Status => StatusOf(_native.Status);
    public bool IsMuted => _native.IsMuted;

    public CallAudioRoute AudioRoute => _native.AudioDevice switch
    {
        PhoneCallAudioDevice.LocalDevice => CallAudioRoute.ThisPc,
        PhoneCallAudioDevice.RemoteDevice => CallAudioRoute.Phone,
        _ => CallAudioRoute.Unknown
    };

    public event Action? Changed;

    /// <summary>Reads the caller details from Windows and starts listening for changes.</summary>
    public static async Task<BluetoothCall> OpenAsync(WinRtCall native)
    {
        string? number = null;
        string? name = null;
        try
        {
            PhoneCallInfo info = await native.GetPhoneCallInfoAsync();
            number = NullIfBlank(info.PhoneNumber);
            // Windows repeats the number as the display name when it knows no contact.
            name = info.DisplayName == info.PhoneNumber ? null : NullIfBlank(info.DisplayName);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading Bluetooth call info failed", error);
        }
        return new BluetoothCall(native, number, name);
    }

    public Task<bool> AcceptAsync() => CompleteAsync(_native.AcceptIncomingAsync());
    public Task<bool> RejectAsync() => CompleteAsync(_native.RejectIncomingAsync());
    public Task<bool> EndAsync() => CompleteAsync(_native.EndAsync());
    public Task<bool> HoldAsync() => CompleteAsync(_native.HoldAsync());
    public Task<bool> ResumeAsync() => CompleteAsync(_native.ResumeFromHoldAsync());
    public Task<bool> MuteAsync() => CompleteAsync(_native.MuteAsync());
    public Task<bool> UnmuteAsync() => CompleteAsync(_native.UnmuteAsync());

    public Task<bool> SetAudioRouteAsync(CallAudioRoute route)
    {
        PhoneCallAudioDevice target = route switch
        {
            CallAudioRoute.ThisPc => PhoneCallAudioDevice.LocalDevice,
            CallAudioRoute.Phone => PhoneCallAudioDevice.RemoteDevice,
            _ => throw new ArgumentOutOfRangeException(nameof(route), route, "Choose this PC or the phone.")
        };
        return CompleteAsync(_native.ChangeAudioDeviceAsync(target));
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _released, 1) != 0) return;
        _native.StatusChanged -= OnNativeChanged;
        _native.AudioDeviceChanged -= OnNativeChanged;
        _native.IsMutedChanged -= OnNativeChanged;
        Changed = null;
    }

    /// <summary>Maps the Windows call status onto Veyro's platform-neutral one.</summary>
    internal static BluetoothCallStatus StatusOf(PhoneCallStatus native) => native switch
    {
        PhoneCallStatus.Incoming => BluetoothCallStatus.Incoming,
        PhoneCallStatus.Dialing => BluetoothCallStatus.Dialing,
        PhoneCallStatus.Talking => BluetoothCallStatus.Talking,
        PhoneCallStatus.Held => BluetoothCallStatus.Held,
        PhoneCallStatus.Ended => BluetoothCallStatus.Ended,
        PhoneCallStatus.Lost => BluetoothCallStatus.Lost,
        _ => BluetoothCallStatus.Unknown
    };

    private void OnNativeChanged(WinRtCall sender, object args) => Changed?.Invoke();

    private static async Task<bool> CompleteAsync(IAsyncOperation<PhoneCallOperationStatus> operation)
    {
        PhoneCallOperationStatus outcome = await operation;
        bool succeeded = outcome == PhoneCallOperationStatus.Succeeded;
        if (!succeeded) VeyroTrace.Warn($"Bluetooth call operation ended with {outcome}");
        return succeeded;
    }

    private static string? NullIfBlank(string? text) => string.IsNullOrWhiteSpace(text) ? null : text;
}
