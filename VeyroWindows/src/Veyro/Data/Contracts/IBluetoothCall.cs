namespace Veyro.Data.Contracts;

/// <summary>
/// One call on the Bluetooth hands-free line (ADR-029). Windows: Windows.ApplicationModel.Calls.PhoneCall.
/// <see cref="Number"/> and <see cref="Name"/> are user data: never logged.
/// </summary>
public interface IBluetoothCall : IDisposable
{
    string CallId { get; }
    string? Number { get; }
    string? Name { get; }
    BluetoothCallStatus Status { get; }
    bool IsMuted { get; }
    CallAudioRoute AudioRoute { get; }

    /// <summary>Status, mute or audio route changed. Raised on a WinRT thread.</summary>
    event Action? Changed;

    Task<bool> AcceptAsync();
    Task<bool> RejectAsync();
    Task<bool> EndAsync();
    Task<bool> HoldAsync();
    Task<bool> ResumeAsync();
    Task<bool> MuteAsync();
    Task<bool> UnmuteAsync();
    Task<bool> SetAudioRouteAsync(CallAudioRoute route);
}
