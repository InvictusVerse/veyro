# 04 — Veyro Link Protocol (VLP)

**Status: normative.** This document describes what Veyro actually implements. Where this document
and the code disagree, that is a bug in one of them and must be resolved, not worked around.

`VLP_VERSION = 1`. Any change to bytes on the wire bumps it and requires updating this document,
`protocol/testvectors/`, and both implementations.

> **History.** Versions of this document before 2026-10-15 described a TCP + **Noise KK/XK**
> channel carrying **canonical CBOR** control frames. That design was replaced during P4 by the
> link layer described here (ADR-020, ADR-021, ADR-022) after the Noise-based handshake failed to
> pair on physical devices. The old text survived the change and misled the Windows port into
> implementing a protocol nothing speaks. If a stale Noise or CBOR reference remains anywhere,
> it is wrong — including the vestigial `NOISE_PATTERN_*`, `PROLOGUE_PREFIX`, `MAX_NOISE_*`, and
> `FRAME_CONTROL/BULK/KEEPALIVE` constants still declared in `Vlp.kt`.

---

## 0. Layer overview

| Layer | Transport | Protection |
|---|---|---|
| L0 Presence | mDNS `_veyro._tcp` + UDP beacon on `239.255.42.99:47653` | none — untrusted hints |
| L1 Connection | WebSocket, plus an unauthenticated preamble and mode branch | none until the mode is chosen |
| L2 Control | JSON frames inside the WebSocket | `FrameCrypto` — AES-GCM in session mode, HMAC-only in pairing mode |
| L3 Bulk data | dedicated raw TCP socket, `VTC1` binary framing | ephemeral per-transfer `authToken` + SHA-256 per item |

The control plane and data plane are deliberately separate (ADR-022). Bulk bytes never enter the
WebSocket queue, which is what stopped the client-side queue exhaustion and the 33% Base64
amplification seen on >90 MB transfers.

A Base64-in-JSON `chunk` message still exists on the control plane as a fallback for peers that
advertise no raw-TCP route. It is correct but slow, and is not the normal path.

---

## 1. Constants

Normative values live in `core/protocol/Vlp.kt` and are mirrored in
`Veyro.Core/Protocol/VlpConstants.cs`.

| Name | Value |
|---|---|
| `VERSION` | `1` |
| `MDNS_SERVICE_TYPE` | `_veyro._tcp` (domain `local.`) |
| `UDP_DISCOVERY_PORT` | `47653` |
| `UDP_MULTICAST_GROUP` | `239.255.42.99` |
| `UDP_MAGIC` | `"VEYR"` = `0x56 0x45 0x59 0x52` |
| `UDP_BEACON_INTERVAL_MILLIS` | `1000` (zero packets when the window is closed, NFR-02) |
| `BEACON_TYPE_QUERY` / `ANNOUNCE` | `0x01` / `0x02` |
| `INFO_SAS` | `"veyro-sas-v1"` |
| `INFO_PAIRING_SECRET` | `"veyro-pair-v1"` |
| `INFO_PRESENCE_TOKEN` | `"veyro-presence-v1"` |
| `HANDSHAKE_TIMEOUT_MILLIS` | `10 000` |
| `IDLE_SESSION_TIMEOUT_MILLIS` | `60 000` |
| `KEEPALIVE_INTERVAL_MILLIS` | `20 000` |
| `PAIRING_WINDOW_MILLIS` | `120 000` |
| `DEFAULT_CHUNK_SIZE` | `4 MiB` (min `256 KiB`, max `16 MiB`) |
| `MAX_DISPLAY_NAME_BYTES` | `32` |
| `MAX_RELATIVE_PATH_BYTES` | `1024` |
| `PART_SUFFIX` / `META_SUFFIX` | `.veyropart` / `.veyrometa` |

Application-level bounds enforced by the connection layer: control frame ≤ **24 MiB** (ADR-021),
preamble ≤ **64 KiB**, `VTC1` payload ≤ **16 MiB**, `VTC1` transfer id ≤ **256** bytes.

---

## 2. Identity

A device identity is an X25519 static key pair, generated once and persisted sealed
(Android Keystore; Windows DPAPI `CurrentUser`).

```
deviceId   = Crockford32( SHA-256( "veyro-id-v1" ‖ staticPublic )[0..15] )     // 26 chars
pkHash8    = SHA-256( "veyro-pk-v1" ‖ staticPublic )[0..7]                     // 8 bytes
```

`deviceId` is a 128-bit truncation rendered in Crockford Base32, so it is case-insensitive and
tolerates the `O`/`0` and `I`/`L`/`1` confusions when read aloud or typed.

A device identity is never regenerated implicitly. Resetting it is a deliberate user action that
invalidates every existing pairing.

---

## 3. L0 — Presence (untrusted)

Presence answers exactly one question: **which IP:port might be a Veyro peer?** It carries no
authority. An attacker may broadcast a record claiming any `deviceId`; the connection then fails
because they do not hold the private key (REQ-D07).

### 3.1 Record

One `key=value` encoding serves both transports. mDNS TXT records are natively `key=value`, so
reusing that form for the UDP payload means **one** codec to implement, test, and mirror — and it
is readable in a packet capture, which matters when diagnosing a hotspot that silently drops
traffic.

| Key | Meaning | Required |
|---|---|---|
| `v` | protocol version, must equal `1` | yes |
| `id` | `deviceId` | yes |
| `pf` | `android` \| `windows` \| `unknown` | yes |
| `cap` | capability bits, **lower-case hex** | yes |
| `pk8` | `pkHash8`, 8 bytes hex — lets a browser pre-filter cheaply | yes |
| `tok` | rotating presence token, 8 bytes hex (§3.4) | yes |
| `p` | TCP port, `1..65535`, ephemeral and advertised | yes |
| `n` | display name, ≤ 32 UTF-8 bytes | **omitted in private mode** |
| `pk` | full X25519 static public key, base64url | **omitted in private mode** |

Beacon payload form: pairs joined with `\n`, UTF-8, ≤ **400 bytes**.

`n` and `pk` are **omitted entirely** in private mode, never sent empty — an empty value still
tells an observer that a name exists and is being withheld (REQ-D06).

Publishing `pk` is safe: a public key is public, and knowing it does not permit impersonation.
What publishing does *not* do is authenticate it, which is precisely why the 6-digit confirmation
in §5 exists. An attacker who substitutes their own key produces a different transcript, so the
two screens show different digits and the user sees the mismatch.

### 3.2 UDP beacon frame

```
offset size  field
0      4     magic   = "VEYR"
4      1     version = 1
5      1     type    = 0x01 QUERY | 0x02 ANNOUNCE
6      2     payloadLength (u16 BE)
8      n     payload = the key=value bytes above
```

A bare QUERY with `payloadLength = 0` is legal and is the smallest valid frame (8 bytes). An
ANNOUNCE with no payload is invalid. A QUERY is answered with a **unicast** ANNOUNCE, never a
broadcast.

Both multicast and directed broadcast are sent: some access points drop multicast while forwarding
broadcast, and some do the reverse. This redundancy is why hotspot scenarios work at all and why
mDNS alone is insufficient.

### 3.3 Decoder obligations

The decoder parses packets anything on the network can send, so it is **total** — no exceptions,
no partial results:

- the declared length is never trusted against the actual received length;
- the received length is used, never the buffer size, or uninitialised tail is read as payload;
- duplicate keys are **rejected**, because picking either silently lets an attacker get a
  different reading on the two platforms;
- a version other than `1` is refused, not guessed (REQ-S08);
- unknown keys are ignored (§11);
- a display name containing control characters or Unicode **bidi overrides** is rejected: those
  reorder rendered text, so a hostile device could advertise a name that *displays* as a trusted
  one (REQ-U04).

### 3.4 Presence token

`tok` is 8 bytes derived with `INFO_PRESENCE_TOKEN` and rotated, so a passive observer cannot use
it as a stable device fingerprint across sessions.

---

## 4. L1 — Connection and mode branch

The connection is a **WebSocket**. Before any key exists, the initiator sends an unauthenticated
preamble, and the receiver answers with the mode this connection will run in.

```
initiator → LinkPreamble      { id, name, platform, protocolVersion }
receiver  → LinkPreambleAck   { mode, reason? }
```

`mode` ∈ `session` | `pairing` | `busy` | `refused`.

The preamble is a **hint and nothing more**. It says "I claim to be this device" so the receiver
can decide which key to use. It grants nothing; the mode decision that follows is what does.

Without this branch a device cannot tell *"we are already paired, start a session"* from *"we have
never met, run pairing"* before choosing a key — which is exactly why the earlier Noise design
could not pair.

| Receiver state | Mode |
|---|---|
| peer in trust store | `session` |
| not in trust store, pairing window open (≤ 120 s, user-initiated) | `pairing` |
| not in trust store, no window | `refused` |
| already serving another session | `busy` |

---

## 5. L2 — Frame cryptography

Every frame after the preamble is a `LinkWireFrame`:

| JSON key | Field |
|---|---|
| `f` | sender `deviceId`, in the clear |
| `n` | nonce, 12 bytes |
| `c` | ciphertext — **session mode** |
| `p` | plaintext — **pairing mode** |
| `m` | HMAC-SHA256 tag |

Exactly one of `c` or `p` is present.

```
mac = HMAC-SHA256( key, fromDeviceId_utf8 ‖ nonce ‖ body )
```

`f` travels in the clear because the receiver needs it to select a key before it can verify
anything. It is therefore untrusted — but it is **inside the MAC**, so a frame cannot be
re-attributed to a different sender without breaking the tag. Binding the sender id costs nothing
and closes a real gap: with a shared group key, an unbound frame could be lifted and replayed as
if it came from a different peer.

### 5.1 Two modes

| | Key | Body |
|---|---|---|
| **Pairing** | PBKDF2-HMAC-SHA256 over the 6-digit PIN, **120 000 iterations**, 256-bit | plaintext + outer HMAC |
| **Session** | persisted 32-byte shared secret | AES-256-GCM, 96-bit nonce, 128-bit tag, + outer HMAC |

PBKDF2 salt is the two device ids, lower-cased and **lexicographically ordered**:

```
salt = min(idA, idB) ‖ ":" ‖ max(idA, idB)        // US-ASCII
```

Ordering makes both sides derive the identical key regardless of who initiated.

The 120 000 iterations are the only thing standing between a 6-digit PIN and an offline attacker
who captured the exchange — roughly 0.1 s on a mid-range phone, unnoticeable once, expensive a
million times over. Combined with the bounded window and the attempt limit, that is what makes a
6-digit secret acceptable.

In session mode the outer HMAC is **defence in depth**: GCM already authenticates the ciphertext,
but the outer MAC additionally binds the frame to the claimed sender.

### 5.2 Verification order

MAC first, with a **constant-time** comparison, then decrypt. A short-circuiting compare leaks how
many leading tag bytes an attacker guessed, which is enough to forge a tag one byte at a time.

A frame that fails to open is dropped and yields no error detail to the peer.

### 5.3 Replay

Nonces are held in a bounded LRU of **4096** entries and a repeat is rejected regardless of
timestamp. The bound matters: an unbounded set is a memory-exhaustion target for anything that can
send frames.

`seq` inside the envelope is monotonic per sender and enforced on receipt, which makes a reordered
or truncated stream detectable rather than silently accepted.

---

## 6. L2 — Control messages

Inside a frame body:

```json
{ "v": 1, "msgId": "...", "from": "...", "seq": 0, "ts": 0, "payload": { "t": "...", ... } }
```

JSON, with `"t"` as the type discriminator. Codec settings are normative:
`ignoreUnknownKeys = true`, `encodeDefaults = true`, `explicitNulls = false`.

| `t` | Direction | Purpose |
|---|---|---|
| `pairHello` | initiator | `deviceId`, name, platform, appVersion, `publicKeyB64?` |
| `pairOk` | target | carries `sessionSecretB64` — **always sealed**, never plaintext |
| `pairFail` | target | reason |
| `hello` | either | first message of a session; `capabilities`, `maxChunk` |
| `helloAck` | either | negotiated `chunkSize` |
| `offer` | sender | `transferId`, `items[]`, `totalBytes`, `senderName`, `preferredRoutes[]` |
| `decision` | receiver | `accept`, `resume?`, `route?`, `tcpHost?`, `tcpPort?`, `fallbackHost?`, `authToken?`, `p2pSsid?`, `p2pPassphrase?` |
| `progressNotice` | receiver | genuine receiver-side progress |
| `itemBegin` / `itemEnd` / `itemResult` | sender / receiver | per-item lifecycle |
| `transferEnd` | sender | `completed`, `failedIndices[]` |
| `chunk` | sender | Base64 fallback only; not the normal path |
| `cancel` | either | `transferId`, reason |
| `ping` / `pong` | either | keepalive, 20 s |

`pairOk` is sealed with the PIN-derived bootstrap key even though the connection is nominally in
plaintext pairing mode. The session secret must not cross the LAN in the clear, and the bootstrap
key is available to seal it. There is no plaintext fallback and therefore no downgrade path.

`items[]` entries are `{ index, relPath, size, mtime, isDir }`.

---

## 7. L3 — Bulk transfer over `VTC1`

### 7.1 Route negotiation

The sender advertises `preferredRoutes` in `offer`. The receiver prepares the route it prefers,
binds a TCP listener, and returns the result in `decision` with both `tcpHost` and `fallbackHost`.

Selection is **fastest-available**, not Wi-Fi-Direct-first (ADR-023). Wi-Fi Direct commonly
negotiates 2.4 GHz and can be several times slower than a 5 GHz or wired LAN, and forming a group
drops the device's existing Wi-Fi association on many Android devices. Wi-Fi Direct's real
advantage is availability — it works when there is no shared network at all. Ties go to
`LAN_TCP`. The route not chosen remains as fallback; a transfer must never fail because the
preferred route failed.

### 7.2 Authentication of the data socket

The receiver mints a 32-byte random `authToken` per transfer and sends it inside the **encrypted**
`decision`. On connect, the sender must present a matching `Auth` frame within 10 s before any
file data is accepted. Mismatched connections are closed immediately.

### 7.3 Frame format

```
offset size  field
0      4     magic "VTC1" = 0x56 0x54 0x43 0x31
4      1     type (u8)
5      2     transferId length (u16 BE), 1..256
7      N     transferId (UTF-8)
7+N    8     seq (u64 BE), monotonic
15+N   4     payload length (u32 BE), ≤ 16 MiB
19+N   M     payload
```

| Type | Name | Payload |
|---|---|---|
| `0x01` | `Auth` | `u16` tokenLen ‖ token ‖ `u16` devIdLen ‖ senderDeviceId ‖ `u8` route |
| `0x02` | `AuthAck` | `u8` ok ‖ `u16` reasonLen ‖ reason |
| `0x03` | `ItemBegin` | `i32` itemIndex ‖ `i64` sizeBytes ‖ `i32` chunkCount ‖ `i32` chunkSize |
| `0x04` | `Chunk` | `i32` itemIndex ‖ `i32` chunkIndex ‖ `i32` dataLen ‖ data |
| `0x05` | `ItemEnd` | `i32` itemIndex ‖ `u16` hashLen ‖ sha256 hex |
| `0x06` | `ItemResult` | `i32` itemIndex ‖ `u8` ok ‖ `u16` errLen ‖ error |
| `0x07` | `TransferEnd` | `u8` completed |
| `0x08` | `Cancel` | `u16` reasonLen ‖ reason |

Route byte: `0x01` Wi-Fi Direct, `0x02` LAN TCP.

All integers big-endian. String lengths are **u16**, except `Chunk.dataLen` which is `i32`.

`Chunk` frames **do not auto-flush**; every other type does. The caller owns the flush cadence for
bulk data, which is what keeps throughput up without starving control responsiveness.

Reading 0 bytes at a frame boundary is a clean EOF and yields no frame. A stream that closes
mid-frame is an error.

### 7.4 Integrity and resume

Each item carries a SHA-256 computed by the sender and re-computed by the receiver. A mismatch
fails that item only, with `HASH_MISMATCH`.

Partial data lands in `<name>.veyropart` beside a `.veyrometa` sidecar; `decision.resume` maps
item index to the chunk indices still needed. Absent means send everything.

---

## 8. Path safety (REQ-T11)

A received `relPath` is rejected unless **all** hold:

- non-empty, ≤ **1024** UTF-8 bytes, well-formed UTF-16 (no lone surrogate), no NUL
- ≤ **64** segments
- no segment equal to `.` or `..`
- not absolute: no leading `/`, no `//` UNC form, no `X:` drive prefix
- no `\` anywhere — the separator is `/` only, and two spellings would mean validating both
- no control characters (`< 0x20`, `0x7F`)
- no segment containing `< > : " \ | ? *`; a `:` is reported specifically as an NTFS
  alternate-data-stream marker
- no segment ending in `.` or space — Windows strips these, so `evil. ` and `evil` become the same
  file, which is a way to overwrite a target whose name was supposed to differ
- no segment whose stem matches `CON PRN AUX NUL COM1-9 LPT1-9`, case-insensitive, with or
  without extension
- resolved target still inside the destination directory after canonicalisation

The validator **returns a typed result and never throws**: one bad path must fail one item, not
the whole transfer → `itemResult{ ok: false, error: "PATH_REJECTED" }`.

Unicode normalisation is deliberately **not** applied. Normalising rewrites the path and breaks
the correspondence between the hash and the filename.

Check order is normative, because it determines which reason a path violating several rules is
reported under: NUL → surrogates → backslash → UNC → absolute → drive letter → per-segment.

Outgoing names use a **separate** sanitiser. Never sanitise received input — silently rewriting a
hostile path hides an attack instead of failing it.

---

## 9. Error codes

| Code | Meaning | Fatal |
|---|---|---|
| `VERSION_UNSUPPORTED` | `VLP_VERSION` mismatch | yes |
| `NOT_PAIRED` | peer not in trust store and no pairing window open | yes |
| `HANDSHAKE_FAILED` | MAC verification failed, or wrong key | yes |
| `AUTH_TIMEOUT` | preamble/mode branch or `VTC1` `Auth` not completed in 10 s | yes |
| `FRAME_INVALID` | bad length, bad type, bad magic, malformed JSON | yes |
| `OFFER_REJECTED` | user declined | no |
| `PATH_REJECTED` | §8 validation failed | no |
| `HASH_MISMATCH` | item verification failed | no |
| `NO_SPACE` | insufficient free space for offer | no |
| `CANCELLED` | user cancelled | no |
| `INTERNAL` | unexpected local failure | no |

Fatal closes the session. Non-fatal fails one item and lets the rest continue — which matters when
one file in a 2000-file folder has a bad name.

---

## 10. Network diagnosis states (REQ-N01..N06)

| State | Detection | Shown remediation |
|---|---|---|
| `OK` | TCP connect + session established | — |
| `AP_ISOLATED` | presence seen, TCP connect to all addresses fails within 1.5 s, local link up | use the other device's hotspot, or a router; isolation is an AP setting |
| `MULTICAST_BLOCKED` | mDNS silent but UDP directed-broadcast works, or the reverse | fall back automatically; inform only if both fail |
| `VPN_INTERFERENCE` | active VPN holds the default route and the LAN subnet is unreachable | suggest split tunnelling or temporarily disabling the VPN |
| `NO_NETWORK` | no connected Wi-Fi/Ethernet transport | guide to Wi-Fi or hotspot |
| `PEER_FIREWALL` | TCP RST / immediate refusal from a reachable host | Windows side: allow Veyro on the current firewall profile |
| `SUBNET_MISMATCH` | peer address in no local subnet and no route | both devices must be on the same network |

---

## 11. Version policy

- `VLP_VERSION` is a single integer. Any change to bytes on the wire bumps it.
- v1 implementations refuse other versions rather than guessing (REQ-S08).
- **Adding** a nullable field with a null default is not a breaking change: `ignoreUnknownKeys`
  handles the forward direction and the null default handles the backward one. Removing a field or
  changing its meaning requires a bump.
- Capability bits are append-only. Never reuse or renumber one — an old peer will read it with the
  old meaning.

---

## 12. Deliberately out of scope for v1

Internet/relay transport · NAT traversal · multi-peer broadcast send · compression ·
multi-stream bulk · BLE transport · group/room concepts.
