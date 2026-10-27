namespace Veyro.Data.Contracts;

/// <summary>This PC's desktop wallpaper, published to the phone (ADR-038). Windows: SystemParametersInfo.</summary>
public interface IWindowsWallpaperSource : IPcWallpaperSource, IDisposable
{
    void Start();
}
