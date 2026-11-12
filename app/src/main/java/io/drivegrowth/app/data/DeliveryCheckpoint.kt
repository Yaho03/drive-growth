package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.json.JSONObject
import java.util.Collections

/** C의 호스트 입력을 기존 core 타입으로 보존한다. SDK/후보 생성/경제 계산은 하지 않는다. */
sealed interface DeliveryCheckpointOperation {
    val requestId:RequestId
    data class Select(val proposed:DeliveryProposed,val offerId:DeliveryOfferId,override val requestId:RequestId,val route:RouteId,val at:EpochMillis):DeliveryCheckpointOperation
    data class Change(val rules:DeliveryChangeRules,val offer:DeliveryChangeOffer,val offerId:DeliveryOfferId,val route:RouteId,val observedRoute:RouteId?=null):DeliveryCheckpointOperation {override val requestId get()=offer.requestId}
    data class Abandon(val rules:DeliveryChangeRules,override val requestId:RequestId):DeliveryCheckpointOperation
    data class Arrival(val rules:DeliverySettlementRules,override val requestId:RequestId,val evidence:Observation<DeliveryArrivalEvidence>,val now:EpochMillis,val expected:DeliveryList,val replacement:DeliveryList):DeliveryCheckpointOperation
    data class Hold(val rules:DeliverySettlementRules,override val requestId:RequestId,val now:EpochMillis):DeliveryCheckpointOperation
}
sealed interface DeliveryCheckpointReceipt {
    data class Selected(val value:DeliveryCommit):DeliveryCheckpointReceipt
    data class Changed(val value:ChangeCommit):DeliveryCheckpointReceipt
    data class Abandoned(val value:AbandonCommit):DeliveryCheckpointReceipt
    data class Arrived(val value:ArrivalCommit):DeliveryCheckpointReceipt
    data class Held(val value:HoldCommit):DeliveryCheckpointReceipt
}
/** 비확정은 지급/완료가 아니다. checkpoint는 SAVED로 복귀하므로 명시 취소 후 새 증거로 준비할 수 있다. */
val DeliveryCheckpointReceipt.isFinal:Boolean get()=when(this) {
    is DeliveryCheckpointReceipt.Arrived->value.outcome !is DeliveryOutcome.AwaitingEvidence
    is DeliveryCheckpointReceipt.Held->value.outcome !is HoldOutcome.StillHeld
    else->true
}

internal object DeliveryCheckpointJson {
    fun encode(c:HostCheckpointCommand.Delivery):JSONObject {
        val op=c.operation
        val value=when(op) {
            is DeliveryCheckpointOperation.Select->DeliveryJson.command(op.requestId,op.offerId,op.proposed).put("route",op.route.value).put("at",op.at.value)
            is DeliveryCheckpointOperation.Change->DeliveryJson.changeCommand(op.rules,op.offer,op.offerId,op.route)
                .apply { op.observedRoute?.let { put("observedRoute", it.value) } }
            is DeliveryCheckpointOperation.Abandon->DeliveryJson.abandonCommand(op.requestId,op.rules)
            is DeliveryCheckpointOperation.Arrival->DeliveryJson.arrivalCommand(op.requestId,op.rules,op.evidence,op.now).put("expected",DeliveryJson.list(op.expected)).put("replacement",DeliveryJson.list(op.replacement))
            is DeliveryCheckpointOperation.Hold->DeliveryJson.holdCommand(op.requestId,op.rules,op.now)
        }
        val kind=when(op){is DeliveryCheckpointOperation.Select->"select";is DeliveryCheckpointOperation.Change->"change";is DeliveryCheckpointOperation.Abandon->"abandon";is DeliveryCheckpointOperation.Arrival->"arrival";is DeliveryCheckpointOperation.Hold->"hold"}
        return JSONObject().put("type","delivery-host-v1").put("content",c.contentVersion.value).put("source",c.source.name).put("kind",kind).put("value",value)
    }
    private fun list(j:JSONObject):DeliveryList=DeliveryJson.list(j).let{it.copy(offers=Collections.unmodifiableList(ArrayList(it.offers)))}
    private fun proposed(j:JSONObject):DeliveryProposed=DeliveryJson.proposed(j).let{it.copy(list=list(j.getJSONObject("list")),offers=Collections.unmodifiableList(ArrayList(it.offers)))}
    fun decode(j:JSONObject):HostCheckpointCommand.Delivery {
        require(j.getString("type")=="delivery-host-v1")
        val v=j.getJSONObject("value")
        val op=when(j.getString("kind")) {
            "select"->DeliveryCheckpointOperation.Select(proposed(v.getJSONObject("proposed")),DeliveryOfferId(v.getString("offer")),RequestId(v.getString("request")),RouteId(v.getString("route")),EpochMillis(DeliveryJson.long(v,"at",0,Long.MAX_VALUE)))
            "change"->DeliveryJson.changeInput(v).let{DeliveryCheckpointOperation.Change(it.rules,it.offer.copy(proposed=proposed(v.getJSONObject("proposed"))),it.offerId,it.route,
                if (v.has("observedRoute")) RouteId(v.getString("observedRoute")) else null)}
            "abandon"->DeliveryJson.abandonInput(v).let{DeliveryCheckpointOperation.Abandon(it.second,it.first)}
            "arrival"->DeliveryJson.arrivalInput(v).let{DeliveryCheckpointOperation.Arrival(it.rules,it.requestId,it.evidence,it.now,list(v.getJSONObject("expected")),list(v.getJSONObject("replacement")))}
            "hold"->DeliveryJson.holdInput(v).let{DeliveryCheckpointOperation.Hold(it.rules,it.requestId,it.now)}
            else->throw org.json.JSONException("미지원 탁송 checkpoint 종류")
        }
        return HostCheckpointCommand.Delivery(ContentVersion(j.getString("content")),InputSource.valueOf(j.getString("source")),op)
    }
}

/** 기존 기능 이력에 전체 호스트 입력을 연결한다. 별도 원장·저장 파일은 만들지 않는다. */
internal object DeliveryCheckpointStorage {
    private val tables=listOf("delivery_requests","delivery_changes","delivery_outcomes","delivery_hold_outcomes")
    fun addColumns(db:SQLiteDatabase){tables.forEach{db.execSQL("ALTER TABLE $it ADD COLUMN host_command TEXT")}}
    private fun table(op:DeliveryCheckpointOperation)=when(op){is DeliveryCheckpointOperation.Select->tables[0];is DeliveryCheckpointOperation.Change->tables[1];is DeliveryCheckpointOperation.Abandon,is DeliveryCheckpointOperation.Arrival->tables[2];is DeliveryCheckpointOperation.Hold->tables[3]}
    private fun stored(db:SQLiteDatabase,c:HostCheckpointCommand.Delivery):Pair<Boolean,String?> = db.rawQuery("SELECT host_command FROM ${table(c.operation)} WHERE request_id=?",arrayOf(c.operation.requestId.value)).use {
        if(!it.moveToFirst())false to null else true to if(it.isNull(0))null else it.text(0)
    }
    fun hasOriginal(db:SQLiteDatabase,c:HostCheckpointCommand.Delivery):Boolean {
        val (exists,value)=stored(db,c)
        if(!exists)return false
        if(value==null) return false // 구버전 기능 이력을 새 host 입력으로 보충하지 않는다.
        if(value!=DeliveryCheckpointJson.encode(c).toString())throw SQLiteException("탁송 호스트 원명령 불일치")
        return true
    }
    fun canCancel(db:SQLiteDatabase,c:HostCheckpointCommand.Delivery):Boolean = !stored(db,c).first
    fun canPrepare(db:SQLiteDatabase,c:HostCheckpointCommand.Delivery):Boolean {
        val (exists,value)=stored(db,c)
        return !exists || value==DeliveryCheckpointJson.encode(c).toString()
    }
    fun commit(store:SqliteGameChangeStore,db:SQLiteDatabase,c:HostCheckpointCommand.Delivery):ContractResult<DeliveryCheckpointReceipt> {
        val original=hasOriginal(db,c)
        if(!original&&!canPrepare(db,c))return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val op=c.operation
        if (!original && op is DeliveryCheckpointOperation.Change &&
            (store.source == InputSource.VEHICLE || op.observedRoute != null)) {
            if (store.source != InputSource.VEHICLE || op.observedRoute == null)
                return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val current = store.delivery.inProgress(db) ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            if (current.active.requestId != op.requestId || current.route != op.observedRoute)
                return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val identity = GameIdentity(actual(store.localProfileId()), store.source)
            when (val verified = DeliveryObservedDistance.verifyChange(db, identity, current, op.offer.traveledMeters, store.currentDeliveryDistanceSession())) {
                is ContractResult.Failure -> return verified
                is ContractResult.Success -> Unit
            }
        }
        var replaced=false
        val result:ContractResult<DeliveryCheckpointReceipt> = when(op) {
            is DeliveryCheckpointOperation.Select->when(val selected=store.delivery.select(op.proposed,op.offerId,op.requestId)) {
                is ContractResult.Failure->selected
                is ContractResult.Success->{
                    if(!original) when(val route=store.delivery.beginRoute(op.requestId,op.route,op.at)) {
                        is ContractResult.Failure->return route
                        is ContractResult.Success->if(route.value.route!=op.route||route.value.startedAt!=op.at)throw SQLiteException("탁송 최초 경로 쓰기 불일치")
                    }
                    ContractResult.Success(DeliveryCheckpointReceipt.Selected(selected.value))
                }
            }
            is DeliveryCheckpointOperation.Change->store.delivery.changeDestination(op.rules,op.offer,op.offerId,op.route).map{DeliveryCheckpointReceipt.Changed(it)}
            is DeliveryCheckpointOperation.Abandon->store.delivery.abandon(op.rules,op.requestId).map{DeliveryCheckpointReceipt.Abandoned(it)}
            is DeliveryCheckpointOperation.Arrival->store.delivery.settleArrival(op.rules,op.requestId,op.evidence,op.now) { expected,_->
                if(expected!=op.expected)ContractResult.Failure(ContractError.STATE_CONFLICT) else {replaced=true;ContractResult.Success(op.replacement)}
            }.map{DeliveryCheckpointReceipt.Arrived(it)}
            is DeliveryCheckpointOperation.Hold->store.delivery.resolveHold(op.rules,op.requestId,op.now).map{DeliveryCheckpointReceipt.Held(it)}
        }
        if(result is ContractResult.Success&&result.value.isFinal&&!original) {
            val receipt=when(val value=result.value) {
                is DeliveryCheckpointReceipt.Selected->value.value.settlement
                is DeliveryCheckpointReceipt.Changed->value.value.settlement
                is DeliveryCheckpointReceipt.Abandoned->value.value.settlement
                is DeliveryCheckpointReceipt.Arrived->value.value.settlement
                is DeliveryCheckpointReceipt.Held->value.value.settlement
            }
            if(receipt!=null && (store.readBalance(db)!=receipt.balance || receipt.changedVehicle?.let{store.findVehicle(db,it.ownedVehicleId)!=it}==true))
                throw SQLiteException("탁송 실제 재화/개체 쓰기 불일치")
            when(op) {
                is DeliveryCheckpointOperation.Select->if(actual(store.delivery.inProgress())!=DeliveryInProgress((result.value as DeliveryCheckpointReceipt.Selected).value.selected.active,op.route,op.at))throw SQLiteException("탁송 실제 최초 경로 불일치")
                is DeliveryCheckpointOperation.Change->if(actual(store.delivery.inProgress())?.route!=op.route)throw SQLiteException("탁송 실제 변경 경로 불일치")
                is DeliveryCheckpointOperation.Abandon,is DeliveryCheckpointOperation.Arrival->if(actual(store.delivery.active())!=null)throw SQLiteException("탁송 활성 종료 쓰기 불일치")
                is DeliveryCheckpointOperation.Hold->if(actual(store.delivery.held()).any{it.requestId==op.requestId})throw SQLiteException("탁송 보류 실제 제거 불일치")
            }
            if (store.source == InputSource.VEHICLE &&
                (op is DeliveryCheckpointOperation.Select || op is DeliveryCheckpointOperation.Change)) {
                val current = store.delivery.inProgress(db) ?: throw SQLiteException("거리 관측에 연결할 탁송 경로 없음")
                val identity = GameIdentity(actual(store.localProfileId()), store.source)
                val commitAt = store.now()
                val at = if (op is DeliveryCheckpointOperation.Select && op.at.value > commitAt.value) op.at else commitAt
                when (val armed = DeliveryObservedDistance.arm(db, identity, current, at, store.currentDeliveryDistanceSession())) {
                    is ContractResult.Failure -> return armed
                    is ContractResult.Success -> Unit
                }
            }
            val arrived=(result.value as? DeliveryCheckpointReceipt.Arrived)?.value?.outcome
            if(arrived is DeliveryOutcome.VehicleHeld && actual(store.delivery.held()).singleOrNull{it.requestId==op.requestId}!=arrived.held)
                throw SQLiteException("탁송 보류 실제 생성 불일치")
            if(replaced && op is DeliveryCheckpointOperation.Arrival && actual(store.delivery.lists(op.replacement.createdOn)).contains(op.replacement)!=true)
                throw SQLiteException("탁송 실제 교체 목록 불일치")
            val encoded=DeliveryCheckpointJson.encode(c).toString()
            if(db.update(table(op),ContentValues().apply{put("host_command",encoded)},"request_id=? AND host_command IS NULL",arrayOf(op.requestId.value))!=1)
                throw SQLiteException("탁송 원명령 metadata 쓰기 누락")
            if(!hasOriginal(db,c))throw SQLiteException("탁송 원명령 metadata 확인 실패")
        }
        return result
    }
    private fun <T> actual(r:ContractResult<T>):T=when(r){is ContractResult.Success->r.value;is ContractResult.Failure->throw SQLiteException("탁송 실제 쓰기 조회 실패: ${r.error}")}
    private fun <T,R> ContractResult<T>.map(f:(T)->R):ContractResult<R> = when(this){is ContractResult.Failure->this;is ContractResult.Success->ContractResult.Success(f(value))}
}
