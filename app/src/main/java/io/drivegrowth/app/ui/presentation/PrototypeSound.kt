package io.drivegrowth.app.ui.presentation

import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.progression.VehicleAction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** HTML의 SFX와 CUE에 대응한다. 앱에서는 현지화 문구 대신 확정된 효과를 직접 선택한다. */
internal enum class PrototypeSound {
    CLICK, COIN, SHUTTER, WHOOSH, POP, CHIME, SPARKLE,
    FANFARE, LEVELUP, DING, KNOCK, TICK3, HORN, DEPART, BRAKE, BAD;

    enum class Wave { SINE, SQUARE, TRIANGLE, SAWTOOTH }

    data class Note(
        val hertz: Double,
        val startMs: Int,
        val durationMs: Int,
        val volume: Double,
        val wave: Wave,
        val slide: Int,
    )

    fun notes(at: Int = 0, step: Int = 0): List<Note> {
        fun note(
            hz: Number, delay: Int, duration: Int, gain: Double,
            wave: Wave = Wave.SQUARE, slide: Int = 0,
        ) = Note(hz.toDouble(), at + delay, duration, gain, wave, slide)

        return when (this) {
            CLICK -> listOf(note(880, 0, 50, .06))
            COIN -> listOf(note(1047, 0, 80, .1), note(1568, 80, 160, .1))
            SHUTTER -> listOf(note(110, 0, 300, .18, Wave.SAWTOOTH, -70))
            WHOOSH -> listOf(note(300, 0, 300, .1, Wave.SINE, 900))
            POP -> listOf(note(400, 0, 120, .12, slide = 500))
            CHIME -> listOf(note(660 * 1.19.pow(step), 0, 250, .12, Wave.TRIANGLE))
            SPARKLE -> List(3) { note(1760 + it * 440, it * 60, 140, .08, Wave.TRIANGLE) }
            FANFARE -> listOf(523, 659, 784, 1047).mapIndexed { i, hz -> note(hz, i * 120, 320, .1) }
            LEVELUP -> listOf(523, 659, 784, 1047, 1319).mapIndexed { i, hz -> note(hz, i * 70, 160, .1) }
            DING -> listOf(note(1319, 0, 350, .1, Wave.TRIANGLE))
            KNOCK -> List(3) { note(150, it * 90, 60, .12) }
            TICK3 -> List(3) { note(1200, it * 100, 30, .07) }
            HORN -> listOf(note(440, 0, 200, .1, Wave.SAWTOOTH), note(554, 0, 200, .08, Wave.SAWTOOTH))
            DEPART -> listOf(note(120, 0, 550, .12, Wave.SAWTOOTH, 90))
            BRAKE -> listOf(note(1400, 0, 280, .05, Wave.SAWTOOTH, -1000))
            BAD -> listOf(note(220, 0, 220, .1, slide = -90))
        }
    }

    companion object {
        fun garageAction(action: VehicleAction, money: MoneyChange?): PrototypeSound? = when (action) {
            VehicleAction.LEVEL_UP, VehicleAction.USE_MATERIAL -> LEVELUP
            VehicleAction.REPAIR -> DING
            VehicleAction.SELL, VehicleAction.SCRAP ->
                COIN.takeIf { money?.direction == MoneyDirection.CREDIT && money.amount.units > 0 }
        }

        fun firstGrant() = TICK3.notes() + COIN.notes(450) + HORN.notes(780) + FANFARE.notes(1250)

        fun expansion() = KNOCK.notes(400) + DING.notes(1000) + POP.notes(1450) + LEVELUP.notes(1700)

        /** 선형 주파수 램프와 지수 감쇠를 PCM으로 합성한다. */
        fun pcm(notes: List<Note>, sampleRate: Int): ShortArray {
            val mix = FloatArray(notes.maxOf { it.startMs + it.durationMs + 20 } * sampleRate / 1_000)
            notes.forEach { note ->
                val start = note.startMs * sampleRate / 1_000
                val length = (note.durationMs + 20) * sampleRate / 1_000
                val duration = note.durationMs / 1000.0
                val slide = (note.hertz + note.slide).coerceAtLeast(30.0) - note.hertz
                repeat(minOf(length, mix.size - start)) { sample ->
                    val time = sample.toDouble() / sampleRate
                    val rampTime = minOf(time, duration)
                    val progress = rampTime / duration
                    val cycles = (note.hertz + slide * progress / 2) * rampTime +
                        (time - rampTime) * (note.hertz + slide)
                    val fraction = cycles - floor(cycles)
                    val signal = when (note.wave) {
                        Wave.SINE -> sin(2 * PI * fraction)
                        Wave.SQUARE -> if (fraction < .5) 1.0 else -1.0
                        Wave.TRIANGLE -> 1 - 4 * abs(fraction - .5)
                        Wave.SAWTOOTH -> 2 * fraction - 1
                    }
                    val gain = note.volume * (.001 / note.volume).pow(progress)
                    mix[start + sample] += (signal * gain).toFloat()
                }
            }
            return ShortArray(mix.size) { i ->
                (mix[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
