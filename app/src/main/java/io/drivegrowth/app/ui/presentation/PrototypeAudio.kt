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
import java.io.IOException
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** 게임 화면의 소리만 관리한다. 공개 메서드는 호스트의 UI 스레드에서 호출한다. */
internal class PrototypeAudio(context: Context) : AutoCloseable {
    enum class Scene(val fileName: String) {
        TITLE("title.mp3"), SHOP("shop.mp3"), GACHA("gacha.mp3"),
        DELIVERY("delivery.mp3"), SETTLE("settle.mp3"),
    }

    enum class Cue { START, FIRST_GRANT, GACHA, SETTLEMENT, EXPAND, DELIVERY_DEPARTURE, DELIVERY_HELD }

    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("presentation_audio", Context.MODE_PRIVATE)
    @Volatile private var permitted = false
    @Volatile private var closed = false
    @Volatile private var revision = 0L
    @Volatile private var focusLossRevision = 0L
    @Volatile private var scene = Scene.TITLE
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
    private var tracks: Set<String>? = null
    private var effect: AudioTrack? = null
    private var releaseEffect: Runnable? = null

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
        handler?.post { stopEffect(); abandonIdleFocus() }
        scheduleMusic()
    }

    fun play(cue: Cue) {
        val expected = revision
        val expectedFocus = focusLossRevision
        if (!canPlay(expected) || focusState.paused) return
        audioHandler().post {
            if (canPlay(expected) && focusLossRevision == expectedFocus) playEffect(cue, expected)
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
        audioHandler().post {
            if (canPlay(expected)) updateMusic(expected)
        }
    }

    private fun updateMusic(expected: Long) {
        val target = scene
        if (musicScene == target && musicRevision == expected) return
        stopMusic()
        val available = tracks ?: (runCatching { app.assets.list("bgm").orEmpty().toSet() }
            .getOrDefault(emptySet()).also { tracks = it })
        if (target.fileName !in available) {
            abandonIdleFocus()
            return
        }
        if (!canPlay(expected) || !requestFocus(AudioManager.AUDIOFOCUS_GAIN)) return
        if (!canPlay(expected) || scene != target) {
            abandonIdleFocus()
            return
        }
        try {
            val player = MediaPlayer()
            music = player
            musicScene = target
            musicRevision = expected
            player.setAudioAttributes(attributes)
            app.assets.openFd("bgm/${target.fileName}").use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.isLooping = true
            player.setOnPreparedListener {
                if (music !== player) return@setOnPreparedListener
                if (!canPlay(expected) || scene != target) {
                    stopMusic()
                    abandonIdleFocus()
                    return@setOnPreparedListener
                }
                musicReady = true
                applyMusicVolume()
                if (!focusState.paused) runCatching { player.start() }
            }
            player.setOnErrorListener { _, _, _ ->
                if (music === player) {
                    stopMusic()
                    abandonIdleFocus()
                }
                true
            }
            player.prepareAsync()
        } catch (_: IOException) {
            stopMusic()
            abandonIdleFocus()
        } catch (_: RuntimeException) {
            stopMusic()
            abandonIdleFocus()
        }
    }

    private fun playEffect(cue: Cue, expected: Long) {
        if (focusState.paused) return
        val samples = pcm(cue)
        if (!canPlay(expected)) return
        stopEffect()
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)) return
        try {
            val track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            effect = track
            val wrote = track.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
            if (wrote != samples.size || !canPlay(expected)) {
                stopEffect()
                abandonIdleFocus()
                return
            }
            track.play()
            releaseEffect = Runnable {
                stopEffect()
                abandonIdleFocus()
            }.also { handler?.postDelayed(it, samples.size * 1_000L / SAMPLE_RATE + 80L) }
        } catch (_: RuntimeException) {
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
                if (musicReady) runCatching { music?.pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!canPlay()) {
                    stopPlayback()
                    return
                }
                focusState.reset()
                if (musicScene == scene && musicRevision == revision) {
                    applyMusicVolume()
                    if (musicReady) runCatching { music?.start() }
                } else {
                    updateMusic(revision)
                }
            }
        }
    }

    private fun applyMusicVolume() {
        runCatching { music?.setVolume(.3f, .3f) }
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
        focusState.releaseIfIdle(music == null && effect == null, ::abandonFocus)
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
        stopMusic()
        abandonFocus()
    }

    private data class Note(
        val hertz: Int,
        val startMs: Int,
        val durationMs: Int,
        val square: Boolean = false,
        val slide: Int = 0,
    )

    /** HTML의 효과음 주파수와 길이를 짧은 로컬 PCM으로 합성한다. */
    private fun pcm(cue: Cue): ShortArray {
        val notes = when (cue) {
            Cue.START -> listOf(Note(300, 0, 300, slide = 900))
            Cue.FIRST_GRANT -> listOf(1760, 2200, 2640).mapIndexed { i, hz -> Note(hz, i * 60, 140) }
            Cue.GACHA -> listOf(523, 659, 784, 1047).mapIndexed { i, hz -> Note(hz, i * 120, 260, square = true) }
            Cue.SETTLEMENT -> listOf(Note(1047, 0, 80, square = true), Note(1568, 80, 160, square = true))
            Cue.DELIVERY_DEPARTURE -> listOf(Note(145, 0, 90), Note(170, 85, 260, slide = 330))
            Cue.DELIVERY_HELD -> listOf(Note(440, 0, 100), Note(330, 110, 150))
            Cue.EXPAND -> listOf(523, 659, 784, 1047, 1319).mapIndexed { i, hz -> Note(hz, i * 70, 160, square = true) }
        }
        val mix = FloatArray(notes.maxOf { it.startMs + it.durationMs } * SAMPLE_RATE / 1_000)
        notes.forEach { note ->
            val start = note.startMs * SAMPLE_RATE / 1_000
            val length = note.durationMs * SAMPLE_RATE / 1_000
            repeat(length) { sample ->
                val t = sample.toDouble() / SAMPLE_RATE
                val progress = sample.toDouble() / length
                val wave = sin(2 * PI * (note.hertz + note.slide * progress / 2) * t)
                val signal = if (note.square) { if (wave < 0) -1.0 else 1.0 } else wave
                val attack = (sample / 44.0).coerceAtMost(1.0)
                mix[start + sample] += (signal * attack * .01.pow(progress) * .12).toFloat()
            }
        }
        return ShortArray(mix.size) { i -> (mix[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
