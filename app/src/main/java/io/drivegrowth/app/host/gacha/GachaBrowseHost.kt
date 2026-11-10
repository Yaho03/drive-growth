package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*
import java.util.UUID
import java.util.concurrent.Executor

/** 실제 저장 조회만 연결한다. 전체 명령의 영속 checkpoint 공급 전에는 추첨을 허용하지 않는다. */
class GachaBrowseHost(
    private val source: GachaHostSource,
    private val worker: Executor,
    private val main: Executor,
) {
    private var listener: ((GachaFrame, Boolean) -> Unit)? = null
    private var enabled = false
    private var released = false
    private var workGeneration = 0L
    // 표시 frame과 작업 무효화 토큰은 별개다. 완료 표시가 자기 작업을 취소하지 않는다.
    private var frameGeneration = 0L
    private var inFlight = false
    private var requested = false
    private var frame = GachaFrame(0, GachaScreen.Loading)

    fun attach(value: (GachaFrame, Boolean) -> Unit) {
        detach(); listener = value; value(frame, false)
    }
    fun detach() { enabled = false; requested = false; workGeneration++; listener = null }
    fun release() { detach(); released = true }
    fun setControlsEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value; workGeneration++
        if (value) refresh() else { requested = false; listener?.invoke(frame, false) }
    }
    fun refresh() {
        if (released || !enabled) return
        workGeneration++; requested = true
        frame = GachaFrame(++frameGeneration, GachaScreen.Loading); listener?.invoke(frame, true)
        launch()
    }
    private fun launch() {
        if (!enabled || released || inFlight || !requested) return
        requested = false; inFlight = true
        val started = workGeneration
        worker.execute {
            val result = try { source.read(RequestId(UUID.randomUUID().toString()), RequestId(UUID.randomUUID().toString())) }
                catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
            main.execute {
                inFlight = false
                if (!released && enabled && workGeneration == started) {
                    val screen = when (result) {
                        is ContractResult.Failure -> if (result.error == ContractError.DATA_UNAVAILABLE)
                            GachaScreen.Missing("저장된 가챠 권위 상태가 아직 제공되지 않았어요", true)
                        else GachaScreen.Failure(result.error, "가챠 조회에 실패했어요 (${result.error})", true)
                        is ContractResult.Success -> {
                            val reason = "추첨 요청 보존 연결이 준비되지 않아 현재 추첨은 지원하지 않아요"
                            GachaScreen.Offers(result.value.copy(availability = GachaAvailability.UNSUPPORTED,
                                options = result.value.options.map { it.copy(availability = GachaAvailability.UNSUPPORTED, description = reason) }))
                        }
                    }
                    frame = GachaFrame(++frameGeneration, screen); listener?.invoke(frame, true)
                }
                launch()
            }
        }
    }
}
