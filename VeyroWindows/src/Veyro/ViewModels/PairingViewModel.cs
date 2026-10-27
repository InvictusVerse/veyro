using System.Net;

namespace Veyro.ViewModels;

/// <summary>
/// The PC pairing screen (ADR-030): QR code and on-screen code from the listener's
/// <see cref="PairingWindow"/>, nearby phones from discovery, and a network check. Shared by
/// onboarding and Settings. The window is open only between <see cref="Start"/> and
/// <see cref="Stop"/>, which the hosting view calls while the screen is visible.
/// The code and the QR content are never logged.
/// </summary>
public sealed class PairingViewModel : BaseViewModel
{
    private readonly VeyroLinkServer _server;
    private readonly IDiscoveryService _discovery;
    private readonly ITrustStore _trustStore;
    private readonly IIdentityManager _identity;
    private readonly ISettingsStore _settings;
    private readonly INetworkDiagnosticsService _network;
    private readonly IQrCodeRenderer _qr;
    private readonly Func<string?> _startupError;
    private readonly DispatcherQueue? _dispatcher;
    private DispatcherQueueTimer? _tick;
    private bool _active;
    private string? _qrContent;
    private byte[]? _qrPng;
    private string _codeText = string.Empty;
    private string _remainingText = string.Empty;
    private double _expiryProgress;
    private bool _isOpen;
    private bool _isCheckingNetwork;
    private string _checksSignature = string.Empty;
    private NetworkCheckResult? _lastCheck;

    public PairingViewModel(
        VeyroLinkServer server,
        IDiscoveryService discovery,
        ITrustStore trustStore,
        IIdentityManager identity,
        ISettingsStore settings,
        INetworkDiagnosticsService network,
        IQrCodeRenderer qr,
        Func<string?> startupError)
    {
        _server = server;
        _discovery = discovery;
        _trustStore = trustStore;
        _identity = identity;
        _settings = settings;
        _network = network;
        _qr = qr;
        _startupError = startupError;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        NearbyPhones.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoNearbyPhones));
    }

    /// <summary>A phone paired while this screen was open. UI thread.</summary>
    public event Action<TrustedPeer>? Paired;

    public ObservableCollection<NearbyPhoneUiModel> NearbyPhones { get; } = [];
    public ObservableCollection<NetworkCheckUiModel> NetworkChecks { get; } = [];

    public bool HasNoNearbyPhones => NearbyPhones.Count == 0;

    public string PcName => _settings.Current.DeviceName;
    public string PcFingerprint => _identity.Fingerprint;

    /// <summary>PNG of the current QR, or null when closed or no address can be advertised.</summary>
    public byte[]? QrPng
    {
        get => _qrPng;
        private set
        {
            if (SetProperty(ref _qrPng, value))
            {
                OnPropertyChanged(nameof(HasQr));
                OnPropertyChanged(nameof(QrUnavailable));
            }
        }
    }

    public bool HasQr => _qrPng is not null;
    public bool QrUnavailable => _isOpen && _qrPng is null;
    public bool IsExpired => _active && !_isOpen;
    public bool IsOpen => _isOpen;

    /// <summary>"123 456": grouped so it is easy to read out and type.</summary>
    public string CodeText
    {
        get => _codeText;
        private set => SetProperty(ref _codeText, value);
    }

    public string RemainingText
    {
        get => _remainingText;
        private set => SetProperty(ref _remainingText, value);
    }

    /// <summary>100 when the code is fresh, 0 when it expires.</summary>
    public double ExpiryProgress
    {
        get => _expiryProgress;
        private set => SetProperty(ref _expiryProgress, value);
    }

    public bool IsCheckingNetwork
    {
        get => _isCheckingNetwork;
        private set => SetProperty(ref _isCheckingNetwork, value);
    }

    public void Start()
    {
        if (_active) return;
        _active = true;
        _server.PairingWindow.Changed += OnWindowChanged;
        _server.PairingCompleted += OnPairingCompleted;
        _discovery.PeersUpdated += OnPeersUpdated;
        try { _ = _discovery.StartAsync(); }
        catch (Exception error) { Core.Diagnostics.VeyroTrace.Error("Discovery could not start for the pairing screen", error); }
        _server.PairingWindow.Open();

        if (_dispatcher is not null)
        {
            _tick ??= _dispatcher.CreateTimer();
            _tick.Interval = TimeSpan.FromSeconds(1);
            _tick.IsRepeating = true;
            _tick.Tick += OnTick;
            _tick.Start();
        }
        OnPropertyChanged(nameof(PcName));
        OnPropertyChanged(nameof(PcFingerprint));
        UpdateNearby(_discovery.DiscoveredPeers);
        Refresh();
        _ = RefreshNetworkChecksAsync();
    }

    public void Stop()
    {
        if (!_active) return;
        _active = false;
        if (_tick is not null)
        {
            _tick.Stop();
            _tick.Tick -= OnTick;
        }
        _server.PairingWindow.Changed -= OnWindowChanged;
        _server.PairingCompleted -= OnPairingCompleted;
        _discovery.PeersUpdated -= OnPeersUpdated;
        _server.PairingWindow.Close();
        _qrContent = null;
        Refresh();
    }

    /// <summary>Replaces the code and restarts the five minutes.</summary>
    public void NewCode()
    {
        if (!_active) return;
        _server.PairingWindow.Open();
        Refresh();
    }

    public async Task RefreshNetworkChecksAsync()
    {
        if (IsCheckingNetwork) return;
        IsCheckingNetwork = true;
        try
        {
            _lastCheck = await _network.CheckAsync();
        }
        finally
        {
            IsCheckingNetwork = false;
            _checksSignature = string.Empty;
            RebuildChecks();
        }
    }

    public async Task AddFirewallRuleAsync()
    {
        // Pressed by the user, so it asks even after the one startup prompt.
        await _network.RequestFirewallRuleAsync();
        await RefreshNetworkChecksAsync();
    }

    private void OnTick(DispatcherQueueTimer sender, object args) => Refresh();

    private void OnWindowChanged() => Post(Refresh);

    private void OnPairingCompleted(TrustedPeer peer) => Post(() =>
    {
        if (!_active) return;
        // Pairing closed the window; stop so the screen does not show it as expired.
        Stop();
        Paired?.Invoke(peer);
    });

    private void OnPeersUpdated(IReadOnlyList<PeerDevice> peers) => Post(() =>
    {
        if (_active) UpdateNearby(peers);
    });

    private void Refresh()
    {
        PairingWindow window = _server.PairingWindow;
        string? code = _active ? window.Code : null;
        bool open = code is not null;
        if (_isOpen != open)
        {
            _isOpen = open;
            OnPropertyChanged(nameof(IsOpen));
        }
        OnPropertyChanged(nameof(IsExpired));
        OnPropertyChanged(nameof(QrUnavailable));

        CodeText = code is null ? "--- ---" : $"{code[..3]} {code[3..]}";
        TimeSpan remaining = open ? window.Remaining : TimeSpan.Zero;
        RemainingText = open
            ? $"Expires in {(int)remaining.TotalMinutes}:{remaining.Seconds:00}"
            : _active ? "This code has expired" : string.Empty;
        ExpiryProgress = Math.Clamp(remaining.TotalSeconds / PairingWindow.Lifetime.TotalSeconds * 100, 0, 100);

        string? content = open ? window.QrContent : null;
        if (!string.Equals(content, _qrContent, StringComparison.Ordinal))
        {
            _qrContent = content;
            QrPng = content is null ? null : RenderOrNull(content);
        }
        RebuildChecks();
    }

    private byte[]? RenderOrNull(string content)
    {
        try { return _qr.RenderPng(content); }
        catch (Exception error)
        {
            // The exception text cannot contain the content; QRCoder reports only sizes.
            Core.Diagnostics.VeyroTrace.Error("QR rendering failed", error);
            return null;
        }
    }

    private void UpdateNearby(IReadOnlyList<PeerDevice> peers)
    {
        var phones = peers
            .Where(peer => peer.Platform != PlatformType.Windows)
            .OrderBy(peer => peer.DisplayName, StringComparer.CurrentCultureIgnoreCase)
            .Select(peer => new NearbyPhoneUiModel(
                peer.DeviceId,
                peer.DisplayName,
                peer.Fingerprint ?? string.Join(", ", peer.Endpoints.Select(e => e.Host)),
                _trustStore.GetPeer(peer.DeviceId) is not null))
            .ToList();
        if (phones.SequenceEqual(NearbyPhones)) return;
        NearbyPhones.Clear();
        foreach (NearbyPhoneUiModel phone in phones) NearbyPhones.Add(phone);
    }

    /// <summary>
    /// Listener and address checks are cheap and re-evaluated every tick; firewall and network
    /// profile come from the last <see cref="RefreshNetworkChecksAsync"/>.
    /// </summary>
    private void RebuildChecks()
    {
        var checks = new List<NetworkCheckUiModel>();
        if (_server.IsRunning)
        {
            checks.Add(new("Veyro is listening", $"Phones connect to this PC on port {_server.Port}.", true));
        }
        else
        {
            checks.Add(new("Veyro is not listening yet",
                _startupError() ?? "The connection listener has not started. If this stays, restart Veyro.", false));
        }

        IReadOnlyList<IPEndPoint> endpoints = PairingQr.LocalEndpoints(_server.Port);
        if (endpoints.Count == 0)
        {
            checks.Add(new("No local network address",
                "This PC has no private IPv4 address. Connect it to the same Wi-Fi, Ethernet network or hotspot as your phone.",
                false, "Open network settings", NetworkCheckAction.OpenNetworkSettings));
        }
        else
        {
            checks.Add(new("Connected to a local network",
                "Reachable at " + string.Join(", ", endpoints.Select(e => e.Address)), true));
        }

        switch (_lastCheck?.Firewall)
        {
            case FirewallRuleState.Present:
                checks.Add(new("Firewall allows Veyro", "Inbound TCP 5150-5169 is allowed on private networks.", true));
                break;
            case FirewallRuleState.Missing:
                checks.Add(new("Firewall rule missing",
                    "Windows Firewall may block your phone. Adding the rule needs administrator approval.",
                    false, "Add rule", NetworkCheckAction.AddFirewallRule));
                break;
            case FirewallRuleState.Unknown:
                checks.Add(new("Could not read the firewall",
                    "If your phone cannot connect, add the Veyro firewall rule.",
                    false, "Add rule", NetworkCheckAction.AddFirewallRule));
                break;
        }

        if (_lastCheck is { Profiles.Count: > 0 } check)
        {
            var publicNetworks = check.Profiles.Where(p => p.IsPublic).Select(p => p.InterfaceName).ToList();
            checks.Add(publicNetworks.Count > 0
                ? new NetworkCheckUiModel("Network set to Public",
                    $"{string.Join(", ", publicNetworks)} is Public. Windows blocks discovery and the Veyro rule there. Set it to Private in network settings.",
                    false, "Open network settings", NetworkCheckAction.OpenNetworkSettings)
                : new NetworkCheckUiModel("Network set to Private", "Discovery and connections are allowed.", true));
        }

        string signature = string.Join('|', checks.Select(c => c.Title + c.Detail));
        if (signature == _checksSignature) return;
        _checksSignature = signature;
        NetworkChecks.Clear();
        foreach (NetworkCheckUiModel item in checks) NetworkChecks.Add(item);
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}
