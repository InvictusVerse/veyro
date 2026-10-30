// Veyro VMS1 media socket test vector generator (ADR-066, 04-PROTOCOL.md 7.5).
//
// Independent of both clients: JDK ChaCha20-Poly1305 only, everything else written out below.
// It first checks the primitive against RFC 8439 2.8.2 and exits non-zero if that fails. Inputs
// are fixed constants, so reruns are byte-identical.
//
// Run from the repository root (keeps TMP, TEMP and hsperfdata off C:):
//   $t = "D:\CodingSoftwares\Temp\genvms1"; New-Item -ItemType Directory -Force $t | Out-Null
//   $env:TMP = $t; $env:TEMP = $t
//   & "D:\CodingSoftwares\Android Studio\jbr\bin\java.exe" -XX:-UsePerfData "-Djava.io.tmpdir=$t" protocol\testvectors\tools\GenVms1.java
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GenVms1 {
    static final HexFormat HEX = HexFormat.of();
    static final Path ROOT = Path.of("protocol", "testvectors", "media");

    static byte[] seq(int from, int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (from + i); return b; }

    static byte[] seal(byte[] key, int streamId, long counter, byte[] aad, byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        nonce[0] = (byte) (streamId >>> 24); nonce[1] = (byte) (streamId >>> 16); nonce[2] = (byte) (streamId >>> 8); nonce[3] = (byte) streamId;
        for (int i = 0; i < 8; i++) nonce[4 + i] = (byte) (counter >>> (56 - 8 * i));
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(plain);
    }

    static void selfTest() throws Exception {
        // RFC 8439 2.8.2.
        byte[] key = HEX.parseHex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = HEX.parseHex("070000004041424344454647");
        byte[] aad = HEX.parseHex("50515253c0c1c2c3c4c5c6c7");
        byte[] plain = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.").getBytes(StandardCharsets.US_ASCII);
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        byte[] out = c.doFinal(plain);
        String tag = HEX.formatHex(out, out.length - 16, out.length);
        if (!tag.equals("1ae10b594f09e26a7e902ecbd0600691")) { System.err.println("RFC 8439 2.8.2 tag mismatch"); System.exit(1); }
    }

    static void u16(ByteArrayOutputStream o, int v) { o.write(v >>> 8); o.write(v); }
    static void u32(ByteArrayOutputStream o, long v) { o.write((int) (v >>> 24)); o.write((int) (v >>> 16)); o.write((int) (v >>> 8)); o.write((int) v); }
    static void u64(ByteArrayOutputStream o, long v) { for (int i = 0; i < 8; i++) o.write((int) (v >>> (56 - 8 * i))); }

    static byte[] preface(int source, byte[] sid, byte[] salt) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("VMS1".getBytes(StandardCharsets.US_ASCII)); o.write(1); o.write(source); u16(o, 0); o.writeBytes(sid); o.writeBytes(salt);
        return o.toByteArray();
    }

    record Rec(String name, String dir, int kind, int flags, byte[] plain) {}

    static String q(String s) { return "\"" + s + "\""; }

    public static void main(String[] args) throws Exception {
        selfTest();
        Files.createDirectories(ROOT);

        byte[] sid = seq(0x10, 16), saltPhone = seq(0x20, 16), saltPc = seq(0x30, 16), okm = seq(0x40, 64);
        byte[] kUp = java.util.Arrays.copyOfRange(okm, 0, 32), kDown = java.util.Arrays.copyOfRange(okm, 32, 64);
        String phoneId = "0123456789ABCDEFGHJKMNPQRS", pcId = "PCPCPCPCPCPCPCPCPCPCPCPCPC";

        ByteArrayOutputStream ctx = new ByteArrayOutputStream();
        ctx.writeBytes("VMS1".getBytes(StandardCharsets.US_ASCII)); ctx.write(1); ctx.writeBytes(sid); ctx.writeBytes(saltPhone); ctx.writeBytes(saltPc);

        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        byte[] p = phoneId.getBytes(StandardCharsets.UTF_8), c = pcId.getBytes(StandardCharsets.UTF_8);
        u16(auth, p.length); auth.writeBytes(p); u16(auth, c.length); auth.writeBytes(c); auth.write(1); auth.writeBytes(sid);

        ByteArrayOutputStream vcfg = new ByteArrayOutputStream();
        vcfg.write(1); vcfg.write(1); u16(vcfg, 1072); u16(vcfg, 2448); u16(vcfg, 0); u16(vcfg, 0); u16(vcfg, 1072); u16(vcfg, 2448);
        u16(vcfg, 0); vcfg.write(0); vcfg.write(0); u32(vcfg, 1);

        ByteArrayOutputStream acfg = new ByteArrayOutputStream();
        acfg.write(1); acfg.write(2); u32(acfg, 48000);

        byte[] au = seq(0xA0, 20);
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        u64(video, 1_000_000L); u32(video, au.length); video.writeBytes(au);

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        u64(audio, 1_005_000L); audio.writeBytes(seq(0x01, 8));

        ByteArrayOutputStream close = new ByteArrayOutputStream();
        close.write(0); u16(close, 4); close.writeBytes("user".getBytes(StandardCharsets.US_ASCII));

        List<Rec> recs = List.of(
            new Rec("auth", "up", 0x01, 0, auth.toByteArray()),
            new Rec("authAck", "down", 0x02, 0, new byte[] {0}),
            new Rec("videoConfig", "up", 0x10, 0, vcfg.toByteArray()),
            new Rec("audioConfig", "up", 0x20, 0, acfg.toByteArray()),
            new Rec("videoKeyframe", "up", 0x11, 0x01 | 0x02 | 0x04, video.toByteArray()),
            new Rec("audio", "up", 0x21, 0, audio.toByteArray()),
            new Rec("close", "up", 0x03, 0, close.toByteArray())
        );

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"description\": \"VMS1 media socket (ADR-066, 04-PROTOCOL.md 7.5). Generated by tools/GenVms1.java from fixed inputs; do not edit by hand. "
            + "okm stands in for exportKey(label, context, 64): kUp = okm[0..32] (stream 1, phone seals), kDown = okm[32..64] (stream 2, PC seals). "
            + "Records of one direction use consecutive counters from 0 in the order listed. record = header (6 bytes, also the AAD) || ciphertext || tag.\",\n");
        sb.append("  \"source\": 1,\n");
        sb.append("  \"sid\": ").append(q(HEX.formatHex(sid))).append(",\n");
        sb.append("  \"saltPhone\": ").append(q(HEX.formatHex(saltPhone))).append(",\n");
        sb.append("  \"saltPc\": ").append(q(HEX.formatHex(saltPc))).append(",\n");
        sb.append("  \"phonePreface\": ").append(q(HEX.formatHex(preface(1, sid, saltPhone)))).append(",\n");
        sb.append("  \"pcPreface\": ").append(q(HEX.formatHex(preface(1, sid, saltPc)))).append(",\n");
        sb.append("  \"exportLabel\": \"veyro-screen-v3\",\n");
        sb.append("  \"exportContext\": ").append(q(HEX.formatHex(ctx.toByteArray()))).append(",\n");
        sb.append("  \"okm\": ").append(q(HEX.formatHex(okm))).append(",\n");
        sb.append("  \"phoneId\": ").append(q(phoneId)).append(",\n");
        sb.append("  \"pcId\": ").append(q(pcId)).append(",\n");
        sb.append("  \"records\": [\n");
        long upCounter = 0, downCounter = 0;
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            boolean up = r.dir().equals("up");
            long counter = up ? upCounter++ : downCounter++;
            byte[] header = new byte[] {(byte) r.kind(), (byte) r.flags(), 0, 0, 0, 0};
            int len = r.plain().length + 16;
            header[2] = (byte) (len >>> 24); header[3] = (byte) (len >>> 16); header[4] = (byte) (len >>> 8); header[5] = (byte) len;
            byte[] sealed = seal(up ? kUp : kDown, up ? 1 : 2, counter, header, r.plain());
            sb.append("    {\"name\": ").append(q(r.name())).append(", \"dir\": ").append(q(r.dir()))
                .append(", \"counter\": ").append(counter).append(", \"kind\": ").append(r.kind()).append(", \"flags\": ").append(r.flags())
                .append(", \"plaintext\": ").append(q(HEX.formatHex(r.plain())))
                .append(", \"record\": ").append(q(HEX.formatHex(header) + HEX.formatHex(sealed))).append("}")
                .append(i < recs.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"badPrefaces\": [\n");
        byte[] good = preface(1, sid, saltPhone);
        List<String[]> bad = new ArrayList<>();
        byte[] b1 = good.clone(); b1[3] = 0x32; bad.add(new String[] {"magic VMS2", HEX.formatHex(b1)});
        byte[] b2 = good.clone(); b2[4] = 2; bad.add(new String[] {"version 2", HEX.formatHex(b2)});
        byte[] b3 = good.clone(); b3[5] = 4; bad.add(new String[] {"source 4", HEX.formatHex(b3)});
        byte[] b4 = good.clone(); b4[7] = 1; bad.add(new String[] {"reserved 1", HEX.formatHex(b4)});
        for (int i = 0; i < bad.size(); i++) {
            sb.append("    {\"why\": ").append(q(bad.get(i)[0])).append(", \"bytes\": ").append(q(bad.get(i)[1])).append("}").append(i < bad.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"badHeaders\": [\n");
        sb.append("    {\"why\": \"unknown kind\", \"kind\": 18, \"flags\": 0, \"length\": 32, \"fromPhone\": true},\n");
        sb.append("    {\"why\": \"AUTH_ACK from the phone\", \"kind\": 2, \"flags\": 0, \"length\": 17, \"fromPhone\": true},\n");
        sb.append("    {\"why\": \"VIDEO from the PC\", \"kind\": 17, \"flags\": 2, \"length\": 40, \"fromPhone\": false},\n");
        sb.append("    {\"why\": \"flags on AUDIO\", \"kind\": 33, \"flags\": 1, \"length\": 40, \"fromPhone\": true},\n");
        sb.append("    {\"why\": \"unknown VIDEO flag\", \"kind\": 17, \"flags\": 16, \"length\": 40, \"fromPhone\": true},\n");
        sb.append("    {\"why\": \"empty plaintext\", \"kind\": 33, \"flags\": 0, \"length\": 16, \"fromPhone\": true},\n");
        sb.append("    {\"why\": \"too long\", \"kind\": 17, \"flags\": 0, \"length\": 65565, \"fromPhone\": true}\n");
        sb.append("  ],\n");
        sb.append("  \"goodHeaders\": [\n");
        sb.append("    {\"kind\": 17, \"flags\": 15, \"length\": 65564, \"fromPhone\": true},\n");
        sb.append("    {\"kind\": 3, \"flags\": 0, \"length\": 19, \"fromPhone\": false},\n");
        sb.append("    {\"kind\": 2, \"flags\": 0, \"length\": 17, \"fromPhone\": false}\n");
        sb.append("  ]\n}\n");
        Files.writeString(ROOT.resolve("vms1.json"), sb.toString());

        // Fragmentation: an independent loop over the rule of 7.5.5.
        StringBuilder f = new StringBuilder();
        f.append("{\n  \"description\": \"How an access unit is split into VIDEO records (04-PROTOCOL.md 7.5.5). Generated by tools/GenVms1.java. Each fragment is [offset, length, flags]; KEY 1, START 2, END 4, DISCONTINUITY 8.\",\n");
        f.append("  \"maxFragment\": 65536,\n  \"cases\": [\n");
        int[][] cases = {{1, 1, 0}, {65536, 1, 0}, {65537, 0, 0}, {150000, 1, 1}, {200, 0, 1}};
        for (int i = 0; i < cases.length; i++) {
            int size = cases[i][0]; boolean key = cases[i][1] == 1, disc = cases[i][2] == 1;
            f.append("    {\"size\": ").append(size).append(", \"key\": ").append(key).append(", \"discontinuity\": ").append(disc).append(", \"fragments\": [");
            int off = 0; boolean firstFrag = true;
            while (off < size) {
                int n = Math.min(65536, size - off);
                int flags = 0;
                if (firstFrag) { flags |= 2; if (key) flags |= 1; if (disc) flags |= 8; }
                if (off + n == size) flags |= 4;
                f.append(firstFrag ? "" : ", ").append("[").append(off).append(", ").append(n).append(", ").append(flags).append("]");
                off += n; firstFrag = false;
            }
            f.append("]}").append(i < cases.length - 1 ? ",\n" : "\n");
        }
        f.append("  ]\n}\n");
        Files.writeString(ROOT.resolve("fragmentation.json"), f.toString());
        System.out.println("wrote " + ROOT.resolve("vms1.json") + " and " + ROOT.resolve("fragmentation.json"));
    }
}
