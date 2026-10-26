package com.veyro.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected dispatchers.
 *
 * Nothing in Veyro calls `Dispatchers.IO` directly. Every component takes this and uses
 * `dispatchers.io`, so a test can substitute a deterministic scheduler and so socket work is
 * never accidentally run on the main thread.
 */
public interface AppDispatchers {
    public val default: CoroutineDispatcher
    public val io: CoroutineDispatcher
    public val main: CoroutineDispatcher
}

public class DefaultAppDispatchers : AppDispatchers {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}