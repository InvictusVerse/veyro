# VLP Test Vectors

Interop and conformance vectors shared by the Android and Windows implementations.

**Why this directory exists:** VLP is implemented twice. Without shared vectors, the two
implementations agree only by accident and disagree only in the field, on a user's hotspot, at the
worst possible moment. Both platforms must pass every vector here before a phase is closed.

## Layout

| Directory | Contents | Status |
|---|---|---|
| `identity/` | deviceId + fingerprint derivation from a fixed public key | populated |
| `path-safety/` | inbound `relPath` accept/reject corpus | **empty — the table previously claimed otherwise** |
| `crypto/` | RFC vectors for the fixed primitives: HKDF-SHA256, ChaCha20-Poly1305, X25519 | **populated** (ADR-058) — enforced by both platforms |
| `frames/` | `VTC3` sealed data-plane records, byte-exact | **populated** — enforced by both platforms |
| `link/` | v3 connection and pairing key schedules, session frames, key exporter, control-plane JSON | **populated** — enforced by both platforms |
| `tools/` | `GenV3.java` (every v3 file above) and `GenVms1.java` (`media/vms1.json`, `media/fragmentation.json`) | source, not vectors |
| `discovery/` | presence record + UDP beacon encodings | **populated** — enforced by both platforms |
| `media/` | VMS1 media socket records (sealed, byte-exact), fragmentation, mirrored-screen geometry (ADR-066) | **populated** — enforced by both platforms |
| `transfer/` | resume bitmap, manifest round trips | **empty — waits on phase P4 close-out** |

`noise/` was removed. Noise is not used: the link layer is `FrameCrypto` over a WebSocket
(ADR-020), and the directory only perpetuated the stale design described in old revisions of
`04-PROTOCOL.md`.

## Who enforces what

| Vector file | Android | Windows |
|---|---|---|
| `identity/device-id.json` | `core/model` `DeviceIdTest` | `Veyro.Tests` `InteropVectorTests` |
| `crypto/hkdf-sha256.json`, `crypto/chacha20poly1305.json`, `crypto/x25519.json` | `core/link` `ChaChaPolyVectorTest` | `Veyro.Tests` `ChaChaPolyVectorTests` |
| `link/connection-schedule-v3.json` | `core/link` `ConnectionScheduleVectorTest` | `Veyro.Tests` `ConnectionScheduleVectorTests` |
| `link/pairing-schedule-v3.json` | `core/link` `ConnectionScheduleVectorTest`, `core/crypto` `PairConfirmVectorTest` (static key) | `Veyro.Tests` `ConnectionScheduleVectorTests`, `PairConfirmVectorTests` |
| `link/session-frames-v3.json` | `core/link` `SessionFrameVectorTest` | `Veyro.Tests` `SessionFrameVectorTests` |
| `link/exporter-v3.json` | `core/link` `ExporterVectorTest` | `Veyro.Tests` `ExporterVectorTests` |
| `frames/vtc3-frames.json` | `core/link` `Vtc3VectorTest` | `Veyro.Tests` `Vtc3VectorTests` |
| `discovery/presence-beacon.json` | `core/protocol` `PresenceBeaconVectorTest` | `Veyro.Tests` `DiscoveryVectorTests` |
| `link/control-messages.json` | `core/link` `ControlMessageVectorTest` | `Veyro.Tests` `ControlMessageVectorTests` |
| `pair-confirm.json` | `core/crypto` `PairConfirmVectorTest` | `Veyro.Tests` `PairConfirmVectorTests` |
| `link-device-info-controls.json` | `core/link` `DeviceInfoControlsVectorTest` | `Veyro.Tests` `DeviceInfoControlsVectorTests` |
| `link-stream.json` | `core/link` `media.MediaRulesVectorTest` | `Veyro.Tests` `Media.MirroringVectorTests` |
| `media/vms1.json` (from `tools/GenVms1.java`) | `core/link` `media.Vms1VectorTest` | `Veyro.Tests` `Media.MirroringVectorTests` |
| `media/fragmentation.json` (from `tools/GenVms1.java`), `media/stream-geometry.json` | `core/link` `media.MediaRulesVectorTest` | `Veyro.Tests` `Media.MirroringVectorTests` |

Run them:

```
# Android
gradlew :core:link:testDebugUnitTest --tests "com.veyro.core.link.SessionFrameVectorTest"
gradlew :core:link:testDebugUnitTest --tests "com.veyro.core.link.ConnectionScheduleVectorTest"

# Windows
dotnet test VeyroWindows/src/Veyro.Tests/Veyro.Tests.csproj
```

Note that `:core:protocol` is a pure-JVM module (`test`) while `:core:link` is an Android library
(`testDebugUnitTest`); the task names differ.

Both suites load the v3 files at runtime (Android from `core/link`, which has a JSON dependency).
Older files are still pinned inline on Android where the module has no JSON dependency, matching
the `DeviceIdTest` convention.

## Retired files (ADR-058)

Link protocol v3 replaced the v2 control-frame crypto and the VTC1 data plane. These files stay
frozen as history; no test reads them and no build sends or accepts what they describe.

| File | Described | Replaced by |
|---|---|---|
| `link/framecrypto.json` | v2 `FrameCrypto` seal/open (explicit `n` and `m` fields) and the PBKDF2 PIN derivation | `link/session-frames-v3.json` |
| `frames/vtc1-frames.json` | the unsealed VTC1 data plane | `frames/vtc3-frames.json` |
| `pair-confirm.json` `expected.bootstrapKeyHex` | the v2 confirm bootstrap key | the static key in `link/pairing-schedule-v3.json` (`confirm`) |

In `link/control-messages.json`, an entry marked `"retiredIn": 3` describes the v2 wire shape; its
values were not edited. The rest of `pair-confirm.json` (keys, ids, commitment, code) is still
current.

## How the v3 files were made

`tools/GenV3.java` is a single-file Java program that uses only JDK primitives (XDH for X25519,
`ChaCha20-Poly1305`, `HmacSHA256`, SHA-256) and writes HKDF as the two RFC 5869 loops. It shares
no code with either client. It first checks itself against RFC 5869 A.1-A.3, RFC 8439 2.8.2 and
RFC 7748 6.1 and exits non-zero if one fails, then derives every v3 value from the formulas in
`ProjectInfo/04-PROTOCOL.md` sections 4, 5 and 7 with fixed inputs, so a rerun is byte-identical.
Run it from the repository root as its header comment shows (TMP, TEMP and hsperfdata stay off C:).

## Format

Each vector file is JSON with a `description`, an `input`, and an `expected` field, so a harness on
either platform can be written in a few dozen lines without a shared library. Vectors are
**frozen**: never edit an existing vector to match an implementation. If an implementation
disagrees, the implementation is wrong, or the protocol changed and needs a version bump.

## The rule

> A vector that only one platform passes is not a passing vector. It is a bug report about the
> other platform.

## Adding a vector

1. Derive the expected value from the specification, **not** from an implementation's output.
   Copying an implementation's output into a vector only encodes that implementation's bugs.
2. If the vector requires cryptography, reference published vectors (RFC 5869 for HKDF, RFC 7748
   for X25519) instead of generating your own — a self-generated crypto vector proves nothing.
3. Add it to the relevant file and note it in `ProjectInfo/10-CHANGELOG.md`.
4. Make **both** platforms enforce it before considering it done.

## Worked example of why rule 1 matters

The first Windows implementation of the (now retired) `VTC1` codec omitted `transferId` and `seq` from the frame
header entirely, used `u32` where the wire uses `u16` for string lengths, and gave `Chunk` a byte
offset where the wire carries a chunk index.

It passed every one of its own tests, because the same wrong assumption produced both its encoder
and its decoder. A round-trip test cannot detect this. Only comparing against a value derived
outside the implementation can — which is what `frames/vtc1-frames.json` did and
`frames/vtc3-frames.json` does now.

