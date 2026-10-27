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
import com.veyro.core.protocol.ProtocolConstants
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
 * Two paths, neither of which involves typing (ADR-030, ADR-031):
 *
 *  - **Scan**: the PC shows a QR code carrying its id, addresses and a one-time code. The phone
 *    scans it and pairs without a prompt on either side.
 *  - **Nearby list**: the phone dials a discovered device. Both screens show the same 6-digit
 *    code, derived from both identity keys and a committed nonce exchange; the user checks they
 *    match and accepts on the other device.
 *
 * ## What this class is and is not responsible for
 *
 * It owns the discovery window and relays the user's answers to [ConnectionManager], which runs
 * the actual exchange. It does **not** persist trust - the link layer does that only after a
 * completed exchange, which is what keeps "trust is created by a finished pairing" a single
 * enforceable path. Inbound requests (another device pairing with this one) are shown by the
 * global `InboundPairingDialog`, not here.
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

        val status: String? = null,
        val error: String? = null,

        /** Scan-to-pair progress (ADR-030). */
        val qr: QrPairingState = QrPairingState.Idle,

        /** Nearby-list pairing progress (confirm code, ADR-031). */
        val confirm: ConfirmPairingState = ConfirmPairingState.Idle,

        /** "Pick from the list" was chosen, or the scanner is unavailable: explain the list path. */
        val showCodeEntryHint: Boolean = false,
    ) {
        val qrBusy: Boolean
            get() = qr is QrPairingState.Connecting || qr is QrPairingState.Securing

        val confirmBusy: Boolean
            get() = confirm is ConfirmPairingState.Connecting ||
                confirm is ConfirmPairingState.Securing ||
                confirm is ConfirmPairingState.AwaitingAcceptance

        /** One pairing at a time: the list and the scanner are disabled while either runs. */
        val pairingBusy: Boolean get() = qrBusy || confirmBusy
    }

    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    private var subscription: DiscoverySubscription? = null
    private var countdown: kotlinx.coroutines.Job? = null
    private var qrJob: Job? = null
    private var confirmJob: Job? = null
    private var lastConfirmCandidate: Candidate? = null

    init {
        deviceRepository.devices
            .onEach { peers -> _state.value = _state.value.copy(candidates = peers.toCandidates()) }
            .launchIn(viewModelScope)

        connectionManager.events
            .onEach { event ->
                // Each pairing attempt reports through its own card or dialog; do not show it twice.
                if (qrJob?.isActive == true || confirmJob?.isActive == true) return@onEach
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
            secondsRemaining = (ProtocolConstants.PAIRING_WINDOW_MILLIS / 1000).toInt(),
            error = null,
        )
        countdown = viewModelScope.launch {
            var remaining = (ProtocolConstants.PAIRING_WINDOW_MILLIS / 1000).toInt()
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

    // ── Nearby list: confirm code (ADR-031) ───────────────────────────────────────────────

    /** Initiator action: pair with a discovered device by comparing a code on both screens. */
    public fun pairWith(candidate: Candidate) {
        if (_state.value.pairingBusy) return
        lastConfirmCandidate = candidate
        val onPc = candidate.platform != Platform.ANDROID
        _state.value = _state.value.copy(
            confirm = ConfirmPairingState.Connecting(candidate.name, onPc),
            status = null,
            error = null,
        )
        confirmJob = viewModelScope.launch {
            connectionManager.pairWithConfirm(
                peerDeviceId = candidate.deviceId.value,
                peerName = candidate.name,
                endpoints = candidate.endpoints,
            ).collect { progress ->
                val next = when (progress) {
                    ConnectionManager.CodePairingProgress.Connecting ->
                        ConfirmPairingState.Connecting(candidate.name, onPc)
                    ConnectionManager.CodePairingProgress.Securing ->
                        ConfirmPairingState.Securing(candidate.name, onPc)
                    is ConnectionManager.CodePairingProgress.AwaitingConfirmation ->
                        ConfirmPairingState.AwaitingAcceptance(
                            peerName = candidate.name,
                            onPc = onPc,
                            code = progress.code,
                            expiresAtMillis = System.currentTimeMillis() + CONFIRM_WINDOW_MS,
                        )
                    is ConnectionManager.CodePairingProgress.Paired ->
                        ConfirmPairingState.Paired(progress.peer.name, progress.peer.deviceId.fingerprint())
                    is ConnectionManager.CodePairingProgress.AlreadyPaired ->
                        ConfirmPairingState.Paired(progress.peerName, candidate.deviceId.fingerprint())
                    is ConnectionManager.CodePairingProgress.Failed ->
                        ConfirmPairingError.from(progress.failure)
                            ?.let { ConfirmPairingState.Failed(candidate.name, onPc, it) }
                            ?: ConfirmPairingState.Idle
                }
                _state.value = _state.value.copy(confirm = next)
            }
            connectionManager.clearEvent()
        }
    }

    /** Cancel while securing or waiting for Accept. The other device's prompt closes too. */
    public fun cancelConfirm() {
        connectionManager.cancelOutboundPairing()
        if (_state.value.confirm !is ConfirmPairingState.AwaitingAcceptance) {
            // Still connecting: there is no prompt on the other side yet, so just stop dialling.
            confirmJob?.cancel()
        }
        _state.value = _state.value.copy(confirm = ConfirmPairingState.Idle)
    }

    public fun dismissConfirm() {
        if (_state.value.confirmBusy) return
        _state.value = _state.value.copy(confirm = ConfirmPairingState.Idle)
    }

    public fun retryConfirm() {
        val candidate = lastConfirmCandidate ?: return
        _state.value = _state.value.copy(confirm = ConfirmPairingState.Idle)
        pairWith(candidate)
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
        if (_state.value.pairingBusy) return
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

    /** "Pick from the list": the nearby-list path, which needs discovery running. */
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
                    ConnectionManager.CodePairingProgress.Securing,
                    is ConnectionManager.CodePairingProgress.AwaitingConfirmation,
                    -> QrPairingState.Securing(qr.name)
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
        if (_state.value.confirmBusy) connectionManager.cancelOutboundPairing()
        stopSearching()
        super.onCleared()
    }

    private companion object {
        /** Matches the PC's prompt (04-PROTOCOL.md 4.3). Display only; the link layer enforces it. */
        const val CONFIRM_WINDOW_MS = 120_000L
    }
}
