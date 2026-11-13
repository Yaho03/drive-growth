package io.drivegrowth.app.ui.presentation

/** 요청·포커스 콜백은 오디오 스레드에서 처리하고, UI에서는 효과음 접수 여부만 읽는다. */
internal class PrototypeAudioFocus {
    @Volatile var paused = false
        private set

    fun pause() { paused = true }
    fun reset() { paused = false }

    fun request(acquire: () -> Boolean) = !paused && acquire()

    fun releaseIfIdle(idle: Boolean, abandon: () -> Unit) {
        if (idle && !paused) abandon()
    }
}
