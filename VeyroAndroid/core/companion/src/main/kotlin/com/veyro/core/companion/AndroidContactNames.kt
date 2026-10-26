package com.veyro.core.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Contact display names via `ContactsContract.PhoneLookup`, with a small LRU cache (ADR-027).
 *
 * Misses are cached too, so a thread list full of unknown numbers costs one lookup each. Returns
 * null without `READ_CONTACTS`. Names and numbers are user data: nothing here logs them.
 */
internal class AndroidContactNames(context: Context) {

    private companion object {
        const val CACHE_SIZE = 128
        const val MISS = ""
    }

    private val appContext: Context = context.applicationContext

    private val cache = object : LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_SIZE
    }

    /** Blocking; call from an IO dispatcher. */
    fun nameFor(number: String): String? {
        if (number.isBlank()) return null
        if (appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        synchronized(cache) { cache[number] }?.let { return it.takeUnless { name -> name == MISS } }

        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = try {
            appContext.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        synchronized(cache) { cache[number] = name ?: MISS }
        return name
    }
}
