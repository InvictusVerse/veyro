namespace Veyro.Data.Contracts;

/// <summary>A paired Bluetooth phone that offers the hands-free profile.</summary>
public sealed record BluetoothPhoneInfo(string TransportId, string Name);
