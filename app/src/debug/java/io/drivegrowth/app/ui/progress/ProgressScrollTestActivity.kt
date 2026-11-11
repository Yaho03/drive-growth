package io.drivegrowth.app.ui.progress

import android.app.Activity
import android.os.Bundle

/** 고정 진행 화면을 실제 창에 배치하는 debug 시험 호스트. */
class ProgressScrollTestActivity : Activity() {
    lateinit var progress: ProgressView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = ProgressView(this) {}
        setContentView(progress)
        progress.render(ProgressPreviewStates.many, true)
    }
}
