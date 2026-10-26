package com.veyro.core.testing

import com.veyro.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

/** Deterministic dispatchers for tests - every module takes AppDispatchers, so this swaps in. */
public class TestAppDispatchers(
    testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : AppDispatchers {
    override val default: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
}