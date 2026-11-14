package io.drivegrowth.app.ui.presentation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.app.ui.rest.RestStopMotionView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** 게임 화면의 소리만 관리한다. 공개 메서드는 호스트의 UI 스레드에서 호출한다. */
internal class PrototypeAudio(context: Context) : AutoCloseable {
    enum class Scene(val fileName: String? = null) {
        TITLE("title.mp3"), REST("rest.mp3"), SHOP, GACHA, DELIVERY, SETTLE,
    }

    enum class Cue {
        CLICK, START, FIRST_GRANT, GACHA, SETTLEMENT, EXPAND,
        DELIVERY_DEPARTURE, DELIVERY_INCOME_ARRIVAL, DELIVERY_VEHICLE_ARRIVAL, DELIVERY_HELD,
    }

    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("presentation_audio", Context.MODE_PRIVATE)
    @Volatile private var permitted = false
    @Volatile private var closed = false
    @Volatile private var revision = 0L
    @Volatile private var focusLossRevision = 0L
    @Volatile private var effectRevision = 0L
    @Volatile private var scene = Scene.SHOP
    @Volatile private var soundEnabled = preferences.getBoolean("enabled", false)
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    private val manager = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    // 아래 재생 상태와 포커스 콜백은 모두 오디오 스레드에서 접근한다.
    private var focus: AudioFocusRequest? = null
    private var focusGain = 0
    private val focusState = PrototypeAudioFocus()
    private var music: MediaPlayer? = null
    private var musicReady = false
    private var musicScene: Scene? = null
    private var musicRevision: Long? = null
    private var effect: AudioTrack? = null
    private var releaseEffect: Runnable? = null
    @Volatile private var restSoundPart: RestStopMotionView.Part? = null
    private var restSound: AudioTrack? = null

    var enabled: Boolean
        get() = soundEnabled
        set(value) {
            if (closed || soundEnabled == value) return
            soundEnabled = value
            preferences.edit().putBoolean("enabled", value).apply()
            updatePermission()
        }

    fun setPresentationAllowed(allowed: Boolean) {
        if (closed || permitted == allowed) return
        permitted = allowed
        updatePermission()
    }

    fun setScene(next: Scene) {
        if (closed || scene == next) return
        scene = next
        revision++
        handler?.post { stopEffect(); stopRestSound(); abandonIdleFocus() }
        scheduleMusic()
    }

    fun setRestSound(part: RestStopMotionView.Part?) {
        if (closed || restSoundPart == part) return
        restSoundPart = part
        val expected = revision
        if (part == null) handler?.post { if (restSoundPart == null) stopRestSound() }
        else if (canPlay(expected) && scene == Scene.REST) {
            audioHandler().post {
                if (canPlay(expected) && scene == Scene.REST && restSoundPart == part) updateRestSound()
            }
        }
    }

    fun play(cue: Cue) = queueEffect { pcm(notes(cue)) }

    fun playGacha(rarity: Rarity, opening: Boolean) = queueEffect { pcm(gachaNotes(rarity, opening)) }

    /** 같은 화면에서 건너뛰어도 대기 중인 효과음과 재생 중인 효과음을 함께 정리한다. */
    fun stopEffectPlayback() {
        effectRevision++
        handler?.post {
            stopEffect()
            abandonIdleFocus()
        }
    }

    private fun queueEffect(samples: () -> ShortArray) {
        val expected = revision
        val expectedFocus = focusLossRevision
        val expectedEffect = effectRevision
        fun canPlayEffect() = canPlay(expected) && focusLossRevision == expectedFocus &&
            effectRevision == expectedEffect && !focusState.paused
        if (!canPlayEffect()) return
        audioHandler().post {
            if (canPlayEffect()) playEffect(samples(), ::canPlayEffect)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        revision++
        handler?.postAtFrontOfQueue {
            stopPlayback()
            worker?.quitSafely()
        }
    }

    private fun updatePermission() {
        revision++
        if (canPlay()) scheduleMusic() else handler?.postAtFrontOfQueue(::stopPlayback)
    }

    private fun canPlay(expected: Long = revision) =
        !closed && permitted && soundEnabled && revision == expected

    private fun audioHandler(): Handler = handler ?: Handler(
        HandlerThread("drive-growth-audio").also { worker = it; it.start() }.looper,
    ).also { handler = it }

    private fun scheduleMusic() {
        if (!canPlay()) return
        val expected = revision
        val expectedFocus = focusLossRevision
        audioHandler().post {
            if (canPlay(expected) && focusLossRevision == expectedFocus) updateMusic(expected)
        }
    }

    private fun updateMusic(expected: Long) {
        val target = scene
        if (musicScene == target && musicRevision == expected) return
        stopMusic()
        val fileName = target.fileName
        if (fileName == null) {
            abandonIdleFocus()
            return
        }
        if (!canPlay(expected)) return
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN)) {
            Log.d("DriveGrowthAudio", "Music focus unavailable: $target")
            return
        }
        if (!canPlay(expected) || scene != target) {
            abandonIdleFocus()
            return
        }
        try {
            val player = MediaPlayer()
            music = player
            musicScene = target
            musicRevision = expected
            app.assets.openFd("bgm/$fileName").use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.setAudioAttributes(attributes)
            player.setOnPreparedListener {
                if (music !== player) return@setOnPreparedListener
                if (!canPlay(expected) || scene != target) {
                    stopMusic()
                    abandonIdleFocus()
                    return@setOnPreparedListener
                }
                try {
                    player.isLooping = true
                } catch (failure: RuntimeException) {
                    Log.w("DriveGrowthAudio", "Could not configure music loop: $target", failure)
                    stopMusic()
                    abandonIdleFocus()
                    return@setOnPreparedListener
                }
                musicReady = true
                applyMusicVolume()
                if (!focusState.paused) {
                    startMusic(player)
                    if (target == Scene.REST && restSound == null) updateRestSound()
                }
            }
            player.setOnErrorListener { _, what, extra ->
                if (music === player) {
                    Log.w("DriveGrowthAudio", "Music playback error: $target ($what/$extra)")
                    stopMusic()
                    abandonIdleFocus()
                }
                true
            }
            player.prepareAsync()
        } catch (failure: IOException) {
            Log.w("DriveGrowthAudio", "Could not open music: $target", failure)
            stopMusic()
            abandonIdleFocus()
        } catch (failure: RuntimeException) {
            Log.w("DriveGrowthAudio", "Could not prepare music: $target", failure)
            stopMusic()
            abandonIdleFocus()
        }
    }

    private fun playEffect(samples: ShortArray, canPlayEffect: () -> Boolean) {
        if (!canPlayEffect()) return
        stopEffect()
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)) {
            Log.d("DriveGrowthAudio", "Effect audio focus unavailable: $scene")
            return
        }
        try {
            val track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            effect = track
            val wrote = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (wrote != samples.size) Log.w("DriveGrowthAudio", "Effect PCM write: $wrote/${samples.size}")
            if (wrote != samples.size || !canPlayEffect()) {
                stopEffect()
                abandonIdleFocus()
                return
            }
            // 쓰는 동안 도착한 포커스 변경을 먼저 처리하고 재생 직전에 다시 검사한다.
            handler?.post {
                if (effect !== track) return@post
                if (!canPlayEffect()) {
                    stopEffect()
                    abandonIdleFocus()
                    return@post
                }
                try {
                    track.play()
                    Log.d("DriveGrowthAudio", "Effect started: $scene (${samples.size} samples)")
                    releaseEffect = Runnable {
                        stopEffect()
                        abandonIdleFocus()
                    }.also { handler?.postDelayed(it, samples.size * 1_000L / SAMPLE_RATE + 80L) }
                } catch (failure: RuntimeException) {
                    Log.w("DriveGrowthAudio", "Effect playback error: $scene", failure)
                    stopEffect()
                    abandonIdleFocus()
                }
            }
        } catch (failure: RuntimeException) {
            Log.w("DriveGrowthAudio", "Effect playback error: $scene", failure)
            stopEffect()
            abandonIdleFocus()
        }
    }

    private fun requestFocus(gain: Int): Boolean = focusState.request { acquireFocus(gain) }

    private fun acquireFocus(gain: Int): Boolean {
        if (focus != null && (focusGain == gain || focusGain == AudioManager.AUDIOFOCUS_GAIN)) return true
        abandonFocus()
        val audioManager = manager ?: return false
        val callbackHandler = handler ?: return false
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (focus === request) onFocusChange(change)
            }, callbackHandler).build()
        if (runCatching { audioManager.requestAudioFocus(request) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        focus = request
        focusGain = gain
        return true
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusLossRevision++
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusState.pause()
                focusLossRevision++
                stopEffect()
                stopRestSound()
                if (musicReady) {
                    try {
                        music?.pause()
                    } catch (_: RuntimeException) {
                        stopMusic()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!canPlay()) {
                    stopPlayback()
                    return
                }
                focusState.reset()
                if (musicScene == scene && musicRevision == revision) {
                    applyMusicVolume()
                    if (musicReady) music?.let(::startMusic)
                    if (scene == Scene.REST) updateRestSound()
                } else {
                    updateMusic(revision)
                }
            }
        }
    }

    private fun startMusic(player: MediaPlayer) {
        try {
            player.start()
        } catch (_: RuntimeException) {
            stopMusic()
            abandonIdleFocus()
        }
    }

    private fun applyMusicVolume() {
        runCatching { music?.setVolume(.45f, .45f) }
    }

    private fun updateRestSound() {
        stopRestSound()
        val part = restSoundPart ?: return
        val expected = revision
        if (scene != Scene.REST || !canPlay(expected) || focusState.paused ||
            !requestFocus(AudioManager.AUDIOFOCUS_GAIN)) return
        try {
            val pcm = app.assets.open("pixel/rest/blanket-${part.asset}-sound.pcm").use { it.readBytes() }
            if (!canPlay(expected) || restSoundPart != part || pcm.isEmpty() || pcm.size % 4 != 0) return
            val samples = ShortArray(pcm.size / 2)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            val track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
            restSound = track
            if (track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) != samples.size ||
                !canPlay(expected) || restSoundPart != part ||
                (part != RestStopMotionView.Part.INTRO && track.setLoopPoints(0, samples.size / 2, -1) != AudioTrack.SUCCESS)) {
                stopRestSound()
                abandonIdleFocus()
                return
            }
            track.play()
        } catch (failure: IOException) {
            Log.w("DriveGrowthAudio", "Could not open rest effects", failure)
            stopRestSound()
            abandonIdleFocus()
        } catch (failure: RuntimeException) {
            Log.w("DriveGrowthAudio", "Could not play rest effects", failure)
            stopRestSound()
            abandonIdleFocus()
        }
    }

    private fun stopRestSound() {
        val previous = restSound
        restSound = null
        runCatching { previous?.release() }
    }

    private fun stopMusic() {
        val previous = music
        music = null
        musicReady = false
        musicScene = null
        musicRevision = null
        runCatching { previous?.release() }
    }

    private fun stopEffect() {
        releaseEffect?.let { handler?.removeCallbacks(it) }
        releaseEffect = null
        val previous = effect
        effect = null
        runCatching { previous?.pause() }
        runCatching { previous?.flush() }
        runCatching { previous?.release() }
    }

    private fun abandonIdleFocus() {
        focusState.releaseIfIdle(music == null && effect == null && restSound == null, ::abandonFocus)
    }

    private fun abandonFocus() {
        val previous = focus
        focus = null
        focusGain = 0
        focusState.reset()
        if (previous != null) runCatching { manager?.abandonAudioFocusRequest(previous) }
    }

    private fun stopPlayback() {
        stopEffect()
        stopRestSound()
        stopMusic()
        abandonFocus()
    }

    private enum class Wave { SINE, SQUARE, TRIANGLE, SAWTOOTH }

    private data class Note(
        val hertz: Int,
        val startMs: Int,
        val durationMs: Int,
        val volume: Double,
        val wave: Wave = Wave.SQUARE,
        val slide: Int = 0,
    )

    private fun notes(cue: Cue) = when (cue) {
        Cue.CLICK -> listOf(Note(880, 0, 50, .06))
        Cue.START -> listOf(Note(300, 0, 300, .1, Wave.SINE, slide = 900))
        Cue.FIRST_GRANT -> listOf(
            Note(1200, 0, 30, .07), Note(1200, 100, 30, .07), Note(1200, 200, 30, .07),
            Note(1047, 450, 80, .1), Note(1568, 530, 160, .1),
            Note(440, 780, 200, .1, Wave.SAWTOOTH), Note(554, 780, 200, .08, Wave.SAWTOOTH),
        ) + fanfare(1250)
        Cue.GACHA -> gachaNotes(Rarity.COMMON, opening = true)
        Cue.SETTLEMENT -> listOf(Note(880, 0, 50, .06), Note(1047, 650, 80, .1), Note(1568, 730, 160, .1)) +
            listOf(1760, 2200, 2640).mapIndexed { index, hz -> Note(hz, 1050 + index * 60, 140, .08, Wave.TRIANGLE) }
        Cue.DELIVERY_DEPARTURE -> listOf(
            Note(1400, 500, 280, .05, Wave.SAWTOOTH, slide = -1000),
            Note(110, 1000, 300, .18, Wave.SAWTOOTH, slide = -70),
            Note(150, 1120, 60, .12), Note(150, 1210, 60, .12), Note(150, 1300, 60, .12),
            Note(400, 1750, 120, .12, slide = 500),
            Note(120, 1900, 550, .12, Wave.SAWTOOTH, slide = 90),
        )
        Cue.DELIVERY_INCOME_ARRIVAL -> arrivalNotes() + listOf(
            Note(110, 1100, 300, .18, Wave.SAWTOOTH, slide = -70),
            Note(1047, 1700, 80, .1), Note(1568, 1780, 160, .1),
        )
        Cue.DELIVERY_VEHICLE_ARRIVAL -> arrivalNotes() + Note(400, 1400, 120, .12, slide = 500)
        Cue.DELIVERY_HELD -> arrivalNotes() + Note(880, 1450, 50, .06)
        Cue.EXPAND -> listOf(523, 659, 784, 1047, 1319).mapIndexed { i, hz -> Note(hz, i * 70, 160, .1) }
    }

    private fun gachaNotes(rarity: Rarity, opening: Boolean): List<Note> {
        if (!opening) return listOf(Note(110, 420, 300, .18, Wave.SAWTOOTH, slide = -70))
        val start = (gachaRevealStart(rarity) * 1000f).toInt()
        val openAt = if (rarity == Rarity.LEGEND) 3300 else 2900
        val revealAt = openAt + 550 - start
        val promotions = when (rarity) {
            Rarity.RARE -> listOf(Note(785, 1400 - start, 250, .12, Wave.TRIANGLE))
            Rarity.LEGEND -> listOf(Note(785, 1150 - start, 250, .12, Wave.TRIANGLE),
                Note(935, 1850 - start, 250, .12, Wave.TRIANGLE))
            else -> emptyList()
        }
        val reveal = if (rarity == Rarity.LEGEND) fanfare(revealAt) else listOf(1760, 2200, 2640).mapIndexed { i, hz ->
            Note(hz, revealAt + i * 60, 140, .08, Wave.TRIANGLE)
        }
        return promotions + Note(300, openAt - start, 300, .1, Wave.SINE, slide = 900) + reveal
    }

    /** 원본 WebAudio의 파형, 타이밍, 주파수 변화를 로컬 PCM으로 합성한다. */
    private fun pcm(notes: List<Note>): ShortArray {
        val mix = FloatArray(notes.maxOf { it.startMs + it.durationMs } * SAMPLE_RATE / 1_000)
        notes.forEach { note ->
            val start = note.startMs * SAMPLE_RATE / 1_000
            val length = note.durationMs * SAMPLE_RATE / 1_000
            repeat(length) { sample ->
                val t = sample.toDouble() / SAMPLE_RATE
                val progress = sample.toDouble() / length
                val cycles = (note.hertz + note.slide * progress / 2) * t
                val fraction = cycles - floor(cycles)
                val signal = when (note.wave) {
                    Wave.SINE -> sin(2 * PI * fraction)
                    Wave.SQUARE -> if (fraction < .5) 1.0 else -1.0
                    Wave.TRIANGLE -> 1 - 4 * abs(fraction - .5)
                    Wave.SAWTOOTH -> 2 * fraction - 1
                }
                val attack = (sample / 44.0).coerceAtMost(1.0)
                val gain = note.volume * (.001 / note.volume).pow(progress)
                mix[start + sample] += (signal * attack * gain).toFloat()
            }
        }
        return ShortArray(mix.size) { i -> (mix[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun arrivalNotes() = listOf(
        Note(1400, 300, 280, .05, Wave.SAWTOOTH, slide = -1000),
        Note(300, 660, 300, .1, Wave.SINE, slide = 900),
    )

    private fun fanfare(start: Int) = listOf(523, 659, 784, 1047).mapIndexed { i, hz ->
        Note(hz, start + i * 120, 320, .1)
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
