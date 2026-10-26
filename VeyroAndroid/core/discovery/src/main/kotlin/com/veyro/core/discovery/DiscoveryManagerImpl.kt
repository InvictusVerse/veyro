package com.veyro.core.discovery

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.DiscoveryWindowState
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.TrustedPeer
import com.veyro.core.protocol.PresenceRecord
import com.veyro.core.protocol.Vlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates discovery: runs both mechanisms inside a bounded window, merges what they see, and
 * guarantees that closing the window really stops everything.
 *
 * ## The window is the whole design
 *
 * NFR-02 promises **zero packets sent when idle**, and NFR-01/NFR-03 promise no idle CPU or battery
 * cost. That is a product claim, not a tuning preference, so discovery is modelled as a *resource*
 * rather than a flag: callers hold a [DiscoveryHandle] and everything stops when the last one is
 * released.
 *
 * Reference counting matters because several things legitimately want discovery at once - the Home
 * screen is open while a transfer is running, say. Without counting, whichever one finished first
 * would silently kill discovery for the other.
 *
 * What "stop" has to mean, concretely: unregister the mDNS service, stop browsing, close the
 * datagram socket, cancel the announce loop, and **release the multicast lock**. Forgetting the
 * last one is a battery drain that only shows up in user reports.
 */
public class DiscoveryManagerImpl(
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val advertiser: NsdMdnsAdvertiser,
    private val browser: MdnsBrowser,
    private val beacon: UdpBeacon,
    private val multicastLock: MulticastLockHolder,
    private val trustedPeers: suspend () -> List<TrustedPeer>,
    private val presenceRecord: suspend (port: Int, includeName: Boolean) -> PresenceRecord?,
    private val listeningPort: suspend () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiscoveryManager {

    private companion object {
        const val TAG = "Discovery"

        /** How long an unseen peer stays in the list before it is dropped. */
        const val STALE_AFTER_MILLIS = 30_000L

        /** How often the merged list is recomputed so staleness takes effect. */
        const val SWEEP_INTERVAL_MILLIS = 5_000L
    }

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _windowState = MutableStateFlow(DiscoveryWindow(isOpen = false))
    override val windowState: StateFlow<DiscoveryWindow> = _windowState.asStateFlow()

    /** Exposed so the UI can explain *why* Veyro is currently discoverable. */
    private val _state = MutableStateFlow(DiscoveryWindowState.CLOSED)
    public val state: StateFlow<DiscoveryWindowState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val openCount = AtomicInteger(0)

    /** Everything observed in the current window. Cleared when the window closes. */
    private val observed = mutableListOf<PeerAdvertisement>()

    private var collectJob: Job? = null
    private var sweepJob: Job? = null
    private var expiryJob: Job? = null
    private var currentReason: DiscoveryReason? = null

    /**
     * Whether the current window lists peers that are not in the trust store.
     *
     * Tracked from the request rather than inferred from [currentReason]. Inferring it
     * (`currentReason == PAIRING`) was a bug: the hub opens a FOREGROUND window, so every unpaired
     * device was dropped, and a first-run user could never see a device to pair with.
     */
    private var includeUnpaired: Boolean = false

    override fun openWindow(request: DiscoveryRequest): DiscoveryHandle {
        val handle = Handle(request)
        scope.launch { acquire(request) }
        return handle
    }

    private inner class Handle(private val request: DiscoveryRequest) : DiscoveryHandle {
        private var closed = false

        override fun close() {
            // Idempotent: a `use {}` block plus an explicit close must not double-decrement and
            // tear down a window another caller still holds.
            if (closed) return
            closed = true
            scope.launch { release() }
        }
    }

    private suspend fun acquire(request: DiscoveryRequest) {
        val count = openCount.incrementAndGet()
        mutex.withLock {
            // Record the most specific reason currently in play, so the UI can say "discoverable
            // for pairing" rather than just "discoverable".
            if (currentReason == null || request.reason == DiscoveryReason.PAIRING) {
                currentReason = request.reason
            }
            // Sticky across overlapping windows: if any holder wants unpaired peers listed, they
            // stay listed until every window closes. Otherwise opening the hub while pairing would
            // silently narrow the list the pairing screen is showing.
            if (request.includeUnpaired) includeUnpaired = true
            if (count > 1) {
                updateState()
                return
            }
            startEverything(request)
        }
    }

    private suspend fun release() {
        val count = openCount.decrementAndGet()
        mutex.withLock {
            if (count > 0) return
            if (count < 0) {
                // Defensive: a negative count means a handle was closed twice past the guard.
                openCount.set(0)
            }
            stopEverything()
        }
    }

    /** Must hold [mutex]. */
    private suspend fun startEverything(request: DiscoveryRequest) {
        val port = listeningPort()
        if (port !in 1..65535) {
            VeyroLog.w(TAG) { "No listening port yet; discovery not started" }
            openCount.set(0)
            return
        }

        // Acquire the lock first: a browse that starts before the lock is held can miss the
        // initial burst of responses on a ROM that filters multicast.
        multicastLock.acquire()

        val identity = presenceRecord(port, request.includeUnpaired)
        if (identity == null) {
            VeyroLog.w(TAG) { "No identity yet; discovery not started" }
            multicastLock.release()
            openCount.set(0)
            return
        }

        runCatching {
            advertiser.start(port, identity.deviceId.value, identity.name, identity)
            browser.start()
            beacon.start(port)
        }.onFailure { VeyroLog.w(TAG, it) { "Discovery failed to start cleanly" } }

        collectJob = scope.launch(dispatchers.default) {
            launch { browser.advertisements.collect { record(it) } }
            launch { browser.lostServices.collect { handleLostService(it) } }
            launch { beacon.advertisements.collect { record(it) } }
        }

        // Recompute periodically so a peer that stopped advertising actually disappears. Only runs
        // while a window is open, so it costs nothing when idle.
        sweepJob = scope.launch(dispatchers.default) {
            while (true) {
                delay(SWEEP_INTERVAL_MILLIS)
                recompute()
            }
        }

        // Send one query immediately rather than waiting for the first announce tick, so the list
        // populates in well under the 2 s NFR-04 target instead of up to a second later.
        scope.launch(dispatchers.io) { runCatching { beacon.sendQuery() } }

        request.timeoutMillis?.let { timeout ->
            expiryJob = scope.launch {
                delay(timeout)
                VeyroLog.i(TAG) { "Discovery window expired after ${timeout}ms" }
                openCount.set(0)
                mutex.withLock { stopEverything() }
            }
        }

        updateState()
        VeyroLog.i(TAG) { "Discovery window open (${request.reason}), advertising port $port" }
    }

    /** Must hold [mutex]. */
    private suspend fun stopEverything() {
        expiryJob?.cancel()
        sweepJob?.cancel()
        collectJob?.cancel()
        expiryJob = null
        sweepJob = null
        collectJob = null

        runCatching { advertiser.stop() }
        runCatching { browser.stop() }
        runCatching { beacon.stop() }
        // Last, and unconditionally: holding this with no window open is a pure battery cost.
        multicastLock.release()

        observed.clear()
        _peers.value = emptyList()
        currentReason = null
        includeUnpaired = false
        updateState()
        VeyroLog.i(TAG) { "Discovery window closed; no packets will be sent" }
    }

    /**
     * Records an advertisement, **replacing** any previous one from the same peer and mechanism.
     *
     * This used to append. That was the cause of the flickering device list: the UDP beacon
     * announces once a second on every usable interface and mDNS re-resolves on its own schedule,
     * so `observed` grew by several entries per second, `recompute()` ran on each one, and
     * `_peers.value` was reassigned to a freshly-built list with new identity - which makes Compose
     * recompose the whole `LazyColumn` roughly once a second, forever.
     *
     * Keying on (deviceId, source) keeps exactly one entry per peer per mechanism. A peer seen by
     * both mDNS and the beacon still produces two entries, which is what `PeerMerger` needs in order
     * to mark it `BOTH` and union its addresses.
     */
    private suspend fun record(advertisement: PeerAdvertisement) {
        val changed = mutex.withLock {
            if (openCount.get() <= 0) return
            val key = advertisement.deviceIdRaw to advertisement.source
            val existing = observed.indexOfFirst { (it.deviceIdRaw to it.source) == key }
            if (existing >= 0) {
                val previous = observed[existing]
                observed[existing] = advertisement
                // A repeat announcement with identical content is the common case and must not
                // trigger any UI work. Only the timestamp differs, so compare everything else.
                !previous.isSameAdvertisedState(advertisement)
            } else {
                observed += advertisement
                true
            }
        }
        if (changed) recompute()
    }

    /**
     * Immediately purges a peer whose mDNS service was reported lost.
     *
     * Without this, a peer that stopped advertising or changed ports remains in the UI for up to
     * 30 seconds (STALE_AFTER_MILLIS), prompting dials to closed ports.
     */
    private suspend fun handleLostService(serviceName: String) {
        val shortId = serviceName.removePrefix("veyro-")
        if (shortId.isEmpty() || shortId == serviceName) return

        val changed = mutex.withLock {
            if (openCount.get() <= 0) return
            val removed = observed.removeAll {
                it.source == DiscoverySource.MDNS &&
                    it.deviceIdRaw.take(shortId.length).equals(shortId, ignoreCase = true)
            }
            if (removed) {
                VeyroLog.i(TAG) { "Purged lost mDNS service $serviceName from discovery" }
            }
            removed
        }
        if (changed) recompute()
    }

    /**
     * Rebuilds the peer list, and emits **only if it actually differs**.
     *
     * The equality check is the second half of the flicker fix. Even with deduplicated input, the
     * periodic sweep and the trust-store reload would otherwise reassign an equal-but-new list and
     * recompose for nothing.
     */
    private suspend fun recompute() {
        val now = clock()
        val snapshot = mutex.withLock {
            observed.removeAll { now - it.observedAtMillis > STALE_AFTER_MILLIS }
            observed.toList()
        }
        if (snapshot.isEmpty() && openCount.get() <= 0) {
            if (_peers.value.isNotEmpty()) _peers.value = emptyList()
            return
        }
        val trusted = runCatching { trustedPeers() }.getOrDefault(emptyList())
        val merged = PeerMerger.merge(
            advertisements = snapshot,
            trusted = trusted,
            nowMillis = now,
            staleAfterMillis = STALE_AFTER_MILLIS,
            includeUnpaired = includeUnpaired,
        )
        // `lastSeen` ticks constantly, so comparing whole objects would always differ. Compare the
        // fields the UI actually renders.
        if (!merged.rendersSameAs(_peers.value)) {
            _peers.value = merged
        }
    }

    private fun updateState() {
        val open = openCount.get() > 0
        _windowState.value = DiscoveryWindow(
            isOpen = open,
            reason = currentReason,
            openedAtMillis = if (open) clock() else 0L,
        )
        _state.value = when {
            !open -> DiscoveryWindowState.CLOSED
            currentReason == DiscoveryReason.PAIRING -> DiscoveryWindowState.PAIRING
            currentReason == DiscoveryReason.TRANSFER -> DiscoveryWindowState.TRANSFER_ACTIVE
            currentReason == DiscoveryReason.ALWAYS_ON -> DiscoveryWindowState.ALWAYS_ON
            else -> DiscoveryWindowState.FOREGROUND
        }
    }

    /** True when the multicast lock is held. Surfaced in diagnostics; it is a common failure. */
    public fun isMulticastLockHeld(): Boolean = multicastLock.isHeld()

    /** For the pairing flow: the bounded window length from the protocol spec. */
    public fun pairingWindowMillis(): Long = Vlp.PAIRING_WINDOW_MILLIS
}

/**
 * True when two advertisements describe the same state, ignoring the observation timestamp.
 *
 * A repeated beacon from an unchanged peer differs only in `observedAtMillis`, and treating that as
 * a change is what made the device list flicker.
 */
private fun PeerAdvertisement.isSameAdvertisedState(other: PeerAdvertisement): Boolean =
    deviceIdRaw == other.deviceIdRaw &&
        name == other.name &&
        platformRaw == other.platformRaw &&
        version == other.version &&
        capabilityBits == other.capabilityBits &&
        endpoints == other.endpoints &&
        source == other.source

/**
 * Compares only what the UI renders.
 *
 * `lastSeenEpochMillis` advances on every announcement, so a naive list comparison would never find
 * two lists equal and the flicker fix would do nothing.
 */
private fun List<PeerDevice>.rendersSameAs(other: List<PeerDevice>): Boolean {
    if (size != other.size) return false
    for (i in indices) {
        val a = this[i]
        val b = other[i]
        if (a.deviceId != b.deviceId ||
            a.displayName != b.displayName ||
            a.trust != b.trust ||
            a.platform != b.platform ||
            a.discoveredVia != b.discoveredVia ||
            a.endpoints != b.endpoints
        ) {
            return false
        }
    }
    return true
}
