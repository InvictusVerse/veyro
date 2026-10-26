// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Platforms/Windows/Calling/PhoneLineService.cs
// and src/Sefirah/Platforms/Windows/Utilities/CallingFeatureUtils.cs, GPL-3.0.
// Modified for Veyro.
using Veyro.Core.Diagnostics;
using Windows.ApplicationModel.Calls;
using Windows.Devices.Enumeration;
using Windows.Foundation.Metadata;
using WinRTPhoneCall = Windows.ApplicationModel.Calls.PhoneCall;

namespace Veyro.Platforms.Windows.Calling;

/// <summary>
/// The PC as the phone's Bluetooth hands-free device (ADR-029). Enumerates paired phones that
/// expose a phone line transport, registers Veyro for the chosen one, connects it and tracks its
/// calls through PhoneLine / PhoneCallManager (CallsPhoneContract v6).
/// Windows 11 only: there the PhoneLineTransportDevice Limited Access Feature check is skipped;
/// Veyro has no LAF token of its own (ADR-028), so Windows 10 reports NotSupported.
/// Ids, statuses and counts may be logged; numbers and names never.
/// </summary>
public sealed class BluetoothCallingService : IBluetoothCallingService
{
    private const string CallsPhoneContract = "Windows.ApplicationModel.Calls.CallsPhoneContract";

    private readonly ISettingsStore _settings;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _connectLock = new(1, 1);
    private readonly Dictionary<Guid, PhoneLine> _lines = [];
    private readonly Dictionary<string, BluetoothCall> _calls = new(StringComparer.Ordinal);
    private readonly HashSet<string> _pending = new(StringComparer.Ordinal);
    private IReadOnlyList<BluetoothPhoneInfo> _phones = [];
    private PhoneLineWatcher? _lineWatcher;
    private BluetoothLineStatus _status;
    private string? _transportId;
    private string? _peerId;
    private bool _initialized;

    public BluetoothCallingService(ISettingsStore settings)
    {
        _settings = settings;
        _status = IsSupported ? BluetoothLineStatus.NotPaired : BluetoothLineStatus.NotSupported;
        _transportId = settings.Current.BluetoothCallTransportId;
        _peerId = settings.Current.BluetoothCallPeerId;
    }

    public event Action? Changed;
    public event Action<IBluetoothCall>? CallAdded;

    /// <summary>
    /// Windows 11 (build 22000+) with in-call control APIs (CallsPhoneContract v6). The OS check comes
    /// first so Windows 10 never touches the transport APIs.
    /// </summary>
    public bool IsSupported { get; } = OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000) && ContractPresent();

    public BluetoothLineStatus Status
    {
        get { lock (_gate) return _status; }
    }

    public string? LinkedPeerId
    {
        get { lock (_gate) return _status == BluetoothLineStatus.Connected ? _peerId : null; }
    }

    public string? SelectedTransportId
    {
        get { lock (_gate) return _transportId; }
    }

    public IReadOnlyList<BluetoothPhoneInfo> Phones
    {
        get { lock (_gate) return _phones; }
    }

    public IReadOnlyList<IBluetoothCall> Calls
    {
        get { lock (_gate) return [.. _calls.Values]; }
    }

    public bool HasIncomingCall
    {
        get { lock (_gate) return _calls.Values.Any(c => c.Status == BluetoothCallStatus.Incoming); }
    }

    public async Task InitializeAsync()
    {
        if (!IsSupported)
        {
            VeyroTrace.Info("Bluetooth calling not supported on this Windows version");
            return;
        }
        if (_initialized) return;
        _initialized = true;

        await RefreshPhonesAsync();
        try
        {
            PhoneCallStore store = await PhoneCallManager.RequestStoreAsync();
            _lineWatcher = store.RequestLineWatcher();
            _lineWatcher.LineAdded += OnLineAddedOrUpdated;
            _lineWatcher.LineUpdated += OnLineAddedOrUpdated;
            _lineWatcher.LineRemoved += OnLineRemoved;
            _lineWatcher.Start();
            PhoneCallManager.CallStateChanged += OnCallStateChanged;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Phone line watcher unavailable", error);
        }

        // Reconnect the phone chosen earlier; Windows keeps the registration across restarts.
        if (SelectedTransportId is { } saved && Phones.Any(p => p.TransportId == saved))
            await ConnectAsync(saved, _peerId);
    }

    public async Task RefreshPhonesAsync()
    {
        if (!IsSupported) return;
        try
        {
            DeviceInformationCollection found = await DeviceInformation.FindAllAsync(
                PhoneLineTransportDevice.GetDeviceSelector(PhoneLineTransport.Bluetooth));
            var phones = found
                .Where(d => !string.IsNullOrEmpty(d.Id))
                .Select(d => new BluetoothPhoneInfo(d.Id, string.IsNullOrWhiteSpace(d.Name) ? "Bluetooth phone" : d.Name))
                .ToList();
            lock (_gate)
            {
                _phones = phones;
                if (_status is BluetoothLineStatus.NotPaired or BluetoothLineStatus.NotSelected)
                    _status = phones.Count == 0 ? BluetoothLineStatus.NotPaired
                        : _transportId is null ? BluetoothLineStatus.NotSelected : BluetoothLineStatus.Disconnected;
            }
            VeyroTrace.Info($"Bluetooth phones with a phone line: {phones.Count}");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Enumerating Bluetooth phones failed", error);
        }
        Raise();
    }

    public async Task ConnectAsync(string transportId, string? peerId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(transportId);
        if (!IsSupported) return;
        await _connectLock.WaitAsync();
        try
        {
            SetStatus(BluetoothLineStatus.Connecting, transportId, peerId);
            BluetoothLineStatus result = await ConnectTransportAsync(transportId);
            SetStatus(result, transportId, peerId);
            await _settings.UpdateAsync(s => s with { BluetoothCallTransportId = transportId, BluetoothCallPeerId = peerId });
            if (result == BluetoothLineStatus.Connected) await CheckActiveCallsAsync();
        }
        finally
        {
            _connectLock.Release();
        }
    }

    public async Task DisconnectAsync()
    {
        await _connectLock.WaitAsync();
        try
        {
            string? transportId = SelectedTransportId;
            if (transportId is not null)
            {
                try
                {
                    PhoneLineTransportDevice? device = PhoneLineTransportDevice.FromId(transportId);
                    if (device?.IsRegistered() == true) device.UnregisterApp();
                }
                catch (Exception error)
                {
                    VeyroTrace.Error("Releasing Bluetooth phone line failed", error);
                }
            }
            ClearCalls();
            lock (_gate)
            {
                _transportId = null;
                _peerId = null;
                _status = _phones.Count == 0 ? BluetoothLineStatus.NotPaired : BluetoothLineStatus.NotSelected;
            }
            await _settings.UpdateAsync(s => s with { BluetoothCallTransportId = null, BluetoothCallPeerId = null });
            VeyroTrace.Info("Bluetooth calling disconnected");
            Raise();
        }
        finally
        {
            _connectLock.Release();
        }
    }

    public async Task<bool> AnswerIncomingAsync()
    {
        BluetoothCall? call;
        lock (_gate) call = _calls.Values.FirstOrDefault(c => c.Status == BluetoothCallStatus.Incoming);
        if (call is null) return false;
        if (!await call.AcceptAsync()) return false;
        // Answering over hands-free normally routes audio here; make sure it does.
        if (call.AudioRoute != CallAudioRoute.ThisPc) await call.SetAudioRouteAsync(CallAudioRoute.ThisPc);
        return true;
    }

    public async Task<bool> RejectIncomingAsync()
    {
        BluetoothCall? call;
        lock (_gate) call = _calls.Values.FirstOrDefault(c => c.Status == BluetoothCallStatus.Incoming);
        return call is not null && await call.RejectAsync();
    }

    public void Dispose()
    {
        if (_lineWatcher is { } watcher)
        {
            watcher.LineAdded -= OnLineAddedOrUpdated;
            watcher.LineUpdated -= OnLineAddedOrUpdated;
            watcher.LineRemoved -= OnLineRemoved;
            try { watcher.Stop(); } catch (Exception error) { VeyroTrace.Error("Stopping phone line watcher failed", error); }
            _lineWatcher = null;
        }
        if (_initialized) PhoneCallManager.CallStateChanged -= OnCallStateChanged;
        ClearCalls();
        _connectLock.Dispose();
    }

    private async Task<BluetoothLineStatus> ConnectTransportAsync(string transportId)
    {
        try
        {
            PhoneLineTransportDevice? device = PhoneLineTransportDevice.FromId(transportId);
            if (device is null) return BluetoothLineStatus.NotPaired;

            DeviceAccessStatus access = await device.RequestAccessAsync();
            if (access != DeviceAccessStatus.Allowed)
            {
                VeyroTrace.Warn($"Bluetooth phone line access: {access}");
                return BluetoothLineStatus.AccessDenied;
            }
            if (!device.IsRegistered()) device.RegisterApp();
            if (!device.IsRegistered())
            {
                VeyroTrace.Warn("Bluetooth phone line registration failed; another app may own the line");
                return BluetoothLineStatus.AccessDenied;
            }
            if (!await device.ConnectAsync())
            {
                VeyroTrace.Warn("Bluetooth phone line did not connect");
                return BluetoothLineStatus.Error;
            }
            VeyroTrace.Info("Bluetooth phone line connected");
            return BluetoothLineStatus.Connected;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Connecting Bluetooth phone line failed", error);
            return BluetoothLineStatus.Error;
        }
    }

    private async void OnLineAddedOrUpdated(PhoneLineWatcher sender, PhoneLineWatcherEventArgs args)
    {
        try
        {
            PhoneLine? line = await PhoneLine.FromIdAsync(args.LineId);
            if (line is null) return;
            lock (_gate) _lines[args.LineId] = line;
            if (IsOurs(line)) await CheckLineAsync(line);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading phone line failed", error);
        }
    }

    private void OnLineRemoved(PhoneLineWatcher sender, PhoneLineWatcherEventArgs args)
    {
        lock (_gate) _lines.Remove(args.LineId);
    }

    private async void OnCallStateChanged(object? sender, object args)
    {
        try { await CheckActiveCallsAsync(); }
        catch (Exception error) { VeyroTrace.Error("Reading Bluetooth calls failed", error); }
    }

    private async Task CheckActiveCallsAsync()
    {
        PhoneLine[] lines;
        lock (_gate) lines = [.. _lines.Values];
        foreach (PhoneLine line in lines.Where(IsOurs)) await CheckLineAsync(line);
    }

    private async Task CheckLineAsync(PhoneLine line)
    {
        try
        {
            PhoneCallsResult result = await line.GetAllActivePhoneCallsAsync();
            foreach (WinRTPhoneCall active in result.AllActivePhoneCalls) await TrackAsync(active);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading active calls of the phone line failed", error);
        }
    }

    private async Task TrackAsync(WinRTPhoneCall active)
    {
        string callId = active.CallId;
        if (string.IsNullOrEmpty(callId) || CallPresentation.IsOver(BluetoothCall.Map(active.Status))) return;
        lock (_gate)
        {
            if (_calls.ContainsKey(callId) || !_pending.Add(callId)) return;
        }
        BluetoothCall call = await BluetoothCall.CreateAsync(active);
        lock (_gate)
        {
            _pending.Remove(callId);
            _calls[callId] = call;
        }
        call.Changed += () => OnCallChanged(call);
        VeyroTrace.Info($"Bluetooth call tracked: {call.Status}");
        CallAdded?.Invoke(call);
        Raise();
    }

    private void OnCallChanged(BluetoothCall call)
    {
        if (CallPresentation.IsOver(call.Status))
        {
            bool removed;
            lock (_gate) removed = _calls.Remove(call.CallId);
            Raise();
            if (removed)
            {
                VeyroTrace.Info("Bluetooth call ended");
                // Handlers of this Changed event already hold their snapshot, so the window still
                // sees the final status before the wrapper lets go of the WinRT call.
                call.Dispose();
            }
            return;
        }
        Raise();
    }

    private bool IsOurs(PhoneLine line)
    {
        lock (_gate)
        {
            return _status == BluetoothLineStatus.Connected && _transportId is not null &&
                   string.Equals(line.TransportDeviceId, _transportId, StringComparison.OrdinalIgnoreCase);
        }
    }

    private void ClearCalls()
    {
        BluetoothCall[] calls;
        lock (_gate)
        {
            calls = [.. _calls.Values];
            _calls.Clear();
        }
        foreach (BluetoothCall call in calls) call.Dispose();
    }

    private void SetStatus(BluetoothLineStatus status, string transportId, string? peerId)
    {
        lock (_gate)
        {
            _status = status;
            _transportId = transportId;
            _peerId = peerId;
        }
        Raise();
    }

    private void Raise()
    {
        if (Changed is not { } handlers) return;
        foreach (Action handler in handlers.GetInvocationList().Cast<Action>())
        {
            try { handler(); }
            catch (Exception error) { VeyroTrace.Error("Bluetooth calling change handler failed", error); }
        }
    }

    private static bool ContractPresent()
    {
        try { return ApiInformation.IsApiContractPresent(CallsPhoneContract, 6); }
        catch (Exception) { return false; }
    }
}
