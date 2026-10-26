package com.veyro.core.protocol

import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.protocol.PairingQrCode.Error
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Table tests for the section 4.2 QR format (ADR-030). Every rejection asserts its reason. */
class PairingQrCodeTest {

    private val id = DeviceId.fromPublicKey(ByteArray(32) { 5 }).value
    private val now = 1_800_000_000L
    private val base = "veyro:pair?v=1&id=$id&n=Office%20PC&a=192.168.1.20:5150&c=123456&e=$now"

    private fun valid(raw: String, at: Long = now): PairingQrCode {
        val result = PairingQrCode.parse(raw, at)
        if (result !is PairingQrCode.Result.Valid) fail("expected valid but got $result for <$raw>")
        return (result as PairingQrCode.Result.Valid).qr
    }

    private fun error(raw: String?, at: Long = now): Error {
        val result = PairingQrCode.parse(raw, at)
        if (result !is PairingQrCode.Result.Invalid) fail("expected rejection for <$raw>")
        return (result as PairingQrCode.Result.Invalid).error
    }

    @Test
    fun `canonical code parses into typed fields`() {
        val qr = valid(base)
        assertEquals(id, qr.deviceId.value)
        assertEquals("Office PC", qr.name)
        assertEquals(listOf(Endpoint("192.168.1.20", 5150)), qr.endpoints)
        assertEquals("123456", qr.code)
        assertEquals(now, qr.expiresAtEpochSeconds)
    }

    @Test
    fun `valid table`() {
        val cases: List<Pair<String, (PairingQrCode) -> Unit>> = listOf(
            // Key order does not matter; unknown keys are ignored for forward compatibility.
            "veyro:pair?c=000000&e=$now&a=10.0.0.2:1&n=x&id=$id&v=1&future=yes" to { qr ->
                assertEquals("000000", qr.code)
                assertEquals(1, qr.endpoints.single().port)
            },
            // All five allowed ranges, at their edges, four endpoints.
            "veyro:pair?v=1&id=$id&n=PC&a=10.255.255.255:65535,172.16.0.1:5150,172.31.255.254:5151,192.168.0.1:5152&c=987654&e=$now" to { qr ->
                assertEquals(4, qr.endpoints.size)
                assertEquals(65535, qr.endpoints[0].port)
            },
            "veyro:pair?v=1&id=$id&n=PC&a=100.64.0.1:5150,100.127.255.255:5150&c=111111&e=$now" to { qr ->
                assertEquals(2, qr.endpoints.size)
            },
            "veyro:pair?v=1&id=$id&n=PC&a=169.254.10.20:5150&c=111111&e=$now" to { qr ->
                assertEquals("169.254.10.20", qr.endpoints.single().host)
            },
            // Duplicate endpoints collapse.
            "veyro:pair?v=1&id=$id&n=PC&a=10.0.0.1:5150,10.0.0.1:5150&c=111111&e=$now" to { qr ->
                assertEquals(1, qr.endpoints.size)
            },
            // Empty name is allowed and becomes null; '+' is a space; UTF-8 decodes.
            "veyro:pair?v=1&id=$id&n=&a=10.0.0.1:5150&c=111111&e=$now" to { qr -> assertNull(qr.name) },
            "veyro:pair?v=1&id=$id&n=My+PC%2B1&a=10.0.0.1:5150&c=111111&e=$now" to { qr ->
                assertEquals("My PC+1", qr.name)
            },
            "veyro:pair?v=1&id=$id&n=Caf%C3%A9&a=10.0.0.1:5150&c=111111&e=$now" to { qr ->
                assertEquals("Caf\u00e9", qr.name)
            },
            // Exactly 64 decoded bytes.
            "veyro:pair?v=1&id=$id&n=${"a".repeat(64)}&a=10.0.0.1:5150&c=111111&e=$now" to { qr ->
                assertEquals(64, qr.name!!.length)
            },
            // Lower-case id is canonicalised; the scheme prefix is case-insensitive.
            "VEYRO:PAIR?v=1&id=${id.lowercase()}&n=PC&a=10.0.0.1:5150&c=111111&e=$now" to { qr ->
                assertEquals(id, qr.deviceId.value)
            },
        )
        for ((raw, check) in cases) check(valid(raw))
    }

    @Test
    fun `expiry allows exactly 600 seconds of skew`() {
        valid(base, at = now + 600)
        valid(base, at = now - 10_000) // A PC clock ahead of the phone is fine.
        assertEquals(Error.EXPIRED, error(base, at = now + 601))
    }

    @Test
    fun `invalid table`() {
        val v = "v=1&id=$id&n=PC"
        val ok = "a=10.0.0.1:5150&c=123456&e=$now"
        val cases: List<Pair<String?, Error>> = listOf(
            null to Error.NOT_VEYRO,
            "" to Error.NOT_VEYRO,
            "https://example.com/?v=1" to Error.NOT_VEYRO,
            "veyro:pair?" to Error.NOT_VEYRO,
            "veyro:pair?garbage" to Error.NOT_VEYRO,
            "veyro:pair?=1" to Error.NOT_VEYRO,
            "veyro:pair?$v&$ok&" + "x".repeat(1100) to Error.NOT_VEYRO,
            // Version
            "veyro:pair?v=2&id=$id&n=PC&$ok" to Error.UNSUPPORTED_VERSION,
            "veyro:pair?v=&id=$id&n=PC&$ok" to Error.UNSUPPORTED_VERSION,
            "veyro:pair?id=$id&n=PC&$ok" to Error.MALFORMED,
            // Missing or repeated keys
            "veyro:pair?v=1&id=$id&$ok" to Error.MALFORMED,
            "veyro:pair?$v&c=123456&e=$now" to Error.MALFORMED,
            "veyro:pair?$v&$ok&c=654321" to Error.MALFORMED,
            "veyro:pair?v=1&v=1&id=$id&n=PC&$ok" to Error.MALFORMED,
            // Device id
            "veyro:pair?v=1&id=SHORT&n=PC&$ok" to Error.BAD_DEVICE_ID,
            "veyro:pair?v=1&id=${id.dropLast(1)}U&n=PC&$ok" to Error.BAD_DEVICE_ID,
            // Name
            "veyro:pair?v=1&id=$id&n=${"a".repeat(65)}&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=${"%C3%A9".repeat(33)}&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=bad%2&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=bad%ZZ&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=%C3%28&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=line%0Abreak&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=raw space&$ok" to Error.BAD_NAME,
            "veyro:pair?v=1&id=$id&n=%\u0663\u0663&$ok" to Error.BAD_NAME,
            // Endpoints
            "veyro:pair?$v&a=&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=8.8.8.8:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=172.32.0.1:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=172.15.0.1:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=100.128.0.1:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=127.0.0.1:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=0.0.0.0:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=255.255.255.255:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.1.1&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.1.1:0&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.1.1:65536&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.1.1:-1&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.01.1:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.1.256:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=192.168.1:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=pc.local:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=[fe80::1]:5150&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=10.0.0.1:1,10.0.0.2:1,10.0.0.3:1,10.0.0.4:1,10.0.0.5:1&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            "veyro:pair?$v&a=10.0.0.1:5150,&c=123456&e=$now" to Error.BAD_ENDPOINTS,
            // Code
            "veyro:pair?$v&a=10.0.0.1:5150&c=12345&e=$now" to Error.BAD_CODE,
            "veyro:pair?$v&a=10.0.0.1:5150&c=1234567&e=$now" to Error.BAD_CODE,
            "veyro:pair?$v&a=10.0.0.1:5150&c=12345a&e=$now" to Error.BAD_CODE,
            "veyro:pair?$v&a=10.0.0.1:5150&c=\u0661\u0662\u0663\u0664\u0665\u0666&e=$now" to Error.BAD_CODE,
            // Expiry
            "veyro:pair?$v&a=10.0.0.1:5150&c=123456&e=" to Error.BAD_EXPIRY,
            "veyro:pair?$v&a=10.0.0.1:5150&c=123456&e=-5" to Error.BAD_EXPIRY,
            "veyro:pair?$v&a=10.0.0.1:5150&c=123456&e=12x" to Error.BAD_EXPIRY,
            "veyro:pair?$v&a=10.0.0.1:5150&c=123456&e=99999999999999999999" to Error.BAD_EXPIRY,
            "veyro:pair?$v&a=10.0.0.1:5150&c=123456&e=${now - 601}" to Error.EXPIRED,
        )
        for ((raw, expected) in cases) {
            assertEquals("for <$raw>", expected, error(raw))
        }
    }

    @Test
    fun `toString never contains the code`() {
        val result = PairingQrCode.parse(base, now)
        assertFalse(result.toString().contains("123456"))
        assertFalse(valid(base).toString().contains("123456"))
        assertTrue(valid(base).toString().contains("redacted"))
    }
}
