package com.veyro.core.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** The runtime permissions "Messages and calls on PC" asks for (ADR-027 point 4). */
public object MessagingPermissions {

    /** Permission to short user-facing label, in request order. */
    public val ALL: Map<String, String> = linkedMapOf(
        Manifest.permission.READ_SMS to "Read SMS",
        Manifest.permission.RECEIVE_SMS to "Receive SMS",
        Manifest.permission.SEND_SMS to "Send SMS",
        Manifest.permission.READ_CONTACTS to "Contacts",
        Manifest.permission.READ_PHONE_STATE to "Phone state",
        Manifest.permission.READ_CALL_LOG to "Call log",
        Manifest.permission.ANSWER_PHONE_CALLS to "Answer calls",
        Manifest.permission.CALL_PHONE to "Make calls",
    )

    /** The permissions in [ALL] not granted right now. */
    public fun missing(context: Context): List<String> =
        ALL.keys.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
