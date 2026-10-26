# Veyro

**Seamlessly connected.** Cross-device file transfer, clipboard and sync between Windows and
Android — over Wi-Fi/LAN/hotspot, with no Bluetooth requirement, no cloud, and no account.

```
        Same Wi-Fi                Android hotspot            Windows hotspot
   Windows ─── LAN ─── Android    Windows ──► 📱 AP          📱 ◄── AP ─── Windows
            discovery                 discovery                  discovery
            over IP                   over IP                    over IP
```

## Why

Quick Share works well, but its discovery path involves Bluetooth/BLE, so you end up enabling a
radio that the transfer will not even use. Veyro makes IP the only required path: if the two
devices share a network — router, Android hotspot, or Windows Mobile Hotspot — they find each
other and transfer, with Bluetooth off.

## Properties

| | |
|---|---|
| **Discovery** | mDNS/DNS-SD `_veyro._tcp` + UDP beacon fallback. No Bluetooth, no location permission. |
| **Security** | X25519 identities, mutually authenticated Noise handshake, ChaCha20-Poly1305 for every byte, QR/6-digit out-of-band pairing. The LAN is never trusted. |
| **Integrity** | Per-file SHA-256 verification, atomic commit, resume that re-verifies on-disk chunks. |
| **Idle cost** | No background scanning, no beacons, no wakelocks when idle. Discovery runs only in a bounded, user-initiated window. |
| **Diagnosis** | AP client isolation, blocked multicast, VPN interference and firewall blocks are detected and named — never a generic "device not found". |
| **Privacy** | No cloud, no account, no telemetry. Display name hidden outside pairing mode. |

## Repository layout

```
veyro/
├─ ProjectInfo/        All project knowledge: requirements, architecture, protocol,
│                      security model, roadmap, status, decisions. START HERE.
├─ protocol/           Language-neutral protocol schema (CDDL) + interop test vectors,
│                      shared by the Android and Windows implementations.
├─ tools/              Helper scripts.
├─ VeyroAndroid/       Android client — Kotlin 2.4.20, Jetpack Compose, multi-module.   ACTIVE
└─ VeyroWindows/       Windows client — .NET 10.                                        SKELETON
```

## Status

**Phase P0 — Foundation.** Structure and specification complete; Android implementation starting.
Current state is tracked in [`ProjectInfo/07-STATUS.md`](ProjectInfo/07-STATUS.md).

## Build (Android)

```powershell
cd D:\Coding\Projects\veyro\VeyroAndroid
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

Toolchain and pinned versions: [`ProjectInfo/09-ENVIRONMENT.md`](ProjectInfo/09-ENVIRONMENT.md).

## Documentation index

| Document | Contents |
|---|---|
| [Overview](ProjectInfo/01-PROJECT-OVERVIEW.md) | Vision, scenarios, principles, ecosystem map |
| [Requirements](ProjectInfo/02-REQUIREMENTS.md) | `REQ-*` / `NFR-*` with status |
| [Architecture](ProjectInfo/03-ARCHITECTURE.md) | Module map, layering rules, object graph |
| [Protocol](ProjectInfo/04-PROTOCOL.md) | VLP v1 wire specification |
| [Security](ProjectInfo/05-SECURITY.md) | Threat model, crypto design, test checklist |
| [Roadmap](ProjectInfo/06-ROADMAP.md) | Phases P0–P8 with exit criteria |
| [Status](ProjectInfo/07-STATUS.md) | What exists right now |
| [Decisions](ProjectInfo/08-DECISIONS.md) | ADR log |
| [Environment](ProjectInfo/09-ENVIRONMENT.md) | Toolchain, versions, build commands |
| [Changelog](ProjectInfo/10-CHANGELOG.md) | Session-by-session history |

## License

GPL-3.0, see [`LICENSE`](LICENSE). Parts are adapted from
[Sefirah](https://github.com/shrimqy/Sefirah) by shrimqy (GPL-3.0); see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
