package com.veyro.core.protocol

import java.io.IOException

/**
 * An AEAD stream failed: a tag did not verify, a record was malformed, truncated or out of order,
 * or a counter ran out (ADR-058). Always fatal to the stream that raised it. The message never
 * carries key material, counters or plaintext.
 */
public class AeadStreamException(message: String, cause: Throwable? = null) : IOException(message, cause)
