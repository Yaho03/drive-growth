package io.drivegrowth.app.simulation

import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class SimulationTimelineTest {
    @Test fun tripUsesOneLogicalDomainAndOneExplicitArrivalThenStableParking() {
        var state = checkNotNull(SimulationTimeline().begin())
        val frames = mutableListOf<SimulationTimeline>()
        do { frames += state; state = state.next() ?: break } while (true)
        assertEquals(65, frames.size)
        assertEquals(1, frames.count { it.arrival })
        assertEquals(61, frames.count { (it.frame().observation.gear as Observation.Available).value == Gear.DRIVE })
        assertEquals(3_605_000L, frames.last().elapsedMs)
        assertTrue(frames.last().controlsAllowed)
        assertTrue(frames.dropLast(1).none { it.controlsAllowed })
        frames.zipWithNext().forEach { (a, b) ->
            assertTrue(b.elapsedMs > a.elapsedMs)
            assertEquals(b.elapsedMs-a.elapsedMs, b.clock.epoch.value-a.clock.epoch.value)
            assertEquals(a.clock.bootId,b.clock.bootId)
        }
        frames.forEach { assertEquals(InputSource.SIMULATION,it.frame().observation.source) }
    }
    @Test fun idleParkingDoesNotCreateArrivalAndRunningCannotStartAgain() {
        val idle=SimulationTimeline();assertTrue(idle.controlsAllowed);assertFalse(idle.arrival);assertNull(idle.next())
        val moving=checkNotNull(idle.begin());assertNull(moving.begin());assertFalse(moving.arrival)
    }
    @Test fun persistedStageReplaysSameFrameWithoutReadingOsTime() {
        var state=checkNotNull(SimulationTimeline().begin());repeat(12){state=checkNotNull(state.next())}
        val restored=SimulationTimeline(state.elapsedMs,state.stage)
        assertEquals(state.frame(),restored.frame());assertEquals(state.clock,restored.clock)
        assertEquals(state.next(),restored.next())
    }
    @Test fun invalidCheckpointIsRejectedRatherThanResettingProgress() {
        for (bad in listOf(-1L to 0,0L to -1,0L to 66,Long.MAX_VALUE to 0,0L to 1,1000L to 65)) {
            try { SimulationTimeline(bad.first,bad.second);fail("잘못된 진행을 초기화하면 안 된다") } catch (_:IllegalArgumentException) { }
        }
    }
}
