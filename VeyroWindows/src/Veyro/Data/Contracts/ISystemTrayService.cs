namespace Veyro.Data.Contracts;

/// <summary>
/// The Veyro icon in the Windows notification area, shown only while tray mode is on (default
/// off, ADR-036). <see cref="IsAvailable"/> is true only while the icon actually exists, so
/// "keep running in the tray" never hides the window without a way back.
/// </summary>
public interface ISystemTrayService : IDisposable
{
    bool IsAvailable { get; }

    /// <summary>Prepares the icon's message window. UI thread. Adds no icon by itself.</summary>
    void Initialize(nint windowHandle);

    /// <summary>Adds or removes the icon. UI thread. A failure to add leaves IsAvailable false.</summary>
    void SetEnabled(bool enabled);
}
