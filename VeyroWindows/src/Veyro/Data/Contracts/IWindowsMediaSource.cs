namespace Veyro.Data.Contracts;

/// <summary>
/// This PC's media sessions and master volume, published to the phone (ADR-026). Windows:
/// GlobalSystemMediaTransportControls and Core Audio.
/// </summary>
public interface IWindowsMediaSource : IPcMediaSource, IDisposable
{
    /// <summary>Starts watching sessions and volume. Failures are logged; the snapshot stays empty.</summary>
    Task StartAsync();

    /// <summary>
    /// How often the master volume is read (ADR-047): null stops reading while no phone is
    /// connected; shorter while the window is on screen. Session changes stay event-driven.
    /// </summary>
    void SetVolumePolling(TimeSpan? interval);
}
