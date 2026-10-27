namespace Veyro.Data.Contracts;

/// <summary>
/// The PC as the phone's Bluetooth hands-free device (ADR-029). Windows 11 only; on Windows 10
/// <see cref="IBluetoothCallLine.Status"/> stays <see cref="BluetoothLineStatus.NotSupported"/>.
/// Windows: PhoneLineTransportDevice, PhoneLine and PhoneCallManager.
/// </summary>
public interface IBluetoothCallingService : IBluetoothCallLine, IDisposable
{
    bool IsSupported { get; }
    IReadOnlyList<BluetoothPhoneInfo> Phones { get; }
    string? SelectedTransportId { get; }
    IReadOnlyList<IBluetoothCall> Calls { get; }

    /// <summary>A new call appeared on the connected line. Raised on a WinRT thread.</summary>
    event Action<IBluetoothCall>? CallAdded;

    /// <summary>Starts watching lines and calls and reconnects the saved phone. Failures are logged.</summary>
    Task InitializeAsync();

    Task RefreshPhonesAsync();

    /// <summary>Registers Veyro for the phone's line and connects it, linked to Veyro peer <paramref name="peerId"/>.</summary>
    Task ConnectAsync(string transportId, string? peerId);

    /// <summary>Gives the line back to Windows and forgets the choice.</summary>
    Task DisconnectAsync();
}
