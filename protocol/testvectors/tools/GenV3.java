// Veyro link protocol v3 test vector generator (ADR-058).
//
// Independent of both clients: JDK primitives only (XDH/X25519, ChaCha20-Poly1305, HmacSHA256,
// SHA-256); HKDF is the two RFC 5869 loops written below. It first checks itself against
// RFC 5869 A.1-A.3, RFC 8439 2.8.2 and RFC 7748 6.1 and exits non-zero if any fails. Inputs are
// fixed constants, so reruns are byte-identical.
//
// Run from the repository root (keeps TMP, TEMP and hsperfdata off C:):
//   $t = "<worktree>\.agents\tmp"; New-Item -ItemType Directory -Force $t | Out-Null
//   $env:TMP = $t; $env:TEMP = $t
//   & "D:\CodingSoftwares\Android Studio\jbr\bin\java.exe" -XX:-UsePerfData "-Djava.io.tmpdir=$t" protocol\testvectors\tools\GenV3.java

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GenV3 {

    static final HexFormat HEX = HexFormat.of();
    static final Path ROOT = Path.of("protocol", "testvectors");

    // Static identities: RFC 7748 6.1 Alice (phone, A) and Bob (PC, B), as in pair-confirm.json.
    static final byte[] SK_A = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    static final byte[] SK_B = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
    static final String ID_A = "2C9VV72DFSF1A2SC0TN6QBAPEC";
    static final String ID_B = "9NHR2CYTZRWMJXWSNTWVRKQW6R";
    static final byte[] NONCE_A = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    static final byte[] NONCE_B = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");

    public static void main(String[] args) throws Exception {
        selfTest();
        Files.createDirectories(ROOT.resolve("crypto"));
        Files.createDirectories(ROOT.resolve("link"));
        Files.createDirectories(ROOT.resolve("frames"));
        write("crypto/hkdf-sha256.json", hkdfFile());
        write("crypto/chacha20poly1305.json", chachaFile());
        write("crypto/x25519.json", x25519File());
        Session s0 = session("session, phone dials PC (first connection)", SK_A, SK_B, "cnI0", "cnR0");
        Session s1 = session("session, same pairing secret, second connection", seed("eI1"), seed("eR1"), "cnI1", "cnR1");
        write("link/connection-schedule-v3.json", connectionFile(s0, s1));
        write("link/pairing-schedule-v3.json", pairingFile());
        write("link/session-frames-v3.json", sessionFramesFile(s0));
        write("link/exporter-v3.json", exporterFile(s0.expSecret));
        write("frames/vtc3-frames.json", vtc3File(s0.expSecret));
        System.out.println("GenV3: all self-checks passed; vectors written.");
    }

    // ── Self test against published vectors ────────────────────────────────────────────────

    static void selfTest() throws Exception {
        for (String[] v : HKDF_RFC) {
            byte[] prk = extract(hex(v[2]), hex(v[1]));
            byte[] okm = expand(prk, hex(v[3]), Integer.parseInt(v[4]));
            check(HEX.formatHex(prk).equals(v[5]), "RFC 5869 " + v[0] + " PRK");
            check(HEX.formatHex(okm).equals(v[6]), "RFC 5869 " + v[0] + " OKM");
        }
        byte[] sealed = chachaSeal(hex(CC_KEY), hex(CC_NONCE), hex(CC_AAD), CC_PLAIN.getBytes(StandardCharsets.US_ASCII));
        check(HEX.formatHex(sealed).equals(CC_CT + CC_TAG), "RFC 8439 2.8.2");
        check(HEX.formatHex(pub(SK_A)).equals(X_PUB_A), "RFC 7748 6.1 Alice public");
        check(HEX.formatHex(pub(SK_B)).equals(X_PUB_B), "RFC 7748 6.1 Bob public");
        check(HEX.formatHex(x25519(SK_A, pub(SK_B))).equals(X_SHARED), "RFC 7748 6.1 shared A");
        check(HEX.formatHex(x25519(SK_B, pub(SK_A))).equals(X_SHARED), "RFC 7748 6.1 shared B");
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("GenV3 self-check FAILED: " + what);
            System.exit(1);
        }
    }

    static final String[][] HKDF_RFC = {
        {"A.1", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b", "000102030405060708090a0b0c", "f0f1f2f3f4f5f6f7f8f9", "42",
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"},
        {"A.2",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f",
            "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            "82",
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87"},
        {"A.3", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b", "", "", "42",
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"},
    };

    static final String CC_KEY = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f";
    static final String CC_NONCE = "070000004041424344454647";
    static final String CC_AAD = "50515253c0c1c2c3c4c5c6c7";
    static final String CC_PLAIN = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    static final String CC_CT = "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116";
    static final String CC_TAG = "1ae10b594f09e26a7e902ecbd0600691";
    static final String X_PUB_A = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
    static final String X_PUB_B = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
    static final String X_SHARED = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";

    // ── Files ──────────────────────────────────────────────────────────────────────────────

    static String hkdfFile() {
        Json j = new Json();
        j.open().str("description", "HKDF-SHA256 (RFC 5869 appendix A.1-A.3), published values. Every v3 derivation uses HKDF-Extract and HKDF-Expand exactly as these exercise them.")
            .str("source", "RFC 5869 appendix A, test cases 1-3").arr("vectors");
        for (String[] v : HKDF_RFC) {
            j.item().str("name", "RFC 5869 " + v[0]).str("ikmHex", v[1]).str("saltHex", v[2]).str("infoHex", v[3])
                .num("length", Integer.parseInt(v[4])).str("prkHex", v[5]).str("okmHex", v[6]).close();
        }
        return j.endArr().close().toString();
    }

    static String chachaFile() {
        Json j = new Json();
        j.open().str("description", "ChaCha20-Poly1305 AEAD (RFC 8439 section 2.8.2), published values. ciphertextHex excludes the tag; the wire carries ciphertext || tag.")
            .str("source", "RFC 8439 section 2.8.2").arr("vectors");
        j.item().str("name", "RFC 8439 2.8.2").str("keyHex", CC_KEY).str("nonceHex", CC_NONCE).str("aadHex", CC_AAD)
            .str("plaintextHex", HEX.formatHex(CC_PLAIN.getBytes(StandardCharsets.US_ASCII)))
            .str("ciphertextHex", CC_CT).str("tagHex", CC_TAG).close();
        return j.endArr().close().toString();
    }

    static String x25519File() {
        Json j = new Json();
        j.open().str("description", "X25519 (RFC 7748 section 6.1), published values. Keys are little-endian byte strings as in the RFC.")
            .str("source", "RFC 7748 section 6.1").arr("vectors");
        j.item().str("name", "RFC 7748 6.1").str("alicePrivateHex", HEX.formatHex(SK_A)).str("alicePublicHex", X_PUB_A)
            .str("bobPrivateHex", HEX.formatHex(SK_B)).str("bobPublicHex", X_PUB_B).str("sharedHex", X_SHARED).close();
        return j.endArr().close().toString();
    }

    static final class Session {
        String name, preamble, ack;
        byte[] eI, eR, epkI, epkR, cnI, cnR, secret, transcript, dh, ikm, prk, kI2r, kR2i, expSecret;
    }

    static Session session(String name, byte[] eI, byte[] eR, String cnILabel, String cnRLabel) throws Exception {
        Session s = new Session();
        s.name = name;
        s.eI = eI;
        s.eR = eR;
        s.epkI = pub(eI);
        s.epkR = pub(eR);
        s.cnI = seed(cnILabel);
        s.cnR = seed(cnRLabel);
        s.secret = seed("pairingSecret");
        s.preamble = "{\"id\":\"" + ID_A + "\",\"name\":\"Test Phone\",\"platform\":\"android\",\"protocolVersion\":3,\"cn\":\""
            + b64(s.cnI) + "\",\"epk\":\"" + b64(s.epkI) + "\"}";
        s.ack = "{\"mode\":\"session\",\"cn\":\"" + b64(s.cnR) + "\",\"epk\":\"" + b64(s.epkR) + "\"}";
        s.transcript = transcript(utf8(s.preamble), utf8(s.ack));
        s.dh = x25519(eI, s.epkR);
        check(Arrays.equals(s.dh, x25519(eR, s.epkI)), "ephemeral DH symmetric");
        s.ikm = cat(s.dh, s.secret);
        s.prk = extract(s.transcript, s.ikm);
        s.kI2r = expand(s.prk, info("veyro-link-v3", "i2r", ID_A, ID_B), 32);
        s.kR2i = expand(s.prk, info("veyro-link-v3", "r2i", ID_A, ID_B), 32);
        s.expSecret = expand(s.prk, info("veyro-link-v3", "exp", ID_A, ID_B), 32);
        return s;
    }

    static String connectionFile(Session... sessions) {
        Json j = new Json();
        j.open().str("description", "Link protocol v3 session-mode key schedule (ProjectInfo/04-PROTOCOL.md section 4, ADR-058). I = initiator (dialler), R = responder. preamble and ack are the exact UTF-8 text frames; the transcript hashes those bytes.")
            .obj("algorithm")
            .str("transcript", "SHA-256(u32be(len P) || P || u32be(len A) || A)")
            .str("dh", "X25519(ephemeralPrivate_self, epk_peer)")
            .str("ikm", "dh || pairingSecret")
            .str("prk", "HKDF-Extract(salt = transcript, ikm)")
            .str("info", "utf8(label) || 0x00 || utf8(role) || 0x00 || utf8(idI) || 0x00 || utf8(idR); label veyro-link-v3; roles i2r, r2i, exp")
            .str("keys", "kI2r = HKDF-Expand(prk, info(i2r), 32); kR2i = HKDF-Expand(prk, info(r2i), 32); expSecret = HKDF-Expand(prk, info(exp), 32)")
            .close()
            .str("note", "The two vectors share the pairing secret and differ only in the fresh ephemeral keys and cn values, so every derived key differs: a frame recorded on one connection cannot open on another.")
            .arr("vectors");
        for (Session s : sessions) {
            j.item().str("name", s.name)
                .obj("input").str("idI", ID_A).str("idR", ID_B)
                .str("ephemeralPrivateIHex", HEX.formatHex(s.eI)).str("ephemeralPrivateRHex", HEX.formatHex(s.eR))
                .str("cnIHex", HEX.formatHex(s.cnI)).str("cnRHex", HEX.formatHex(s.cnR))
                .str("pairingSecretHex", HEX.formatHex(s.secret))
                .str("preamble", s.preamble).str("ack", s.ack).close()
                .obj("expected")
                .str("ephemeralPublicIHex", HEX.formatHex(s.epkI)).str("ephemeralPublicRHex", HEX.formatHex(s.epkR))
                .str("preambleHex", HEX.formatHex(utf8(s.preamble))).str("ackHex", HEX.formatHex(utf8(s.ack)))
                .str("transcriptHex", HEX.formatHex(s.transcript)).str("dhHex", HEX.formatHex(s.dh))
                .str("ikmHex", HEX.formatHex(s.ikm)).str("prkHex", HEX.formatHex(s.prk))
                .str("kI2rHex", HEX.formatHex(s.kI2r)).str("kR2iHex", HEX.formatHex(s.kR2i))
                .str("expSecretHex", HEX.formatHex(s.expSecret)).close()
                .close();
        }
        return j.endArr().close().toString();
    }

    static String pairingFile() throws Exception {
        byte[] pkA = pub(SK_A), pkB = pub(SK_B);
        byte[] staticDh = x25519(SK_A, pkB);
        byte[] staticInfo = cat(utf8("veyro-static-v3"), new byte[]{0}, utf8(ID_A), new byte[]{0}, utf8(ID_B));
        byte[] newSecret = seed("newPairingSecret");
        Json j = new Json();
        j.open().str("description", "Link protocol v3 pairing key schedules (ProjectInfo/04-PROTOCOL.md sections 4.2-4.5, ADR-058): confirm-code pairing and QR pairing, then the switch to session keys after pairOk. A = phone = initiator, B = PC = responder; static keys are the RFC 7748 6.1 pair (pair-confirm.json).")
            .obj("algorithm")
            .str("staticKey", "HKDF-SHA256(ikm = X25519(staticPrivate_self, pk_peer), salt = transcript, info = utf8(\"veyro-static-v3\") || 0x00 || utf8(idI) || 0x00 || utf8(idR), 32)")
            .str("confirm", "salt = SHA-256(transcript || nA || nB); ikm = dh || staticKey; label veyro-pair-confirm-v3")
            .str("qr", "salt = transcript; ikm = dh || staticKey || qrMix; label veyro-pair-qr-v3")
            .str("keys", "prk = HKDF-Extract(salt, ikm); kI2r, kR2i, chainKey = HKDF-Expand(prk, info(label, i2r | r2i | chain), 32)")
            .str("afterPairOk", "prkSession = HKDF-Extract(salt = transcript, ikm = chainKey || newPairingSecret); session kI2r, kR2i, expSecret with label veyro-link-v3")
            .str("qrSecret", "qrPrk = HKDF-Extract(utf8(\"veyro-qr-v3\"), qrSecret); proofKey = HKDF-Expand(qrPrk, \"veyro-qr-proof-v3\", 32); qrMix = HKDF-Expand(qrPrk, \"veyro-qr-mix-v3\", 32)")
            .str("qrProof", "HMAC-SHA256(proofKey, utf8(\"veyro-qr-proof-v3\") || 0x00 || utf8(idPhone) || 0x00 || utf8(idPc) || 0x00 || transcript)")
            .close();

        // Confirm pairing.
        {
            byte[] eI = seed("confirm/eI"), eR = seed("confirm/eR");
            byte[] cnI = seed("confirm/cnI"), cnR = seed("confirm/cnR");
            byte[] commit = sha256(utf8("veyro-commit-v1"), pkB, pkA, NONCE_B);
            String preamble = "{\"id\":\"" + ID_A + "\",\"name\":\"Test Phone\",\"platform\":\"android\",\"protocolVersion\":3,\"pair\":\"confirm\",\"pk\":\""
                + b64(pkA) + "\",\"cn\":\"" + b64(cnI) + "\",\"epk\":\"" + b64(pub(eI)) + "\"}";
            String ack = "{\"mode\":\"pairing-confirm\",\"pk\":\"" + b64(pkB) + "\",\"commit\":\"" + b64(commit)
                + "\",\"cn\":\"" + b64(cnR) + "\",\"epk\":\"" + b64(pub(eR)) + "\"}";
            byte[] tr = transcript(utf8(preamble), utf8(ack));
            byte[] dh = x25519(eI, pub(eR));
            byte[] staticKey = expand(extract(tr, staticDh), staticInfo, 32);
            byte[] salt = sha256(tr, NONCE_A, NONCE_B);
            byte[] prk = extract(salt, cat(dh, staticKey));
            String label = "veyro-pair-confirm-v3";
            byte[] chain = expand(prk, info(label, "chain", ID_A, ID_B), 32);
            j.obj("confirm")
                .obj("input").str("idI", ID_A).str("idR", ID_B)
                .str("staticPrivateIHex", HEX.formatHex(SK_A)).str("staticPrivateRHex", HEX.formatHex(SK_B))
                .str("ephemeralPrivateIHex", HEX.formatHex(eI)).str("ephemeralPrivateRHex", HEX.formatHex(eR))
                .str("cnIHex", HEX.formatHex(cnI)).str("cnRHex", HEX.formatHex(cnR))
                .str("nAHex", HEX.formatHex(NONCE_A)).str("nBHex", HEX.formatHex(NONCE_B))
                .str("newPairingSecretHex", HEX.formatHex(newSecret))
                .str("preamble", preamble).str("ack", ack).close()
                .obj("expected")
                .str("staticPublicIHex", HEX.formatHex(pkA)).str("staticPublicRHex", HEX.formatHex(pkB))
                .str("ephemeralPublicIHex", HEX.formatHex(pub(eI))).str("ephemeralPublicRHex", HEX.formatHex(pub(eR)))
                .str("commitHex", HEX.formatHex(commit))
                .str("transcriptHex", HEX.formatHex(tr)).str("dhHex", HEX.formatHex(dh))
                .str("staticDhHex", HEX.formatHex(staticDh)).str("staticInfoHex", HEX.formatHex(staticInfo))
                .str("staticKeyHex", HEX.formatHex(staticKey)).str("saltHex", HEX.formatHex(salt))
                .str("prkHex", HEX.formatHex(prk))
                .str("kI2rHex", HEX.formatHex(expand(prk, info(label, "i2r", ID_A, ID_B), 32)))
                .str("kR2iHex", HEX.formatHex(expand(prk, info(label, "r2i", ID_A, ID_B), 32)))
                .str("chainKeyHex", HEX.formatHex(chain));
            afterPairing(j, tr, chain, newSecret);
            j.close().close();
        }

        // QR pairing.
        {
            byte[] eI = seed("qr/eI"), eR = seed("qr/eR");
            byte[] cnI = seed("qr/cnI"), cnR = seed("qr/cnR");
            byte[] qrSecret = Arrays.copyOf(seed("qr/secret"), 16);
            String s = Base64.getUrlEncoder().withoutPadding().encodeToString(qrSecret);
            String qrString = "veyro:pair?v=2&id=" + ID_B + "&n=Test+PC&a=192.168.1.20:47654&s=" + s + "&e=1700000000";
            String preamble = "{\"id\":\"" + ID_A + "\",\"name\":\"Test Phone\",\"platform\":\"android\",\"protocolVersion\":3,\"pair\":\"qr\",\"pk\":\""
                + b64(pkA) + "\",\"cn\":\"" + b64(cnI) + "\",\"epk\":\"" + b64(pub(eI)) + "\"}";
            String ack = "{\"mode\":\"pairing\",\"pk\":\"" + b64(pkB) + "\",\"cn\":\"" + b64(cnR) + "\",\"epk\":\"" + b64(pub(eR)) + "\"}";
            byte[] tr = transcript(utf8(preamble), utf8(ack));
            byte[] dh = x25519(eI, pub(eR));
            byte[] staticKey = expand(extract(tr, staticDh), staticInfo, 32);
            byte[] qrPrk = extract(utf8("veyro-qr-v3"), qrSecret);
            byte[] proofKey = expand(qrPrk, utf8("veyro-qr-proof-v3"), 32);
            byte[] qrMix = expand(qrPrk, utf8("veyro-qr-mix-v3"), 32);
            byte[] proof = hmac(proofKey, cat(utf8("veyro-qr-proof-v3"), new byte[]{0}, utf8(ID_A), new byte[]{0}, utf8(ID_B), new byte[]{0}, tr));
            byte[] prk = extract(tr, cat(dh, staticKey, qrMix));
            String label = "veyro-pair-qr-v3";
            byte[] chain = expand(prk, info(label, "chain", ID_A, ID_B), 32);
            j.obj("qr")
                .obj("input").str("idI", ID_A).str("idR", ID_B)
                .str("staticPrivateIHex", HEX.formatHex(SK_A)).str("staticPrivateRHex", HEX.formatHex(SK_B))
                .str("ephemeralPrivateIHex", HEX.formatHex(eI)).str("ephemeralPrivateRHex", HEX.formatHex(eR))
                .str("cnIHex", HEX.formatHex(cnI)).str("cnRHex", HEX.formatHex(cnR))
                .str("qrSecretHex", HEX.formatHex(qrSecret)).str("qrString", qrString).num("qrNowEpochSeconds", 1700000000)
                .str("newPairingSecretHex", HEX.formatHex(newSecret))
                .str("preamble", preamble).str("ack", ack).close()
                .obj("expected")
                .str("qrSecretBase64Url", s)
                .str("ephemeralPublicIHex", HEX.formatHex(pub(eI))).str("ephemeralPublicRHex", HEX.formatHex(pub(eR)))
                .str("transcriptHex", HEX.formatHex(tr)).str("dhHex", HEX.formatHex(dh))
                .str("staticKeyHex", HEX.formatHex(staticKey))
                .str("qrPrkHex", HEX.formatHex(qrPrk)).str("proofKeyHex", HEX.formatHex(proofKey)).str("qrMixHex", HEX.formatHex(qrMix))
                .str("qrProofHex", HEX.formatHex(proof)).str("qrProofBase64", b64(proof))
                .str("prkHex", HEX.formatHex(prk))
                .str("kI2rHex", HEX.formatHex(expand(prk, info(label, "i2r", ID_A, ID_B), 32)))
                .str("kR2iHex", HEX.formatHex(expand(prk, info(label, "r2i", ID_A, ID_B), 32)))
                .str("chainKeyHex", HEX.formatHex(chain));
            afterPairing(j, tr, chain, newSecret);
            j.close().close();
        }
        return j.close().toString();
    }

    static void afterPairing(Json j, byte[] tr, byte[] chain, byte[] newSecret) {
        byte[] prk = extract(tr, cat(chain, newSecret));
        j.str("sessionPrkHex", HEX.formatHex(prk))
            .str("sessionKI2rHex", HEX.formatHex(expand(prk, info("veyro-link-v3", "i2r", ID_A, ID_B), 32)))
            .str("sessionKR2iHex", HEX.formatHex(expand(prk, info("veyro-link-v3", "r2i", ID_A, ID_B), 32)))
            .str("sessionExpSecretHex", HEX.formatHex(expand(prk, info("veyro-link-v3", "exp", ID_A, ID_B), 32)));
    }

    static final class Frame {
        String name, dir, from, envelope, wire;
        long counter;
        byte[] aad, nonce, sealed;
    }

    static Frame frame(String name, String dir, String from, long counter, byte[] key, String envelope) throws Exception {
        Frame f = new Frame();
        f.name = name;
        f.dir = dir;
        f.from = from;
        f.counter = counter;
        f.envelope = envelope;
        f.aad = cat(utf8(from), new byte[]{0, (byte) (dir.equals("i2r") ? 1 : 2)}, u64(counter));
        f.nonce = cat(u32(0), u64(counter));
        f.sealed = chachaSeal(key, f.nonce, f.aad, utf8(envelope));
        f.wire = "{\"f\":\"" + from + "\",\"c\":\"" + b64(f.sealed) + "\"}";
        return f;
    }

    static String sessionFramesFile(Session s) throws Exception {
        String hello = "{\"v\":3,\"msgId\":\"m-1\",\"from\":\"" + ID_A + "\",\"seq\":1,\"ts\":1700000000000,\"payload\":{\"t\":\"hello\",\"deviceId\":\""
            + ID_A + "\",\"deviceName\":\"Test Phone\",\"platform\":\"android\",\"capabilities\":0,\"maxChunk\":4194304}}";
        String helloAck = "{\"v\":3,\"msgId\":\"m-2\",\"from\":\"" + ID_B + "\",\"seq\":1,\"ts\":1700000000001,\"payload\":{\"t\":\"helloAck\",\"deviceId\":\""
            + ID_B + "\",\"deviceName\":\"Test PC\",\"platform\":\"windows\",\"capabilities\":0,\"chunkSize\":4194304}}";
        String ping = "{\"v\":3,\"msgId\":\"m-3\",\"from\":\"" + ID_A + "\",\"seq\":2,\"ts\":1700000000002,\"payload\":{\"t\":\"ping\",\"nonce\":\"n-1\"}}";
        Frame f0 = frame("hello, initiator to responder, counter 0", "i2r", ID_A, 0, s.kI2r, hello);
        Frame f1 = frame("helloAck, responder to initiator, counter 0", "r2i", ID_B, 0, s.kR2i, helloAck);
        Frame f2 = frame("ping, initiator to responder, counter 1", "i2r", ID_A, 1, s.kI2r, ping);
        byte[] flipped = f0.sealed.clone();
        flipped[flipped.length - 1] ^= 1;
        String flippedWire = "{\"f\":\"" + ID_A + "\",\"c\":\"" + b64(flipped) + "\"}";
        String wrongFrom = "{\"f\":\"" + ID_B + "\",\"c\":\"" + b64(f0.sealed) + "\"}";
        String b64NoPad = b64(f0.sealed).replace("=", "");
        String badBase64 = "{\"f\":\"" + ID_A + "\",\"c\":\"" + b64NoPad + "\"}";

        Json j = new Json();
        j.open().str("description", "Link protocol v3 session frames (ProjectInfo/04-PROTOCOL.md section 5, ADR-058). Keys are the first vector of connection-schedule-v3.json. Each frame is sealed with ChaCha20-Poly1305 under the sender's direction key; the receiver computes nonce and AAD from its own counter and the expected peer id, never from the wire.")
            .obj("algorithm")
            .str("wire", "{\"f\": fromDeviceId, \"c\": base64(ciphertext || tag)}, standard padded base64")
            .str("nonce", "u32be(0) || u64be(counter)")
            .str("aad", "utf8(fromDeviceId) || 0x00 || dirByte || u64be(counter); dirByte 0x01 for i2r, 0x02 for r2i")
            .str("envelope", "plaintext is the envelope JSON; v must be 3, from must be the peer id, seq must be counter + 1")
            .close()
            .obj("input").str("idI", ID_A).str("idR", ID_B).str("kI2rHex", HEX.formatHex(s.kI2r)).str("kR2iHex", HEX.formatHex(s.kR2i)).close()
            .arr("frames");
        for (Frame f : new Frame[]{f0, f1, f2}) {
            j.item().str("name", f.name).str("direction", f.dir).str("from", f.from).num("counter", f.counter)
                .str("envelope", f.envelope).str("aadHex", HEX.formatHex(f.aad)).str("nonceHex", HEX.formatHex(f.nonce))
                .str("sealedHex", HEX.formatHex(f.sealed)).str("wire", f.wire).close();
        }
        j.endArr().str("negativesNote", "Each negative is opened in order by a fresh receiver for direction 'direction' that expects peer 'peerId'. Every wire before index failsAt opens; the one at failsAt must be rejected and the connection closed.")
            .arr("negatives");
        negative(j, "replay within the connection", "i2r", ID_A, 1, f0.wire, f0.wire);
        negative(j, "skipped counter (frame at counter 1 first)", "i2r", ID_A, 0, f2.wire);
        negative(j, "reordered pair", "i2r", ID_A, 0, f2.wire, f0.wire);
        negative(j, "reflected frame (own i2r frame read as r2i)", "r2i", ID_A, 0, f0.wire);
        negative(j, "flipped tag bit", "i2r", ID_A, 0, flippedWire);
        negative(j, "wrong f (not the expected peer)", "i2r", ID_A, 0, wrongFrom);
        negative(j, "c without base64 padding", "i2r", ID_A, 0, badBase64);
        return j.endArr().close().toString();
    }

    static void negative(Json j, String name, String dir, String peer, int failsAt, String... wires) {
        j.item().str("name", name).str("direction", dir).str("peerId", peer).num("failsAt", failsAt).arr("wires");
        for (String w : wires) j.value(w);
        j.endArr().close();
    }

    static String exporterFile(byte[] expSecret) {
        byte[] saltA = seed("vtc3/saltA"), saltB = seed("vtc3/saltB");
        saltA = Arrays.copyOf(saltA, 16);
        saltB = Arrays.copyOf(saltB, 16);
        byte[] vtcContext = cat(utf8("tx1"), new byte[]{0}, saltA, saltB);
        Object[][] cases = {
            {"veyro-vtc3", vtcContext, 64},
            {"veyro-screen-v3", hex("00000001"), 32},
            {"x", new byte[0], 1},
            {"veyro-camera-v3", new byte[0], 100},
        };
        Json j = new Json();
        j.open().str("description", "Link protocol v3 key exporter (ProjectInfo/04-PROTOCOL.md section 5.4, ADR-058). expSecret is the first vector of connection-schedule-v3.json.")
            .str("algorithm", "HKDF-Expand(expSecret, u16be(15) || utf8(\"veyro-export-v3\") || u16be(len label) || utf8(label) || u16be(len context) || context, length)")
            .str("expSecretHex", HEX.formatHex(expSecret)).arr("vectors");
        for (Object[] c : cases) {
            String label = (String) c[0];
            byte[] ctx = (byte[]) c[1];
            int len = (Integer) c[2];
            byte[] info = cat(u16(15), utf8("veyro-export-v3"), u16(utf8(label).length), utf8(label), u16(ctx.length), ctx);
            j.item().str("label", label).str("contextHex", HEX.formatHex(ctx)).num("length", len)
                .str("infoHex", HEX.formatHex(info)).str("outputHex", HEX.formatHex(expand(expSecret, info, len))).close();
        }
        j.endArr().str("invalidNote", "Each must be refused with IllegalArgumentException / ArgumentException before any output.").arr("invalid");
        invalid(j, "empty label", "", 0, 32);
        invalid(j, "label with a space", "veyro vtc3", 0, 32);
        invalid(j, "label of 65 characters", "a".repeat(65), 0, 32);
        invalid(j, "context of 1025 bytes", "veyro-vtc3", 1025, 32);
        invalid(j, "length 0", "veyro-vtc3", 0, 0);
        invalid(j, "length 8161", "veyro-vtc3", 0, 8161);
        return j.endArr().close().toString();
    }

    static void invalid(Json j, String name, String label, int ctxLen, int len) {
        j.item().str("name", name).str("label", label).num("contextLength", ctxLen).num("length", len).close();
    }

    // ── VTC3 ───────────────────────────────────────────────────────────────────────────────

    static byte[] header(int type, String tid, long seq, int len) {
        byte[] t = utf8(tid);
        return cat(utf8("VTC3"), new byte[]{(byte) type}, u16(t.length), t, u64(seq), u32(len));
    }

    static byte[] record(int type, String tid, long seq, int streamId, byte[] key, byte[] plain) throws Exception {
        byte[] h = header(type, tid, seq, plain.length + 16);
        byte[] sealed = chachaSeal(key, cat(u32(streamId), u64(seq)), h, plain);
        return cat(h, sealed);
    }

    static String vtc3File(byte[] expSecret) throws Exception {
        String tid = "tx1";
        byte[] saltA = Arrays.copyOf(seed("vtc3/saltA"), 16), saltB = Arrays.copyOf(seed("vtc3/saltB"), 16);
        byte[] context = cat(utf8(tid), new byte[]{0}, saltA, saltB);
        byte[] okm = expand(expSecret, cat(u16(15), utf8("veyro-export-v3"), u16(10), utf8("veyro-vtc3"), u16(context.length), context), 64);
        byte[] s2r = Arrays.copyOfRange(okm, 0, 32), r2s = Arrays.copyOfRange(okm, 32, 64);
        byte[] openA = cat(header(9, tid, 0, 16), saltA);
        byte[] openB = cat(header(9, tid, 0, 16), saltB);
        byte[] data = utf8("abc");
        String abcHash = HEX.formatHex(sha256(data));

        List<Object[]> s2rFrames = new ArrayList<>();
        s2rFrames.add(new Object[]{"Auth", 1, cat(u16(3), utf8("tok"), u16(ID_A.length()), utf8(ID_A), new byte[]{2}),
            "{\"type\":\"Auth\",\"authToken\":\"tok\",\"senderDeviceId\":\"" + ID_A + "\",\"route\":\"LAN_TCP\"}"});
        s2rFrames.add(new Object[]{"ItemBegin", 3, cat(u32(0), u64(3), u32(1), u32(524288)),
            "{\"type\":\"ItemBegin\",\"itemIndex\":0,\"sizeBytes\":3,\"chunkCount\":1,\"chunkSize\":524288}"});
        s2rFrames.add(new Object[]{"Chunk", 4, cat(u32(0), u32(0), u32(3), data),
            "{\"type\":\"Chunk\",\"itemIndex\":0,\"chunkIndex\":0,\"dataHex\":\"616263\"}"});
        s2rFrames.add(new Object[]{"ItemEnd", 5, cat(u32(0), u16(64), utf8(abcHash)),
            "{\"type\":\"ItemEnd\",\"itemIndex\":0,\"sha256Hex\":\"" + abcHash + "\"}"});
        s2rFrames.add(new Object[]{"TransferEnd", 7, new byte[]{1}, "{\"type\":\"TransferEnd\",\"completed\":true}"});
        s2rFrames.add(new Object[]{"Cancel", 8, cat(u16(4), utf8("user")), "{\"type\":\"Cancel\",\"reason\":\"user\"}"});
        List<Object[]> r2sFrames = new ArrayList<>();
        r2sFrames.add(new Object[]{"AuthAck", 2, cat(new byte[]{1}, u16(0)), "{\"type\":\"AuthAck\",\"ok\":true}"});
        r2sFrames.add(new Object[]{"ItemResult", 6, cat(u32(0), new byte[]{1}, u16(0)), "{\"type\":\"ItemResult\",\"itemIndex\":0,\"ok\":true}"});
        r2sFrames.add(new Object[]{"Cancel", 8, cat(u16(4), utf8("user")), "{\"type\":\"Cancel\",\"reason\":\"user\"}"});

        Json j = new Json();
        j.open().str("description", "VTC3 sealed data-plane records (ProjectInfo/04-PROTOCOL.md section 7.3, ADR-058). Keys come from the key exporter (exporter-v3.json, same expSecret) with both stream-open salts in the context. Plaintexts are the VTC1 payload encodings, unchanged.")
            .obj("algorithm")
            .str("streamOpen", "\"VTC3\" | 0x09 | u16be tidLen | tid | u64be 0 | u32be 16 | salt(16), cleartext, first record in each direction")
            .str("keys", "okm = exportKey(\"veyro-vtc3\", utf8(tid) || 0x00 || saltA || saltB, 64); keyS2R = okm[0..32] (stream id 1), keyR2S = okm[32..64] (stream id 2)")
            .str("record", "\"VTC3\" | type | u16be tidLen | tid | u64be seq | u32be ctLen | ciphertext || tag; AAD = the header bytes; nonce = u32be(streamId) || u64be(seq); seq is the framer counter from 0")
            .close()
            .obj("input").str("transferId", tid).str("expSecretHex", HEX.formatHex(expSecret))
            .str("saltAHex", HEX.formatHex(saltA)).str("saltBHex", HEX.formatHex(saltB)).close()
            .obj("expected").str("contextHex", HEX.formatHex(context)).str("okmHex", HEX.formatHex(okm))
            .str("keyS2RHex", HEX.formatHex(s2r)).str("keyR2SHex", HEX.formatHex(r2s))
            .str("streamOpenSenderHex", HEX.formatHex(openA)).str("streamOpenReceiverHex", HEX.formatHex(openB)).close()
            .arr("records");
        List<byte[]> s2rRecords = new ArrayList<>();
        List<byte[]> r2sRecords = new ArrayList<>();
        long seq = 0;
        for (Object[] f : s2rFrames) {
            byte[] rec = record((Integer) f[1], tid, seq, 1, s2r, (byte[]) f[2]);
            s2rRecords.add(rec);
            recordEntry(j, (String) f[0], "s2r", 1, seq, (String) f[3], (byte[]) f[2], rec);
            seq++;
        }
        seq = 0;
        for (Object[] f : r2sFrames) {
            byte[] rec = record((Integer) f[1], tid, seq, 2, r2s, (byte[]) f[2]);
            r2sRecords.add(rec);
            recordEntry(j, (String) f[0], "r2s", 2, seq, (String) f[3], (byte[]) f[2], rec);
            seq++;
        }
        j.endArr();

        byte[] flipped = s2rRecords.get(0).clone();
        flipped[flipped.length - 1] ^= 1;
        byte[] tamperedHeader = s2rRecords.get(0).clone();
        tamperedHeader[4] = 3; // Auth claims to be ItemBegin: the AAD no longer matches
        byte[] tamperedPayload = s2rRecords.get(2).clone();
        tamperedPayload[header(4, tid, 2, 0).length] ^= 1;
        byte[] wrongTid = record(1, "tx2", 0, 1, s2r, (byte[]) s2rFrames.get(0)[2]);
        byte[] okmOther = expand(expSecret, cat(u16(15), utf8("veyro-export-v3"), u16(10), utf8("veyro-vtc3"),
            u16(context.length), cat(utf8(tid), new byte[]{0}, saltB, saltA)), 64);
        byte[] otherAttempt = record(1, tid, 0, 1, Arrays.copyOfRange(okmOther, 0, 32), (byte[]) s2rFrames.get(0)[2]);
        byte[] truncated = Arrays.copyOf(s2rRecords.get(0), s2rRecords.get(0).length - 1);
        byte[] negativeSeq = s2rRecords.get(0).clone();
        int seqOff = 4 + 1 + 2 + 3;
        negativeSeq[seqOff] = (byte) 0x80;

        j.str("negativesNote", "Each negative feeds the records, concatenated, to a fresh reader for 'direction' (s2r: key keyS2R, stream id 1; r2s: keyR2S, stream id 2) expecting transfer id tx1. Records before failsAt decode; the read at failsAt must fail, and nothing after it is read.")
            .arr("negatives");
        vnegative(j, "replayed record", "s2r", 1, s2rRecords.get(0), s2rRecords.get(0));
        vnegative(j, "reordered records", "s2r", 0, s2rRecords.get(1), s2rRecords.get(0));
        vnegative(j, "reflected record (receiver's AuthAck read by the receiver)", "s2r", 0, r2sRecords.get(0));
        vnegative(j, "flipped tag bit", "s2r", 0, flipped);
        vnegative(j, "tampered header type", "s2r", 0, tamperedHeader);
        vnegative(j, "tampered payload byte", "s2r", 2, s2rRecords.get(0), s2rRecords.get(1), tamperedPayload);
        vnegative(j, "wrong transfer id", "s2r", 0, wrongTid);
        vnegative(j, "record from another attempt (other salts)", "s2r", 0, otherAttempt);
        vnegative(j, "truncated record (EOF inside it)", "s2r", 0, truncated);
        vnegative(j, "negative seq", "s2r", 0, negativeSeq);
        vnegative(j, "stream-open where a sealed record is due", "s2r", 0, openA);
        return j.endArr().close().toString();
    }

    static void recordEntry(Json j, String name, String dir, int streamId, long seq, String frame, byte[] plain, byte[] rec) {
        int headerLen = rec.length - plain.length - 16;
        j.item().str("name", name).str("direction", dir).num("streamId", streamId).num("seq", seq)
            .raw("frame", frame).str("plaintextHex", HEX.formatHex(plain))
            .str("headerHex", HEX.formatHex(Arrays.copyOf(rec, headerLen)))
            .str("nonceHex", HEX.formatHex(cat(u32(streamId), u64(seq))))
            .str("recordHex", HEX.formatHex(rec)).close();
    }

    static void vnegative(Json j, String name, String dir, int failsAt, byte[]... records) {
        j.item().str("name", name).str("direction", dir).num("failsAt", failsAt).arr("recordsHex");
        for (byte[] r : records) j.value(HEX.formatHex(r));
        j.endArr().close();
    }

    // ── Primitives ─────────────────────────────────────────────────────────────────────────

    static byte[] extract(byte[] salt, byte[] ikm) {
        return hmac(salt.length == 0 ? new byte[32] : salt, ikm);
    }

    static byte[] expand(byte[] prk, byte[] info, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; out.size() < length; i++) {
            t = hmac(prk, cat(t, info, new byte[]{(byte) i}));
            out.writeBytes(t);
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] aad, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(plain);
    }

    static byte[] x25519(byte[] priv, byte[] peerPublic) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("XDH");
        PrivateKey sk = kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, priv));
        byte[] u = peerPublic.clone();
        u[31] &= 0x7f;
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) be[i] = u[31 - i];
        PublicKey pk = kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, be)));
        KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(sk);
        ka.doPhase(pk, true);
        return ka.generateSecret();
    }

    static byte[] pub(byte[] priv) throws Exception {
        byte[] base = new byte[32];
        base[0] = 9;
        return x25519(priv, base);
    }

    static byte[] transcript(byte[] p, byte[] a) {
        return sha256(u32(p.length), p, u32(a.length), a);
    }

    static byte[] info(String label, String role, String idI, String idR) {
        return cat(utf8(label), new byte[]{0}, utf8(role), new byte[]{0}, utf8(idI), new byte[]{0}, utf8(idR));
    }

    /** Fixed, documented test inputs: SHA-256 of a label. */
    static byte[] seed(String label) {
        return sha256(utf8("veyro-v3-vector/" + label));
    }

    static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    static byte[] u32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static byte[] u64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    static void write(String relative, String content) throws Exception {
        Files.writeString(ROOT.resolve(relative), content + "\n", StandardCharsets.UTF_8);
    }

    // ── Minimal pretty JSON writer (two-space indent, LF) ──────────────────────────────────

    static final class Json {
        private final StringBuilder sb = new StringBuilder();
        private final List<Boolean> first = new ArrayList<>();
        private int depth = 0;

        private void sep() {
            if (!first.isEmpty()) {
                int last = first.size() - 1;
                if (!first.get(last)) sb.append(',');
                first.set(last, false);
                sb.append('\n');
                indent();
            }
        }

        private void indent() {
            for (int i = 0; i < depth; i++) sb.append("  ");
        }

        private void key(String k) {
            sep();
            sb.append(quote(k)).append(": ");
        }

        Json open() {
            sep();
            sb.append('{');
            depth++;
            first.add(true);
            return this;
        }

        Json obj(String k) {
            key(k);
            sb.append('{');
            depth++;
            first.add(true);
            return this;
        }

        Json item() {
            return open();
        }

        Json close() {
            depth--;
            first.remove(first.size() - 1);
            sb.append('\n');
            indent();
            sb.append('}');
            return this;
        }

        Json arr(String k) {
            key(k);
            sb.append('[');
            depth++;
            first.add(true);
            return this;
        }

        Json endArr() {
            depth--;
            first.remove(first.size() - 1);
            sb.append('\n');
            indent();
            sb.append(']');
            return this;
        }

        Json str(String k, String v) {
            key(k);
            sb.append(quote(v));
            return this;
        }

        Json num(String k, long v) {
            key(k);
            sb.append(v);
            return this;
        }

        Json raw(String k, String json) {
            key(k);
            sb.append(json);
            return this;
        }

        Json value(String v) {
            sep();
            sb.append(quote(v));
            return this;
        }

        static String quote(String s) {
            StringBuilder q = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> q.append("\\\"");
                    case '\\' -> q.append("\\\\");
                    case '\n' -> q.append("\\n");
                    default -> {
                        if (c < 0x20) q.append(String.format("\\u%04x", (int) c));
                        else q.append(c);
                    }
                }
            }
            return q.append('"').toString();
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
