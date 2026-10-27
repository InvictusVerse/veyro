package com.veyro.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * The current time, refreshed every [periodMillis] while the screen is started (ADR-048).
 *
 * A plain `delay` loop in a `LaunchedEffect` keeps waking the CPU after the user presses Home:
 * the Activity is only stopped, so the composition and its effects live on. This clock stops at
 * ON_STOP and picks up again, with a fresh reading, at ON_START.
 *
 * Ticks only while [enabled] and until the time reaches [until]. A change of [key] takes a new
 * reading at once (a new media position sample, a new offer).
 */
@Composable
public fun rememberTickingClock(
    periodMillis: Long,
    enabled: Boolean = true,
    until: Long = Long.MAX_VALUE,
    key: Any? = null,
    clock: () -> Long = System::currentTimeMillis,
): Long {
    val currentClock by rememberUpdatedState(clock)
    var now by remember { mutableLongStateOf(clock()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(periodMillis, enabled, until, key, lifecycle) {
        now = currentClock()
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = currentClock()
                if (now >= until) break
                delay(periodMillis)
            }
        }
    }
    return now
}
