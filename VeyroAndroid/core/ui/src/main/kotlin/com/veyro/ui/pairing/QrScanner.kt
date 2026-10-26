package com.veyro.ui.pairing

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What one scan attempt produced. [Scanned.raw] holds the pairing code: never log it. */
public sealed interface QrScanOutcome {
    public class Scanned(public val raw: String) : QrScanOutcome {
        override fun toString(): String = "Scanned(<redacted>)"
    }

    public data object Cancelled : QrScanOutcome

    /** Play services is missing or too old, or the scanner module could not be loaded. */
    public data object Unavailable : QrScanOutcome
}

/**
 * The Google code scanner (ADR-030), wrapped as one suspend call.
 *
 * The camera UI runs inside Play services, so Veyro holds no CAMERA permission and ships no
 * camera code. On a phone without Play services, or while the scanner module is still being
 * downloaded, the task fails and the caller shows the nearby-list-plus-code fallback.
 *
 * The Task callbacks stay inside this file; callers only see the suspend function (AGENTS.md).
 */
public object QrScanner {

    public suspend fun scan(context: Context): QrScanOutcome {
        val client = try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, options)
        } catch (_: Throwable) {
            // Linkage or Play services errors surface here on some devices.
            return QrScanOutcome.Unavailable
        }
        return suspendCancellableCoroutine { continuation ->
            val task = try {
                client.startScan()
            } catch (_: Throwable) {
                continuation.resume(QrScanOutcome.Unavailable)
                return@suspendCancellableCoroutine
            }
            task
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue
                    if (continuation.isActive) {
                        continuation.resume(
                            if (raw.isNullOrEmpty()) QrScanOutcome.Cancelled else QrScanOutcome.Scanned(raw),
                        )
                    }
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(QrScanOutcome.Cancelled)
                }
                .addOnFailureListener {
                    // The exception text can name the module state; it never contains scan data.
                    if (continuation.isActive) continuation.resume(QrScanOutcome.Unavailable)
                }
        }
    }
}
