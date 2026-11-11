package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import org.json.JSONException

/**
 * 같은 DB·실제 localProfileId의 미확정 명령 한 개. worker 전용이며 금액/개체 원장을 소유하지 않는다.
 * SAVED의 명시 취소와 STARTED의 원래 결과 복구를 구분한다. 알 수 없는 결과는 자동 삭제하지 않는다.
 */
class HostCommandCheckpointLedger(private val store: SqliteGameChangeStore) {
    companion object {
        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE host_command_checkpoint (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, phase INTEGER NOT NULL CHECK(phase IN (0,1)), digest TEXT NOT NULL)")
        }
    }

    fun load(profileId: ProfileId): ContractResult<HostCommandCheckpoint?> = store.atomic { db ->
        when (val checked = profile(profileId)) {
            is ContractResult.Failure -> checked
            is ContractResult.Success -> ContractResult.Success(read(db, checked.value))
        }
    }
    /** 자기 typed 원결과만 조회한다. 실제 부재는 null이고 현재 조건으로 재실행하지 않는다. */
    fun facilityResult(profileId:ProfileId,command:HostCheckpointCommand):ContractResult<HostCheckpointResult?> = store.atomic { db ->
        when(val checked=profile(profileId)){is ContractResult.Failure->return@atomic checked;else->Unit}
        if(!FacilityCheckpointStorage.supports(command)||!command.validFor(store.source))return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        FacilityCheckpointStorage.original(store,db,profileId,command)
    }
    private fun strict(c:HostCheckpointCommand)=c is HostCheckpointCommand.Delivery||c is HostCheckpointCommand.Quest||c is HostCheckpointCommand.Achievement||c is HostCheckpointCommand.Decoration||FacilityCheckpointStorage.supports(c)

    fun save(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<HostCommandCheckpoint> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId)
        if (prior != null) return@atomic if (prior.command == command) ContractResult.Success(prior) else conflict()
        if (command is HostCheckpointCommand.Quest && command.value.profileId!=profileId) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        if(command is HostCheckpointCommand.Achievement && command.value.profileId != profileId) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        if(command is HostCheckpointCommand.Decoration&&command.value.profileId!=profileId)return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        if (command.usesReservedAchievementNamespace || !command.validFor(store.source))
            return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val encoded = HostCheckpointJson.encode(profileId, command)
        // 잘못된 호출 입력을 DB 손상으로 저장하지 않는다.
        val decoded = try { HostCheckpointJson.decode(encoded) } catch (_: RuntimeException) {
            return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        if (decoded != (profileId to command)) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        if (command is HostCheckpointCommand.Delivery) {
            if(!DeliveryCheckpointStorage.canPrepare(db,command)) return@atomic conflict()
        } else if(command is HostCheckpointCommand.Decoration) {
            when(val old=store.decorations.originalIn(db,command.value)) {
                is ContractResult.Failure->return@atomic old
                is ContractResult.Success->if(old.value==null)when(val ready=store.decorations.prepareIn(db,command.value)){is ContractResult.Failure->return@atomic ready;else->Unit}
            }
        } else if(FacilityCheckpointStorage.supports(command)) {
            when(val valid=FacilityCheckpointStorage.prepare(store,db,profileId,command)){is ContractResult.Failure->return@atomic valid;else->Unit}
        } else if(command is HostCheckpointCommand.Quest) {
            when(val old=store.quests.settlement(command.value.requestId)) {
                is ContractResult.Failure->return@atomic old
                is ContractResult.Success->old.value?.let{if(it.command!=command.value)return@atomic conflict()}
            }
        } else if (command is HostCheckpointCommand.Achievement) {
            val original = store.achievements.confirmed(db, command.requestId)
            if (original != null) {
                if (original.command != command.value) return@atomic conflict()
            } else {
                val valid = store.achievements.validateUnstarted(command.value)
                if (valid is ContractResult.Failure) return@atomic valid
            }
        } else if (store.requestUnavailable(db, command.requestId)) return@atomic conflict()
        val decorationBefore=if(command is HostCheckpointCommand.Decoration)store.decorations.state(profileId)else null
        if(decorationBefore is ContractResult.Failure)return@atomic decorationBefore
        db.execSQL("INSERT INTO host_command_checkpoint VALUES(1,?,0,?)", arrayOf(encoded,HostCheckpointJson.digest(encoded,0)))
        val saved=HostCommandCheckpoint(profileId,if(strict(command))decoded.second else command,HostCheckpointPhase.SAVED)
        if(strict(command) && read(db,profileId)!=saved)throw SQLiteException("명령 durable SAVED 실제 쓰기 누락")
        if(decorationBefore!=null&&store.decorations.state(profileId)!=decorationBefore)throw SQLiteException("꾸미기 checkpoint의 비금융 상태 변경")
        ContractResult.Success(saved)
    }

    /**
     * STARTED와 기존 commit을 같은 DB 거래에 확정한다. 새 시도가 확정 거절되면 SAVED도 복원된다.
     * 기존 STARTED의 결과 불명은 실패로 지우지 않는다. 현재 입력으로 재prepare하지 않는다.
     */
    fun execute(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<HostCheckpointResult> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId) ?: return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (prior.command != command) return@atomic conflict()
        // 이 두 유형의 STARTED는 기능 확정과 같은 거래에서만 남는다. 원결과 소실을 새 실행으로 채우지 않는다.
        if(prior.phase==HostCheckpointPhase.STARTED) when(command) {
            is HostCheckpointCommand.Quest->when(val original=store.quests.settlement(command.value.requestId)) {
                is ContractResult.Failure->return@atomic original
                is ContractResult.Success->if(original.value==null)return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            }
            is HostCheckpointCommand.Achievement -> if (store.achievements.confirmed(db, command.requestId) == null)
                return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is HostCheckpointCommand.Delivery->if(!DeliveryCheckpointStorage.hasOriginal(db,command))return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is HostCheckpointCommand.Decoration->when(val original=store.recoverCheckpointNamespace(db, prior) { store.decorations.originalIn(db,command.value) }){is ContractResult.Failure->return@atomic original;is ContractResult.Success->if(original.value==null)return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
            is HostCheckpointCommand.FacilityPurchase,is HostCheckpointCommand.VehicleResting->when(val original=store.recoverCheckpointNamespace(db, prior) { FacilityCheckpointStorage.original(store,db,profileId,command) }) {
                is ContractResult.Failure->return@atomic original
                is ContractResult.Success->if(original.value==null)return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            }
            else->Unit
        }
        db.execSQL("UPDATE host_command_checkpoint SET phase=1,digest=? WHERE id=1",arrayOf(HostCheckpointJson.digest(HostCheckpointJson.encode(profileId,command),1)))
        // nested 실패는 바깥 STARTED 쓰기도 rollback한다. 성공이면 원장과 표시가 함께 남는다.
        if(strict(command) && read(db,profileId)!=HostCommandCheckpoint(profileId,command,HostCheckpointPhase.STARTED))
            throw SQLiteException("명령 STARTED 실제 쓰기 누락")
        val result = store.recoverCheckpointNamespace(db, prior) {
            if (command is HostCheckpointCommand.Decoration && prior.phase == HostCheckpointPhase.SAVED)
                wrap(store.decorations.commitFromSavedCheckpointIn(db, command.value)) { HostCheckpointResult.Decoration(it) }
            else commit(command)
        }
        if(result is ContractResult.Success && result.value is HostCheckpointResult.Delivery && !(result.value as HostCheckpointResult.Delivery).value.isFinal) {
            db.execSQL("UPDATE host_command_checkpoint SET phase=0,digest=? WHERE id=1",arrayOf(HostCheckpointJson.digest(HostCheckpointJson.encode(profileId,command),0)))
            if(read(db,profileId)!=HostCommandCheckpoint(profileId,command,HostCheckpointPhase.SAVED))throw SQLiteException("탁송 비확정 SAVED 복귀 누락")
        }
        if (command is HostCheckpointCommand.Achievement && result is ContractResult.Success &&
            read(db, profileId) != HostCommandCheckpoint(profileId, command, HostCheckpointPhase.STARTED))
            throw SQLiteException("업적 확정 뒤 STARTED 실제 상태 불일치")
        if(command is HostCheckpointCommand.Decoration&&result is ContractResult.Success&&read(db,profileId)!=HostCommandCheckpoint(profileId,command,HostCheckpointPhase.STARTED))
            throw SQLiteException("꾸미기 확정 뒤 STARTED 실제 상태 불일치")
        result
    }

    /** 실제 원래 정산이 존재하고 전체 명령 재생이 검증될 때만 같은 checkpoint를 지운다. */
    fun finish(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<HostCheckpointResult> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId) ?: return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (prior.command != command || prior.phase != HostCheckpointPhase.STARTED) return@atomic conflict()
        val receiptExists = if(command is HostCheckpointCommand.Decoration) when(val old=store.recoverCheckpointNamespace(db, prior) { store.decorations.originalIn(db,command.value) }) {
            is ContractResult.Failure->return@atomic old
            is ContractResult.Success->old.value!=null
        } else if(FacilityCheckpointStorage.supports(command)) when(val old=store.recoverCheckpointNamespace(db, prior) { FacilityCheckpointStorage.original(store,db,profileId,command) }) {
            is ContractResult.Failure->return@atomic old
            is ContractResult.Success->old.value!=null
        } else if(command is HostCheckpointCommand.Quest) when(val old=store.quests.settlement(command.value.requestId)) {
            is ContractResult.Failure->return@atomic old
            is ContractResult.Success->old.value?.let{if(it.command!=command.value)return@atomic conflict();true}?:false
        } else if (command is HostCheckpointCommand.Achievement) {
            store.achievements.confirmed(db, command.requestId)?.let {
                if (it.command != command.value) return@atomic conflict()
                true
            } ?: false
        } else if(command is HostCheckpointCommand.Delivery) DeliveryCheckpointStorage.hasOriginal(db,command) else db.rawQuery("SELECT 1 FROM settlements WHERE request_id=?",arrayOf(command.requestId.value)).use { it.moveToFirst() }
        if (!receiptExists) return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        // 같은 쓰기 거래 안에서 기존 receipt 존재를 고정한 뒤 원장 자체 decoder로 검증·재생한다.
        val result = store.recoverCheckpointNamespace(db, prior) { commit(command) }
        if (result is ContractResult.Failure) return@atomic result
        val decorationBefore=if(command is HostCheckpointCommand.Decoration)store.decorations.state(profileId)else null
        if(decorationBefore is ContractResult.Failure)return@atomic decorationBefore
        if (db.delete("host_command_checkpoint", "id=1", null) != 1) throw SQLiteException("checkpoint 삭제 대상 누락")
        if(decorationBefore!=null&&store.decorations.state(profileId)!=decorationBefore)throw SQLiteException("꾸미기 checkpoint의 비금융 상태 변경")
        if((FacilityCheckpointStorage.supports(command)||command is HostCheckpointCommand.Decoration||command is HostCheckpointCommand.Achievement)&&read(db,profileId)!=null)throw SQLiteException("시설/쉬기 checkpoint 실제 삭제 누락")
        result
    }

    fun cancelUnstarted(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<Unit> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId) ?: return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (prior.command != command || prior.phase != HostCheckpointPhase.SAVED) return@atomic conflict()
        fun requestUnavailable() = store.recoverCheckpointNamespace(db, prior) { store.requestUnavailable(db, command.requestId) }
        val unavailable=when(command) {
            is HostCheckpointCommand.Delivery->!DeliveryCheckpointStorage.canCancel(db,command)
            is HostCheckpointCommand.Decoration->when(val old=store.recoverCheckpointNamespace(db, prior) { store.decorations.originalIn(db,command.value) }){is ContractResult.Failure->return@atomic old;is ContractResult.Success->old.value!=null}
            is HostCheckpointCommand.Quest->when(val old=store.quests.settlement(command.value.requestId)) {
                is ContractResult.Failure->return@atomic old
                is ContractResult.Success->old.value!=null
            }
            is HostCheckpointCommand.Achievement -> store.achievements.confirmed(db, command.requestId) != null ||
                store.requestUnavailable(db, command.requestId, achievementClaim = true)
            is HostCheckpointCommand.FacilityPurchase,is HostCheckpointCommand.VehicleResting->when(val old=store.recoverCheckpointNamespace(db, prior) { FacilityCheckpointStorage.original(store,db,profileId,command) }) {
                is ContractResult.Failure->return@atomic old
                is ContractResult.Success->old.value!=null||requestUnavailable()
            }
            else->requestUnavailable()
        }
        if (unavailable) return@atomic conflict()
        val decorationBefore=if(command is HostCheckpointCommand.Decoration)store.decorations.state(profileId)else null
        if(decorationBefore is ContractResult.Failure)return@atomic decorationBefore
        if (db.delete("host_command_checkpoint", "id=1", null) != 1) throw SQLiteException("checkpoint 취소 대상 누락")
        if(decorationBefore!=null&&store.decorations.state(profileId)!=decorationBefore)throw SQLiteException("꾸미기 checkpoint의 비금융 상태 변경")
        if((FacilityCheckpointStorage.supports(command)||command is HostCheckpointCommand.Decoration||command is HostCheckpointCommand.Achievement)&&read(db,profileId)!=null)throw SQLiteException("시설/쉬기 checkpoint 실제 취소 누락")
        ContractResult.Success(Unit)
    }

    private fun profile(expected: ProfileId): ContractResult<ProfileId> = when (val actual = store.localProfileId()) {
        is ContractResult.Failure -> actual
        is ContractResult.Success -> if (actual.value == expected) actual else ContractResult.Failure(ContractError.INVALID_INPUT)
    }

    private fun read(db: SQLiteDatabase, profile: ProfileId): HostCommandCheckpoint? =
        db.rawQuery("SELECT payload,phase,digest FROM host_command_checkpoint WHERE id=1", null).use { row ->
            if (!row.moveToFirst()) return@use null
            val phaseNumber = row.integer(1,0,1).toInt()
            if (row.text(2) != HostCheckpointJson.digest(row.text(0),phaseNumber)) throw JSONException("checkpoint digest 불일치")
            val (owner, command) = HostCheckpointJson.decode(row.text(0))
            if (owner != profile || command.origin != store.source) throw JSONException("checkpoint 프로필/출처 불일치")
            val phase = if (phaseNumber == 0) HostCheckpointPhase.SAVED else HostCheckpointPhase.STARTED
            HostCommandCheckpoint(owner, command, phase)
        }

    private fun commit(command: HostCheckpointCommand): ContractResult<HostCheckpointResult> = when (command) {
        is HostCheckpointCommand.Gacha -> wrap(store.commitGacha(command.value)) { HostCheckpointResult.Gacha(it) }
        is HostCheckpointCommand.Garage -> wrap(store.commitGameChange(command.value)) { HostCheckpointResult.Garage(it) }
        is HostCheckpointCommand.Material -> wrap(store.commitMaterial(command.value)) { HostCheckpointResult.Material(it) }
        is HostCheckpointCommand.Repair -> wrap(store.commitRepair(command.value)) { HostCheckpointResult.Repair(it) }
        is HostCheckpointCommand.Quest -> wrap(store.quests.claim(command.value)){HostCheckpointResult.Quest(it)}
        is HostCheckpointCommand.Achievement -> wrap(store.achievements.claim(command.value)) { HostCheckpointResult.Achievement(it) }
        is HostCheckpointCommand.Decoration -> wrap(store.decorations.commit(command.value)){HostCheckpointResult.Decoration(it)}
        is HostCheckpointCommand.Delivery -> wrap(store.atomic{db->DeliveryCheckpointStorage.commit(store,db,command)}) {HostCheckpointResult.Delivery(it)}
        is HostCheckpointCommand.Expand -> wrap(commitExpand(command)) { HostCheckpointResult.Expand(it) }
        is HostCheckpointCommand.FacilityPurchase,is HostCheckpointCommand.VehicleResting -> store.atomic { db ->
            val profileId=when(val r=store.localProfileId()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
            FacilityCheckpointStorage.commit(store,db,profileId,command)
        }
    }
    /** 확장 기존 입구를 재사용하되 새 확정의 필수 쓰기와 원래 전체 receipt 연결을 확인한다. */
    private fun commitExpand(command:HostCheckpointCommand.Expand):ContractResult<Settlement> = store.atomic { db ->
        val profileId=when(val r=store.localProfileId()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        val checkpoint=read(db,profileId)
        if(checkpoint?.command!=command || checkpoint.phase!=HostCheckpointPhase.STARTED)
            throw SQLiteException("확장 실행 표시의 실제 쓰기 불일치")
        val recorded=db.rawQuery("SELECT 1 FROM settlements WHERE request_id=?",arrayOf(command.requestId.value)).use{it.moveToFirst()}
        if(!recorded) expansionValues(db) // 잘못된 현재 숫자를 getLong/getInt로 정상값에 보정하지 않는다.
        val receipt=when(val r=store.rental.expand(command.requestId,command.offer)) {
            is ContractResult.Failure->return@atomic r
            is ContractResult.Success->r.value
        }
        if(receipt.transaction.requestId!=command.requestId || receipt.transaction.source!=command.source ||
            receipt.transaction.kind!=TransactionKind.FACILITY || receipt.transaction.ruleVersion!=command.offer.ruleVersion ||
            receipt.transaction.money!=MoneyChange(MoneyDirection.DEBIT,command.offer.price) || receipt.changedVehicle!=null)
            throw SQLiteException("확장 원명령/정산 연결 불일치")
        db.rawQuery("SELECT settlement_id,result FROM settlements WHERE request_id=?",arrayOf(command.requestId.value)).use {
            if(!it.moveToFirst() || it.text(0)!=receipt.settlementId.value || it.text(1)!=LedgerJson.settlement(receipt).toString())
                throw SQLiteException("확장 공용 정산 원문 불일치")
        }
        if(!recorded) {
            val actual=expansionValues(db)
            if(actual.first!=receipt.balance.units || actual.second!=command.offer.fromLevel+1 || actual.third!=command.offer.nextCapacity)
                throw SQLiteException("확장 실제 재화/단계/공간 쓰기 불일치")
        }
        ContractResult.Success(receipt)
    }
    private fun expansionValues(db:SQLiteDatabase):Triple<Long,Int,Int> {
        val balance=db.rawQuery("SELECT balance FROM account WHERE id=1",null).use {
            if(!it.moveToFirst())throw SQLiteException("확장 계정 누락")
            it.integer(0,0,Long.MAX_VALUE)
        }
        return db.rawQuery("SELECT level,capacity FROM shop WHERE id=1",null).use {
            if(!it.moveToFirst())throw SQLiteException("확장 가게 누락")
            Triple(balance,it.integer(0,1,Int.MAX_VALUE.toLong()).toInt(),it.integer(1,1,Int.MAX_VALUE.toLong()).toInt())
        }
    }
    private inline fun <A> wrap(result: ContractResult<A>, convert: (A) -> HostCheckpointResult): ContractResult<HostCheckpointResult> =
        when (result) { is ContractResult.Failure -> result; is ContractResult.Success -> ContractResult.Success(convert(result.value)) }
    private fun conflict() = ContractResult.Failure(ContractError.STATE_CONFLICT)
}
