using Veyro.Core.Diagnostics;
using Windows.System.Power;

namespace Veyro.Platforms.Windows.Services;

/// <summary>
/// The PC battery from <see cref="PowerManager"/> (ADR-026). A desktop without a battery reports
/// no reading, so nothing is sent to the phone.
/// </summary>
public sealed class WindowsBatterySource : IWindowsBatterySource
{
    private bool _started;

    public event Action? Changed;

    public PcBatteryReading? Current
    {
        get
        {
            try
            {
                BatteryStatus status = PowerManager.BatteryStatus;
                if (status == BatteryStatus.NotPresent) return null;
                int percent = PowerManager.RemainingChargePercent;
                if (percent is < 0 or > 100) return null;
                // Idle on adequate external power is "plugged in and full": shown as charging.
                bool charging = status == BatteryStatus.Charging ||
                                (status == BatteryStatus.Idle && PowerManager.PowerSupplyStatus == PowerSupplyStatus.Adequate);
                return new PcBatteryReading(percent, charging);
            }
            catch (Exception error)
            {
                VeyroTrace.Error("Reading PC battery failed", error);
                return null;
            }
        }
    }

    public void Start()
    {
        if (_started) return;
        try
        {
            PowerManager.RemainingChargePercentChanged += OnPowerChanged;
            PowerManager.BatteryStatusChanged += OnPowerChanged;
            PowerManager.PowerSupplyStatusChanged += OnPowerChanged;
            _started = true;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Subscribing to power events failed", error);
        }
    }

    public void Dispose()
    {
        if (!_started) return;
        _started = false;
        PowerManager.RemainingChargePercentChanged -= OnPowerChanged;
        PowerManager.BatteryStatusChanged -= OnPowerChanged;
        PowerManager.PowerSupplyStatusChanged -= OnPowerChanged;
    }

    private void OnPowerChanged(object? sender, object e) => Changed?.Invoke();
}
