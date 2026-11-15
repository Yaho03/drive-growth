package io.drivegrowth.app.ui.presentation

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.progression.VehicleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeSoundTest {
    @Test
    fun firstGrantPlaysOriginalCardCoinHornAndFanfareCues() {
        val notes = PrototypeSound.firstGrant()
        assertEquals(listOf(0, 100, 200, 450, 530, 780, 780, 1250, 1370, 1490, 1610),
            notes.map { it.startMs })
        assertEquals(listOf(1200, 1200, 1200, 1047, 1568, 440, 554, 523, 659, 784, 1047),
            notes.map { it.hertz.toInt() })
        assertTrue(notes.subList(5, 7).all { it.wave == PrototypeSound.Wave.SAWTOOTH })
    }

    @Test
    fun expansionPlaysKnockDingPopAndLevelUpInOriginalOrder() {
        val notes = PrototypeSound.expansion()
        assertEquals(listOf(400, 490, 580, 1000, 1450, 1700, 1770, 1840, 1910, 1980),
            notes.map { it.startMs })
        assertEquals(listOf(150, 150, 150, 1319, 400, 523, 659, 784, 1047, 1319),
            notes.map { it.hertz.toInt() })
        assertEquals(500, notes[4].slide)
    }

    @Test
    fun confirmedGarageActionsUseOriginalCuesAndOnlyCreditedDisposalPlaysCoin() {
        assertEquals(PrototypeSound.LEVELUP, PrototypeSound.garageAction(VehicleAction.LEVEL_UP, null))
        assertEquals(PrototypeSound.LEVELUP, PrototypeSound.garageAction(VehicleAction.USE_MATERIAL, null))
        assertEquals(PrototypeSound.DING, PrototypeSound.garageAction(VehicleAction.REPAIR, null))
        val credit = MoneyChange(MoneyDirection.CREDIT, GameMoney(10))
        for (action in listOf(VehicleAction.SELL, VehicleAction.SCRAP)) {
            assertEquals(PrototypeSound.COIN, PrototypeSound.garageAction(action, credit))
            assertNull(PrototypeSound.garageAction(action, null))
            assertNull(PrototypeSound.garageAction(action, credit.copy(amount = GameMoney(0))))
            assertNull(PrototypeSound.garageAction(action, credit.copy(direction = MoneyDirection.DEBIT)))
        }
    }

    @Test
    fun gainStartsAtVolumeAndHoldsFloorUntilOscillatorStops() {
        val sampleRate = 22050
        val samples = PrototypeSound.pcm(PrototypeSound.CLICK.notes(), sampleRate)
        assertEquals(1543, samples.size)
        assertEquals((.06 * Short.MAX_VALUE).toInt(), samples[0].toInt())
        assertTrue(samples.copyOfRange(1103, samples.size).all { kotlin.math.abs(it.toInt()) == 32 })
    }
}
