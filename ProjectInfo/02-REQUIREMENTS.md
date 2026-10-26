# 02 — Requirements

IDs are stable. Reference them from code comments and commit messages
(e.g. `// REQ-D03`, `fix(discovery): honour REQ-D05 bounded window`).

Status values: `TODO` · `WIP` · `DONE` · `DEFERRED(phase)` · `DROPPED`

---

## A. Identity & Pairing

| ID | Requirement | Status |
|---|---|---|
| REQ-I01 | On first launch each install generates a long-term X25519 static identity key pair. | DONE |
| REQ-I02 | The private key never leaves the device and is never included in any message. | DONE |
| REQ-I03 | Private key at rest is encrypted by a hardware-backed key (Android Keystore / StrongBox when available; DPAPI or CNG on Windows). | WIP - sealing done + JVM-tested; hardware backing needs an on-device test |
| REQ-I04 | `DeviceId` is derived deterministically from the public key, is stable across restarts, and survives app updates. | DONE |
| REQ-I05 | A human-readable 8-byte fingerprint is displayable for every device (e.g. `A7 4C 91 2E 55 0B 83 1D`). | WIP - derivation done and shown in Settings; screen never rendered on a device |
| REQ-I06 | First-time pairing uses an out-of-band channel: QR code scan **or** a 6-digit code compared on both devices. | TODO |
| REQ-I07 | Pairing requires explicit confirmation on **both** devices before trust is persisted. | TODO |
| REQ-I08 | A paired peer is stored as `TrustedPeer{deviceId, staticPublicKey, name, platform, pairedAt, pairingSecret, autoAccept}`. | DONE |
| REQ-I09 | The user can rename, re-verify, or revoke any paired device; revocation is immediate and local. | TODO |
| REQ-I10 | Identity can be reset by the user, which invalidates all pairings. | WIP - logic + UI action done; the trust store is HMAC-bound to the identity so a reset really does invalidate pairings. Untested on a device |

## B. Discovery

| ID | Requirement | Status |
|---|---|---|
| REQ-D01 | Discovery works over any IP network shared by the two devices: router LAN, Android hotspot, Windows Mobile Hotspot. | TODO |
| REQ-D02 | Discovery never requires Bluetooth, Bluetooth permissions, or location permissions. | TODO |
| REQ-D03 | Primary mechanism is mDNS/DNS-SD service `_veyro._tcp`. | TODO |
| REQ-D04 | Secondary mechanism is UDP multicast + subnet broadcast beacons, used when mDNS is unavailable or blocked. | TODO |
| REQ-D05 | Discovery runs only inside a bounded, explicitly-started window. No background scanning by default. | TODO |
| REQ-D06 | Advertised data contains no stable identifier that a stranger can use to track the device across networks (rotating presence token). | TODO |
| REQ-D07 | Discovery output is treated as untrusted: it yields candidate endpoints only, never trust. | TODO |
| REQ-D08 | Already-paired devices are recognised and shown as trusted without any user action. | TODO |
| REQ-D09 | Multiple addresses per peer (Wi-Fi, Ethernet, hotspot interface) are collected and raced on connect. | TODO |
| REQ-D10 | BLE discovery exists as an explicit opt-in fallback only. | DEFERRED(P4) |

## C. Network diagnosis

| ID | Requirement | Status |
|---|---|---|
| REQ-N01 | After discovery, reachability is probed; a peer that is visible but unreachable is reported as such. | TODO |
| REQ-N02 | AP/client isolation is detected and named (`AP_ISOLATED`) with concrete remediation steps. | TODO |
| REQ-N03 | Blocked multicast is detected and named (`MULTICAST_BLOCKED`). | TODO |
| REQ-N04 | An active VPN that captures LAN routes is detected and named (`VPN_INTERFERENCE`). | TODO |
| REQ-N05 | Absence of any usable network is named (`NO_NETWORK`) with hotspot guidance. | TODO |
| REQ-N06 | The diagnosis panel never shows a generic "device not found" without a named cause. | TODO |

## D. Secure channel

| ID | Requirement | Status |
|---|---|---|
| REQ-S01 | Every session is mutually authenticated using the peers' static keys established at pairing. | TODO |
| REQ-S02 | Every session has forward secrecy via ephemeral keys. | TODO |
| REQ-S03 | All application data is encrypted and authenticated (AEAD). No plaintext control or bulk data. | TODO |
| REQ-S04 | An unpaired peer cannot complete a session handshake; unauthenticated inbound connections are accepted only while a pairing window is open. | TODO |
| REQ-S05 | Replay of a previously recorded session must fail. | TODO |
| REQ-S06 | A MITM on the local network cannot impersonate either side, even with full packet control. | TODO |
| REQ-S07 | The handshake is implemented against published test vectors stored in `protocol/testvectors/`. | WIP - HKDF (RFC 5869) and X25519 (RFC 7748) vectors pass; Noise vectors pending P3 |
| REQ-S08 | Protocol version is negotiated; incompatible versions fail with a clear message, never silently. | TODO |

## E. Transfer

| ID | Requirement | Status |
|---|---|---|
| REQ-T01 | Send files, multiple files, and folders (recursive, preserving relative paths). | TODO |
| REQ-T02 | Receiving requires explicit authorization per transfer, unless the user enabled auto-accept for that specific device. | TODO |
| REQ-T03 | Auto-accept is per-device, default off, and never applies to unpaired devices. | WIP - storage + default-off done and tested; no transfer path yet |
| REQ-T04 | Every file is verified with a full-content SHA-256 after transfer; mismatch fails the item loudly and does not expose a partial file as complete. | TODO |
| REQ-T05 | Transfers are resumable after disconnect, app kill, or reboot. | TODO |
| REQ-T06 | Resume re-verifies already-written chunks from disk before trusting them. | TODO |
| REQ-T07 | In-progress files are stored as `.veyropart` with a sidecar manifest and atomically renamed only on successful verification. | TODO |
| REQ-T08 | Progress reporting includes per-item and overall bytes, rate, and ETA. | TODO |
| REQ-T09 | Transfers survive the app going to background (foreground service with notification). | TODO |
| REQ-T10 | A transfer can be cancelled by either side at any time, leaving no orphaned partial files after cleanup. | TODO |
| REQ-T11 | Filenames from the peer are sanitised; path traversal (`..`, absolute paths, reserved names, NTFS streams) is rejected. | DONE |
| REQ-T12 | Duplicate target names are resolved by a documented policy (ask / rename / overwrite), default *rename*. | TODO |
| REQ-T13 | Transfer history is persisted locally with outcome and verification result. | TODO |

## F. User experience

| ID | Requirement | Status |
|---|---|---|
| REQ-U01 | Sending is at most: pick files → pick device → send. | TODO |
| REQ-U02 | Trusted devices appear automatically with no per-transfer pairing step. | TODO |
| REQ-U03 | Each device entry shows name, platform, trust state, last seen, and fingerprint on demand. | TODO |
| REQ-U04 | Two devices with similar names are disambiguated by platform, fingerprint and last-seen. | TODO |
| REQ-U05 | Android share-sheet integration: Veyro appears as a share target. | TODO |
| REQ-U06 | Dark and light themes; Material 3 with dynamic colour where available. | TODO |
| REQ-U07 | The app is usable without reading documentation; all network failures are explained in-app. | TODO |

## W. Windows integration (ADR-012)

These are the requirements that decided the Windows platform choice. They are the reason the client
is a packaged C# app rather than shared Kotlin: each one needs MSIX package identity, and most need
a COM handler or a Windows contract.

| ID | Requirement | Status |
|---|---|---|
| REQ-W01 | Right-click a file or folder in Explorer offers "Send to Veyro" in the **modern** Windows 11 context menu, not only under "Show more options". | DEFERRED(P5) |
| REQ-W02 | Veyro appears as a target in the Windows **Share** sheet. | DEFERRED(P5) |
| REQ-W03 | A tray icon shows transfer state and gives quick access to recent devices. | DEFERRED(P5) |
| REQ-W04 | Toast notifications for incoming offers carry **Accept** and **Decline** actions, so a transfer can be authorized without opening the window. | DEFERRED(P5) |
| REQ-W05 | Optional start-on-login, **off by default**, consistent with NFR-01..03. | DEFERRED(P5) |
| REQ-W06 | Discovery and transfer continue with the main window closed. | DEFERRED(P5) |
| REQ-W07 | The first run explains the Windows Firewall prompt rather than letting the user dismiss it and silently lose discovery. | DEFERRED(P5) |
| REQ-W08 | Windows Mobile Hotspot is a supported topology, verified. | DEFERRED(P5) |
| REQ-W09 | The identity private key at rest is protected by DPAPI (CurrentUser) or a CNG persisted key, with an ACL limited to the user. | DEFERRED(P5) |
| REQ-W10 | `Veyro.Protocol` and `Veyro.Crypto` are written against `04-PROTOCOL.md` and `protocol/testvectors/` only - **never** by translating the Kotlin. Translation copies bugs and manufactures false agreement. | DEFERRED(P5) |
| REQ-W11 | Every test vector that the Android client passes, the Windows client also passes, in CI-equivalent local verification. | DEFERRED(P5) |

## G. Future capabilities (same channel)

| ID | Requirement | Status |
|---|---|---|
| REQ-C01 | Universal clipboard: text push with size cap and per-device opt-in. | DEFERRED(P5) |
| REQ-C02 | Clipboard never auto-syncs to an unpaired device and never leaves the LAN. | DEFERRED(P5) |
| REQ-C03 | Folder sync with conflict detection. | DEFERRED(P6) |
| REQ-C04 | Notification mirroring. | DEFERRED(P7) |
| REQ-C05 | Handoff of a URL/document to the other device. | DEFERRED(P7) |

---

## Non-functional requirements

| ID | Requirement | Target | Status |
|---|---|---|---|
| NFR-01 | Idle CPU usage | ≈ 0% — no timers, no polling, no background scan | TODO |
| NFR-02 | Idle network traffic | 0 packets sent when no window is open | TODO |
| NFR-03 | Idle battery impact | No wakelocks, no periodic jobs, no BLE scan | TODO |
| NFR-04 | Discovery latency | Peer visible within 2 s of opening the window on a quiet LAN | TODO |
| NFR-05 | Throughput | ≥ 70% of achievable link throughput on Wi-Fi 5 / Gigabit Ethernet | TODO |
| NFR-06 | Memory | Streaming I/O; peak heap independent of file size (no full-file buffering) | TODO |
| NFR-07 | Large files | Single file ≥ 50 GB supported (64-bit sizes and offsets throughout) | TODO |
| NFR-08 | Crypto overhead | AEAD must not become the throughput bottleneck on mid-range ARM64 | TODO |
| NFR-09 | Cold start | App interactive in < 500 ms on mid-range hardware | TODO |
| NFR-10 | APK size | Release APK < 15 MB | DONE - measured 3.69 MB |
| NFR-11 | Offline | Full functionality with no internet connectivity whatsoever | TODO |
| NFR-12 | Portability | `core:model`, `core:protocol` contain no Android APIs so the spec can be reimplemented on Windows 1:1 | TODO |
| NFR-13 | Testability | Protocol and crypto layers unit-testable on the JVM with no device | DONE - 121 JVM tests, no emulator; required a LogSink seam and a SealingKeyProvider seam |

## Platform targets

| | Android | Windows |
|---|---|---|
| Minimum | API 29 (Android 10) | Windows 10 21H2 |
| Target | API 37 (Android 17) | Windows 11 |
| Language | Kotlin 2.4.20 | C# / .NET 10 |
| UI | Jetpack Compose + Material 3 | WinUI 3 (ADR-012) |
| Packaging | APK / AAB | MSIX with package identity (required for REQ-W01/W02/W04) |
| Code shared | — | **None.** The contract is `protocol/schema/` + `protocol/testvectors/` |
