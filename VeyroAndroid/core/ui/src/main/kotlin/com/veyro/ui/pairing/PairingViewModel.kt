package com.veyro.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.link.ConnectionManager
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.TrustState
import com.veyro.core.model.fingerprint
import com.veyro.core.protocol.PairingQrCode
import com.veyro.core.protocol.Vlp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Drives pairing through [ConnectionManager].
 *
 * ## How pairing works
 *
 * The two devices find each other over discovery, which gives each an address for the other. The
 * initiator dials; the target shows a 6-digit PIN; the initiator types it. The PIN is the only
 * secret, and it is protected by PBKDF2 plus a short window plus an attempt limit (ADR-020). No key
 * material crosses the network in the clear.
 *
 * ## What this class is and is not responsible for
 *
 * It owns the discovery window and relays the user's answers (consent on the target, PIN on the
 * initiator) to [ConnectionManager], which runs the actual exchange. It does **not** persist trust - the
 * link layer does that only after a completed exchange, which is what keeps "trust is created by a
 * finished pairing" a single enforceable path.
 */
public class PairingViewModel(
    private val deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    public data class Candidate(
        val deviceId: DeviceId,
        val name: String,
        val platform: Platform,
        val endpoints: List<Endpoint>,
        val isAlreadyPaired: Boolean,
    )

    public data class UiState(
        val isSearching: Boolean = false,
        val secondsRemaining: Int = 0,
        val candidates: List<Candidate> = emptyList(),

        /** The target's PIN to read aloud, set once a peer starts pairing with this device. */
        val inboundPin: String? = null,
        val inboundPeerName: String? = null,

        /** True when this device is the initiator and must prompt for the PIN. */
        val awaitingPinEntry: Boolean = false,
        val outboundPeerName: String? = null,

        val status: String? = null,
        val error: String? = null,

        /** Scan-to-pair progress (ADR-030). */
        val qr: QrPairingState = QrPairingState.Idle,

        /** "Enter code instead" was chosen, or the scanner is unavailable: explain the list path. */
        val showCodeEntryHint: Boolean = false,
    ) {
        val qrBusy: Boolean
            get() = qr is QrPairingState.Connecting || qr is QrPairingState.Securing
    }

    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    private var subscription: DiscoverySubscription? = null
    private var countdown: kotlinx.coroutines.Job? = null
    private var qrJob: Job? = null

    init {
        deviceRepository.devices
            .onEach { peers -> _state.value = _state.value.copy(candidates = peers.toCandidates()) }
            .launchIn(viewModelScope)

        // The target device: a peer asked to pair, so show the PIN and the consent is implicit in
        // the user having opened this screen. (Explicit consent arrives with the inbound dialog.)
        connectionManager.pendingInbound
            .onEach { pending ->
                _state.value = _state.value.copy(
                    inboundPin = pending?.pin?.takeIf { it != "..." },
                    inboundPeerName = pending?.peerDeviceName,
                )
            }
            .launchIn(viewModelScope)

        // The initiator device: the target answered "pairing", so prompt for the PIN.
        connectionManager.pendingOutbound
            .onEach { pending ->
                _state.value = _state.value.copy(
                    awaitingPinEntry = pending != null,
                    outboundPeerName = pending?.peerDeviceName,
                )
            }
            .launchIn(viewModelScope)

        connectionManager.events
            .onEach { event ->
                // A scan-to-pair attempt reports through its own card; do not show it twice.
                if (qrJob?.isActive == true) return@onEach
                when (event) {
                    is ConnectionManager.ConnectionEvent.Paired ->
                        _state.value = _state.value.copy(status = "Paired with ${event.peer.name}", error = null)
                    is ConnectionManager.ConnectionEvent.Failed ->
                        _state.value = _state.value.copy(error = event.reason, status = null)
                    is ConnectionManager.ConnectionEvent.Connected ->
                        _state.value = _state.value.copy(status = "Connected to ${event.peerName}")
                    else -> Unit
                }
            }
            .launchIn(viewModelScope)
    }

    /** Opens discovery and starts listening, so this device can both see peers and be paired with. */
    public fun startSearching() {
        if (subscription != null) return
        connectionManager.startListening()
        subscription = deviceRepository.observeNearby(forPairing = true)
        _state.value = _state.value.copy(
            isSearching = true,
            secondsRemaining = (Vlp.PAIRING_WINDOW_MILLIS / 1000).toInt(),
            error = null,
        )
        countdown = viewModelScope.launch {
            var remaining = (Vlp.PAIRING_WINDOW_MILLIS / 1000).toInt()
            while (remaining > 0) {
                kotlinx.coroutines.delay(1_000)
                remaining--
                _state.value = _state.value.copy(secondsRemaining = remaining)
            }
            stopSearching()
        }
    }

    public fun stopSearching() {
        countdown?.cancel(); countdown = null
        subscription?.close(); subscription = null
        _state.value = _state.value.copy(isSearching = false, secondsRemaining = 0)
    }

    /** Initiator action: dial a discovered device to begin pairing. */
    public fun pairWith(candidate: Candidate) {
        _state.value = _state.value.copy(status = "Contacting ${candidate.name}...", error = null)
        viewModelScope.launch {
            connectionManager.connect(candidate.deviceId.value, candidate.name, candidate.endpoints)
        }
    }

    /** Initiator action: submit the PIN read from the other device. Null cancels. */
    public fun submitPin(pin: String?) {
        connectionManager.submitPin(pin)
    }

    /** Target action: accept or decline an incoming pairing request. */
    public fun respondToInbound(accept: Boolean) {
        connectionManager.respondToInbound(accept)
    }

    public fun clearStatus() {
        _state.value = _state.value.copy(status = null, error = null)
        connectionManager.clearEvent()
    }

    // ── Scan to pair (ADR-030) ─────────────────────────────────────────────────────────────

    /**
     * Runs [scan] in the ViewModel scope so a rotation while the scanner is open does not lose
     * the result. [scan] is the Play services scanner in production.
     */
    public fun scanQr(scan: suspend () -> QrScanOutcome) {
        if (_state.value.qrBusy) return
        viewModelScope.launch { onScanOutcome(scan()) }
    }

    /** Handles one scan result. The raw text is parsed here and never logged or stored. */
    public fun onScanOutcome(outcome: QrScanOutcome) {
        when (outcome) {
            QrScanOutcome.Cancelled -> Unit
            QrScanOutcome.Unavailable -> {
                _state.value = _state.value.copy(
                    qr = QrPairingState.Failed(QrPairingError.SCANNER_UNAVAILABLE),
                    showCodeEntryHint = true,
                )
                startSearching()
            }
            is QrScanOutcome.Scanned -> when (val result = PairingQrCode.parse(outcome.raw, nowEpochSeconds())) {
                is PairingQrCode.Result.Invalid ->
                    _state.value = _state.value.copy(qr = QrPairingState.Failed(QrPairingError.from(result.error)))
                is PairingQrCode.Result.Valid -> pairWithScannedCode(result.qr)
            }
        }
    }

    /** "Enter code instead": the list-plus-typed-code path, which needs discovery running. */
    public fun showCodeEntry() {
        _state.value = _state.value.copy(showCodeEntryHint = true)
        startSearching()
    }

    public fun dismissQrStatus() {
        if (_state.value.qrBusy) return
        _state.value = _state.value.copy(qr = QrPairingState.Idle)
    }

    private fun pairWithScannedCode(qr: PairingQrCode) {
        qrJob?.cancel()
        _state.value = _state.value.copy(qr = QrPairingState.Connecting(qr.name), status = null, error = null)
        // Scanned endpoints first, then any discovery has seen for the same id. Discovery is
        // untrusted, but here it only adds addresses; the identity check decides who is real.
        val discovered = _state.value.candidates.firstOrNull { it.deviceId == qr.deviceId }?.endpoints.orEmpty()
        val endpoints = (qr.endpoints + discovered).distinct()
        qrJob = viewModelScope.launch {
            connectionManager.pairWithCode(
                peerDeviceId = qr.deviceId.value,
                peerName = qr.name ?: "your PC",
                endpoints = endpoints,
                code = qr.code,
            ).collect { progress ->
                val next = when (progress) {
                    ConnectionManager.CodePairingProgress.Connecting -> QrPairingState.Connecting(qr.name)
                    ConnectionManager.CodePairingProgress.Securing -> QrPairingState.Securing(qr.name)
                    is ConnectionManager.CodePairingProgress.Paired ->
                        QrPairingState.Paired(progress.peer.name, progress.peer.deviceId.fingerprint())
                    is ConnectionManager.CodePairingProgress.AlreadyPaired ->
                        QrPairingState.Paired(progress.peerName, qr.deviceId.fingerprint())
                    is ConnectionManager.CodePairingProgress.Failed ->
                        QrPairingState.Failed(QrPairingError.from(progress.failure))
                }
                _state.value = _state.value.copy(qr = next)
            }
            connectionManager.clearEvent()
        }
    }

    private fun List<PeerDevice>.toCandidates(): List<Candidate> = map { peer ->
        Candidate(
            deviceId = peer.deviceId,
            name = peer.displayName,
            platform = peer.platform,
            endpoints = peer.endpoints,
            isAlreadyPaired = peer.trust == TrustState.TRUSTED,
        )
    }

    override fun onCleared() {
        stopSearching()
        super.onCleared()
    }
}
