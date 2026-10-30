package com.veyro.core.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionKeeperTest {

    private val pc = KeepTarget("PC-ID", "Office PC")

    /** Always returns [unit]: 0.5 is no jitter, 0.0 is -20 %, 0.75 is +10 %. */
    private class FixedRandom(private val unit: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = unit
    }

    private class Rig(
        initialTarget: KeepTarget?,
        val succeed: Boolean = false,
        clock: () -> Long,
        unit: Double = 0.5,
    ) {
        val target = MutableStateFlow(initialTarget)
        val connected = MutableStateFlow<String?>(null)
        val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var attempts = 0
        val keeper = CompanionKeeper(
            targets = target.map { listOfNotNull(it) },
            connectedPeerIds = connected.map { setOfNotNull(it) },
            wakeups = wakeups,
            attempt = { attempts++; succeed },
            random = FixedRandom(unit),
            nowMs = clock,
        )
    }

    private fun TestScope.rig(target: KeepTarget?, succeed: Boolean = false, unit: Double = 0.5) =
        Rig(target, succeed, { testScheduler.currentTime }, unit)

    @Test
    fun `retries with growing backoff and never spins`() = runTest {
        val rig = rig(pc)
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
        val rig = rig(pc)
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
        val rig = rig(pc)
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
        val rig = rig(null)
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
            random = FixedRandom(0.5),
            nowMs = { testScheduler.currentTime },
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
    fun `jitter moves each backoff by up to 20 percent either way`() {
        assertEquals(4_000L, CompanionKeeper.jittered(5_000L, 0.0))
        assertEquals(5_000L, CompanionKeeper.jittered(5_000L, 0.5))
        assertEquals(5_500L, CompanionKeeper.jittered(5_000L, 0.75))
        // The top of the range is open: just under +20 %.
        assertEquals(true, CompanionKeeper.jittered(5_000L, 0.999_999) in 5_999L..6_000L)
        assertEquals(0L, CompanionKeeper.jittered(0L, 0.3))
    }

    @Test
    fun `the jittered backoff is what the loop sleeps`() = runTest {
        val early = rig(pc, unit = 0.0)
        val job = launch { early.keeper.run() }
        runCurrent()
        assertEquals(1, early.attempts)
        advanceTimeBy(3_999); runCurrent()
        assertEquals("5 s minus 20 %", 1, early.attempts)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, early.attempts)
        // Second step: 15 s minus 20 % = 12 s.
        advanceTimeBy(11_998); runCurrent()
        assertEquals(2, early.attempts)
        advanceTimeBy(3); runCurrent()
        assertEquals(3, early.attempts)
        job.cancel()

        val late = rig(pc, unit = 0.75)
        val lateJob = launch { late.keeper.run() }
        runCurrent()
        advanceTimeBy(5_499); runCurrent()
        assertEquals("5 s plus 10 %", 1, late.attempts)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, late.attempts)
        lateJob.cancel()
    }

    @Test
    fun `a session that dies within 15 s keeps the backoff growing`() = runTest {
        val rig = rig(pc, succeed = true)
        val job = launch { rig.keeper.run() }
        runCurrent()
        assertEquals(1, rig.attempts)

        // Connects, drops after 3 s: no instant redial, the first step (5 s) comes first.
        rig.connected.value = pc.deviceId; runCurrent()
        advanceTimeBy(3_000)
        rig.connected.value = null; runCurrent()
        assertEquals("no redial straight after a short session", 1, rig.attempts)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(2, rig.attempts)

        // Connects again, drops after 2 s: the wait grows to the next step (15 s).
        rig.connected.value = pc.deviceId; runCurrent()
        advanceTimeBy(2_000)
        rig.connected.value = null; runCurrent()
        advanceTimeBy(14_999); runCurrent()
        assertEquals("still waiting the second step", 2, rig.attempts)
        advanceTimeBy(2); runCurrent()
        assertEquals(3, rig.attempts)

        // And again: 30 s.
        rig.connected.value = pc.deviceId; runCurrent()
        advanceTimeBy(1_000)
        rig.connected.value = null; runCurrent()
        advanceTimeBy(29_999); runCurrent()
        assertEquals(3, rig.attempts)
        advanceTimeBy(2); runCurrent()
        assertEquals(4, rig.attempts)
        job.cancel()
    }

    @Test
    fun `a session that lasted longer than 15 s resets the backoff`() = runTest {
        val rig = rig(pc, succeed = true)
        val job = launch { rig.keeper.run() }
        runCurrent()
        // Two short sessions grow the backoff.
        repeat(2) {
            rig.connected.value = pc.deviceId; runCurrent()
            advanceTimeBy(1_000)
            rig.connected.value = null; runCurrent()
            advanceTimeBy(20_000); runCurrent()
        }
        val before = rig.attempts

        // A session of a minute: the drop redials at once, and the next step is 5 s again.
        rig.connected.value = pc.deviceId; runCurrent()
        advanceTimeBy(60_000)
        rig.connected.value = null; runCurrent()
        assertEquals("immediate redial after a long session", before + 1, rig.attempts)

        rig.connected.value = pc.deviceId; runCurrent()
        advanceTimeBy(1_000)
        rig.connected.value = null; runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertEquals("backoff restarted at 5 s", before + 2, rig.attempts)
        job.cancel()
    }

    @Test
    fun `a successful attempt waits for the session before trying again`() = runTest {
        val rig = rig(pc, succeed = true)
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
