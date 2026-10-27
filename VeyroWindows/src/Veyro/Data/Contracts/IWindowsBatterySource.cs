namespace Veyro.Data.Contracts;

/// <summary>This PC's battery, published to the phone (ADR-026). Windows: PowerManager.</summary>
public interface IWindowsBatterySource : IPcBatterySource, IDisposable
{
    void Start();
}
