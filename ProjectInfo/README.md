# Veyro — Project Info

This folder is the **single source of truth** for the Veyro project. Every design decision,
requirement, status change and environment detail is recorded here. Keep it updated as work
progresses; code without a corresponding entry here is considered undocumented.

## How to use this folder

| File | Purpose | Update when |
|---|---|---|
| [`01-PROJECT-OVERVIEW.md`](01-PROJECT-OVERVIEW.md) | What Veyro is, product vision, naming, ecosystem map | Vision or scope changes |
| [`02-REQUIREMENTS.md`](02-REQUIREMENTS.md) | Functional + non-functional requirements, with IDs | A requirement is added/changed/dropped |
| [`03-ARCHITECTURE.md`](03-ARCHITECTURE.md) | Module map, layering rules, dependency graph | Modules added/moved, layering rules change |
| [`04-PROTOCOL.md`](04-PROTOCOL.md) | Veyro Link Protocol (VLP) wire specification | Any wire-format change (bump protocol version) |
| [`05-SECURITY.md`](05-SECURITY.md) | Threat model, crypto design, key handling | Any security-relevant change |
| [`06-ROADMAP.md`](06-ROADMAP.md) | Phases, milestones, what is in/out of each | Milestone planning |
| [`07-STATUS.md`](07-STATUS.md) | **Living status** — what exists, what is stubbed, next action | Every work session |
| [`08-DECISIONS.md`](08-DECISIONS.md) | ADR log — decision, alternatives, consequences | Every non-obvious technical choice |
| [`09-ENVIRONMENT.md`](09-ENVIRONMENT.md) | Local toolchain, SDK paths, pinned versions, build commands | Toolchain or version changes |
| [`10-CHANGELOG.md`](10-CHANGELOG.md) | Human-readable change history per session | End of every work session |

## Rules for this folder

1. **Status first.** Before starting work, read `07-STATUS.md`. After finishing, update it.
2. **Decisions are append-only.** Never delete an ADR in `08-DECISIONS.md`; supersede it.
3. **Protocol changes are versioned.** Any change to `04-PROTOCOL.md` that affects bytes on the
   wire must bump `VLP_VERSION` and be reflected in `protocol/schema/` and `protocol/testvectors/`.
4. **No requirement without an ID.** Requirements are referenced from code comments and commits
   as `REQ-xxx` / `NFR-xxx`.
5. **Honest status.** "Stubbed", "compiles but does nothing", and "not started" are valid and
   preferred over optimistic wording.

## Quick orientation

```
veyro/
├─ ProjectInfo/        ← you are here: all project knowledge
├─ protocol/           ← language-neutral protocol schemas + test vectors (shared by both apps)
├─ tools/              ← helper scripts
├─ VeyroAndroid/       ← Android app (Kotlin, Compose)  — ACTIVE
└─ VeyroWindows/       ← Windows app (.NET)              — SKELETON ONLY
```
