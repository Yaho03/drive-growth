package io.drivegrowth.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import io.drivegrowth.app.host.facility.*
import io.drivegrowth.app.R
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.VehicleRef

sealed interface FacilityControlsEvent {
    data class SetResting(val version:Int,val vehicle:VehicleRef,val expected:Boolean,val desired:Boolean):FacilityControlsEvent
    data object Retry:FacilityControlsEvent
    data object Acknowledge:FacilityControlsEvent
    data object CancelSaved:FacilityControlsEvent
}

/** 기존 ShopStyle로 조립하는 코드 전용 View. 명령 ID·저장·gate 권위는 호스트가 제공한다. */
@SuppressLint("ViewConstructor")
class FacilityControlsView(context:Context,private val onEvent:(FacilityControlsEvent)->Unit):LinearLayout(context) {
    private val style=ShopStyle(context)
    private var state=FacilityHostState()
    private var allowed=false
    init{orientation=VERTICAL}
    fun render(value:FacilityHostState,controlsEnabled:Boolean,names:Map<VehicleId,String>)=with(style) {
        state=value;allowed=controlsEnabled;removeAllViews()
        if(!allowed){addView(text("주차 중 조작이 허용되면 시설·영업 쉬기를 확인할 수 있어요."));return@with}
        addView(text("시설·영업 쉬기",24,bold=true))
        when {
            value.busy->addView(text("원래 요청을 확인하고 있어요."))
            value.foreign->addView(text("다른 기능에 보관된 요청을 먼저 확인해 주세요."))
            value.unsupported->addView(text("시설·영업 쉬기 저장 연결을 확인할 수 없어요."))
        }
        value.error?.let{addView(text("요청 결과를 확인하지 못했어요. 원래 요청으로 다시 확인할 수 있어요."))}
        val receipt=value.receipt
        if(receipt!=null) {
            val description=when(receipt) {
                is FacilityReceipt.Purchased->"시설 구매의 원래 정산을 확인했어요. 확인을 마치면 현재 가게를 다시 불러와요."
                is FacilityReceipt.Rested->"당시 영업 쉬기: ${if(receipt.before)"켜짐" else "꺼짐"} → ${if(receipt.after)"켜짐" else "꺼짐"}. 현재 설정은 확인을 마친 뒤 다시 불러와요."
            }
            addView(text(description).apply{tag="facility:receipt"})
            add(this@FacilityControlsView,button("결과 확인",!value.busy){
                if(allowed && !state.busy && state.receipt==receipt)onEvent(FacilityControlsEvent.Acknowledge)
            }.apply{tag="facility:ack"})
        } else if(value.pending!=null) {
            addView(text("보관한 원래 요청을 확인하고 있어요. 새 요청은 확인 후 사용할 수 있어요."))
            add(this@FacilityControlsView,button("미실행 요청 취소",!value.busy){
                if(allowed && !state.busy && state.pending==value.pending && state.receipt==null)onEvent(FacilityControlsEvent.CancelSaved)
            }.apply{tag="facility:cancel"})
        }
        val facts=value.facts
        if(facts!=null && value.pending==null && receipt==null && !value.foreign && !value.unsupported) {
            val rows=FacilityHostMapper.restingRows(facts)
            if(rows.isEmpty())addView(text("영업 쉬기를 설정할 보유 차량이 없어요."))
            for(row in rows) {
                val car=facts.vehicles.single{it.ownedVehicleId==row.vehicle.ownedVehicleId}
                val name=names[car.vehicleId]
                val card=card();add(this@FacilityControlsView,card)
                card.addView(text(name?:"차량 이름 확인 불가",22,bold=true))
                val rarity=context.getString(when(car.rarity) {
                    Rarity.COMMON->R.string.garage_rarity_common
                    Rarity.ADVANCED->R.string.garage_rarity_advanced
                    Rarity.RARE->R.string.garage_rarity_rare
                    Rarity.LEGEND->R.string.garage_rarity_legend
                })
                card.addView(text("$rarity · 게임 레벨 ${car.level} · ${row.description}"))
                card.addView(text(if(row.activity==null)"활동 상태 확인 불가" else when(row.activity) {
                    io.drivegrowth.core.progression.VehicleActivity.IDLE->"대기 중"
                    io.drivegrowth.core.progression.VehicleActivity.RENTED->"대여 중 · 쉬기는 반납 후 배정에 적용"
                    io.drivegrowth.core.progression.VehicleActivity.RETURNING->"반납 이동 중 · 쉬기는 반납 후 배정에 적용"
                    io.drivegrowth.core.progression.VehicleActivity.NEEDS_REPAIR->"수리 필요"
                }))
                add(card,button(if(row.resting)"영업 쉬기 끄기" else "영업 쉬기 켜기",!value.busy && value.error==null && row.activity!=null && name!=null){
                    if(allowed && state==value && !state.busy && state.error==null)
                        onEvent(FacilityControlsEvent.SetResting(value.version,row.vehicle,row.resting,!row.resting))
                }.apply{tag="facility:rest:${row.vehicle.ownedVehicleId.value}"})
            }
        }
        if(receipt==null)add(this@FacilityControlsView,button("다시 확인",!value.busy){
            if(allowed && !state.busy && state==value)onEvent(FacilityControlsEvent.Retry)
        }.apply{tag="facility:retry"})
    }
}
