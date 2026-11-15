package io.drivegrowth.app.ui.presentation

import android.content.ContextWrapper
import android.view.View

/** 일반 게임 조작에서만 호스트의 소리 설정과 조작 가능 상태를 사용한다. */
interface PrototypeButtonSoundHost {
    fun onGameButtonClick(button: View)
}

internal fun View.setGameButtonClick(available: () -> Boolean = { true }, action: () -> Unit) {
    val host = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<PrototypeButtonSoundHost>().firstOrNull()
    if (host != null) isSoundEffectsEnabled = false
    setOnClickListener {
        if (isEnabled && available()) {
            val playClick = host != null && isAttachedToWindow && isShown && hasWindowFocus()
            action()
            if (playClick) host?.onGameButtonClick(this)
        }
    }
}
