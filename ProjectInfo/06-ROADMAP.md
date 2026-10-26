# 06 — Roadmap

Phases are ordered by dependency, not by calendar. A phase is done when its exit criteria pass.

---

## P0 — Foundation ✅ (current)

**Goal:** a correct, buildable, well-structured skeleton with the design written down.

- [x] Repository layout, `ProjectInfo/` knowledge base
- [x] Protocol + security specification drafted
- [x] Gradle multi-module Android project, convention plugins, version catalog
- [x] All `core:*` / `feature:*` modules created and wired
- [x] App shell: Compose + Material 3 + navigation, manual DI container
- [ ] CI-equivalent local verification: `assembleDebug` + `test` green

**Exit:** `gradlew assembleDebug` succeeds; app launches to a hub screen; no module cycles.

---

## P1 — Identity & local plumbing (Android)

**Goal:** the device knows who it is and can persist trust.

- `:core:crypto` — seed generation, X25519 key derivation, Keystore sealing, HKDF, HMAC
- `:core:model` — `DeviceId`, `PeerDevice`, `TrustedPeer`, enums
- `:core:data` — `TrustStore`, `SettingsStore`, `TransferHistoryStore` (CBOR files, atomic writes)
- `:feature:settings` — device name, identity fingerprint, reset identity
- Unit tests for key derivation determinism and `deviceId` stability

**Exit:** fingerprint shown in Settings, stable across restarts and app updates; REQ-I01..I05 DONE.

---

## P2 — Discovery & diagnosis (Android ↔ Android first)

**Goal:** two Android devices see each other on LAN and on a hotspot, with no Bluetooth.

- `:core:network` — `NetworkMonitor`, interface/subnet enumeration, reachability probe
- `:core:discovery` — NSD/mDNS advertiser + browser, UDP beacon fallback, bounded windows
- `:feature:home` — live nearby list
- Diagnosis engine for `AP_ISOLATED`, `MULTICAST_BLOCKED`, `VPN_INTERFERENCE`, `NO_NETWORK`
- Field matrix test: same router / Android hotspot / Windows hotspot / isolation on

**Exit:** REQ-D01..D09, REQ-N01..N06 DONE; zero packets sent with no window open (NFR-02 measured).

---

## P3 — Secure channel + pairing

**Goal:** two devices can establish a mutually authenticated encrypted session.

- `:core:crypto` — Noise KK/XK state machine + official test vectors
- `:core:transport` — listener, dialer, address racing, framing, `SecureSession`
- `:core:protocol` — frame codec, CBOR control messages, `Hello`/`HelloAck`
- `:feature:pairing` — QR display, QR scan (CameraX + ML Kit), SAS confirmation, both-sides accept
- `:feature:devices` — list, rename, revoke, fingerprint, auto-accept toggle
- Negative-path tests from `05-SECURITY.md §7`

**Exit:** REQ-S01..S08, REQ-I06..I09 DONE; spoofing and MITM tests fail the attacker.

---

## P4 — Transfer engine

**Goal:** reliable, resumable, verified file transfer.

- `:core:transfer` — manifest building, chunk scheduling, streaming hash, `.veyropart` + sidecar,
  resume with re-verification, atomic commit
- `:core:service` — foreground service, progress notification, cancel action
- `:feature:send` / `:feature:receive` — pick, offer, authorize, progress, result
- `:feature:history` — persisted outcomes
- Android share-sheet target
- Throughput and large-file testing (≥ 50 GB, NFR-07)

**Exit:** REQ-T01..T13 DONE; interrupted 20 GB transfer resumes and verifies.

---

## P5 — Windows client

**Goal:** Windows ↔ Android, the actual product. **C# / .NET 10, WinUI 3, MSIX** (ADR-012).

Ordered so that the riskiest thing - a second, independent protocol implementation - is proven
before any UI is built on top of it.

1. **`Veyro.Protocol` + `Veyro.Crypto`**, written against `04-PROTOCOL.md` and
   `protocol/testvectors/` **only**. Reading the Kotlin and translating it is forbidden (REQ-W10):
   translation copies bugs and produces two implementations that agree for the wrong reason.
   - X25519 via NSec or BouncyCastle.NET; `ChaCha20Poly1305` is in the BCL.
   - Identity at rest via DPAPI (CurrentUser) or CNG (REQ-W09).
   - **Exit: every vector the Android client passes, this passes (REQ-W11).**
2. **`Veyro.Discovery`** - mDNS + UDP, interface enumeration, and the firewall-profile problem
   (REQ-W07). A dismissed firewall prompt silently kills discovery, so it must be explained, not
   just triggered.
3. **`Veyro.Transport` + `Veyro.Transfer`** - mirror of the Kotlin engine, same chunk protocol and
   resume semantics.
4. **`Veyro.Daemon`** - discovery and transfer must survive the window being closed (REQ-W06).
5. **`Veyro.App`** - WinUI 3, packaged MSIX. This is where package identity pays for itself:
   Explorer context menu via an `IExplorerCommand` COM handler (REQ-W01), Share target (REQ-W02),
   tray icon (REQ-W03), actionable toasts (REQ-W04), optional start-on-login defaulting to off
   (REQ-W05).
6. **Topology verification** - same router, Android hotspot, **Windows Mobile Hotspot** (REQ-W08),
   and AP isolation.

**Exit:** all P1-P4 requirements pass cross-platform in both directions, and all of group W passes.

**Note on sequencing:** steps 1-2 can begin as soon as the Android side has stable vectors for the
handshake, i.e. after P3. They do not need to wait for P4.

---

## P6 — Veyro Clipboard

- Text clipboard push over the existing session, per-device opt-in, size cap
- Android clipboard access constraints (background read restrictions) handled explicitly
- `:feature:clipboard` becomes real

---

## P7 — Veyro Sync

- One-way then two-way folder sync, change detection, conflict policy
- Reuses the chunk + hash layer from P4

---

## P8 — Handoff / notifications / polish

- URL + document handoff
- Notification mirroring (Android → Windows)
- BLE fallback discovery (opt-in) with its own threat analysis
- Multi-stream bulk transport if measurements justify it
- Wi-Fi Direct group fallback when no common network exists

---

## Explicitly deferred, with reasons

| Item | Why not now |
|---|---|
| Internet relay / NAT traversal | Changes the whole trust and privacy story; v1 is LAN-only by design |
| iOS/macOS/Linux clients | Protocol stays portable, but each is a full product |
| Compression | Most real payloads (media, archives) are incompressible; measure before adding |
| Multi-stream bulk | Single TCP stream likely saturates typical links; optimise only with data |
| Account system / device cloud | Contradicts the zero-cloud principle |
