package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.ranking.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOutcome
import io.drivegrowth.core.garage.Garage
import org.json.JSONObject
import org.json.JSONException
import java.time.LocalDate
import java.util.Collections

/**
 * worker 전용 읽기. 호출자가 이미 확인한 같은 프로필의 기존 store와 실제 콘텐츠를 공급한다.
 * 새 DB/초기화/재화 쓰기/네트워크를 만들지 않는다. 미열람 목록 대신 확정 이력 전체를 읽는다.
 */
class RankingReadSource(private val store: SqliteGameChangeStore, private val source: InputSource,
    content: GameContentSnapshot, definitions: RankingDefinitions = RankingDefinitions.acceptedLocal(),
    /** 현재 같은 DB의 실제 퀘스트 claim 원장이 검증한 지급 요청 ID. 없으면 미분류 입금을 추측하지 않는다. */
    private val questCauses: ((SQLiteDatabase) -> ContractResult<Set<RequestId>>)? = null,
    /** 기존 LocalDate 키의 실제 출발 당시 시간대 근거. 현재 기기 시간대로 대신하지 않는다. */
    private val departureZone: ((String) -> Observation<java.time.ZoneId>)? = null) {
    private val content=content.copy(catalog=content.catalog.copy(vehicles=Collections.unmodifiableList(ArrayList(content.catalog.vehicles))),
        vehicles=Collections.unmodifiableList(ArrayList(content.vehicles)))
    private val definitions = definitions.frozen()

    fun read(asOf: EpochMillis): ContractResult<RankingSnapshot> = store.atomic { db ->
        if (store.source != source || asOf.value < 0) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val values = linkedMapOf<RankingMetric,RankingValue>()
        val history = store.listVehicleHistory()
        fun historyValue(read: (List<OwnedVehicle>) -> RankingValue): RankingValue = when(history) {
            is ContractResult.Failure -> RankingValue.Failure(history.error)
            is ContractResult.Success -> when(val checked=Garage(content.catalog).owned(history.value)) {
                is ContractResult.Failure -> RankingValue.Failure(checked.error)
                is ContractResult.Success -> read(history.value)
            }
        }
        values[RankingMetric.SHOP_LEVEL] = metric {
            val shop=required(store.rental.shop())
            val level=db.rawQuery("SELECT level FROM shop WHERE id=1",null).use {
                if(!it.moveToFirst()) throw SQLiteException("가게 상태 누락")
                it.integer(0,1,Int.MAX_VALUE.toLong())
            }
            if(level.toInt()!=shop.progress.shopLevel) throw SQLiteException("가게 레벨 불일치")
            RankingValue.Available(level.toBigDecimal(),"현재 같은 원장의 가게 레벨")
        }
        values[RankingMetric.OWNED] = historyValue { RankingAggregation.owned(it) }
        values[RankingMetric.HIGHEST_VALUE] = historyValue { RankingAggregation.highest(it,content.vehicles.associate { c->c.vehicleId to c.price }) }
        values[RankingMetric.COLLECTION] = if(definitions.collection==null) RankingAggregation.collection(emptyList(),null)
            else metric { RankingAggregation.collection(required(Garage(content.catalog).collection(store)),definitions.collection) }
        // 선택한 정의에 필요한 기록만 읽는다. 정책 부재를 정상 0으로 바꾸지 않는다.
        val needDrives=definitions.week!=null || definitions.income?.included?.contains(RankingIncomeOrigin.DRIVING)==true
        val receipts by lazy { allReceipts(db) }
        val drives by lazy { driving(db,receipts) }
        values[RankingMetric.EARNED] = if(definitions.income==null) RankingAggregation.income(emptyList(),null,source)
            else metric {
                val rows=mutableListOf<RankingIncome>()
                val included=definitions.income!!.included
                if(RankingIncomeOrigin.DRIVING in included) rows+=drives.income
                if(RankingIncomeOrigin.RENTAL in included) rows+=rental(db,receipts)
                if(RankingIncomeOrigin.DELIVERY_INCOME in included) rows+=delivery(db,receipts)
                if(RankingIncomeOrigin.DISPOSAL in included || RankingIncomeOrigin.QUEST in included) rows+=disposalAndCheckUnclassified(db,receipts)
                RankingAggregation.income(rows,definitions.income,source)
            }
        values[RankingMetric.WEEKLY_DISTANCE] = if(definitions.week==null) RankingAggregation.week(emptyList(),null,source,asOf)
            else metric { RankingAggregation.week(drives.days,definitions.week,source,asOf) }
        ContractResult.Success(RankingSnapshot(source,asOf,content.catalog.contentVersion,content.ruleVersion,
            Collections.unmodifiableMap(values),definitions))
    }

    private fun allReceipts(db: SQLiteDatabase): Map<RequestId,Settlement> = db.rawQuery(
        "SELECT request_id,settlement_id,result FROM settlements",null).use { rows ->
        val result=linkedMapOf<RequestId,Settlement>()
        while(rows.moveToNext()) {
            val text=rows.text(2);val j=JSONObject(text)
            LedgerJson.integer(j,"balance",0,Long.MAX_VALUE);LedgerJson.integer(j,"at",Long.MIN_VALUE,Long.MAX_VALUE)
            val receipt=LedgerJson.settlement(j)
            if(receipt.transaction.requestId.value!=rows.text(0) || receipt.settlementId.value!=rows.text(1) ||
                receipt.transaction.source!=source || LedgerJson.settlement(receipt).toString()!=text || result.put(receipt.transaction.requestId,receipt)!=null)
                throw SQLiteException("랭킹 정산 식별·출처·형식 불일치")
        };result
    }

    private data class DrivingEvidence(val days:List<RankingDayDistance>,val income:List<RankingIncome>)
    /** 공통 reader의 원명령/가격/정산 검증을 재사용하고, 일누계 합·고아정산·출발 zone은 별도로 확인한다. */
    private fun driving(db:SQLiteDatabase,receipts:Map<RequestId,Settlement>):DrivingEvidence {
        val days=linkedMapOf<LocalDate,Long>()
        db.rawQuery("SELECT day,source,rewarded FROM driving_days",null).use { rows->while(rows.moveToNext()) {
            val day=DeliveryJson.date(rows.text(0));val value=rows.integer(2,0,Long.MAX_VALUE)
            if(rows.text(1)!=source.name || days.put(day,value)!=null) throw SQLiteException("주행 일자·출처 불일치")
        }}
        val dayZones=linkedMapOf<LocalDate,Observation<java.time.ZoneId>>()
        val sums=linkedMapOf<LocalDate,Long>();val income=mutableListOf<RankingIncome>();val ownedRequests=mutableSetOf<RequestId>()
        val reader=DrivingRecordReader(source)
        db.rawQuery("SELECT drive_id FROM driving_drives",null).use { rows->while(rows.moveToNext()) {
            val id=rows.text(0)
            val stored=reader.readDrive(db,id)?:throw SQLiteException("주행 원결과 누락")
            val commit=stored.commit;val day=commit.day.day;val rewarded=commit.rewardedMeters
            if((days[day]?:-1)<commit.day.rewardedMeters)throw SQLiteException("주행 누계 불일치")
            val request=RequestId("drive/$id");ownedRequests+=request
            val recorded=commit.settlement
            if(recorded==null) { if(receipts[request]!=null)throw SQLiteException("0원 주행 정산 불일치") }
            else {
                if(receipts[request]!=recorded)throw SQLiteException("주행 공용 정산 불일치")
                income+=RankingIncome(request,RankingIncomeOrigin.DRIVING,commit.amount,source)
            }
            sums[day]=Math.addExact(sums[day]?:0,rewarded)
            val proof=departureZone?.invoke(id)?:Observation.Missing(MissingReason.NO_DATA)
            dayZones[day]=if(day !in dayZones || dayZones[day]==proof)proof else Observation.Missing(MissingReason.NO_DATA)
        }}
        if(days.any { (day,value)->sums[day]!=value } || sums.keys!=days.keys || receipts.values.any{it.transaction.kind==TransactionKind.DRIVING && it.transaction.requestId !in ownedRequests})
            throw SQLiteException("주행 누계와 원인 이력 누락")
        return DrivingEvidence(days.map{RankingDayDistance(it.key,minOf(it.value,500000L),source,dayZones.getValue(it.key))},income)
    }

    private fun rental(db:SQLiteDatabase,receipts:Map<RequestId,Settlement>):List<RankingIncome> {
        val result=mutableListOf<RankingIncome>();val ids=mutableSetOf<RequestId>()
        db.rawQuery("SELECT id FROM rentals WHERE settlement_request IS NOT NULL",null).use { rows->while(rows.moveToNext()) {
            val completed=required(store.rental.completedRental(RequestId(rows.text(0)))) ?: throw SQLiteException("완료 대여 누락")
            val receipt=completed.receipt.settlement;val id=receipt.transaction.requestId
            if(receipts[id]!=receipt || receipt.transaction.money?.direction!=MoneyDirection.CREDIT || !ids.add(id)) throw SQLiteException("완료 대여 정산 불일치")
            result+=RankingIncome(id,RankingIncomeOrigin.RENTAL,receipt.transaction.money!!.amount,source)
        }}
        if(receipts.values.any{it.transaction.kind==TransactionKind.RENTAL && it.transaction.requestId !in ids}) throw SQLiteException("대여 원인 누락")
        return result
    }

    private fun delivery(db:SQLiteDatabase,receipts:Map<RequestId,Settlement>):List<RankingIncome> {
        val result=mutableListOf<RankingIncome>()
        for(receipt in receipts.values.filter{it.transaction.kind==TransactionKind.DELIVERY && it.transaction.money?.direction==MoneyDirection.CREDIT}) {
            val request=receipt.transaction.requestId.value
            if(!request.startsWith("delivery/")) throw SQLiteException("탁송 원인 식별 오류")
            when {
                request.endsWith("/settle")-> {
                    val id=RequestId(request.removePrefix("delivery/").removeSuffix("/settle"))
                    if(!exists(db,"delivery_outcomes",id)) throw SQLiteException("탁송 완료 원인 누락")
                    val arrival=required(store.delivery.arrivalResult(id)) // null은 검증된 포기 결과다. 아래 수익에는 포함하지 않는다.
                    if(arrival!=null) {
                        if(arrival.settlement!=receipt) throw SQLiteException("탁송 정산 연결 오류")
                        if(arrival.outcome is DeliveryOutcome.IncomeCompleted) result+=RankingIncome(receipt.transaction.requestId,RankingIncomeOrigin.DELIVERY_INCOME,receipt.transaction.money!!.amount,source)
                    }
                }
                request.endsWith("/hold")-> {
                    val hold=required(store.delivery.holdResult(RequestId(request.removePrefix("delivery/").removeSuffix("/hold"))))
                    if(hold?.settlement!=receipt) throw SQLiteException("임시 보관 환급 원인 누락")
                }
                request.endsWith("/change")->if(!exists(db,"delivery_changes",RequestId(request.removePrefix("delivery/").removeSuffix("/change")))) throw SQLiteException("차액 환급 원인 누락")
                else->throw SQLiteException("미확인 탁송 입금 원인")
            }
        };return result
    }
    /** 처분 수익만 원래 GARAGE Update와 재생 정산으로 확인한다. 기타 입금을 퀘스트로 추측하지 않는다. */
    private fun disposalAndCheckUnclassified(db:SQLiteDatabase,receipts:Map<RequestId,Settlement>):List<RankingIncome> {
        val result=mutableListOf<RankingIncome>()
        val questIds=questCauses?.let{required(it(db))}.orEmpty()
        for(id in questIds){
            val receipt=receipts[id]?:throw SQLiteException("퀘스트 지급 정산 누락")
            val money=receipt.transaction.money
            if(money?.direction!=MoneyDirection.CREDIT)throw SQLiteException("퀘스트 수익 방향 오류")
            result+=RankingIncome(id,RankingIncomeOrigin.QUEST,money.amount,source)
        }
        for(receipt in receipts.values.filter{it.transaction.requestId !in questIds && it.transaction.money?.direction==MoneyDirection.CREDIT}) {
            if(receipt.transaction.kind in setOf(TransactionKind.DRIVING,TransactionKind.RENTAL,TransactionKind.DELIVERY)) continue
            if(receipt.transaction.kind!=TransactionKind.GARAGE) throw MissingEvidence("포함 또는 제외 원인이 확인되지 않은 입금이 있습니다.")
            val car=receipt.changedVehicle
            if(car?.lifecycle in setOf(VehicleLifecycle.SOLD,VehicleLifecycle.SCRAPPED)) {
                if(car==null || car.revision.value==0) throw SQLiteException("처분 revision 오류")
                val command=GameChange(receipt.transaction.requestId,TransactionKind.GARAGE,source,receipt.transaction.ruleVersion,receipt.transaction.money,
                    VehicleWrite.Update(car.ownedVehicleId,Revision(car.revision.value-1),car.level,car.lifecycle,car.levelProgressPercent))
                if(readRecordedSettlement(db,command)!=receipt) throw SQLiteException("처분 원래 정산 불일치")
                result+=RankingIncome(command.requestId,RankingIncomeOrigin.DISPOSAL,receipt.transaction.money!!.amount,source)
            } else if(car?.acquisitionSource==VehicleAcquisitionSource.INITIAL_GRANT && car.lifecycle==VehicleLifecycle.OWNED && car.revision==Revision(0)) {
                // 초기 지원금은 제외한다. 현재 잔액이나 일반 CREDIT를 수익으로 채택하지 않는다.
            } else throw MissingEvidence("분류되지 않은 입금이 있습니다. 퀘스트 등 확정 원인 조회 연결이 필요합니다.")
        }
        return result
    }
    private fun exists(db:SQLiteDatabase,table:String,id:RequestId)=db.rawQuery("SELECT 1 FROM $table WHERE request_id=?",arrayOf(id.value)).use{it.moveToFirst()}
    private class MissingEvidence(val reason:String):RuntimeException()
    private class EvidenceFailure(val error:ContractError):RuntimeException()
    private fun <T> required(result:ContractResult<T>):T=when(result){is ContractResult.Success->result.value;is ContractResult.Failure->throw EvidenceFailure(result.error)}
    private fun metric(read:()->RankingValue):RankingValue=try{read()}catch(e:MissingEvidence){RankingValue.Missing(e.reason)}catch(e:RankingProvenanceFailure){RankingValue.Failure(e.error)}catch(e:EvidenceFailure){RankingValue.Failure(e.error)}catch(_:JSONException){RankingValue.Failure(ContractError.STORAGE_FAILURE)}catch(_:RuntimeException){RankingValue.Failure(ContractError.STORAGE_FAILURE)}
}
