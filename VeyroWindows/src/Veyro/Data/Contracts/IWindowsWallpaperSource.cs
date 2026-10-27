namespace Veyro.Data.Contracts;

/// <summary>This PC's desktop wallpaper, published to the phone (ADR-038). Windows: SystemParametersInfo.</summary>
public interface IWindowsWallpaperSource : IPcWallpaperSource, IDisposable
{
    void Start();

    /// <summary>Polls only while a phone is connected to hear about it (ADR-047).</summary>
    void SetActive(bool active);
}
