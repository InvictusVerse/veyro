using System.Collections.Concurrent;
using Veyro.Core.Diagnostics;
using Windows.ApplicationModel.Calls;
using Windows.Devices.Enumeration;
using Windows.Foundation.Metadata;
using WinRtCall = Windows.ApplicationModel.Calls.PhoneCall;

namespace Veyro.Platform.Calling;

/// <summary>
/// The PC as the phone's Bluetooth hands-free device (ADR-029). It lists paired phones that offer
/// a phone line transport, registers Veyro as the app for the chosen one, connects it and follows
/// the calls on its line through <c>PhoneLine</c> and <c>PhoneCallManager</c>.
/// <para>
/// Limited Access Feature (ADR-028): Veyro holds no unlock token for the phone line transport.
/// The service therefore relies on Windows 11, where the transport APIs are open to every app,
/// and reports <see cref="BluetoothLineStatus.NotSupported"/> when the calling API contract
/// is missing. Ids, statuses and counts may be logged. Numbers and names never.
/// </para>
/// </summary>
public sealed class BluetoothCallingService : IBluetoothCallingService
{
    private const string CallsContractName = "Windows.ApplicationModel.Calls.CallsPhoneContract";
    private const ushort CallsContractMajorVersion = 6;

    /// <summary>The link state the UI reads. Replaced as a whole under <see cref="_sync"/>.</summary>
    private sealed record LinkState(BluetoothLineStatus Status, string? TransportId, string? PeerId);

    private readonly ISettingsStore _settings;
    private readonly object _sync = new();
    private readonly SemaphoreSlim _linkChange = new(1, 1);
    private readonly ConcurrentDictionary<Guid, PhoneLine> _knownLines = new();
    private readonly ConcurrentDictionary<string, BluetoothCall> _liveCalls = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _claimedCalls = new(StringComparer.Ordinal);
    private volatile LinkState _link;
    private volatile IReadOnlyList<BluetoothPhoneInfo> _phones = [];
    private PhoneLineWatcher? _watcher;
    private int _started;
    private bool _disposed;

    public BluetoothCallingService(ISettingsStore settings)
    {
        _settings = settings;
        IsSupported = CallingApiAvailable();
        var saved = settings.Current;
        _link = new LinkState(
            IsSupported ? BluetoothLineStatus.NotPaired : BluetoothLineStatus.NotSupported,
            saved.BluetoothCallTransportId,
            saved.BluetoothCallPeerId);
    }

    public event Action? Changed;
    public event Action<IBluetoothCall>? CallAdded;

    public bool IsSupported { get; }
    public BluetoothLineStatus Status => _link.Status;
    public string? SelectedTransportId => _link.TransportId;
    public IReadOnlyList<BluetoothPhoneInfo> Phones => _phones;
    public IReadOnlyList<IBluetoothCall> Calls => [.. _liveCalls.Values];

    /// <summary>The peer is only meaningful while the line is connected.</summary>
    public string? LinkedPeerId => _link.Status == BluetoothLineStatus.Connected ? _link.PeerId : null;

    public bool HasIncomingCall => _liveCalls.Values.Any(call => call.Status == BluetoothCallStatus.Incoming);

    public async Task InitializeAsync()
    {
        if (!IsSupported)
        {
            VeyroTrace.Info("Bluetooth calling is not available on this Windows version");
            return;
        }
        if (Interlocked.Exchange(ref _started, 1) != 0) return;

        await RefreshPhonesAsync();
        await StartLineWatcherAsync();

        // Windows keeps the app registration between runs, so only the connection is repeated.
        string? remembered = SelectedTransportId;
        if (remembered is not null && _phones.Any(phone => phone.TransportId == remembered))
            await ConnectAsync(remembered, _link.PeerId);
    }

    public async Task RefreshPhonesAsync()
    {
        if (!IsSupported) return;
        try
        {
            DeviceInformationCollection devices = await DeviceInformation.FindAllAsync(
                PhoneLineTransportDevice.GetDeviceSelector(PhoneLineTransport.Bluetooth));
            List<BluetoothPhoneInfo> phones = [];
            foreach (DeviceInformation device in devices)
            {
                if (string.IsNullOrEmpty(device.Id)) continue;
                phones.Add(new BluetoothPhoneInfo(device.Id, string.IsNullOrWhiteSpace(device.Name) ? "Bluetooth phone" : device.Name));
            }

            lock (_sync)
            {
                _phones = phones;
                // Only an idle link is re-evaluated. A connecting or connected one keeps its state.
                if (_link.Status is BluetoothLineStatus.NotPaired or BluetoothLineStatus.NotSelected)
                    _link = _link with { Status = IdleStatus(phones.Count, _link.TransportId) };
            }
            VeyroTrace.Info($"Bluetooth phones offering a phone line: {phones.Count}");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Listing Bluetooth phones failed", error);
        }
        NotifyChanged();
    }

    public async Task ConnectAsync(string transportId, string? peerId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(transportId);
        if (!IsSupported) return;

        await _linkChange.WaitAsync();
        try
        {
            SetLink(BluetoothLineStatus.Connecting, transportId, peerId);
            BluetoothLineStatus outcome = await OpenTransportAsync(transportId);
            SetLink(outcome, transportId, peerId);
            await _settings.UpdateAsync(s => s with { BluetoothCallTransportId = transportId, BluetoothCallPeerId = peerId });
            if (outcome == BluetoothLineStatus.Connected) await ReadAllLinesAsync();
        }
        finally
        {
            _linkChange.Release();
        }
    }

    public async Task DisconnectAsync()
    {
        await _linkChange.WaitAsync();
        try
        {
            ReleaseTransport(SelectedTransportId);
            DropAllCalls();
            lock (_sync) _link = new LinkState(IdleStatus(_phones.Count, null), null, null);
            await _settings.UpdateAsync(s => s with { BluetoothCallTransportId = null, BluetoothCallPeerId = null });
            VeyroTrace.Info("Bluetooth calling disconnected");
            NotifyChanged();
        }
        finally
        {
            _linkChange.Release();
        }
    }

    public async Task<bool> AnswerIncomingAsync()
    {
        BluetoothCall? ringing = FindRinging();
        if (ringing is null || !await ringing.AcceptAsync()) return false;

        // The call is answered for hands-free use, so its audio has to end up on this PC.
        if (ringing.AudioRoute != CallAudioRoute.ThisPc) await ringing.SetAudioRouteAsync(CallAudioRoute.ThisPc);
        return true;
    }

    public async Task<bool> RejectIncomingAsync()
    {
        BluetoothCall? ringing = FindRinging();
        return ringing is not null && await ringing.RejectAsync();
    }

    public void Dispose()
    {
        lock (_sync)
        {
            if (_disposed) return;
            _disposed = true;
        }
        if (_watcher is { } watcher)
        {
            watcher.LineAdded -= OnLineSeen;
            watcher.LineUpdated -= OnLineSeen;
            watcher.LineRemoved -= OnLineGone;
            try { watcher.Stop(); }
            catch (Exception error) { VeyroTrace.Error("Stopping the phone line watcher failed", error); }
            _watcher = null;
        }
        if (_started != 0) PhoneCallManager.CallStateChanged -= OnCallStateChanged;
        DropAllCalls();
        _linkChange.Dispose();
    }

    // ---- connection ----------------------------------------------------------------------

    private async Task StartLineWatcherAsync()
    {
        try
        {
            PhoneCallStore store = await PhoneCallManager.RequestStoreAsync();
            PhoneLineWatcher watcher = store.RequestLineWatcher();
            watcher.LineAdded += OnLineSeen;
            watcher.LineUpdated += OnLineSeen;
            watcher.LineRemoved += OnLineGone;
            _watcher = watcher;
            watcher.Start();
            PhoneCallManager.CallStateChanged += OnCallStateChanged;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("The phone line watcher could not start", error);
        }
    }

    private static async Task<BluetoothLineStatus> OpenTransportAsync(string transportId)
    {
        try
        {
            PhoneLineTransportDevice? transport = PhoneLineTransportDevice.FromId(transportId);
            if (transport is null) return BluetoothLineStatus.NotPaired;

            DeviceAccessStatus access = await transport.RequestAccessAsync();
            if (access != DeviceAccessStatus.Allowed)
            {
                VeyroTrace.Warn($"Windows answered the phone line access request with {access}");
                return BluetoothLineStatus.AccessDenied;
            }

            if (!transport.IsRegistered()) transport.RegisterApp();
            if (!transport.IsRegistered())
            {
                VeyroTrace.Warn("Registering for the phone line failed; another app may hold it");
                return BluetoothLineStatus.AccessDenied;
            }

            if (!await transport.ConnectAsync())
            {
                VeyroTrace.Warn("The phone line did not connect");
                return BluetoothLineStatus.Error;
            }
            VeyroTrace.Info("Bluetooth phone line connected");
            return BluetoothLineStatus.Connected;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Opening the Bluetooth phone line failed", error);
            return BluetoothLineStatus.Error;
        }
    }

    private static void ReleaseTransport(string? transportId)
    {
        if (transportId is null) return;
        try
        {
            PhoneLineTransportDevice? transport = PhoneLineTransportDevice.FromId(transportId);
            if (transport is not null && transport.IsRegistered()) transport.UnregisterApp();
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Giving the phone line back failed", error);
        }
    }

    private void SetLink(BluetoothLineStatus status, string? transportId, string? peerId)
    {
        lock (_sync) _link = new LinkState(status, transportId, peerId);
        NotifyChanged();
    }

    private static BluetoothLineStatus IdleStatus(int phoneCount, string? chosenTransportId)
    {
        if (phoneCount == 0) return BluetoothLineStatus.NotPaired;
        return chosenTransportId is null ? BluetoothLineStatus.NotSelected : BluetoothLineStatus.Disconnected;
    }

    // ---- phone lines and calls -----------------------------------------------------------

    private async void OnLineSeen(PhoneLineWatcher sender, PhoneLineWatcherEventArgs args)
    {
        try
        {
            PhoneLine? line = await PhoneLine.FromIdAsync(args.LineId);
            if (line is null) return;
            _knownLines[args.LineId] = line;
            if (OnConnectedLine(line)) await ReadLineAsync(line);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading a phone line failed", error);
        }
    }

    private void OnLineGone(PhoneLineWatcher sender, PhoneLineWatcherEventArgs args)
        => _knownLines.TryRemove(args.LineId, out _);

    private async void OnCallStateChanged(object? sender, object args)
    {
        try { await ReadAllLinesAsync(); }
        catch (Exception error) { VeyroTrace.Error("Reading Bluetooth calls failed", error); }
    }

    private bool OnConnectedLine(PhoneLine line)
    {
        LinkState link = _link;
        return link.Status == BluetoothLineStatus.Connected
               && link.TransportId is { } transportId
               && string.Equals(line.TransportDeviceId, transportId, StringComparison.OrdinalIgnoreCase);
    }

    private async Task ReadAllLinesAsync()
    {
        foreach (PhoneLine line in _knownLines.Values.Where(OnConnectedLine).ToArray())
            await ReadLineAsync(line);
    }

    private async Task ReadLineAsync(PhoneLine line)
    {
        try
        {
            PhoneCallsResult result = await line.GetAllActivePhoneCallsAsync();
            foreach (WinRtCall native in result.AllActivePhoneCalls) await AdoptCallAsync(native);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading the active calls of the phone line failed", error);
        }
    }

    private async Task AdoptCallAsync(WinRtCall native)
    {
        string callId = native.CallId;
        if (string.IsNullOrEmpty(callId) || CallPresentation.IsOver(BluetoothCall.StatusOf(native.Status))) return;
        // A call is announced once, even when two events report it while its details load.
        if (!_claimedCalls.TryAdd(callId, 0)) return;

        BluetoothCall call;
        try
        {
            call = await BluetoothCall.OpenAsync(native);
        }
        catch
        {
            _claimedCalls.TryRemove(callId, out _);
            throw;
        }

        _liveCalls[callId] = call;
        call.Changed += () => OnTrackedCallChanged(call);
        if (CallPresentation.IsOver(call.Status))
        {
            // It ended while its details were loading: nobody was told about it, so say nothing.
            RetireCall(call, announce: false);
            return;
        }

        VeyroTrace.Info($"Bluetooth call tracked: {call.Status}");
        CallAdded?.Invoke(call);
        NotifyChanged();
    }

    private void OnTrackedCallChanged(BluetoothCall call)
    {
        if (CallPresentation.IsOver(call.Status)) RetireCall(call, announce: true);
        else NotifyChanged();
    }

    private void RetireCall(BluetoothCall call, bool announce)
    {
        bool wasLive = _liveCalls.TryRemove(call.CallId, out _);
        _claimedCalls.TryRemove(call.CallId, out _);
        if (announce) NotifyChanged();
        if (!wasLive) return;
        if (announce) VeyroTrace.Info("Bluetooth call ended");
        // Subscribers have already read the final status by now. Releasing the Windows call is safe.
        call.Dispose();
    }

    private void DropAllCalls()
    {
        foreach (BluetoothCall call in _liveCalls.Values.ToArray())
        {
            _liveCalls.TryRemove(call.CallId, out _);
            call.Dispose();
        }
        _claimedCalls.Clear();
    }

    private BluetoothCall? FindRinging()
        => _liveCalls.Values.FirstOrDefault(call => call.Status == BluetoothCallStatus.Incoming);

    private void NotifyChanged()
    {
        Action? subscribers = Changed;
        if (subscribers is null) return;
        foreach (Delegate subscriber in subscribers.GetInvocationList())
        {
            try { ((Action)subscriber)(); }
            catch (Exception error) { VeyroTrace.Error("A Bluetooth calling change handler failed", error); }
        }
    }

    private static bool CallingApiAvailable()
    {
        try { return ApiInformation.IsApiContractPresent(CallsContractName, CallsContractMajorVersion); }
        catch (Exception) { return false; }
    }
}
