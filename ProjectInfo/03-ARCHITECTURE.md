# 03 — Architecture

## Layering

```
┌──────────────────────────────────────────────────────────────────────┐
│  :app                       composition root, navigation, DI wiring  │
├──────────────────────────────────────────────────────────────────────┤
│  :feature:*                 screens + ViewModels (one per feature)   │
├──────────────────────────────────────────────────────────────────────┤
│  :core:ui  :core:designsystem      presentation-shared Compose code  │
├──────────────────────────────────────────────────────────────────────┤
│  :core:data                 repositories, trust store, settings      │
│  :core:service              foreground services, notifications       │
├──────────────────────────────────────────────────────────────────────┤
│  :core:transfer             chunking, resume, hashing, progress      │
│  :core:transport            TCP sockets + secure channel sessions    │
│  :core:discovery            mDNS + UDP beacons                       │
│  :core:network              connectivity state, reachability probes  │
│  :core:crypto               identity keys, keystore, handshake impl  │
├──────────────────────────────────────────────────────────────────────┤
│  :core:protocol  (pure JVM) wire format, frames, messages, versions  │
│  :core:model     (pure JVM) domain types shared by everything        │
│  :core:common               dispatchers, Result, logging, utils      │
└──────────────────────────────────────────────────────────────────────┘
```

### Dependency rules (enforced by review; violations are build-breaking by design)

1. `:core:model` and `:core:protocol` are **pure Kotlin/JVM**. They must not reference any
   `android.*` API. This is what makes the protocol reimplementable on Windows and unit-testable
   without a device. (NFR-12, NFR-13)
2. A `:feature:*` module must never depend on another `:feature:*` module. Shared UI goes to
   `:core:ui`; shared behaviour goes to a `:core:*` module.
3. `:core:designsystem` must not depend on `:core:model` — it is theme + primitives only.
   `:core:ui` is the module allowed to know about domain types.
4. Only `:app` may depend on every module. `:app` holds the object graph.
5. Lower layers never depend on higher layers. `:core:protocol` never imports `:core:transport`.
6. No module except `:app` and `:core:service` may touch `Context`-dependent Android lifecycle APIs
   directly; they receive what they need through constructor parameters.

## Module responsibilities

| Module | Kind | Responsibility | Must NOT |
|---|---|---|---|
| `:app` | app | Activity, navigation graph, `VeyroContainer` (manual DI), share-sheet intents | contain business logic |
| `:feature:home` | lib | Hub: nearby + trusted devices, quick send entry point | own networking code |
| `:feature:devices` | lib | Paired device list, rename, revoke, fingerprint | own pairing crypto |
| `:feature:pairing` | lib | QR display/scan, 6-digit SAS confirmation flow | persist trust itself |
| `:feature:send` | lib | File/folder picking, target selection, send progress | implement chunking |
| `:feature:receive` | lib | Incoming offer prompt, receive progress, destination | implement chunking |
| `:feature:history` | lib | Past transfers, outcomes, re-send | write files |
| `:feature:settings` | lib | Device name, discoverability, destination folder, identity reset | touch sockets |
| `:feature:clipboard` | lib | Placeholder for Veyro Clipboard (P5) | — |
| `:core:ui` | lib | Model-aware Compose components (DeviceCard, TransferRow, ProgressBar) | hold state/ViewModels |
| `:core:designsystem` | lib | Colour, type, shape, theme, icons, primitive components | know domain types |
| `:core:data` | lib | `TrustStore`, `SettingsStore`, `TransferHistoryStore`, repositories | parse wire bytes |
| `:core:service` | lib | `VeyroTransferService`, `VeyroReceiveService`, notifications | own UI |
| `:core:transfer` | lib | Transfer engine: manifests, chunk scheduling, hashing, resume, atomic commit | open sockets |
| `:core:transport` | lib | Listener, dialer, address racing, `SecureSession` lifecycle, frame I/O | decide policy |
| `:core:discovery` | lib | `MdnsAdvertiser/Browser`, `UdpBeacon`, presence tokens, bounded windows | connect to peers |
| `:core:network` | lib | `NetworkMonitor`, interface/subnet enumeration, reachability + isolation probes | hold app state |
| `:core:crypto` | lib | Identity generation/storage, HKDF, AEAD, handshake state machine | know about files |
| `:core:protocol` | **jvm** | `VLP_VERSION`, frame codec, CBOR messages, error codes, path sanitiser | use Android APIs |
| `:core:model` | **jvm** | `DeviceId`, `PeerDevice`, `TrustedPeer`, `TransferItem`, enums | use Android APIs |
| `:core:common` | lib | `AppDispatchers`, `VeyroResult`, `Log`, byte/hex/base32 utils | depend on other core modules |
| `:core:testing` | lib | Fakes, fixtures, coroutine test rules | ship in release |

## Runtime object graph (manual DI — see ADR-005)

```
VeyroApplication
  └── VeyroContainer                       (lazy, process-scoped)
        ├── AppDispatchers
        ├── IdentityManager          ──→ core:crypto
        ├── TrustStore               ──→ core:data
        ├── SettingsStore            ──→ core:data
        ├── TransferHistoryStore     ──→ core:data
        ├── NetworkMonitor           ──→ core:network
        ├── DiscoveryManager         ──→ core:discovery  (needs IdentityManager, TrustStore)
        ├── TransportManager         ──→ core:transport  (needs IdentityManager, TrustStore)
        ├── TransferEngine           ──→ core:transfer   (needs TransportManager, SettingsStore)
        └── DeviceRepository         ──→ core:data       (merges discovery + trust + history)
```

ViewModels are created by a `ViewModelProvider.Factory` in `:app` that pulls from `VeyroContainer`.
Feature modules declare plain constructors — no DI framework annotations anywhere.

## Concurrency model

- Everything asynchronous is **coroutines + Flow**. No callbacks escape a `core:*` module.
- `AppDispatchers` injects `Default` / `IO` / `Main` so tests can substitute them.
- One `CoroutineScope` per long-lived component, cancelled with its owner. No `GlobalScope`.
- Socket I/O is blocking-on-`Dispatchers.IO` with structured cancellation; a cancelled transfer
  closes its sockets in a `finally` block.
- Discovery and transport own their scopes; the UI only *collects*. Rotating the screen must never
  cancel a transfer (that is why transfers live in a service, REQ-T09).

## State flow for a send

```
feature:send                core:transfer           core:transport        core:discovery
    │ pick files                  │                       │                     │
    │ openWindow() ───────────────┼───────────────────────┼────────────────────►│ advertise+browse
    │ ◄─── Flow<List<PeerDevice>> ┼───────────────────────┼─────────────────────┤
    │ choose peer                 │                       │                     │
    │ send(items, peerId) ───────►│                       │                     │
    │                             │ connect(peerId) ─────►│ race addresses      │
    │                             │                       │ handshake (KK)      │
    │                             │ ◄── SecureSession ────┤                     │
    │                             │ offer → decision      │                     │
    │ ◄─ Flow<TransferProgress> ──┤ stream chunks         │                     │
    │                             │ verify + complete     │                     │
    │                             │ closeWindow() ────────┼────────────────────►│ stop
```

## Source layout convention

```
<module>/
├─ build.gradle.kts
└─ src/
   ├─ main/kotlin/com/veyro/<module-path>/...
   ├─ main/res/...                 (only UI modules)
   ├─ main/AndroidManifest.xml     (only when something must be declared)
   └─ test/kotlin/com/veyro/<module-path>/...
```

Package = `com.veyro.core.<name>` or `com.veyro.feature.<name>`, matching the Gradle path.

## Build logic

`build-logic/convention` holds Gradle convention plugins so that module build files stay ~10 lines:

| Plugin id | Applies to |
|---|---|
| `veyro.android.application` | `:app` |
| `veyro.android.library` | every `:core:*` / `:feature:*` Android library |
| `veyro.android.library.compose` | UI-bearing libraries |
| `veyro.android.feature` | `:feature:*` (library + compose + common feature deps) |
| `veyro.jvm.library` | `:core:model`, `:core:protocol` |

All versions come from `gradle/libs.versions.toml`. No version literals in module build files.

---

## Windows client architecture (`VeyroWindows/`) — ADR-012

C# / .NET 10, WinUI 3, MSIX. Three projects, and the layering rule from the Android side is preserved:
**the protocol and crypto live in a UI-free library that is testable without a window.**

```
┌──────────────────────────────────────────────────────────────────────┐
│  Veyro            WinUI 3 + MSIX app: Views, ViewModels, UserControls│
│                   Platforms/Windows (DPAPI, tray, interop), Container│
├──────────────────────────────────────────────────────────────────────┤
│  Veyro.Core       net10.0, zero WinUI/Windows-UI references          │
│                   Models · Protocol · Crypto · Data · Discovery      │
└──────────────────────────────────────────────────────────────────────┘
          ▲
  Veyro.Tests      net10.0 + xUnit, references Veyro.Core only
```

### Dependency rules (the Windows mirror of the Android rules)

1. `Veyro.Core` targets plain `net10.0` and must not reference `Microsoft.WindowsAppSDK`,
   `Microsoft.UI.*`, or `Windows.*`. It is the counterpart of `:core:protocol` + `:core:model`
   (ADR-009) and the reason the protocol can be tested without a packaged app.
2. Anything needing DPAPI, the tray, COM interop, or a window handle lives under
   `Veyro/Platforms/Windows/`, behind an interface declared in `Veyro/Data/Contracts/`.
3. Only `VeyroContainer` wires the object graph. No DI framework, mirroring ADR-005.
4. ViewModels never touch sockets or the filesystem directly; they depend on the service
   interfaces in `Veyro/Data/Contracts/`.
5. Package versions live in `src/Directory.Packages.props` (Central Package Management) —
   the `libs.versions.toml` equivalent. No version literals in a `.csproj`.

### Project layout

| Path | Responsibility |
|---|---|
| `src/Veyro.sln` | Solution; `x64` and `ARM64` configurations |
| `src/global.json` | Pins .NET SDK 10.0.103, `rollForward: latestFeature` |
| `src/Directory.Build.props` | Shared properties; redirects `bin`/`obj` to `VeyroWindows/artifacts/` |
| `src/Directory.Packages.props` | Central Package Management — all package versions |
| `src/Veyro.Core/Models/` | `DeviceId`, `Crockford32`, `PeerDevice`, `TrustedPeer`, transfer + companion models |
| `src/Veyro.Core/Protocol/` | `VlpConstants`, `RelativePath`, `TcpFrame`, `TcpFrameCodec` (VTC1) |
| `src/Veyro.Core/Crypto/` | `Hkdf`, `X25519Crypto`, `DefaultIdentityManager`, `ISeedStore` |
| `src/Veyro.Core/Data/` | `ISettingsStore`/`ITrustStore`/`ITransferHistoryStore` + file-backed implementations |
| `src/Veyro.Core/Discovery/` | `IDiscoveryService`, `ITransferService` contracts |
| `src/Veyro/Container/` | `VeyroContainer` — manual DI root (ADR-005) |
| `src/Veyro/Data/` | `Contracts/`, `Enums/`, `Models/` (observable UI models) |
| `src/Veyro/Platforms/Windows/` | `DpapiSeedStore`, `SystemTrayService`, `InteropHelpers` |
| `src/Veyro/Services/` | Theme, device manager, file transfer, screen mirror |
| `src/Veyro/UserControls/` | `TitleBar`, `DeviceControlCenter`, `MediaSessionsControl`, `NotificationsControl`, `ActiveTransferBanner` |
| `src/Veyro/ViewModels/` | `BaseViewModel` + per-page VMs, `Settings/` subfolder |
| `src/Veyro/Views/` | `MainPage`, `TransfersPage`, `MessagesPage`, `CallsPage`, `AppsPage`, `SettingsPage` |
| `src/Veyro/Dialogs/` | `ConnectionRequestDialog` (6-digit pairing confirmation) |
| `src/Veyro/Package.appxmanifest` | MSIX identity, `veyro://` protocol, Share Target, startup task |
| `src/Veyro.Tests/` | xUnit suite over `Veyro.Core` |

### Identity storage: DPAPI, not Keystore

Android seals the X25519 seed with a hardware-backed Keystore key (REQ-I03). Windows has no
equivalent guarantee available to a packaged desktop app, so `DpapiSeedStore` uses
`ProtectedData` at `CurrentUser` scope with a fixed entropy string and the same
atomic temp-then-rename write. This is a deliberately weaker property than StrongBox/TEE and is
recorded here so it is not mistaken for parity.

### Provenance and licence hygiene

The Windows shell layout (`Views` / `ViewModels` / `UserControls` / `Platforms` split, split-pane
navigation) follows **standard WinUI 3 and MVVM conventions**. All C# and XAML in `VeyroWindows/`
is original work written for Veyro.

During the P5 bootstrap a third-party WinUI companion app was briefly checked out under `test/`
to survey how such apps are usually laid out. That project is **GPL-3.0**, which is incompatible
with Veyro's proprietary licensing, so:

- No code, markup, resources, or assets were copied from it.
- The checkout was **deleted** from the tree on 2026-10-15, and `test/` is git-ignored.
- Nothing in `VeyroWindows/` is a derivative work of it.

Rule going forward: **do not clone copyleft projects into this tree.** If a third-party
implementation must be consulted, read it outside the repository and record only the conclusion
here. Directory conventions and framework idioms are not protectable expression; literal code is.

