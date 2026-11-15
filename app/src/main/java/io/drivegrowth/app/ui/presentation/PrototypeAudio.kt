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
import io.drivegrowth.app.ui.presentation.PrototypeSound.*
import io.drivegrowth.app.ui.rest.RestStopMotionView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 게임 화면의 소리만 관리한다. 공개 메서드는 호스트의 UI 스레드에서 호출한다. */
internal class PrototypeAudio(context: Context) : AutoCloseable {
    enum class Scene(val fileName: String? = null) {
        TITLE("title.mp3"), REST("rest.mp3"), SHOP, GACHA, DELIVERY, SETTLE,
    }

    enum class Cue {
        CLICK, START, GACHA, SETTLEMENT, EXPAND,
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
    private var effectOwner: AtomicBoolean? = null
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

    fun play(cue: Cue) = queueEffect(onlyWhenIdle = cue == Cue.CLICK) {
        PrototypeSound.pcm(notes(cue), SAMPLE_RATE)
    }

    fun play(sound: PrototypeSound) = queueEffect(onlyWhenIdle = sound == CLICK) {
        PrototypeSound.pcm(sound.notes(), SAMPLE_RATE)
    }

    fun playGacha(rarity: Rarity, opening: Boolean) = queueEffect { PrototypeSound.pcm(gachaNotes(rarity, opening), SAMPLE_RATE) }

    fun playFirstGrant(): () -> Unit {
        val cancelled = AtomicBoolean()
        queueEffect(cancelled) { PrototypeSound.pcm(PrototypeSound.firstGrant(), SAMPLE_RATE) }
        return {
            if (cancelled.compareAndSet(false, true)) handler?.post {
                if (effectOwner === cancelled) {
                    stopEffect()
                    abandonIdleFocus()
                }
            }
        }
    }

    /** 같은 화면에서 건너뛰어도 대기 중인 효과음과 재생 중인 효과음을 함께 정리한다. */
    fun stopEffectPlayback() {
        effectRevision++
        handler?.post {
            stopEffect()
            abandonIdleFocus()
        }
    }

    private fun queueEffect(
        cancelled: AtomicBoolean? = null, onlyWhenIdle: Boolean = false, samples: () -> ShortArray,
    ) {
        val expected = revision
        val expectedFocus = focusLossRevision
        val expectedEffect = effectRevision
        fun canPlayEffect() = canPlay(expected) && focusLossRevision == expectedFocus &&
            effectRevision == expectedEffect && cancelled?.get() != true && !focusState.paused
        if (!canPlayEffect()) return
        audioHandler().post {
            if (canPlayEffect() && (!onlyWhenIdle || effect == null)) {
                playEffect(samples(), cancelled, ::canPlayEffect)
            }
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

    private fun playEffect(samples: ShortArray, owner: AtomicBoolean?, canPlayEffect: () -> Boolean) {
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
            effectOwner = owner
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
        effectOwner = null
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

    private fun notes(cue: Cue): List<PrototypeSound.Note> = when (cue) {
        Cue.CLICK -> CLICK.notes()
        Cue.START -> WHOOSH.notes()
        Cue.GACHA -> gachaNotes(Rarity.COMMON, opening = true)
        Cue.SETTLEMENT -> CLICK.notes() + COIN.notes(650) + SPARKLE.notes(1050)
        Cue.DELIVERY_DEPARTURE -> BRAKE.notes(500) + SHUTTER.notes(1000) +
            KNOCK.notes(1120) + POP.notes(1750) + DEPART.notes(1900)
        Cue.DELIVERY_INCOME_ARRIVAL -> arrivalNotes() + SHUTTER.notes(1100) + COIN.notes(1700)
        Cue.DELIVERY_VEHICLE_ARRIVAL -> arrivalNotes() + POP.notes(1400)
        Cue.DELIVERY_HELD -> arrivalNotes() + CLICK.notes(1450)
        Cue.EXPAND -> PrototypeSound.expansion()
    }

    private fun gachaNotes(rarity: Rarity, opening: Boolean): List<PrototypeSound.Note> {
        if (!opening) return SHUTTER.notes(420)
        val start = (gachaRevealStart(rarity) * 1000f).toInt()
        val openAt = if (rarity == Rarity.LEGEND) 3300 else 2900
        val revealAt = openAt + 550 - start
        val promotions = when (rarity) {
            Rarity.RARE -> CHIME.notes(1400 - start, step = 1)
            Rarity.LEGEND -> CHIME.notes(1150 - start, step = 1) + CHIME.notes(1850 - start, step = 2)
            else -> emptyList()
        }
        val revealSound = if (rarity == Rarity.LEGEND) FANFARE else SPARKLE
        return promotions + WHOOSH.notes(openAt - start) + revealSound.notes(revealAt)
    }

    private fun arrivalNotes() = BRAKE.notes(300) + WHOOSH.notes(660)

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
