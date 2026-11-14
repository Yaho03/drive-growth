package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.planPurchase
import org.json.JSONArray
import org.json.JSONObject

/** 같은 원장의 typed 명령/원결과 참조 metadata. 구매 금액은 기존 settlements만 권위다. */
internal object FacilityCheckpointStorage {
    fun createTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE facility_command_results(request_id TEXT PRIMARY KEY NOT NULL,profile TEXT NOT NULL,source TEXT NOT NULL,command TEXT NOT NULL,result TEXT NOT NULL,digest TEXT NOT NULL)")
    }
    fun supports(c:HostCheckpointCommand)=c is HostCheckpointCommand.FacilityPurchase||c is HostCheckpointCommand.VehicleResting
    private fun conflict()=ContractResult.Failure(ContractError.STATE_CONFLICT)
    private fun boolean(j:JSONObject,k:String)=j.get(k) as? Boolean?:throw org.json.JSONException("설정 결과 boolean 형식")
    fun original(store:SqliteGameChangeStore,db:SQLiteDatabase,profile:ProfileId,c:HostCheckpointCommand):ContractResult<HostCheckpointResult?> =
        db.rawQuery("SELECT profile,source,command,result,digest FROM facility_command_results WHERE request_id=?",arrayOf(c.requestId.value)).use { row ->
            if(!row.moveToFirst()) {
                if(c is HostCheckpointCommand.FacilityPurchase)db.rawQuery("SELECT command FROM settlements WHERE request_id=?",arrayOf(c.requestId.value)).use{s->
                    if(s.moveToFirst()&&s.text(0)==JSONArray(listOf("facility",c.offer.facility.name,c.offer.trial,c.offer.ruleVersion.value,c.offer.price.units)).toString())throw SQLiteException("시설 금융 정산의 typed 원명령 누락")
                }
                return@use if(store.requestUnavailable(db,c.requestId))conflict() else ContractResult.Success(null)
            }
            val command=row.text(2);val raw=row.text(3);val decoded=HostCheckpointJson.decode(command)
            if(row.text(0)!=profile.value||row.text(1)!=store.source.name||decoded.first!=profile||decoded.second.origin!=store.source||!supports(decoded.second)||decoded.second.requestId!=c.requestId||row.text(4)!=ObservedDrivingJson.digest(command+"\n"+raw))throw SQLiteException("시설/쉬기 원결과 출처·프로필·명령 손상")
            if(decoded.second!=c)return@use conflict()
            val j=JSONObject(raw)
            val result:HostCheckpointResult
            val canonical:JSONObject
            when(c) {
                is HostCheckpointCommand.FacilityPurchase -> {
                    val settlementId=j.getString("settlement")
                    val receipt=db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?",arrayOf(c.requestId.value)).use { s ->
                        if(!s.moveToFirst())throw SQLiteException("시설 최초 공용 정산 누락")
                        val value=LedgerJson.settlement(JSONObject(s.text(2)))
                        val expected=JSONArray(listOf("facility",c.offer.facility.name,c.offer.trial,c.offer.ruleVersion.value,c.offer.price.units)).toString()
                        if(s.text(0)!=settlementId||s.text(1)!=expected||value.settlementId.value!=settlementId||value.transaction.requestId!=c.requestId||value.transaction.source!=c.source||value.transaction.kind!=TransactionKind.FACILITY||value.transaction.ruleVersion!=c.offer.ruleVersion||value.transaction.money!=MoneyChange(MoneyDirection.DEBIT,c.offer.price)||value.changedVehicle!=null||LedgerJson.settlement(value).toString()!=s.text(2))throw SQLiteException("시설 최초 정산 전체 연결 불일치")
                        value
                    }
                    result=HostCheckpointResult.FacilityPurchase(receipt);canonical=JSONObject().put("settlement",settlementId)
                }
                is HostCheckpointCommand.VehicleResting -> {
                    val before=boolean(j,"before");val after=boolean(j,"after")
                    if(before!=c.expected||after!=c.desired)throw SQLiteException("쉬기 최초 전후 상태 불일치")
                    result=HostCheckpointResult.VehicleResting(VehicleRestingReceipt(profile,c,before,after));canonical=JSONObject().put("before",before).put("after",after)
                }
                else -> throw SQLiteException("미지원 시설 원결과 종류")
            }
            if(canonical.toString()!=raw)throw SQLiteException("시설/쉬기 전체 원결과 손상")
            ContractResult.Success(result)
        }
    fun prepare(store:SqliteGameChangeStore,db:SQLiteDatabase,profile:ProfileId,c:HostCheckpointCommand):ContractResult<Unit> {
        when(val old=original(store,db,profile,c)){is ContractResult.Failure->return old;is ContractResult.Success->if(old.value!=null)return ContractResult.Success(Unit)}
        if(store.requestUnavailable(db,c.requestId))return conflict()
        return when(c) {
            is HostCheckpointCommand.FacilityPurchase -> {
                val d=when(val r=store.trialDefinitions.load()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                if(d.version!=c.definitionVersion||d.facilityOffers[c.offer.facility]!=c.offer)return conflict()
                val shop=when(val r=store.rental.shop()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                val balance=when(val r=store.balance()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                when(val r=c.offer.planPurchase(shop.progress,shop.facilities,balance)){is ContractResult.Failure->r;else->ContractResult.Success(Unit)}
            }
            is HostCheckpointCommand.VehicleResting -> {
                val car=store.findVehicle(db,c.vehicle.ownedVehicleId)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                val rest=when(val r=store.rental.restingVehicles()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                if(car.lifecycle!=VehicleLifecycle.OWNED||car.revision!=c.vehicle.revision||(car.ownedVehicleId in rest)!=c.expected)return conflict()
                ContractResult.Success(Unit)
            }
            else -> ContractResult.Failure(ContractError.INVALID_INPUT)
        }
    }
    fun commit(store: SqliteGameChangeStore, db: SQLiteDatabase, profile: ProfileId, c: HostCheckpointCommand,
        onNewFacility: () -> Unit): ContractResult<HostCheckpointResult> {
        when(val old=original(store,db,profile,c)){is ContractResult.Failure->return old;is ContractResult.Success->old.value?.let{return ContractResult.Success(it)}}
        when(val valid=prepare(store,db,profile,c)){is ContractResult.Failure->return valid;else->Unit}
        val result:HostCheckpointResult
        val raw:String
        when(c) {
            is HostCheckpointCommand.FacilityPurchase -> {
                val before=when(val r=store.rental.shop()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                val receipt=when(val r=store.rental.buy(c.requestId,c.offer)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                val after=when(val r=store.rental.shop()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                if(after.facilities!=before.facilities+c.offer.facility||after.progress!=before.progress||after.capacity!=before.capacity||store.readBalance(db)!=receipt.balance)throw SQLiteException("시설 실제 설치/재화 후조건 불일치")
                result=HostCheckpointResult.FacilityPurchase(receipt);raw=JSONObject().put("settlement",receipt.settlementId.value).toString()
            }
            is HostCheckpointCommand.VehicleResting -> {
                val before=when(val r=store.rental.restingVehicles()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                val car=store.findVehicle(db,c.vehicle.ownedVehicleId);val balance=store.readBalance(db);val activity=store.rental.activity(db,c.vehicle.ownedVehicleId)
                when(val r=store.rental.setResting(c.vehicle.ownedVehicleId,c.desired)){is ContractResult.Failure->return r;else->Unit}
                val after=when(val r=store.rental.restingVehicles()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
                val expected=if(c.desired)before+c.vehicle.ownedVehicleId else before-c.vehicle.ownedVehicleId
                if(after!=expected||store.findVehicle(db,c.vehicle.ownedVehicleId)!=car||store.readBalance(db)!=balance||store.rental.activity(db,c.vehicle.ownedVehicleId)!=activity)throw SQLiteException("쉬기 실제 설정/개체/재화/활동 후조건 불일치")
                result=HostCheckpointResult.VehicleResting(VehicleRestingReceipt(profile,c,c.expected,c.desired));raw=JSONObject().put("before",c.expected).put("after",c.desired).toString()
            }
            else -> return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        val command=HostCheckpointJson.encode(profile,c)
        val values=ContentValues().apply{put("request_id",c.requestId.value);put("profile",profile.value);put("source",store.source.name);put("command",command);put("result",raw);put("digest",ObservedDrivingJson.digest(command+"\n"+raw))}
        if(db.insertOrThrow("facility_command_results",null,values)<0)throw SQLiteException("시설/쉬기 원결과 무삽입")
        when(val actual=original(store,db,profile,c)){is ContractResult.Failure->throw SQLiteException("시설/쉬기 실제 원결과 불일치");is ContractResult.Success->if(actual.value!=result)throw SQLiteException("시설/쉬기 실제 원결과 누락")}
        // 신규 분기만 설치 후조건과 typed 원정산 쓰기를 통과한다.
        if (c is HostCheckpointCommand.FacilityPurchase) onNewFacility()
        return ContractResult.Success(result)
    }
}
