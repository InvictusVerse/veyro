# 01 — Project Overview

## What Veyro is

**Veyro** is a cross-device ecosystem for Windows and Android that moves data between your own
devices over the local network, with no cloud, no account, and no Bluetooth requirement.

> **Veyro — Seamlessly connected.**

The first product is file transfer (**Veyro Share**). The architecture is built so clipboard,
sync, notifications and handoff are additional *capabilities on one secure channel*, not separate
apps.

## The problem being solved

Quick Share works well but its discovery path pulls in Bluetooth/BLE and other mechanisms, so the
user is forced to turn on Bluetooth even when both devices are already on the same Wi-Fi or
hotspot. That is an unnecessary precondition for a transfer that will happen over Wi-Fi anyway.

Veyro inverts the priority:

```
          Wi-Fi / LAN / hotspot   →  PRIMARY discovery + transport
          BLE                     →  OPTIONAL fallback (opt-in, v2)
          Cloud                   →  NEVER
```

## Network scenarios that must work without Bluetooth

| Scenario | Topology | Expected |
|---|---|---|
| Same router | Windows ── Wi-Fi/Ethernet ── Router ── Wi-Fi ── Android | Mutual discovery, direct transfer |
| Android hotspot | Windows joins Android's hotspot | Mutual discovery, direct transfer |
| Windows Mobile Hotspot | Android joins Windows' hotspot | Mutual discovery, direct transfer |
| Wi-Fi Direct / P2P group | Negotiated group, no infrastructure | v2 |
| AP client isolation on | Hotspot blocks peer↔peer traffic | **Detect and explain**, not "device not found" |
| No network at all | — | Clear state, offer hotspot guidance |

## Ecosystem map

```
                              VEYRO
                                │
      ┌───────────┬─────────────┼─────────────┬───────────┐
      │           │             │             │           │
   Share       Clipboard      Sync         Handoff      Link
   files/      universal      folders/     continue     pairing &
   photos      copy-paste     data         on other     device
                                           device       management
      └───────────┴─────────────┴─────────────┴───────────┘
                                │
                    ONE secure channel (VLP)
                                │
                 ┌──────────────┴──────────────┐
                 │                             │
          Discovery (mDNS/UDP)         Transport (TCP + Noise)
```

Product names reserved for later phases: **Veyro Share**, **Veyro Sync**, **Veyro Clipboard**,
**Veyro Drop**, **Veyro Handoff**, **Veyro Link**, **Veyro Hub**.

## Design principles

1. **Wi-Fi first, Bluetooth optional.** BLE is never required for a LAN/hotspot transfer.
2. **Zero idle cost.** When idle: no scanning, no beacons, no wakelocks, no polling. Discovery runs
   only inside an explicit, bounded window.
3. **The local network is not trusted.** Being on the same Wi-Fi grants nothing. Discovery only
   produces *candidates*; authentication decides.
4. **No invented cryptography.** Standard primitives (X25519, ChaCha20-Poly1305, SHA-256, HKDF)
   and a published handshake pattern. Our own code is the application protocol, not the crypto.
5. **Explicit authorization.** Nothing is written to disk without either a user decision or a
   per-device rule the user created.
6. **Verifiable integrity.** Every file and every resumed chunk is hash-verified before it counts.
7. **Honest failure.** Every failure mode has a named diagnosis and a concrete remediation, never a
   generic "not found".
8. **One protocol, many capabilities.** Clipboard/sync/handoff reuse the same session, identity and
   trust store as file transfer.

## Non-goals

- No cloud relay, no account system, no telemetry.
- No internet-based transfers in v1 (LAN/hotspot only).
- No iOS/macOS/Linux client in v1. The protocol stays portable so it remains possible, but note
  that choosing WinUI 3 for Windows (ADR-012) means the Windows UI is not reusable elsewhere -
  only the protocol and its test vectors are.
- No remote desktop / screen control in v1.
