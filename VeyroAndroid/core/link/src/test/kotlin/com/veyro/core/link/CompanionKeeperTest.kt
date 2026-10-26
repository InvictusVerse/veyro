package com.veyro.core.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
            targets = target.map { listOfNotNull(it) },
            connectedPeerIds = connected.map { setOfNotNull(it) },
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
        assertEquals(KeeperState.Looking(pc), rig.keeper.stateOf(pc.deviceId))

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
        assertEquals(KeeperState.Connected(pc), rig.keeper.stateOf(pc.deviceId))
        advanceTimeBy(600_000); runCurrent()
        assertEquals(0, rig.attempts)

        rig.connected.value = null
        runCurrent()
        assertEquals(KeeperState.Looking(pc), rig.keeper.stateOf(pc.deviceId))
        assertEquals(1, rig.attempts)
        job.cancel()
    }

    @Test
    fun `no target is idle and makes no attempts`() = runTest {
        val rig = Rig(null)
        val job = launch { rig.keeper.run() }
        runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(KeeperState.Idle, rig.keeper.stateOf(pc.deviceId))
        assertEquals(0, rig.attempts)

        rig.target.value = pc
        runCurrent()
        assertEquals(1, rig.attempts)
        job.cancel()
    }

    @Test
    fun `two targets are kept independently and share network wakeups`() = runTest {
        val laptop = KeepTarget("LAPTOP-ID", "Laptop")
        val targets = MutableStateFlow(listOf(pc, laptop))
        val connected = MutableStateFlow<Set<String>>(emptySet())
        val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val attempts = mutableMapOf<String, Int>()
        val keeper = CompanionKeeper(
            targets = targets,
            connectedPeerIds = connected,
            wakeups = wakeups,
            attempt = { t -> attempts[t.deviceId] = (attempts[t.deviceId] ?: 0) + 1; false },
        )
        val job = launch { keeper.run() }
        runCurrent()
        assertEquals("one attempt per target", mapOf(pc.deviceId to 1, laptop.deviceId to 1), attempts)
        assertEquals(KeeperState.Looking(pc), keeper.stateOf(pc.deviceId))
        assertEquals(KeeperState.Looking(laptop), keeper.stateOf(laptop.deviceId))

        // The office PC connects: its loop stops, the laptop's keeps its own backoff.
        connected.value = setOf(pc.deviceId)
        runCurrent()
        assertEquals(KeeperState.Connected(pc), keeper.stateOf(pc.deviceId))
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, attempts[pc.deviceId])
        assertEquals(2, attempts[laptop.deviceId])

        // One network change wakes every loop that is still looking.
        wakeups.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertEquals(3, attempts[laptop.deviceId])
        assertEquals(1, attempts[pc.deviceId])

        // Both connected: nothing is attempted.
        connected.value = setOf(pc.deviceId, laptop.deviceId)
        runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(3, attempts[laptop.deviceId])
        assertEquals(KeeperState.Connected(laptop), keeper.stateOf(laptop.deviceId))

        // The office PC drops: only it is looked for again; the laptop is untouched.
        connected.value = setOf(laptop.deviceId)
        runCurrent()
        assertEquals(KeeperState.Looking(pc), keeper.stateOf(pc.deviceId))
        assertEquals(2, attempts[pc.deviceId])
        assertEquals(KeeperState.Connected(laptop), keeper.stateOf(laptop.deviceId))

        // A target removed (user disconnected it on Home) stops its loop.
        targets.value = listOf(laptop)
        runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(2, attempts[pc.deviceId])
        assertEquals(KeeperState.Idle, keeper.stateOf(pc.deviceId))
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
        assertEquals(KeeperState.Connected(pc), rig.keeper.stateOf(pc.deviceId))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, rig.attempts)
        job.cancel()
    }
}
