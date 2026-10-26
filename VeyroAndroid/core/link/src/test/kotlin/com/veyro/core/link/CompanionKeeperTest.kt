package com.veyro.core.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionKeeperTest {

    private val pc = KeepTarget("PC-ID", "Office PC")

    private class Rig(initialTarget: KeepTarget?, val succeed: Boolean = false) {
        val target = MutableStateFlow(initialTarget)
        val connected = MutableStateFlow<String?>(null)
        val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var attempts = 0
        val keeper = CompanionKeeper(
            target = target,
            connectedPeerId = connected,
            wakeups = wakeups,
            attempt = { attempts++; succeed },
        )
    }

    @Test
    fun `retries with growing backoff and never spins`() = runTest {
        val rig = Rig(pc)
        val job = launch { rig.keeper.run() }
        runCurrent()
        assertEquals(1, rig.attempts)
        assertEquals(KeeperState.Looking(pc), rig.keeper.state.value)

        advanceTimeBy(4_999); runCurrent()
        assertEquals("nothing before the first backoff", 1, rig.attempts)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, rig.attempts)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(3, rig.attempts)

        // An hour of failures stays bounded by the 5-minute cap: a handful, not thousands.
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(true, rig.attempts in 10..20)
        job.cancel()
    }

    @Test
    fun `a network change cuts the backoff short and resets it`() = runTest {
        val rig = Rig(pc)
        val job = launch { rig.keeper.run() }
        runCurrent()
        advanceTimeBy(5_001); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(3, rig.attempts) // now sleeping 30 s

        rig.wakeups.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertEquals(4, rig.attempts)
        // Backoff restarted at 5 s.
        advanceTimeBy(5_001); runCurrent()
        assertEquals(5, rig.attempts)
        job.cancel()
    }

    @Test
    fun `connected means no attempts, and a drop starts looking again`() = runTest {
        val rig = Rig(pc)
        rig.connected.value = pc.deviceId
        val job = launch { rig.keeper.run() }
        runCurrent()
        assertEquals(KeeperState.Connected(pc), rig.keeper.state.value)
        advanceTimeBy(600_000); runCurrent()
        assertEquals(0, rig.attempts)

        rig.connected.value = null
        runCurrent()
        assertEquals(KeeperState.Looking(pc), rig.keeper.state.value)
        assertEquals(1, rig.attempts)
        job.cancel()
    }

    @Test
    fun `no target is idle and makes no attempts`() = runTest {
        val rig = Rig(null)
        val job = launch { rig.keeper.run() }
        runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(KeeperState.Idle, rig.keeper.state.value)
        assertEquals(0, rig.attempts)

        rig.target.value = pc
        runCurrent()
        assertEquals(1, rig.attempts)
        job.cancel()
    }

    @Test
    fun `a successful attempt waits for the session before trying again`() = runTest {
        val rig = Rig(pc, succeed = true)
        val job = launch { rig.keeper.run() }
        runCurrent()
        assertEquals(1, rig.attempts)
        advanceTimeBy(5_000); runCurrent()
        rig.connected.value = pc.deviceId
        runCurrent()
        assertEquals(KeeperState.Connected(pc), rig.keeper.state.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, rig.attempts)
        job.cancel()
    }
}
