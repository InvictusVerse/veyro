# 05 — Security Model

**Principle:** being on the same Wi-Fi grants nothing. Discovery is untrusted input;
authentication decides everything.

---

## 1. Assets

| Asset | Why it matters |
|---|---|
| Static identity private key | Compromise = full impersonation of the device |
| `pairingSecret` per peer | Compromise = presence correlation, not impersonation |
| Files in transit | Confidentiality + integrity |
| Files at rest in the destination folder | An attacker who can write there can plant executables |
| Trust store (`TrustedPeer` records) | Tampering = silently adding an attacker as "trusted" |
| Display name / device inventory | Privacy; leaks user identity across public networks |

## 2. Adversaries

| # | Adversary | Capability | Must not be able to |
|---|---|---|---|
| A1 | Passive LAN sniffer | Reads all frames | Learn file names, contents, or the display name of a private-mode device |
| A2 | Active LAN attacker | Injects, drops, replays, spoofs ARP/DNS/mDNS | Impersonate a paired peer, MITM a session, replay a session |
| A3 | Malicious app on the same Wi-Fi | Full network stack, knows the protocol | Get a file accepted by the user's device, or push a file silently |
| A4 | Malicious app on the same Android device | Normal app permissions, no root | Read the identity key, read the trust store, trigger a send |
| A5 | Hostile hotspot / AP owner | Full network control, can also drop traffic | Downgrade crypto, MITM, or make an isolation failure look like success |
| A6 | Shoulder-surfer / QR photographer | Sees the pairing QR briefly | Complete pairing without the other user accepting |
| A7 | Device thief (unlocked) | Physical access | *Out of scope for v1 — see §8* |

## 3. Controls mapped to adversaries

| Control | Defeats |
|---|---|
| Mutual static-key authentication (Noise KK) | A2, A3, A5 |
| Out-of-band key transfer via QR + `Noise_XK` during pairing | A2, A5, A6 |
| 6-digit SAS from the transcript hash, confirmed on both screens | A2, A6 |
| Explicit accept on **both** devices to create trust | A3, A6 |
| Ephemeral keys per session (forward secrecy) | A1 retroactive decryption |
| AEAD on every byte, including control messages | A1, A2 |
| Prologue binding (`version` + `mode`) into the transcript | A5 downgrade |
| Single fixed cipher suite, no negotiation | A5 downgrade |
| Presence records carry no authority | A2, A3 |
| Rotating presence token, name hidden in private mode | A1 tracking |
| Per-transfer authorization; auto-accept only per paired device | A3 |
| Path sanitisation before any filesystem write | A2, A3 |
| Full-content SHA-256 verification + atomic rename | A2 corruption/injection |
| Resume re-verifies on-disk chunks before trusting them | A2 injection across sessions |
| Identity key sealed by Android Keystore / StrongBox | A4 |
| App-private storage for key + trust store | A4 |

## 4. Cryptographic design

### 4.1 Primitives (fixed, no negotiation)

| Purpose | Algorithm |
|---|---|
| Key agreement | X25519 |
| AEAD | ChaCha20-Poly1305 (IETF, 96-bit nonce) |
| Hash | SHA-256 |
| KDF | HKDF-SHA256 |
| MAC (presence tokens) | HMAC-SHA256 |
| Content verification | SHA-256 |

Rationale: ChaCha20-Poly1305 is constant-time in software and fast on ARM without AES
instructions, which matters on mid-range Android (NFR-08). All five are in the Noise spec's
standard set and present in BouncyCastle and .NET.

### 4.2 Handshake

- Paired sessions: `Noise_KK_25519_ChaChaPoly_SHA256`
- Pairing: `Noise_XK_25519_ChaChaPoly_SHA256`

See [`04-PROTOCOL.md §4`](04-PROTOCOL.md) for the exact prologue, SAS and key-derivation strings.

### 4.3 Why not TLS 1.3?

Considered seriously; see ADR-004. Summary: TLS is the safer default in general, but both ends here
already know each other's long-term public key, which is exactly the Noise KK case — no PKI, no
certificates, no hostname semantics, no X.509 parser. Using TLS would mean self-signed certificates
plus manual pinning, which reintroduces the certificate machinery without its benefits.

**The honest risk:** we would be implementing the Noise *state machine* ourselves (over vetted
primitives). That is less risky than inventing crypto, but more risky than using a platform TLS
stack. Mitigations, all mandatory before any release:

1. The state machine is implemented once in `:core:crypto`, kept small and total.
2. It is validated against the official Noise test vectors, checked into
   `protocol/testvectors/noise/`, run in CI on both platforms.
3. Negative tests: tampered ciphertext, truncated message, reordered message, replayed handshake,
   wrong static key, nonce rollover.
4. Independent review of that file before 1.0.
5. If any of the above cannot be met, fall back to TLS 1.3 with pinned self-signed certificates —
   this decision is explicitly reversible and is marked provisional in ADR-004.

### 4.4 Key storage

**Android**

```
identity.bin  = AES-256-GCM( key = AndroidKeyStore alias "veyro.identity.v1",
                             plaintext = 32-byte seed )
```

- Keystore key: AES-256-GCM, `setUserAuthenticationRequired(false)` (transfers must work with the
  screen locked), `setRandomizedEncryptionRequired(true)`, StrongBox when
  `FEATURE_STRONGBOX_KEYSTORE` is present, falling back to TEE.
- Ciphertext + IV stored in `filesDir` (app-private, not backed up).
- `android:allowBackup="false"` and no inclusion in `data_extraction_rules` — the identity must not
  be cloned to another device by a backup restore.
- Trust store is also stored in `filesDir`, authenticated with a separate Keystore-derived HMAC key
  so an attacker with filesystem access cannot silently add a trusted peer.

**Windows** (planned)

- DPAPI `CurrentUser` protected blob, or a CNG persisted key in the user key container.
- ACL restricted to the user; stored under `%LOCALAPPDATA%\Veyro\`.

### 4.5 What is *not* protected

Stated plainly:

- An attacker who gets code execution as the user on either device wins. We do not defend against a
  compromised host.
- A rooted/jailbroken Android device can extract the sealed seed if Keystore is bypassed.
- v1 does not require screen unlock to send to a trusted device — so an unlocked stolen phone can
  send files to the paired PC. §8 tracks this.

## 5. Authorization rules

```
inbound connection
  ├─ peer deviceId in trust store?
  │    ├─ no  → pairing window open?  ── no ─→ close, no detail leaked
  │    │                               └─ yes → Noise_XK → SAS → BOTH must accept
  │    └─ yes → Noise_KK → session
  └─ session established
       └─ TransferOffer arrives
            ├─ peer.autoAccept == true  → accept, notify
            └─ otherwise                → prompt user with: peer name, fingerprint,
                                          item count, total size, top-level names
```

- `autoAccept` is **per device**, default **off**, settable only from the paired-devices screen,
  and is cleared if the user revokes and re-pairs.
- There is no global "accept from anyone" switch. Ever.
- Offers larger than free space are refused with `NO_SPACE` before any write.

## 6. Privacy

| Leak | Mitigation |
|---|---|
| Display name broadcast to every network | `n` omitted in private mode (default outside pairing) |
| Stable identifier enables cross-network tracking | rotating `tok`, instance name derived from `deviceId` prefix only |
| File names visible on the wire | all control messages are inside the AEAD channel |
| Transfer history exposed to other apps | app-private storage only |
| Analytics | none — no telemetry, no crash reporting that includes peer data |

## 7. Security testing checklist (must pass before 1.0)

- [ ] Noise KK/XK official test vectors pass on Android and Windows
- [ ] Tampered ciphertext → session aborts, no partial data written
- [ ] Replayed full handshake → rejected
- [ ] Wrong static key → `HANDSHAKE_FAILED`, no oracle distinguishing "unknown peer" from "bad key"
- [ ] Spoofed mDNS record claiming a trusted `deviceId` → connection attempted, handshake fails, UI shows the peer as unreachable, not trusted
- [ ] Rogue peer sends `TransferOffer` without pairing → impossible (no session)
- [ ] `relPath` fuzz corpus (traversal, reserved names, unicode normalisation, long paths) → all rejected or confined
- [ ] Corrupt one byte of a `.veyropart` between sessions → resume detects and re-fetches that chunk
- [ ] Interrupt at 90% of a 20 GB file → resume completes with correct final hash
- [ ] Nonce rollover handling (synthetic test)
- [ ] Keystore key deleted out-of-band (e.g. biometric reset) → app recovers with a clear "identity lost, re-pair required" state instead of crashing
- [ ] `allowBackup=false` verified in the merged manifest
- [ ] No sensitive values in logcat in release builds

## 8. Known gaps / tracked for later

| # | Gap | Planned phase |
|---|---|---|
| G1 | No "require unlock to send" option | P3 |
| G2 | No per-transfer biometric confirmation for large/sensitive transfers | P3 |
| G3 | Trust store not sealed against an attacker with root | accepted |
| G4 | Noise implementation is first-party (see §4.3 mitigations) | must close before 1.0 |
| G5 | No key rotation story for the static identity | P4 |
| G6 | BLE fallback will need its own threat analysis | P4, before implementation |
