package com.veyro.core.companion

import com.veyro.core.companion.ClipClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the "Copied on PC" notification offers for a copied text (ADR-043). */
class ClipClassifierTest {

    private fun check(text: String?, kind: Kind, value: String) {
        assertEquals("for '$text'", ClipClassifier.Result(kind, value), ClipClassifier.classify(text))
    }

    @Test
    fun `links, with or without a title around them`() {
        check("https://youtu.be/dQw4w9WgXcQ", Kind.LINK, "https://youtu.be/dQw4w9WgXcQ")
        check("Cricket scores - https://www.cricbuzz.com/live.", Kind.LINK, "https://www.cricbuzz.com/live")
        check("(see https://example.com/a?b=1)", Kind.LINK, "https://example.com/a?b=1")
        check("www.instagram.com/reel/abc", Kind.LINK, "https://www.instagram.com/reel/abc")
        check("  HTTP://EXAMPLE.COM  ", Kind.LINK, "HTTP://EXAMPLE.COM")
    }

    @Test
    fun `non-web schemes are not links`() {
        check("javascript:alert(1)", Kind.TEXT, "javascript:alert(1)")
        check("ftp://example.com/file", Kind.TEXT, "ftp://example.com/file")
        check("file:///C:/secret.txt", Kind.TEXT, "file:///C:/secret.txt")
    }

    @Test
    fun `phone numbers`() {
        check("+91 98765 43210", Kind.PHONE, "+919876543210")
        check("(555) 123-4567", Kind.PHONE, "5551234567")
        check("9876543210", Kind.PHONE, "9876543210")
        check("022 2345 6789", Kind.PHONE, "02223456789")
    }

    @Test
    fun `email addresses`() {
        check("someone@example.com", Kind.EMAIL, "someone@example.com")
        check("Mail someone@example.com today", Kind.TEXT, "Mail someone@example.com today")
    }

    @Test
    fun `one-time codes`() {
        check("482913", Kind.CODE, "482913")
        check("Your verification code is 482913. Do not share it.", Kind.CODE, "482913")
        check("OTP: 4829", Kind.CODE, "4829")
        check("12345678", Kind.CODE, "12345678")
        check("123", Kind.TEXT, "123")
        check("Meet at 1730 near gate 4", Kind.TEXT, "Meet at 1730 near gate 4")
    }

    @Test
    fun `everything else is text`() {
        check("Meeting at 5", Kind.TEXT, "Meeting at 5")
        check(null, Kind.TEXT, "")
        check("   ", Kind.TEXT, "")
    }
}
