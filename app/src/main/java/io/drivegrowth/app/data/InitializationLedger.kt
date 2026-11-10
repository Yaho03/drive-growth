package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

/** 명시 공급값이다. 시험 콘텐츠/후보를 실제 차량 권위로 바꾸지 않는다. */
class InitializationAuthority(source: InputSource, content: GameContentSnapshot, allowedCandidates: Set<VehicleId>) {
    val source = source
    val content = content.copy(catalog = content.catalog.copy(vehicles = Collections.unmodifiableList(ArrayList(content.catalog.vehicles))),
        vehicles = Collections.unmodifiableList(ArrayList(content.vehicles)))
    val allowedCandidates: Set<VehicleId> = Collections.unmodifiableSet(LinkedHashSet(allowedCandidates))
}

/** 기존 공용 DB·지갑·shop 공간만 사용한다. helper/capability 연결은 공용 writer가 적용한다. */
internal class InitializationLedger(
    private val store: SqliteGameChangeStore,
    private val authority: (() -> ContractResult<InitializationAuthority>)? = null,
) : InitializationChangeStore, GameChangeStore by store {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE initialization_profile (id INTEGER PRIMARY KEY CHECK(id=1), profile_id TEXT UNIQUE NOT NULL, source TEXT NOT NULL, proof_id TEXT, proof_revision INTEGER, marker_request TEXT)")
            db.execSQL("CREATE TABLE initialization_requests (request_id TEXT PRIMARY KEY NOT NULL, profile_id TEXT UNIQUE NOT NULL, command TEXT NOT NULL, original_state TEXT NOT NULL, result TEXT NOT NULL)")
        }
        /** 실제 LedgerHelper.onCreate의 빈 DB 확인/공용 표 생성 뒤에만 호출한다. */
        fun onNewDatabase(db: SQLiteDatabase, source: InputSource) {
            if (db.version != 0 || !db.inTransaction()) throw SQLiteException("신규 생성 경계 밖 NEW 발급 금지")
            verifyAccount(db, source)
            db.rawQuery("SELECT balance FROM account WHERE id=1",null).use {
                if (!it.moveToFirst() || it.integer(0,0,Long.MAX_VALUE)!=0L) throw SQLiteException("신규 계정 잔액 불일치")
            }
            for (table in listOf("vehicles", "settlements", "initialization_profile", "initialization_requests")) {
                db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { if (it.moveToFirst()) throw SQLiteException("신규 생성에 기존 자료 존재") }
            }
            val id = "local-" + UUID.randomUUID()
            db.execSQL("INSERT INTO initialization_profile VALUES(1,?,?,?,?,NULL)",
                arrayOf(id, source.name, UUID.randomUUID().toString(), 0))
        }
        /** 이전 DB에는 로컬 식별만 붙이고 NEW 근거를 만들지 않는다. 기존 자료는 지우지 않는다. */
        fun onExistingDatabase(db: SQLiteDatabase, source: InputSource) {
            if (db.version <= 0 || !db.inTransaction()) throw SQLiteException("기존 DB 이전 경계 오류")
            verifyAccount(db, source)
            db.execSQL("INSERT INTO initialization_profile VALUES(1,?,?,NULL,NULL,NULL)", arrayOf("local-" + UUID.randomUUID(), source.name))
        }
        private fun verifyAccount(db: SQLiteDatabase, source: InputSource) {
            db.rawQuery("SELECT source FROM account WHERE id=1", null).use {
                if (!it.moveToFirst() || it.text(0) != source.name || it.moveToNext()) throw SQLiteException("프로필 원장 출처 불일치")
            }
        }
    }
    /** 가챠 공간이 구성됐어도 초기화 기록의 손상을 무시하지 않는다. 현재 개체/잔액은 재생 조건이 아니다. */
    internal fun capacityIfInitialized(db: SQLiteDatabase): Int? =
        if (recorded(db, profile(db)) == null) null else capacity(db)
    private data class Profile(val id: ProfileId, val proof: NewProfileEvidence?, val marker: RequestId?)
    private fun profile(db: SQLiteDatabase): Profile = db.rawQuery(
        "SELECT profile_id,source,proof_id,proof_revision,marker_request FROM initialization_profile WHERE id=1", null).use { row ->
        if (!row.moveToFirst()) throw SQLiteException("로컬 프로필 누락")
        val id = ProfileId(row.text(0))
        if (row.text(1) != store.source.name) throw SQLiteException("프로필 source 불일치")
        if (row.isNull(2) != row.isNull(3)) throw SQLiteException("신규 근거 일부 누락")
        val proof = if (row.isNull(2)) null else NewProfileEvidence(id, store.source, row.text(2), Revision(row.integer(3,0,Int.MAX_VALUE.toLong()).toInt()))
        val marker = if (row.isNull(4)) null else RequestId(row.text(4))
        if (row.moveToNext()) throw SQLiteException("프로필 중복")
        Profile(id, proof, marker)
    }
    fun profileId(): ContractResult<ProfileId> = store.read { profile(it).id }
    private fun ownedCount(db: SQLiteDatabase): Int = db.rawQuery("SELECT owned_id,payload FROM vehicles",null).use { row ->
        var count = 0
        while (row.moveToNext()) {
            val text = row.text(1); val vehicle = LedgerJson.vehicle(JSONObject(text))
            if (vehicle.ownedVehicleId.value != row.text(0) || LedgerJson.vehicle(vehicle).toString() != text) throw JSONException("개체 원본 연결 오류")
            if (vehicle.lifecycle == VehicleLifecycle.OWNED) count++
        }
        count
    }
    private fun capacity(db: SQLiteDatabase): Int = db.rawQuery("SELECT capacity FROM shop WHERE id=1",null).use {
        if (!it.moveToFirst()) throw SQLiteException("공용 공간 누락")
        it.integer(0,1,Int.MAX_VALUE.toLong()).toInt()
    }
    private fun current(db: SQLiteDatabase, p: Profile): ContractResult<InitializationState> {
        val supplied = when (val result = authority?.invoke()) {
            null -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (supplied.source != store.source) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val content = supplied.content
        val candidates = supplied.allowedCandidates
        if (candidates.any { id -> id !in FirstGrant.CANDIDATES || content.catalog.vehicles.none { it.vehicleId == id } || content.vehicles.none { it.vehicleId == id } })
            return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        return ContractResult.Success(InitializationState(p.id,store.source,content.catalog.contentVersion,content.ruleVersion,
            p.proof?.let { ProfileInitializationStatus.New(it) } ?: ProfileInitializationStatus.Unknown(MissingReason.NO_DATA),
            store.readBalance(db),ownedCount(db),FirstGrant.INITIAL_MONEY,FirstGrant.INITIAL_GARAGE_CAPACITY,candidates))
    }
    override fun readInitialization(profileId: ProfileId): ContractResult<InitializationState> = store.atomic { db ->
        val p = profile(db)
        if (p.id != profileId) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val old = recorded(db,p)
        if (old != null) ContractResult.Success(old.second.copy(status=ProfileInitializationStatus.Existing(old.first),
            balance=store.readBalance(db),ownedVehicleCount=ownedCount(db)))
        else current(db,p)
    }
    override fun commitInitialization(command: InitializationCommand): ContractResult<InitializationSettlement> = store.atomic { db ->
        val p = profile(db)
        if (command.profileId != p.id || command.grant.source != store.source) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        recorded(db,p)?.let { (old,_) ->
            return@atomic if (old.command == command) ContractResult.Success(old) else ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        if (p.proof == null) return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (store.requestUnavailable(db,command.grant.requestId)) return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        val before = when (val result = current(db,p)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val ready = when (val result = prepareInitialization(command,before)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value as InitializationPreparation.Ready
        }
        if (store.findVehicle(db,ready.vehicle.ownedVehicleId) != null || capacity(db) != ready.garageCapacity)
            return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        val encoded = InitializationJson.command(command).toString()
        val receipt = when (val result = store.record(db,command.grant,encoded)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        if (store.readBalance(db) != ready.balance || store.findVehicle(db, ready.vehicle.ownedVehicleId) != ready.vehicle)
            throw SQLiteException("신규 초기화의 실제 잔액/개체 쓰기 불일치")
        val result = InitializationSettlement(command,receipt,ready.garageCapacity,ready.marker)
        db.execSQL("UPDATE shop SET capacity=? WHERE id=1",arrayOf(ready.garageCapacity))
        db.execSQL("UPDATE initialization_profile SET marker_request=? WHERE id=1",arrayOf(command.grant.requestId.value))
        db.execSQL("INSERT INTO initialization_requests VALUES(?,?,?,?,?)",arrayOf(command.grant.requestId.value,p.id.value,encoded,
            InitializationJson.state(before).toString(),InitializationJson.result(result).toString()))
        if (recorded(db,profile(db))?.first != result) throw JSONException("초기화 확정 연결 검증 실패")
        ContractResult.Success(result)
    }
    /** 현재 콘텐츠가 아니라 기록한 당시 상태로 원래 전체 정산의 연결을 검증한다. */
    private fun recorded(db: SQLiteDatabase,p: Profile): Pair<InitializationSettlement,InitializationState>? {
        db.rawQuery("SELECT request_id,profile_id,command,original_state,result FROM initialization_requests",null).use { row ->
            if (row.moveToFirst()) {
                val key = RequestId(row.text(0));val encoded = row.text(2);val command = InitializationJson.command(JSONObject(encoded))
                if (p.marker != key || p.proof == null || p.proof != command.evidence || row.text(1) != p.id.value ||
                    command.profileId != p.id || command.grant.requestId != key || command.grant.source != store.source || InitializationJson.command(command).toString() != encoded)
                    throw JSONException("초기화 명령/프로필/마커 연결 오류")
                val beforeText=row.text(3);val before=InitializationJson.state(JSONObject(beforeText))
                val prepared=(prepareInitialization(command,before) as? ContractResult.Success)?.value as? InitializationPreparation.Ready
                    ?: throw JSONException("초기화 당시 명령 검증 실패")
                val saved=row.text(4);val result=InitializationJson.result(command,JSONObject(saved))
                if (InitializationJson.state(before).toString()!=beforeText || InitializationJson.result(result).toString()!=saved ||
                    before.profileId!=p.id || before.source!=store.source || result.marker!=prepared.marker || result.garageCapacity!=prepared.garageCapacity ||
                    result.grant.balance!=prepared.balance || result.grant.changedVehicle!=prepared.vehicle ||
                    result.grant.transaction.requestId!=key || result.grant.transaction.kind!=command.grant.kind ||
                    result.grant.transaction.source!=store.source || result.grant.transaction.ruleVersion!=command.grant.ruleVersion ||
                    result.grant.transaction.money!=command.grant.money) throw JSONException("초기화 최초 결과 손상")
                db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?",arrayOf(key.value)).use { receipt ->
                    if (!receipt.moveToFirst() || receipt.text(0)!=result.grant.settlementId.value || receipt.text(1)!=encoded ||
                        receipt.text(2)!=LedgerJson.settlement(result.grant).toString()) throw JSONException("초기화 공용 정산 손상")
                }
                if (row.moveToNext()) throw JSONException("프로필 초기화 중복 기록")
                return result to before
            }
        }
        if (p.marker!=null) throw JSONException("초기화 마커의 전체 결과 누락")
        db.rawQuery("SELECT command FROM settlements WHERE request_id=?",arrayOf(FirstGrant.REQUEST_ID.value)).use { row ->
            if (row.moveToFirst()) {
                val encoded = row.text(0)
                if (encoded.trimStart().startsWith("{") && JSONObject(encoded).optString("type")=="initialization-v1")
                    throw JSONException("초기화 공용 정산만 남음")
            }
        }
        return null
    }
}

/** canonical 재직렬화 비교와 공용 정수 decoder로 TEXT/실수/NULL 보정을 거절한다. */
internal object InitializationJson {
    private fun str(j:JSONObject,key:String)=j.get(key) as? String ?: throw JSONException("$key 문자열 오류")
    private fun integer(j:JSONObject,key:String,max:Long=Long.MAX_VALUE)=LedgerJson.integer(j,key,0,max)
    fun proof(e:NewProfileEvidence)=JSONObject().put("profile",e.profileId.value).put("source",e.source.name).put("id",e.proofId).put("revision",e.revision.value)
    fun proof(j:JSONObject)=NewProfileEvidence(ProfileId(str(j,"profile")),InputSource.valueOf(str(j,"source")),str(j,"id"),Revision(integer(j,"revision",Int.MAX_VALUE.toLong()).toInt()))
    fun command(c:InitializationCommand)=JSONObject().put("type","initialization-v1").put("profile",c.profileId.value)
        .put("proof",proof(c.evidence)).put("content",c.contentVersion.value).put("grant",LedgerJson.command(c.grant)).put("capacity",c.garageCapacity)
    fun command(j:JSONObject):InitializationCommand {
        if(str(j,"type")!="initialization-v1")throw JSONException("초기화 명령 종류 오류")
        val g=j.getJSONObject("grant");val m=g.getJSONObject("money");val write=g.getJSONObject("write")
        if(str(write,"type")!="add")throw JSONException("초기화 Add 형식 오류")
        val grant=GameChange(RequestId(str(g,"request")),TransactionKind.valueOf(str(g,"kind")),InputSource.valueOf(str(g,"source")),RuleVersion(str(g,"rule")),
            MoneyChange(MoneyDirection.valueOf(str(m,"direction")),GameMoney(integer(m,"amount"))),VehicleWrite.Add(LedgerJson.vehicle(write.getJSONObject("vehicle"))))
        return InitializationCommand(ProfileId(str(j,"profile")),proof(j.getJSONObject("proof")),ContentVersion(str(j,"content")),grant,integer(j,"capacity",Int.MAX_VALUE.toLong()).toInt())
    }
    fun state(s:InitializationState):JSONObject {
        val status=s.status as? ProfileInitializationStatus.New ?: throw JSONException("초기화 원상태는 NEW")
        return JSONObject().put("profile",s.profileId.value).put("source",s.source.name).put("content",s.contentVersion.value).put("rule",s.ruleVersion.value)
            .put("proof",proof(status.evidence)).put("balance",s.balance.units).put("owned",s.ownedVehicleCount).put("initialMoney",s.initialMoney.units)
            .put("capacity",s.initialGarageCapacity).put("candidates",JSONArray(s.availableCandidates.map{it.value}.sorted()))
    }
    fun state(j:JSONObject):InitializationState {
        val candidates=j.getJSONArray("candidates");val ids=(0 until candidates.length()).map{VehicleId(candidates.get(it) as? String ?: throw JSONException("후보 문자열 오류"))}
        if(ids.distinct().size!=ids.size)throw JSONException("중복 후보")
        return InitializationState(ProfileId(str(j,"profile")),InputSource.valueOf(str(j,"source")),ContentVersion(str(j,"content")),RuleVersion(str(j,"rule")),
            ProfileInitializationStatus.New(proof(j.getJSONObject("proof"))),GameMoney(integer(j,"balance")),integer(j,"owned",Int.MAX_VALUE.toLong()).toInt(),
            GameMoney(integer(j,"initialMoney")),integer(j,"capacity",Int.MAX_VALUE.toLong()).toInt(),ids.toSet())
    }
    fun result(s:InitializationSettlement)=JSONObject().put("receipt",LedgerJson.settlement(s.grant)).put("capacity",s.garageCapacity)
        .put("marker",JSONObject().put("profile",s.marker.profileId.value).put("request",s.marker.requestId.value).put("proof",proof(s.marker.evidence)))
    fun result(c:InitializationCommand,j:JSONObject):InitializationSettlement {
        val receipt=j.getJSONObject("receipt");integer(receipt,"balance");integer(receipt,"at")
        val m=j.getJSONObject("marker")
        return InitializationSettlement(c,LedgerJson.settlement(receipt),integer(j,"capacity",Int.MAX_VALUE.toLong()).toInt(),
            InitializationMarker(ProfileId(str(m,"profile")),RequestId(str(m,"request")),proof(m.getJSONObject("proof"))))
    }
}
