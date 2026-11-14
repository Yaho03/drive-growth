package io.drivegrowth.app.host.garage

import android.animation.ValueAnimator
import android.content.Context
import android.widget.LinearLayout
import io.drivegrowth.app.ui.garage.GarageCareMotion
import io.drivegrowth.app.ui.garage.GarageCareOutcome
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.garage.VehicleArtwork
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.presentation.GarageCareMotionView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleAction

/** 준비된 core 값만 표시한다. 계산/저장/gate 판정은 하지 않는다. */
class GarageCommandView(context: Context, private val confirm: () -> Unit, private val cancel: () -> Unit,
    private val retry: () -> Unit, private val cancelPending: () -> Unit, private val choose: (VehicleRef) -> Unit, private val done: () -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true)
    private val homeStyle = MapPanelStyle(context)
    private val featureStyle = MapPanelStyle(context, featureWindow = true)
    private var featureWindow = false
    private val artwork = VehicleArtwork(context, style)
    private val careMotion = GarageCareMotion()
    private val careScene = GarageCareMotionView(context)
    private var renderedState: GarageCommandState? = null
    private var controlsEnabled = false
    private var startMotion: Runnable? = null
    private var generation = 0L
    init { orientation = VERTICAL; setBackgroundColor(0xffffedbd.toInt()) }

    private fun canPlayCareMotion() = controlsEnabled && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && hasWindowFocus() && ValueAnimator.areAnimatorsEnabled()

    internal fun dismissMotion() {
        careMotion.clear()
        stopScene()
    }

    private fun stopScene() {
        startMotion?.let(::removeCallbacks)
        startMotion = null
        careScene.stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) handler?.post {
            if (!isAttachedToWindow || windowVisibility != VISIBLE) dismissMotion()
        }
    }

    override fun onDetachedFromWindow() {
        // HOME의 같은 프레임 안에서 패널을 다시 붙이는 경우가 있다.
        handler?.post { if (!isAttachedToWindow) dismissMotion() }
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) dismissMotion()
    }

    fun render(state: GarageCommandState, enabled: Boolean) = render(state, enabled, featureWindow = false)

    internal fun render(state: GarageCommandState, enabled: Boolean, featureWindow: Boolean) = with(style) {
        val themeChanged = this@GarageCommandView.featureWindow != featureWindow
        if (state == renderedState && enabled == controlsEnabled && !themeChanged) return@with
        if (themeChanged) {
            this@GarageCommandView.featureWindow = featureWindow
            setBackgroundColor(if (featureWindow) CleanUi.CARD_SOFT else 0xffffedbd.toInt())
        }
        val mapStyle = if (featureWindow) featureStyle else homeStyle
        renderedState = state
        controlsEnabled = enabled
        val effect = careMotion.observe(state, canPlayCareMotion())
        stopScene()
        fun text(label: String, size: Int = 16, bold: Boolean = false) =
            style.text(label, size, mapStyle.ink, bold)
        generation++; val token = generation
        removeAllViews(); setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!enabled) {
            addView(text("주차 후 조작이 허용되면 이용할 수 있습니다."))
            return@with
        }
        val content = column(); addView(scroll(content), LayoutParams(-1,-1))
        content.addView(text("차량 관리", 30, bold = true))
        fun action(label: String, ready: Boolean = true, primary: Boolean = false, callback: () -> Unit) {
            add(content, mapStyle.styleButton(button(label, enabled = ready, primary = primary) {
                if (token == generation) callback()
            }, primary))
        }
        fun pending(p: GaragePending, showArtwork: Boolean = true) {
            val details = column().apply {
                background = mapStyle.chip()
                setPadding(dp(12), dp(10), dp(12), dp(12))
            }
            add(content, details)
            val title = p.name ?: if (p.command is GarageCommand.Material) "보관한 강화 대상 차량" else p.request.id.value
            details.addView(text(title, 24, bold = true))
            p.vehicle?.let {
                if (showArtwork) details.addView(artwork.view(it.vehicleId, it.rarity, 120, showRing = false).apply {
                    background = null
                }, LayoutParams(-1, dp(120)))
                details.addView(text(rarity(it.rarity), 16, bold = true))
                details.addView(text("조회 당시 Lv.${it.level} · 진행 ${it.levelProgressPercent}% · 실제 취득액 ${it.acquisitionCost.units} 게임머니"))
            }
            details.addView(text(p.balance?.let { "조회 잔액 ${it.units} 게임머니" } ?: "실행 결과에서 당시 잔액을 확인합니다"))
            when(val command = p.command) {
                is GarageCommand.Single -> {
                    val write = command.value.vehicle as VehicleWrite.Update
                    details.addView(text("명령에 저장된 변경 · ${when(write.lifecycle) { VehicleLifecycle.OWNED -> "보유"; VehicleLifecycle.SOLD -> "판매"; VehicleLifecycle.SCRAPPED -> "폐차"; VehicleLifecycle.CONSUMED -> "재료 소비" }} · Lv.${write.level} · 진행 ${write.levelProgressPercent}%"))
                    details.addView(text(command.value.money?.let {
                        "${if(it.direction == MoneyDirection.DEBIT) "차감" else "지급"} ${it.amount.units} 게임머니"
                    } ?: "게임머니 변경 없음"))
                }
                is GarageCommand.Material -> {
                    details.addView(text("소비 대상 · ${p.materialName ?: "보관한 재료 차량"}"))
                    details.addView(text("대상 Lv.${command.value.target.level} · 진행 ${command.value.target.levelProgressPercent}%"))
                    details.addView(text("비용 ${command.value.cost.units} 게임머니"))
                }
                is GarageCommand.Repair -> {
                    details.addView(text("수리비 ${command.value.cost.units} 게임머니 · 확정 사고 ${command.value.expectedAccidentCount}회"))
                    details.addView(text("수리 성공 시 활동을 대기로 회복하며 사고 이력과 실제 취득액은 보존합니다"))
                }
            }
            details.addView(style.text("확인한 비용과 대상은 같은 요청으로 처리합니다", 15, mapStyle.muted))
        }
        when(state) {
            GarageCommandState.Idle -> Unit
            GarageCommandState.Loading -> content.addView(text("저장된 상태와 명령을 확인하고 있습니다"))
            is GarageCommandState.Materials -> {
                content.addView(text("같은 차종의 다른 보유·대기 차량을 선택해 주세요"))
                if(state.value.candidates.isEmpty()) content.addView(text("사용할 수 있는 재료 차량이 없습니다"))
                state.value.candidates.forEach { car ->
                    val name = state.value.candidateNames.getValue(car.ownedVehicleId)
                    action("$name · ${car.rarity} · Lv.${car.level}") { choose(VehicleRef(car.ownedVehicleId,car.revision)) }
                }
                action("취소", callback = cancel)
            }
            is GarageCommandState.Review -> {
                pending(state.pending)
                content.addView(text(if(state.recovering) "보관한 요청입니다. 미실행이면 표시된 원래 명령을 처리하고, 이미 실행됐으면 당시 결과를 확인합니다" else "아직 실행하지 않았습니다. 확인하면 보관한 이 명령만 확정합니다"))
                if(!state.canConfirm) content.addView(text("차량 관리는 준비 중입니다. 지금은 비용과 조건만 확인할 수 있습니다"))
                action(if (state.recovering) "보관 요청 처리 · 결과 확인" else "확정", state.canConfirm, primary = true) {
                    careMotion.arm(state, canPlayCareMotion())
                    confirm()
                }
                action("취소 / 화면 닫기", callback = cancel)
            }
            is GarageCommandState.Running -> { content.addView(text("같은 요청을 보존해 처리 중입니다", bold = true)); pending(state.pending) }
            is GarageCommandState.Confirmed -> {
                val outcome = effect?.outcome ?: GarageCareOutcome.from(state)
                outcome?.let {
                    val message = when (it.action) {
                        VehicleAction.LEVEL_UP -> "레벨업 완료 · Lv.${it.vehicle.level} · 진행 ${it.vehicle.levelProgressPercent}%"
                        VehicleAction.USE_MATERIAL -> "재료 강화 완료 · Lv.${it.vehicle.level} · 진행 ${it.vehicle.levelProgressPercent}%"
                        VehicleAction.REPAIR -> "수리 완료 · 대기"
                        VehicleAction.SELL -> "판매 완료"
                        VehicleAction.SCRAP -> "폐차 완료"
                    }
                    content.addView(text(message, 20, bold = true).apply {
                        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                    })
                    if (it.action == VehicleAction.SELL || it.action == VehicleAction.SCRAP) {
                        it.credit?.let { payment -> content.addView(text("확정 지급액 ${payment.units} 게임머니")) }
                        content.addView(text("도감에 보유 기록이 남아요"))
                    }
                }
                if (effect != null) {
                    (careScene.parent as? LinearLayout)?.removeView(careScene)
                    content.addView(careScene, LayoutParams(-1, dp(76)))
                    val start = Runnable {
                        startMotion = null
                        if (generation == token) careScene.play(effect, ::canPlayCareMotion)
                    }
                    startMotion = start
                    postOnAnimation(start)
                }
                val balance = when(val receipt = state.receipt) {
                    is GarageReceipt.Single -> receipt.value.balance
                    is GarageReceipt.Material -> receipt.value.balance
                    is GarageReceipt.Repair -> receipt.value.balance
                }
                content.addView(text("당시 확정 잔액 ${balance.units} 게임머니", 24, bold = true))
                action("결과 확인", primary = true) { dismissMotion(); done() }
                pending(state.pending, showArtwork = false)
                content.addView(text("이 요청의 확정 결과를 확인했습니다. 현재 차고와 잔액은 다시 조회합니다"))
            }
            is GarageCommandState.Rejected -> {
                content.addView(text("원장이 미실행 요청의 취소를 확인했습니다 (${state.error}). 최신 차량 상태로 다시 선택할 수 있습니다"))
                action("차고로", callback = cancel)
            }
            is GarageCommandState.Failure -> {
                content.addView(text(when(state.error) {
                    ContractError.INSUFFICIENT_FUNDS -> "게임머니가 부족합니다"
                    ContractError.CAPACITY_EXCEEDED -> "차고 공간이 부족합니다"
                    ContractError.STATE_CONFLICT -> "확인한 차량 상태 또는 요청이 달라졌습니다"
                    ContractError.INVALID_INPUT -> "레벨·재료·판매 조건에 맞지 않는 요청입니다"
                    ContractError.DATA_UNAVAILABLE -> "필요한 저장 정보 또는 기능을 확인할 수 없습니다"
                    ContractError.UX_RESTRICTED -> "현재 차량 관리 조작이 제한됩니다"
                    else -> "처리 결과를 확인하지 못했습니다 (${state.error})"
                }))
                state.cancelError?.let { content.addView(text("미실행 취소를 확인하지 못해 원래 명령을 보존합니다 ($it)")) }
                state.pending?.let {
                    pending(it); action("보관한 같은 명령으로 결과 확인", callback = retry)
                    action("저장소에서 미실행 확인 후 요청 취소", callback = cancelPending)
                }
                action("화면 닫기 (보관 요청 유지)", callback = cancel)
            }
        }
    }
}
