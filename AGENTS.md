# AGENTS.md — working agreement for this repository

## Read this first

**`ProjectInfo/` is the single source of truth.** Before doing anything:

1. Read [`ProjectInfo/07-STATUS.md`](ProjectInfo/07-STATUS.md) — what exists, what is stubbed, what is next.
2. Read [`ProjectInfo/03-ARCHITECTURE.md`](ProjectInfo/03-ARCHITECTURE.md) — module map and layering rules.
3. For anything touching the wire, read [`ProjectInfo/04-PROTOCOL.md`](ProjectInfo/04-PROTOCOL.md) and
   [`ProjectInfo/05-SECURITY.md`](ProjectInfo/05-SECURITY.md).

After doing anything, update `ProjectInfo/07-STATUS.md` and append to
`ProjectInfo/10-CHANGELOG.md`. Non-obvious technical choices get an ADR in
`ProjectInfo/08-DECISIONS.md`.

## Hard constraints

- **Nothing may be written to `C:`.** No caches, no SDKs, no temp artefacts. `GRADLE_USER_HOME` is
  `D:\CodingSoftwares\GradleCache` and must stay that way. Free space is tight on both drives.
- **Do not download what is already on disk.** Prefer versions already in the Gradle cache
  (see `ProjectInfo/09-ENVIRONMENT.md`). Adding a dependency is a decision, not a reflex.
- **`D:\Coding\VisualStudio\Invox_Android` is read-only.** It is a reference project. Never create,
  edit, or delete anything inside it.
- **No new top-level folders** without recording why in `ProjectInfo/`.

## Architecture rules (enforced by review)

1. `:core:model` and `:core:protocol` are pure Kotlin/JVM — **zero `android.*` imports**. They are
   mirrored by the Windows implementation and must stay portable (ADR-009).
2. A `:feature:*` module never depends on another `:feature:*` module.
3. Only `:app` wires the object graph. No DI framework — manual `VeyroContainer` (ADR-005).
4. Lower layers never depend on higher layers.
5. All versions live in `VeyroAndroid/gradle/libs.versions.toml`. No version literals in module
   build files.
6. Module build files stay minimal by applying a convention plugin from `build-logic/`.

## Protocol and security rules

- Any change to bytes on the wire bumps `VLP_VERSION` **and** updates `ProjectInfo/04-PROTOCOL.md`,
  `protocol/schema/`, and `protocol/testvectors/`.
- Never invent cryptography. Primitives are fixed: X25519, ChaCha20-Poly1305, SHA-256, HKDF-SHA256,
  HMAC-SHA256. No cipher-suite negotiation.
- Discovery data is untrusted input. It must never be treated as proof of identity.
- Nothing is written to the filesystem from the network without path validation
  (`ProjectInfo/04-PROTOCOL.md §7`) and an authorization decision.
- Do not log keys, seeds, `pairingSecret`, or file contents. Not even in debug builds.

## Code conventions

- Kotlin, sources under `src/main/kotlin/`, package mirrors the Gradle path
  (`:core:transport` → `com.veyro.core.transport`).
- Coroutines + `Flow` for everything asynchronous. No callbacks crossing a module boundary.
  No `GlobalScope`. Dispatchers are injected via `AppDispatchers`.
- Public API of a `core` module is an interface where a platform or fake implementation is
  plausible; the implementation class is internal where possible.
- Every `REQ-*` a change implements should be referenced in the code comment or commit message.
- No emoji in source or committed files.

## Build and verification

```powershell
cd D:\Coding\Projects\veyro\VeyroAndroid
.\gradlew.bat assembleDebug      # must stay green
.\gradlew.bat test               # JVM tests; protocol/crypto must be testable without a device
```

Do not claim something works without running it. "Compiles but untested" is an acceptable and
preferred status note.

## Current focus

**Android first.** `VeyroWindows/` is an intentional skeleton; do not implement it until the
Android side reaches the phase that needs it (see `ProjectInfo/06-ROADMAP.md`, phase P5).
