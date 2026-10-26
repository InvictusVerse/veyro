# VLP v1 Test Vectors

Interop and conformance vectors shared by the Android and Windows implementations.

**Why this directory exists:** VLP is implemented twice. Without shared vectors, the two
implementations agree only by accident and disagree only in the field, on a user's hotspot, at the
worst possible moment. Both platforms must pass every vector here before a phase is closed.

## Layout

| Directory | Contents | Status |
|---|---|---|
| `identity/` | deviceId + fingerprint derivation from a fixed public key | populated |
| `path-safety/` | inbound `relPath` accept/reject corpus | **empty — the table previously claimed otherwise** |
| `frames/` | `VTC1` data-plane frame encodings, byte-exact | **populated** — enforced by both platforms |
| `link/` | `FrameCrypto` seal/open, PBKDF2 derivation, control-plane JSON | **populated** — enforced by both platforms |
| `discovery/` | presence record + UDP beacon encodings | **populated** — enforced by both platforms |
| `transfer/` | resume bitmap, manifest round trips | **empty — waits on phase P4 close-out** |

`noise/` was removed. Noise is not used: the link layer is `FrameCrypto` over a WebSocket
(ADR-020), and the directory only perpetuated the stale design described in old revisions of
`04-PROTOCOL.md`.

## Who enforces what

| Vector file | Android | Windows |
|---|---|---|
| `identity/device-id.json` | `core/model` `DeviceIdTest` | `Veyro.Tests` `InteropVectorTests` |
| `frames/vtc1-frames.json` | `core/protocol` `Vtc1VectorTest` | `Veyro.Tests` `InteropVectorTests` |
| `link/framecrypto.json` | `core/link` `FrameCryptoVectorTest` | `Veyro.Tests` `FrameCryptoVectorTests` |
| `discovery/presence-beacon.json` | `core/protocol` `PresenceBeaconVectorTest` | `Veyro.Tests` `DiscoveryVectorTests` |
| `link/control-messages.json` | `core/link` `ControlMessageVectorTest` | `Veyro.Tests` `ControlMessageVectorTests` |

Run them:

```
# Android
gradlew :core:protocol:test --tests "com.veyro.core.protocol.Vtc1VectorTest"
gradlew :core:link:testDebugUnitTest --tests "com.veyro.core.link.FrameCryptoVectorTest"

# Windows
dotnet test VeyroWindows/src/Veyro.Tests/Veyro.Tests.csproj
```

Note that `:core:protocol` is a pure-JVM module (`test`) while `:core:link` is an Android library
(`testDebugUnitTest`); the task names differ.

The Windows suite loads `frames/vtc1-frames.json` at runtime and drives one test per vector, so
adding a vector to the JSON automatically adds a Windows test. The Android suite pins the same
constants inline, matching the existing `DeviceIdTest` convention, because `core/protocol` is a
pure-Kotlin module with no JSON dependency and adding one for tests is not worth the coupling.

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

The first Windows implementation of the `VTC1` codec omitted `transferId` and `seq` from the frame
header entirely, used `u32` where the wire uses `u16` for string lengths, and gave `Chunk` a byte
offset where the wire carries a chunk index.

It passed every one of its own tests, because the same wrong assumption produced both its encoder
and its decoder. A round-trip test cannot detect this. Only comparing against a value derived
outside the implementation can — which is what `frames/vtc1-frames.json` now does.

