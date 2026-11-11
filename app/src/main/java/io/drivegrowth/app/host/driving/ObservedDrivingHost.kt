package io.drivegrowth.app.host.driving

import io.drivegrowth.core.contract.ContractResult
import java.util.UUID
import java.util.concurrent.Executor

/** UI 입구만 세대로 차단한다. 이미 받은 관측/pause의 worker 영속 작업은 stop 뒤에도 순서대로 끝낸다. */
class ObservedDrivingHost(private val worker: Executor, private val main: Executor,
    private val clock: () -> RecordingClock,
    private val observe: (String, DrivingFrame, RecordingClock) -> ContractResult<DrivingRecordingResult>,
    private val pause: (String, RecordingClock) -> ContractResult<DrivingRecordingResult>,
    private val nextReceiver: () -> String = { UUID.randomUUID().toString() }) {
    private var receiver: String? = null
    private var listener: ((ContractResult<DrivingRecordingResult>) -> Unit)? = null
    fun start(listener: (ContractResult<DrivingRecordingResult>) -> Unit): String {
        check(receiver == null)
        receiver = nextReceiver().also { require(it.isNotBlank()) }
        this.listener = listener
        return checkNotNull(receiver) // UI 응답 상관용이며 boot/clock domain으로 쓰지 않는다.
    }
    fun accept(frame: DrivingFrame, completed: (() -> Unit)? = null) {
        val token = receiver ?: return
        worker.execute {
            val result = observe(token, frame, clock()) // 처리 지연을 원관측의 수신 시각으로 바꾸지 않는다.
            main.execute {
                if (receiver == token) listener?.invoke(result)
                completed?.invoke() // 시연 입력의 backpressure. 실제 관측 입력은 공급하지 않는다.
            }
        }
    }
    fun stop() {
        val token = receiver ?: return
        receiver = null
        listener = null
        val stoppedAt = clock()
        worker.execute { pause(token, stoppedAt) }
    }
}
