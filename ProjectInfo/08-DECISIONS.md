# 08 — Architecture Decision Records

Append-only. Never edit a decision after it is accepted — add a new ADR that supersedes it.

Format: **Context → Decision → Alternatives → Consequences → Status**

---

## ADR-001 — Wi-Fi/LAN-first discovery, Bluetooth optional

**Context.** Quick Share's discovery pulls in Bluetooth/BLE even when both devices are already on
the same Wi-Fi or hotspot, forcing the user to enable a radio that the transfer will not use.

**Decision.** All discovery and transport in v1 runs over IP. Bluetooth/BLE is not referenced by
any required code path, and the app declares no Bluetooth or location permissions.

**Alternatives.** (a) Mirror Quick Share's stack — rejected, it is the problem we are solving.
(b) BLE for discovery + Wi-Fi for transfer — rejected for v1; it reintroduces the radio
requirement and a second permission story (BLE scanning historically implies location).

**Consequences.** Devices with no common IP network cannot discover each other in v1. This is
handled by diagnosis (`NO_NETWORK`) with hotspot guidance rather than a silent failure. Wi-Fi
Direct and BLE fallback are deferred to P8 as opt-in.

**Status.** Accepted.

---

## ADR-002 — mDNS primary, UDP beacons as fallback

**Context.** mDNS is the standard, well-behaved mechanism and is supported natively on both
platforms. It is also silently broken on some hotspot drivers, some Windows firewall profiles, and
some APs that drop multicast.

**Decision.** Advertise and browse `_veyro._tcp` via mDNS first. Run UDP multicast + per-interface
directed broadcast beacons concurrently in the same discovery window, deduplicating by `deviceId`.

**Alternatives.** (a) mDNS only — rejected, fails exactly in the hotspot cases we must support.
(b) UDP only — rejected, reinvents service discovery and behaves worse on normal LANs.

**Consequences.** Two code paths to maintain and test. Dedup logic required. Diagnosis can
distinguish `MULTICAST_BLOCKED` by observing which mechanism produced a result.

**Status.** Accepted.

---

## ADR-003 — Single X25519 static identity, no separate signing key

**Context.** The original sketch used an Ed25519 identity key for signatures plus an X25519 key for
key agreement, requiring a binding between them.

**Decision.** One X25519 static key pair per installation is the identity. Mutual authentication
comes from the Noise KK handshake, which proves possession of the static private key. Where a MAC
is needed outside a session (presence tokens), use HMAC-SHA256 with the per-peer `pairingSecret`.

**Alternatives.** (a) Ed25519 + X25519 derived from one seed, with a self-signature binding them —
more moving parts, an extra primitive, and a binding format to get right, for no capability we
need. (b) Ed25519 only — cannot do key agreement.

**Consequences.** No signature primitive in v1 at all. Fewer primitives to implement and test.
If offline-verifiable signed artifacts are ever needed (e.g. signed update manifests), an Ed25519
key can be added later as a separate, clearly-scoped key.

**Status.** Accepted.

---

## ADR-004 — Noise KK/XK over TLS 1.3 *(provisional)*

**Context.** We need a mutually authenticated, forward-secret channel between two peers that
already know each other's long-term public key after pairing.

**Decision.** Use `Noise_KK_25519_ChaChaPoly_SHA256` for paired sessions and
`Noise_XK_25519_ChaChaPoly_SHA256` for pairing, implemented over BouncyCastle primitives on
Android and .NET primitives on Windows.

**Alternatives.** TLS 1.3 with self-signed certificates and manual pinning. TLS has the enormous
advantage of a reviewed, maintained implementation on both platforms. Its disadvantages here:
certificates and X.509 parsing add attack surface we do not need, hostname/SNI semantics are
meaningless peer-to-peer, raw public keys (RFC 7250) are awkward on both platforms, and pinning
logic ends up being hand-written anyway.

**Consequences and the honest risk.** We would be implementing a handshake *state machine*
ourselves. That is materially safer than inventing cryptography — the primitives and the pattern
are published and vetted — but it is still less safe than using a platform TLS stack. This ADR is
therefore **provisional** and is gated on all of the following before 1.0:

1. Official Noise test vectors checked into `protocol/testvectors/noise/`, passing on both platforms.
2. Negative tests: tampered, truncated, reordered, replayed, wrong-static-key.
3. The state machine isolated in one small file, reviewed independently.

If any gate cannot be met, we revert to TLS 1.3 + pinned self-signed certificates. Reverting costs
a protocol version bump, which is acceptable.

**Status.** Provisionally accepted — revisit before P3 completion.

---

## ADR-005 — Manual dependency injection, no Hilt/Dagger/Koin

**Context.** The app has a small, mostly process-scoped object graph: identity, stores, discovery,
transport, transfer engine.

**Decision.** A hand-written `VeyroContainer` created by `VeyroApplication`, with constructor
injection everywhere and a single `ViewModelProvider.Factory` in `:app`.

**Alternatives.** Hilt — the default choice for a team-scale Android app. Rejected here because it
adds KSP + Dagger codegen to every module, slows incremental builds across ~22 modules, pulls in
annotation-processing dependencies, and buys little for a graph this shape. Koin — runtime
resolution and no compile-time safety, which is a worse trade than writing the graph explicitly.

**Consequences.** The container must be maintained by hand; adding a dependency means editing one
file. Feature modules stay framework-free and trivially unit-testable. If the graph grows past what
one readable file can express, revisit with a new ADR rather than bolting on a framework quietly.

**Status.** Accepted.

---

## ADR-006 — CBOR files for persistence, no Room in v1

**Context.** We need to persist a trust store, settings, transfer history and per-file resume
sidecars. Volumes are tiny (tens of records), except history which is append-mostly.

**Decision.** `kotlinx-serialization` CBOR written to app-private files with an atomic
write-temp-then-rename, plus an HMAC over the trust store. No Room, no DataStore, no KSP.

**Alternatives.** Room — correct for relational, queryable, large datasets; overkill here and it
drags in KSP. DataStore Proto — reasonable, but adds protobuf tooling for the same outcome.
SharedPreferences — not suitable for structured records.

**Consequences.** No SQL querying; history filtering happens in memory. If history grows into
tens of thousands of entries or needs real queries, migrate to Room/SQLite behind the existing
`TransferHistoryStore` interface — that is why it is an interface.

**Status.** Accepted.

---

## ADR-007 — Private-mode presence tokens are O(paired peers)

**Context.** To avoid broadcasting a stable identifier (privacy) while still letting paired peers
recognise each other, advertisements carry a rotating token that a browser must test against each
stored `pairingSecret`.

**Decision.** Accept the linear scan for v1, with the scan bounded to `epoch` and `epoch-1`.

**Consequences.** Cost is `2 × pairedPeers` HMACs per observed advertisement. With < 20 peers this
is irrelevant. Must be benchmarked before private mode becomes the default; if it is not, fall back
to advertising a truncated `deviceId` in private mode and accept the weaker privacy property.

**Status.** Accepted, with a measurement gate in P2.

---

## ADR-008 — Single TCP stream for bulk transfer in v1

**Context.** Multi-stream transfer is a common throughput trick, but it multiplies handshake cost,
scheduling complexity, and resume bookkeeping.

**Decision.** One TCP connection per session carrying both control and bulk frames, with
`TCP_NODELAY`, OS-tuned socket buffers, and 4 MiB chunks.

**Alternatives.** N parallel data connections — deferred. QUIC — attractive (streams, built-in
crypto, better loss recovery) but adds a large dependency on both platforms and does not obviously
beat TCP on a clean LAN.

**Consequences.** If measurements in P4 show we cannot hit NFR-05 (≥ 70% of link throughput),
revisit with data. Do not add parallelism speculatively.

**Status.** Accepted.

---

## ADR-009 — `core:model` and `core:protocol` are pure JVM modules

**Context.** The protocol must be reimplemented byte-for-byte on Windows, and the wire logic must
be unit-testable without a device or emulator.

**Decision.** Those two modules use the Kotlin JVM plugin only. Any `android.*` import in them is a
defect.

**Consequences.** Some convenience APIs (e.g. `android.util.Base64`, `Uri`) are unavailable there
and must be replaced with pure-Kotlin equivalents in `:core:common`. Worth it: these are the
modules the Windows port mirrors, and they run in millisecond-scale JVM tests.

**Status.** Accepted.

---

## ADR-010 — Pin to already-cached toolchain versions

**Context.** The development machine has limited free disk space and an existing Gradle cache at
`D:\CodingSoftwares\GradleCache` containing Gradle 9.7.1, AGP 9.3.1, Kotlin 2.4.20, Compose BOM
2026.09.00 and others.

**Decision.** Pin the build to versions already present in that cache, and reuse the existing
Gradle wrapper jar, rather than downloading the newest releases (Gradle 9.8.0 / AGP 9.4.1).

**Alternatives.** Always-latest — costs hundreds of MB of downloads for no functional gain at this
stage, on a machine with ~24 GB free.

**Consequences.** Versions are current-minus-one at most. Upgrade deliberately, as its own change,
when there is a reason. Record every version in `09-ENVIRONMENT.md`.

**Status.** Accepted.

---

## ADR-011 — minSdk 29 (Android 10)

**Context.** Lower minSdk widens reach; higher minSdk removes legacy storage and networking
branches.

**Decision.** `minSdk = 29`, `targetSdk = 37`, `compileSdk = 37`.

**Rationale.** API 29 is the first release with scoped storage as the norm, removing the legacy
external-storage code path entirely. It also gives stable `ConnectivityManager.NetworkCallback`
behaviour and multi-network APIs that the hotspot scenarios depend on. Real-world coverage at API
29+ is high enough that the removed complexity is worth more than the marginal reach.

**Consequences.** Devices on Android 9 and below are unsupported. Notification runtime permission
(API 33+) and foreground-service types (API 34+) still require version-gated code.

**Status.** Accepted.

---

## ADR-012 — Windows client is C# / .NET 10, packaged MSIX, WinUI 3

**Decided 2026-10-08.** Supersedes the earlier "undecided" version of this ADR.

**Context.** The Windows client needed a language and a UI framework. The candidates were
Kotlin Multiplatform (sharing code with the Android client), C# / .NET, and C++.

**Decision.**

| | |
|---|---|
| Language | **C# / .NET 10** (already installed: 10.0.103) |
| UI | **WinUI 3** (Windows App SDK) |
| Packaging | **MSIX with package identity** |
| Shared with Android | **Nothing at the code level.** The protocol specification and test vectors are the contract |

**The deciding requirement** was stated plainly by the project owner: *smooth integration in
Windows.* Concretely that means the things below, and they are what drove every part of this
decision:

- Right-click a file in Explorer -> "Send to Veyro", in the **modern** Windows 11 context menu
  rather than buried under "Show more options".
- Appear as a target in the Windows **Share** sheet.
- Tray icon, start-on-login, actionable toast notifications.

These are not UI-toolkit features. They require **MSIX package identity**:

- The modern Win11 context menu needs an `IExplorerCommand` COM handler registered through a
  package manifest. The legacy registry verb still works but only renders in the overflow menu.
- The Share contract requires a packaged app.
- Toasts with action buttons need a registered `AppUserModelID`, which package identity provides.

So the packaging decision came first and the UI framework followed it: WinUI 3 is the
well-trodden path for a packaged Windows 11 app, and it gives genuinely native Fluent chrome
(mica, acrylic, correct title bar behaviour) rather than an approximation.

**Why not KMP.** The honest case for KMP was strong and it was not about convenience: sharing
`core:protocol` and the Noise state machine would mean one implementation, one review (ADR-004),
and no possibility of the two clients drifting. It was rejected because:

1. Compose Desktop is JVM-only, so the Windows client would ship a bundled JVM.
2. Windows shell integration from the JVM means JNA/JNI for every item in the list above. MSIX
   packaging of a JVM app with a COM context-menu handler is possible and unpleasant.
3. The drift risk KMP would eliminate is already mitigated by `protocol/schema/vlp-v1.cddl` and
   `protocol/testvectors/`, which exist for exactly this purpose. A vector that passes on one
   platform and fails on the other is a bug report, not a disagreement.

**Why not C++.** Two reasons, the first serious: the Windows client parses untrusted network input
and writes files from it, which is precisely the shape of code where a C++ length error becomes
remote code execution. Having made the path validator rigorous, implementing it in a language
where mistakes are exploitable rather than thrown would undermine that. Second, it shares nothing
with the Kotlin side, so a solo developer would maintain two implementations of a security
protocol with no safety benefit in return.

**A correction to an earlier claim.** While weighing these options it was asserted that C# would
give "a ~15 MB self-contained exe with NativeAOT". That is wrong for a GUI app: **NativeAOT does
not support WinUI 3 or WPF**. A self-contained WinUI 3 MSIX is realistically 40-60 MB packaged and
~100 MB installed, and idle RSS for a .NET GUI app is roughly 40-80 MB - not dramatically better
than a JVM.

The real advantage of C# here is **integration, not footprint.** The footprint argument against
KMP was weaker than stated; the shell-integration argument is the one that actually holds.

**Consequences.**

- The protocol exists twice. `protocol/testvectors/` moves from "good practice" to **load-bearing**:
  it is now the only mechanism preventing divergence, and every phase must add vectors before its
  Windows counterpart is written.
- `Veyro.Protocol` and `Veyro.Crypto` must be written against `04-PROTOCOL.md` and the vectors,
  **never** by reading the Kotlin and translating it. Translation copies bugs and gives false
  confidence that two implementations agree.
- A second crypto integration is needed: .NET has `ChaCha20Poly1305` in the BCL, but X25519 needs
  NSec or BouncyCastle.NET. The at-rest key goes to DPAPI or CNG rather than a Keystore.
- Keep `Veyro.Daemon` separate from `Veyro.App` (as already scaffolded), so discovery and transfer
  are not tied to a window being open. Weigh the IPC cost against a single tray-resident app at P5.
- If idle footprint later proves unacceptable, the fallback is a NativeAOT headless daemon plus a
  thin UI - which the existing project split already allows.

**Status.** Accepted.

---

## ADR-013 — Reuse patterns from Invox_Android, not code, by default

**Context.** The author's existing Android music app (`D:\Coding\VisualStudio\Invox_Android`) is a
working Kotlin 2.4.20 / AGP 9.x / Compose project with a Ktor client+server, services and
networking already proven on this machine.

**Decision.** Treat it as a reference for *build configuration and patterns* that are known to work
on this toolchain. Copy code only where it is genuinely generic (e.g. permission helpers, service
scaffolding, build DSL), always with attribution in `10-CHANGELOG.md`, and never modify that
repository.

**Consequences.** Faster, lower-risk setup of the build; no architectural coupling to a music app.
Findings recorded in `11-INVOX-FINDINGS.md`.

**Status.** Accepted.

---

## ADR-014 — `ktor-network` for sockets, not `java.net` and not Ktor HTTP

**Context.** The transport layer needs a TCP listener and dialer whose lifetime is tied to
coroutine scopes, so that cancelling a transfer closes its sockets deterministically.

**Decision.** Use `io.ktor:ktor-network` (suspend `aSocket()` server/client) in `:core:transport`.
Nothing else from Ktor: no HTTP client, no HTTP server, no WebSockets.

**Alternatives.**
- `java.net.Socket` on `Dispatchers.IO` — works, but every read/write is a blocking call that must
  be manually wrapped for cancellation, and a cancelled coroutine does not interrupt a blocked
  `read()`. That is a real leak source during a cancelled 20 GB transfer.
- Ktor WebSockets over HTTP, which is what the Invox ecosystem code does. Rejected: it adds an HTTP
  handshake, an HTTP server, and a frame layer on top of the frame layer we already need, and
  Invox's protocol is JSON text frames capped at 1 MiB — unusable for bulk binary.
- QUIC. Attractive on paper (streams, built-in crypto, better loss recovery) but a large dependency
  on both platforms and no obvious win on a clean LAN.

**Consequences.** `ktor-network` 3.5.2 is already in the Gradle cache (~70 KB) so it costs nothing
to add. Ktor's TLS artifact is deliberately NOT used — the channel is Noise, per ADR-004.

**Status.** Accepted.

---

## ADR-015 — Build memory is tuned to this machine's commit limit, not to a template

**Context.** The first full build killed the Gradle daemon twice with `Failed to commit metaspace`
(`hs_err_pid*.log`). The settings had been copied from `Invox_Android`: `-Xmx6g`,
`MaxMetaspaceSize=1g`, 4 workers, parallel execution.

**Measurement.** 15.7 GB physical, i7-13650HX with **20 cores**, Windows 11. The binding constraint
is not free RAM but the **system commit limit**: the page file is fixed at 4-8 GB, so the commit
limit is ~19.7 GB with only ~5 GB free while WSA (~2.4 GB) and Memory Compression (~2.6 GB) are
resident. 20 cores makes it worse, because ParallelGC sizes its thread pool from the core count and
every GC thread costs committed stack.

**Decision.** `-Xmx2g`, `MaxMetaspaceSize=512m`, `ReservedCodeCacheSize=256m`,
`ParallelGCThreads=2`, `org.gradle.parallel=false`, `org.gradle.workers.max=1`, Kotlin compiling
in-process so there is one JVM rather than two.

**Alternatives.** Enlarging the page file would also work, but changing the user's system
configuration to make a build fit is the wrong order of operations, and the project should build on
a modest machine regardless.

**Consequences.** A full `assembleDebug` takes ~3.5 minutes instead of ~1.5. The settings are
commented in `gradle.properties` with the measurement and the exact two lines to raise once memory
is freed. Do not raise them without re-measuring.

**Status.** Accepted.

---

## ADR-016 — BouncyCastle lightweight API only, never the JCA provider

**Context.** `minSdk` is 29. Every primitive Veyro needs is on the platform except X25519, which
`KeyAgreement.getInstance("XDH")` exposes only from API 33. API 29-32 therefore needs a library,
and `bcprov-jdk18on` is the obvious one — but it is ~12 MB, carrying the full JCA provider plus
post-quantum, CMS, S/MIME and OpenPGP.

**Decision.**
1. Take SHA-256, HMAC-SHA256 and ChaCha20-Poly1305 from the platform (`MessageDigest`, `Mac`,
   `Cipher`); implement HKDF on `Mac` per RFC 5869.
2. Use BouncyCastle **only** for X25519, and only through its lightweight API
   (`org.bouncycastle.crypto.agreement.X25519Agreement`), as direct class references.
3. Never call `Security.addProvider(BouncyCastleProvider())`.
4. Never add a `-keep class org.bouncycastle.**` rule.

**Why it matters.** As a JCA provider, algorithms are resolved reflectively by name. R8 cannot
follow that, so the entire 12 MB survives shrinking. Direct lightweight-API references give R8 a
concrete call graph and let it discard the rest. The blanket keep rules initially written into
`proguard-rules.pro` would have silently defeated this; they were removed and replaced with a
comment explaining why they must not come back.

**Alternatives.** Tink (also large), or implementing X25519 ourselves — rejected, because
constant-time field arithmetic is precisely where hand-rolled crypto fails, and AGENTS.md forbids
inventing cryptography.

**Consequences.** Debug APKs still carry the full 12 MB because debug builds are not shrunk; that
is expected. Rationale is duplicated in `core/crypto/.../CryptoProviders.kt` so it is visible at the
point of use.

**Measured 2026-10-08 — gate passed.** `assembleDebug` 41.90 MB, `assembleRelease` **3.69 MB**. R8
discarded roughly 38 MB, confirming the prediction. NFR-10 (< 15 MB) is met with a wide margin. The
blanket keep rules that were initially written into `proguard-rules.pro` would have prevented this
entirely, which is why that file now carries a comment forbidding them rather than just omitting
them.

**Status.** Accepted; measurement gate passed.

---

## ADR-017 — No `material-icons-extended`

**Context.** `:core:designsystem` initially exposed
`androidx.compose.material:material-icons-extended` as `api`, so any Material icon was available
everywhere.

**Measurement.** Debug APK **67.79 MB**, of which 66 MB was dex. Replacing the artifact with the
~40-icon `material-icons-core`: **37.88 MB**. A 30 MB difference, measured, from one dependency.

**Cause.** `material-icons-extended` declares every Material icon as a Kotlin function — tens of
thousands of methods of generated code.

**Decision.** Use `material-icons-core` only. If an icon outside that set is needed, add that one
icon as a vector drawable resource.

**Consequences.** Icon choice is constrained: the bottom bar uses `Home`, `Phone`, `List` and
`Settings` instead of better-fitting `Devices`/`History` glyphs, which is cosmetic debt to settle
with two vector drawables. R8 would strip unused icons in a release build anyway, but a 68 MB debug
APK slows every install cycle, and exposing the artifact as `api` from `:core:designsystem` pushed
it into every feature module.

**Status.** Accepted. Point 3 (strict route hierarchy) is amended by **ADR-023**.

---

## ADR-023 — Route selection is fastest-available, not Wi-Fi Direct first

**Context.** ADR-022 point 3 fixed the route hierarchy as `WIFI_DIRECT -> LAN_TCP`, on the
assumption that a direct peer-to-peer link is always faster than going through the network. That
assumption does not survive contact with real hardware:

- An Android/Windows Wi-Fi Direct group commonly negotiates **2.4 GHz**, yielding roughly
  40-100 Mbps of real throughput. 5 GHz P2P requires both radios to support and agree on it.
- A shared LAN over 5 GHz Wi-Fi or Ethernet routinely delivers **300-900 Mbps**.
- Forming a P2P group costs seconds of negotiation and, on many Android devices, **drops the
  existing Wi-Fi association** while the group is up.

So the fixed hierarchy can make a transfer several times slower, and briefly disconnect the user
from their network, in exchange for a "direct" link that was never the faster one. Wi-Fi Direct's
real advantage is **availability**, not speed: it works when there is no shared network at all.

**Decision.**
Route selection becomes a choice between candidates rather than a fixed order.

1. **Enumerate candidates.** `LAN_TCP` is a candidate when the peers are mutually reachable on a
   shared subnet. `WIFI_DIRECT` is a candidate when both peers report P2P capability.
2. **Rank by expected throughput**, derived from local link facts that each side already knows,
   not from a timing probe (a probe costs more than it saves on small transfers):
   | Local link | Rank |
   |---|---|
   | Ethernet | highest |
   | Wi-Fi 5 GHz / 6 GHz | high |
   | Wi-Fi Direct where both peers advertise 5 GHz P2P | high |
   | Wi-Fi 2.4 GHz | medium |
   | Wi-Fi Direct defaulting to 2.4 GHz | medium |
3. **No shared subnet means Wi-Fi Direct wins by default**, because it is the only candidate. This
   is the case the feature exists for.
4. **The loser stays as fallback.** A transfer must never fail because the preferred route failed.
   The existing `tcpHost` / `fallbackHost` pair in `TransferDecision` already carries this.
5. **Prefer not to tear down the user's Wi-Fi association.** When `LAN_TCP` ranks equal to
   `WIFI_DIRECT`, pick `LAN_TCP`, because forming a group has a side effect on the device's
   connectivity and losing the network is more user-visible than a marginal speed difference.
6. **Past measurements may refine future choices.** `TransferThroughputMetrics` already records
   `route` and achieved throughput per transfer; a peer-scoped rolling average is a legitimate
   future input to the ranking. Not required for the first implementation.

**This is not a wire change.** `TransferOffer.preferredRoutes` remains an ordered list of the same
string values, `TransferDecision.route` remains the receiver's final choice, and `TcpFrame.Auth`
still carries the route byte. The sender orders `preferredRoutes` by its own assessment; the
receiver combines that with its own link facts and decides. `VLP_VERSION` stays at 1.

A richer scheme in which each side publishes a numeric link-quality hint would let both peers rank
with full information, but that requires new fields and therefore a `VLP_VERSION` bump. Deferred
until there is evidence the ordering-only signal is insufficient.

**Consequences.**
- Transfers on a healthy 5 GHz or wired LAN stop being penalised by a slower P2P group.
- The user's Wi-Fi connection is no longer dropped to form a group that was not faster anyway.
- Wi-Fi Direct remains fully supported and becomes the automatic choice exactly when it is the
  only way to move bytes.
- Ranking is a heuristic over link metadata, not a measurement. It can be wrong. Because both
  routes stay available and fallback is automatic, a wrong ranking costs throughput, never a
  failed transfer.
- Windows must expose its interface type and Wi-Fi band to participate in ranking; until it does,
  the Windows client ranks conservatively and will favour `LAN_TCP` whenever a shared subnet
  exists.

**Status.** Accepted. Amends ADR-022 point 3.

---

## ADR-018 — Control messages are compact binary, not CBOR

**Context.** `04-PROTOCOL.md` section 5.2 specified canonical CBOR with integer keys for control
messages. While implementing `:core:transfer`, a simpler option turned out to be available.

**Decision.** Control messages use a hand-written compact binary encoding: a one-byte type tag
followed by length-prefixed strings and fixed-width big-endian integers (`ControlCodec`).

**Why this beat CBOR.** It removes a serialization dependency from `:core:transfer` for ten messages
with fixed shapes, and every field is either a length-prefixed string or a fixed-width integer -
which is trivially mirrorable in C# and readable in a packet capture. CBOR's shape-flexibility buys
nothing for a closed set of ten messages, and its main advantage over JSON (binary size) is not
worth a dependency here.

**Why this beats JSON** (which an earlier draft of this work used, and which
`PresenceCodec` still uses for mDNS TXT records): control messages are exchanged inside the
encrypted channel on every chunk boundary, so the parse cost is on the hot path, and JSON would
mean dragging a JSON serializer into the transfer path for no benefit.

**Note the deliberate inconsistency, and why it is not one.** Three encodings now coexist:

| Data | Encoding | Reason |
|---|---|---|
| Presence records (mDNS/UDP) | `key=value` text | mDNS TXT is natively that, and it must be readable in a capture when diagnosing a hotspot |
| Control messages (in-session) | Compact binary | Ten fixed shapes on the hot path; no dependency, trivially portable |
| Trust store (at rest) | CBOR | Holds raw 32-byte keys, which JSON can only express as integer arrays |

Each choice follows from the constraints of that specific data, which is a better argument than
consistency for its own sake.

**Consequences.** `04-PROTOCOL.md` section 5.2 and the CDDL schema in `protocol/schema/` must be
updated to describe the binary encoding rather than CBOR. **This is a wire-format change and needs a
`VLP_VERSION` review** - currently still 1, on the basis that no peer has ever been built against
the CBOR draft, so there is no compatibility to break. That justification stops being true the
moment the Windows client exists.

**Status.** Accepted, with the schema update outstanding.

---

## ADR-020 — Link layer ported from the Invox ecosystem code, replacing raw TCP + Noise

**Decided 2026-10-09.** Supersedes the transport half of ADR-014 and puts ADR-004 on hold.

**Context.** Veyro had a raw-TCP transport with hand-rolled length-prefixed framing and a
first-party Noise KK/XK handshake. Both compiled. Both had tests - 13 loopback integration tests and
22 Noise property tests, all passing. **Neither connected two real devices.** Discovery worked on
hardware (confirmed in logcat), pairing did not, and two separate bugs meant the pairing screen
never even started discovery.

Meanwhile the author has a working, field-tested system in `Invox_Android`
(`app/src/main/java/com/invictus/invox/services/ecosystem/`) that pairs and connects these exact
phones today.

**Decision.** Port the Invox link layer into a new `:core:link` module:

| Veyro | Ported from | What it does |
|---|---|---|
| `LinkCrypto` | `protocol/Crypto.kt` | PBKDF2 PIN bootstrap, AES-GCM + HMAC frames, nonce replay cache |
| `LinkSession` | `transport/Session.kt` | Authenticated conversation, rekey, heartbeat, monotonic seq |
| `LinkServer` / `LinkClient` | `transport/WsServer.kt`, `WsClient.kt` | Ktor CIO WebSocket server on an ephemeral port; OkHttp client with `TCP_NODELAY` |
| `PairingWindowRegistry` | `pairing/PairingManager.kt` | PIN window, TTL, attempt limit |
| `LinkManager` | `EcosystemManager` handshake flow | Preamble -> mode branch -> consent -> PIN -> session |
| `LinkMessages` | `protocol/Messages.kt` | Wire frame, envelope, sealed message hierarchy |

**Why this is the right call and not a retreat.** The deciding argument is not that the Invox crypto
is better - it is not - but that **the flow is what was missing**. Veyro had a handshake with no
conversation around it. A device cannot pick a key until it knows who is calling, and it cannot
learn that from an encrypted frame it has no key for. The preamble/mode-branch step is precisely
that missing piece, and no amount of work on the Noise state machine would have supplied it.

Three further reasons the transport change is independently justified:

1. **WebSocket gives a real connection boundary.** The HTTP upgrade either succeeds or fails;
   raw TCP accepts and then silently stalls.
2. **Protocol-level ping/pong detects a half-open connection.** A phone that walks out of Wi-Fi
   range leaves a raw TCP socket looking open for minutes.
3. **Framing is library code, not ours.** One less hand-written parser between a hostile network and
   the app.

**What is given up, stated plainly.** The Invox scheme has **no forward secrecy** and **no
static-key mutual authentication**. Security rests on a 6-digit PIN protected by PBKDF2 at 120k
iterations, a 5-attempt limit and a 60-second window. That is adequate for LAN pairing between
devices the user physically controls, and it is materially weaker than Noise KK, which Veyro already
had working in tests.

**ADR-004 is therefore paused, not withdrawn.** The Noise implementation and its 22 tests remain in
`:core:crypto` and are not deleted. Upgrading `LinkSession` to carry a Noise handshake inside the
WebSocket is a well-defined later step: the transport, the preamble and the mode branch all stay,
and only the key agreement changes. Doing that **after** the flow is proven on hardware is the right
order; doing it before is what produced a stack that compiled and did not work.

**Consequences.**

- `:core:transport` (raw TCP + Noise sessions) is superseded for device-to-device connections. It
  is left in place for now rather than deleted, because its loopback tests still document the
  framing contract and the transfer engine's `SecureSession` interface.
- The protocol specification in `04-PROTOCOL.md` now describes a system Veyro does not implement.
  **That document must be rewritten or explicitly marked as the future target** before it misleads
  the Windows implementation.
- Base64-in-JSON for bulk chunks costs ~33% overhead. Accepted for now: one frame format beats two,
  and binary frames are a measured optimisation rather than a correctness fix.

**Status.** Accepted.

---

## ADR-019 — The product's own screens live in `:core:ui`, not in `feature/*`

**Context.** Each of the seven main screens had its own `:feature:*` module.

**Decision.** Home, Devices, Pairing, Send, Receive, History and Settings moved into `:core:ui`
under `com.veyro.ui.<screen>`. `feature/` is reserved for genuine additions on top of the transfer
core - Clipboard today, Sync and Handoff later.

**Reasoning.** Veyro *is* a file-transfer app. Those screens are not features bolted onto it, they
are the product. Seven modules bought module-level isolation that nothing needed and cost seven
build files, seven namespaces and seven places to look for a screen. A separate module earns its
keep when something is genuinely separable and optional, which is exactly what `feature/` now means.

**Consequence, stated honestly.** `:core:ui` now depends on `data`, `transfer`, `crypto` and
`network`, so it sits **above** `:core:data` and the `core/` prefix is a misnomer. A rename was
rejected as import churn for no functional gain. The rule that actually matters still holds: nothing
in `data`, `transfer`, `link`, `discovery`, `crypto`, `network`, `protocol` or `model` references the
UI, and module count dropped from 22 to 17.

**Status.** Accepted.

---

## ADR-021 — WebSocket engine configuration: OkHttp framing delegation and application-level bounds

**Context.** In physical Android testing, Ktor client threw `WebSocketException: Max frame size switch is not supported in OkHttp engine.` because `ConnectionClient` configured `install(WebSockets) { maxFrameSize = 24L * 1024 * 1024 }` under `HttpClient(OkHttp)`. Ktor's `OkHttpWebsocketSession` explicitly enforces `if (value != Long.MAX_VALUE) throw WebSocketException(...)` because OkHttp does not support dynamic per-session max frame size switching. The immediate crash caused client sockets to terminate during connection establishment, inducing cascading `ClosedReceiveChannelException` and `Timed out waiting for preamble (10000ms)` on the server.

**Decision.**
1. Omit `maxFrameSize` in the Ktor `WebSockets` client configuration block for `HttpClient(OkHttp)`, allowing it to default to `Long.MAX_VALUE`. OkHttp manages socket framing buffers internally.
2. Maintain `maxFrameSize = 24L * 1024 * 1024` on the server in `ConnectionServer` via Ktor CIO `ServerWebSockets`, which natively enforces inbound frame size limits against denial-of-service.
3. Enforce application-level size bounds in `PeerConnection.start()` (`MAX_FRAME_SIZE_BYTES = 24 MB`) and in `ConnectionManager` (`MAX_PREAMBLE_BYTES = 64 KB`). If any peer transmits an oversized unauthenticated frame or session frame, the connection is dropped immediately before memory exhaustion occurs.

**Alternatives.**
- *(a) Switch client engine to `ktor-client-cio`.* Rejected. OkHttp is battle-tested on Android, natively handles DNS, IPv6, cellular/Wi-Fi switching, system network security config, and no-delay socket factories without adding new dependencies.
- *(b) Leave frames unbounded.* Rejected. Allowing unbounded frame allocation exposes devices to remote memory-exhaustion crashes.

**Consequences.**
- The client engine crash is eliminated.
- Handshakes proceed past the WebSocket upgrade to preamble exchange.
- Explicit memory-exhaustion protections are retained across both directions of the wire.

**Status.** Accepted.

---

## ADR-022 — Hybrid Transport Architecture: WebSocket Control Plane with Raw TCP Payload Plane (Wi-Fi Direct & LAN Fallback)

**Context.** In initial implementations, Veyro sent chunk payloads Base64-encoded inside WebSocket text frames (`LinkWireFrame`). In physical-device testing with large payloads (>90 MB):
1. OkHttp client enforced an internal 16 MiB outbound queue limit, forcing connection drops when socket writes lagged.
2. Base64 encoding incurred ~33% wire byte amplification and unnecessary garbage collector pressure.
3. Wi-Fi networks with high interference or access-point client isolation limited transfer speeds.

**Decision.**
Decouple the control plane from the data plane while preserving the established WebSocket session lifecycle:
1. **Retain WebSocket for Control:** Pairing, session coordination, transfer offers, user acceptance/rejection, cancellation, heartbeats, and progress notices stay on the established WebSocket session (`PeerConnection`). No secondary control channels or competing session abstractions are introduced.
2. **Native Raw TCP Sockets for Data Payloads:** Bulk file payloads are migrated to dedicated raw TCP sockets with a minimal binary frame format (`VTC1`), streaming chunks directly to and from storage (`PartFile`) without Base64 encoding or JSON parsing overhead.
3. **Route Hierarchy (Wi-Fi Direct -> LAN TCP):**
   - The sender advertises route preferences (`WIFI_DIRECT`, `LAN_TCP`) in `TransferOffer`.
   - The receiver prioritizes establishing a Wi-Fi Direct peer-to-peer group via `WifiP2pManager`. If successful, the receiver binds a TCP server socket to the P2P interface address.
   - If Wi-Fi Direct is unavailable, unsupported, or negotiation fails, the receiver falls back immediately to LAN TCP, binding to `0.0.0.0` and providing its LAN IP address (or loopback for testing).
4. **Ephemerally Authenticated Data Socket:**
   - The receiver generates a secure 32-byte cryptographic random hex token (`authToken`) per transfer and conveys it to the sender inside the encrypted WebSocket `TransferDecision`.
   - Upon raw TCP socket connection, the sender must transmit a binary `Auth` frame matching `authToken` within a 10-second timeout before any file data is accepted. Mismatched or unauthenticated connections are rejected and terminated immediately.
5. **Backward Compatibility:**
   - If a peer does not advertise raw TCP routes in its offer (e.g., legacy peer or synthetic unit test harness), `TransferRunner` seamlessly routes chunks over the WebSocket control channel.

**Consequences.**
- Bulk payload bytes no longer touch the WebSocket queue, eliminating OkHttp queue exhaustion and Base64 memory overhead.
- Throughput on device-to-device transfers reaches native socket wire speed (Wi-Fi Direct 802.11ac/ax speeds where available).
- The WebSocket session remains healthy and responsive to cancellations, user interactions, and ping/pong keepalives even under heavy file transfer loads.

**Status.** Accepted.

