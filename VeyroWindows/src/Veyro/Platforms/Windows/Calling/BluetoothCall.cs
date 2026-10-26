// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Platforms/Windows/Calling/PhoneCall.cs, GPL-3.0.
// Modified for Veyro.
using Veyro.Core.Diagnostics;
using Windows.ApplicationModel.Calls;
using WinRTPhoneCall = Windows.ApplicationModel.Calls.PhoneCall;

namespace Veyro.Platforms.Windows.Calling;

/// <summary>
/// Thin wrapper over a WinRT <see cref="WinRTPhoneCall"/> on the Bluetooth hands-free line
/// (ADR-029). Number and name come from <c>PhoneCallInfo</c> and are never logged.
/// </summary>
internal sealed class BluetoothCall : IBluetoothCall
{
    private readonly WinRTPhoneCall _call;
    private bool _disposed;

    private BluetoothCall(WinRTPhoneCall call, string? number, string? name)
    {
        _call = call;
        Number = number;
        Name = name;
        _call.StatusChanged += OnChanged;
        _call.AudioDeviceChanged += OnChanged;
        _call.IsMutedChanged += OnChanged;
    }

    public static async Task<BluetoothCall> CreateAsync(WinRTPhoneCall call)
    {
        string? number = null, name = null;
        try
        {
            PhoneCallInfo info = await call.GetPhoneCallInfoAsync();
            number = string.IsNullOrWhiteSpace(info.PhoneNumber) ? null : info.PhoneNumber;
            name = string.IsNullOrWhiteSpace(info.DisplayName) || info.DisplayName == info.PhoneNumber ? null : info.DisplayName;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading Bluetooth call info failed", error);
        }
        return new BluetoothCall(call, number, name);
    }

    public string CallId => _call.CallId;
    public string? Number { get; }
    public string? Name { get; }
    public BluetoothCallStatus Status => Map(_call.Status);
    public bool IsMuted => _call.IsMuted;
    public CallAudioRoute AudioRoute => _call.AudioDevice switch
    {
        PhoneCallAudioDevice.LocalDevice => CallAudioRoute.ThisPc,
        PhoneCallAudioDevice.RemoteDevice => CallAudioRoute.Phone,
        _ => CallAudioRoute.Unknown
    };

    public event Action? Changed;

    public async Task<bool> AcceptAsync() => Ok(await _call.AcceptIncomingAsync());
    public async Task<bool> RejectAsync() => Ok(await _call.RejectIncomingAsync());
    public async Task<bool> EndAsync() => Ok(await _call.EndAsync());
    public async Task<bool> HoldAsync() => Ok(await _call.HoldAsync());
    public async Task<bool> ResumeAsync() => Ok(await _call.ResumeFromHoldAsync());
    public async Task<bool> MuteAsync() => Ok(await _call.MuteAsync());
    public async Task<bool> UnmuteAsync() => Ok(await _call.UnmuteAsync());

    public async Task<bool> SetAudioRouteAsync(CallAudioRoute route)
    {
        PhoneCallAudioDevice device = route switch
        {
            CallAudioRoute.ThisPc => PhoneCallAudioDevice.LocalDevice,
            CallAudioRoute.Phone => PhoneCallAudioDevice.RemoteDevice,
            _ => throw new ArgumentOutOfRangeException(nameof(route))
        };
        return Ok(await _call.ChangeAudioDeviceAsync(device));
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _call.StatusChanged -= OnChanged;
        _call.AudioDeviceChanged -= OnChanged;
        _call.IsMutedChanged -= OnChanged;
        Changed = null;
    }

    private void OnChanged(WinRTPhoneCall sender, object args) => Changed?.Invoke();

    private static bool Ok(PhoneCallOperationStatus status)
    {
        if (status != PhoneCallOperationStatus.Succeeded) VeyroTrace.Warn($"Bluetooth call operation: {status}");
        return status == PhoneCallOperationStatus.Succeeded;
    }

    internal static BluetoothCallStatus Map(PhoneCallStatus status) => status switch
    {
        PhoneCallStatus.Incoming => BluetoothCallStatus.Incoming,
        PhoneCallStatus.Dialing => BluetoothCallStatus.Dialing,
        PhoneCallStatus.Talking => BluetoothCallStatus.Talking,
        PhoneCallStatus.Held => BluetoothCallStatus.Held,
        PhoneCallStatus.Ended => BluetoothCallStatus.Ended,
        PhoneCallStatus.Lost => BluetoothCallStatus.Lost,
        _ => BluetoothCallStatus.Unknown
    };
}
