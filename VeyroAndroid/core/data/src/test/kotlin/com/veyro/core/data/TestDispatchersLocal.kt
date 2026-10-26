package com.veyro.core.data

import com.veyro.core.common.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Local dispatcher fake.
 *
 * Deliberately not reusing `:core:testing`: that module depends on `:core:data` (it fakes
 * `TrustStore`), so depending on it from these tests would be a circular project dependency.
 * Duplicating three lines is the right trade against restructuring the module graph for a fake.
 */
internal class TestAppDispatchers(
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
) : AppDispatchers {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}