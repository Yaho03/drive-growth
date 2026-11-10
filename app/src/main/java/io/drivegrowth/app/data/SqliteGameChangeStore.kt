package io.drivegrowth.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.QuestDefinitions
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.VehicleHistoryStore
import io.drivegrowth.core.progression.VehicleActivity
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** 단일 기기 거래 원장. 호출자는 메인 스레드 밖에서 사용한다. 출처마다 별도 DB를 사용한다. */
class SqliteGameChangeStore(
    context: Context,
    internal val source: InputSource,
    databaseName: String = "game-ledger-${source.name.lowercase()}.db",
    internal val gachaAuthority: (() -> ContractResult<GachaAuthority>)? = null,
    initialGachaProgress: GachaProgress? = null,
    internal val materialVersions: (() -> ContractResult<MaterialVersions>)? = null,
    /** 검증된 현재 콘텐츠 공급. 조회 중 상태 변경 없이 같은 버전의 가격/규칙을 제공한다. */
    internal val repairContent: (() -> ContractResult<GameContentSnapshot>)? = null,
    internal val initializationAuthority: (() -> ContractResult<InitializationAuthority>)? = null,
    internal val firstGiftContent: (() -> ContractResult<GameContentSnapshot>)? = null,
    internal val questDefinitions: (() -> ContractResult<QuestDefinitions>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : FirstRentalGiftStore, MaterialChangeStore, RepairChangeStore, InitializationChangeStore, VehicleHistoryStore, Closeable {
    private val helper = LedgerHelper(context.applicationContext, databaseName, source, initialGachaProgress)

    /** 디스크 쓰기 후 COMMIT 이전의 실패를 재현하기 위한 모듈 내부 시험 지점. */
    internal var beforeCommit: (() -> Unit)? = null
    internal var lastStorageFailure: LedgerFailureReason? = null
        private set

    @Synchronized
    override fun commitGameChange(change: GameChange): ContractResult<Settlement> {
        if (change.source != source) return failure(ContractError.INVALID_INPUT)
        return atomic { db -> record(db, change, LedgerJson.command(change).toString()) }
    }

    val delivery = DeliveryLedger(this)
    val driving = DrivingLedger(this)
    val observedDriving = SqliteObservedDrivingJournal()
    fun activateObservedDriving(profileId:ProfileId) = atomic { db -> observedDriving.activate(db,io.drivegrowth.app.host.GameIdentity(profileId,source)) }
    fun observedDrivingReceipt(driveId:String):ContractResult<io.drivegrowth.app.host.driving.ObservedDrivingReceipt?> = atomic { db ->
        val p=when(val r=localProfileId()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        observedDriving.committed(db,io.drivegrowth.app.host.GameIdentity(p,source),driveId)
    }
    val rental = RentalLedger(this)
    private val initialization = InitializationLedger(this, initializationAuthority)
    fun localProfileId() = initialization.profileId()
    val quests = QuestLedger(this, ::localProfileId) { questDefinitions?.invoke() ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
    val hostCommands = HostCommandCheckpointLedger(this)
    override fun readInitialization(profileId: ProfileId) = initialization.readInitialization(profileId)
    override fun commitInitialization(command: InitializationCommand) = initialization.commitInitialization(command)
    private val firstGift = FirstRentalGiftLedger(this, { initialization.profileId() }, { id ->
        when (val read = initialization.readInitialization(id)) {
            is ContractResult.Failure -> read
            is ContractResult.Success -> {
                val initialized = (read.value.status as? ProfileInitializationStatus.Existing)?.original
                if (initialized != null) ContractResult.Success(Unit) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            }
        }
    }, firstGiftContent)
    override fun readFirstRentalGift(profileId: ProfileId) = firstGift.readFirstRentalGift(profileId)
    override fun commitFirstRentalGift(command: FirstRentalGiftCommand) = firstGift.commitFirstRentalGift(command)
    private val repair = RepairLedger(this)
    override fun readRepairState(target: OwnedVehicleId) = repair.readState(target)
    override fun repairSettlement(requestId: RequestId) = repair.settlement(requestId)
    override fun commitRepair(command: RepairCommand) = repair.commit(command)
    private val material = MaterialLedger(this)
    override fun readMaterialState(target: OwnedVehicleId, material: OwnedVehicleId) = this.material.readState(target, material)
    override fun commitMaterial(command: MaterialCommand) = material.commit(command)
    internal fun now() = EpochMillis(clock())
    private val gacha = GachaLedger(this)
    override fun readGachaState() = gacha.readState()
    override fun commitGacha(command: GachaCommand) = gacha.commit(command)

    @Synchronized
    internal fun <T> atomic(action: (SQLiteDatabase) -> ContractResult<T>): ContractResult<T> = storage {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = action(db)
            if (result is ContractResult.Success) {
                beforeCommit?.invoke()
                db.setTransactionSuccessful()
            }
            result
        } finally {
            db.endTransaction()
        }
    }

    internal enum class RequestDomain { GENERAL, DELIVERY, DRIVING }

    internal fun requestUnavailable(db: SQLiteDatabase, id: RequestId, questClaim: Boolean = false): Boolean {
        if (id.value.startsWith("quest/") && !(questClaim && id.value.startsWith("quest/claim/"))) return true
        if (id.value.startsWith("delivery/") || id.value.startsWith("drive/")) return true
        val queries = listOf(
            "SELECT 1 FROM settlements WHERE request_id=?",
            "SELECT 1 FROM visits WHERE id=?",
            "SELECT 1 FROM gacha_requests WHERE request_id=?",
            "SELECT 1 FROM delivery_requests WHERE request_id=?",
            "SELECT 1 FROM material_requests WHERE request_id=?",
            "SELECT 1 FROM repair_requests WHERE request_id=?",
            "SELECT 1 FROM initialization_requests WHERE request_id=?",
            "SELECT 1 FROM first_gift_requests WHERE request_id=?",
            "SELECT 1 FROM first_gift_markers WHERE request_id=?",
            "SELECT 1 FROM quest_claims WHERE request_id=?")
        return queries.any { sql -> db.rawQuery(sql, arrayOf(id.value)).use { it.moveToFirst() } }
    }

    internal fun record(db: SQLiteDatabase, change: GameChange, command: String,
                        domain: RequestDomain = RequestDomain.GENERAL): ContractResult<Settlement> {
        if (change.source != source) return failure(ContractError.INVALID_INPUT)
        val id = change.requestId.value
        if (domain == RequestDomain.DELIVERY && change.kind != TransactionKind.DELIVERY ||
            domain == RequestDomain.DRIVING && change.kind != TransactionKind.DRIVING)
            return failure(ContractError.INVALID_INPUT)
        for (table in listOf("visits", "gacha_requests", "delivery_requests", "material_requests", "repair_requests", "initialization_requests", "first_gift_requests", "first_gift_markers", "quest_claims")) {
            val key = if (table == "visits") "id" else "request_id"
            if (db.rawQuery("SELECT 1 FROM $table WHERE $key=?", arrayOf(id)).use { it.moveToFirst() })
                return failure(ContractError.STATE_CONFLICT)
        }
        // 기능 소유 이력이 있는 ID는 같은 명령처럼 보여도 GENERAL 재생 대상이 아니다.
        for ((table, expression) in listOf(
            "driving_drives" to "'drive/' || drive_id",
            "delivery_outcomes" to "'delivery/' || request_id || '/settle'",
            "delivery_hold_outcomes" to "'delivery/' || request_id || '/hold'",
            "delivery_changes" to "'delivery/' || request_id || '/change'")) {
            if (db.rawQuery("SELECT 1 FROM $table WHERE $expression=?", arrayOf(id)).use { it.moveToFirst() })
                return failure(ContractError.STATE_CONFLICT)
        }
        val existing = db.rawQuery("SELECT command, result FROM settlements WHERE request_id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        if (existing != null) {
            // v4에서 허용한 GENERAL 동일 명령은 새 prefix 예약보다 먼저 검증·재생한다.
            return if (domain == RequestDomain.GENERAL && existing.first == command)
                ContractResult.Success(readRecordedSettlement(db, change) ?: throw SQLiteException("기존 정산 누락"))
            else failure(ContractError.STATE_CONFLICT)
        }
        // 기존 GENERAL 원명령 replay 뒤에만 신규 Quest namespace를 예약한다.
        if (id.startsWith("quest/")) return failure(ContractError.STATE_CONFLICT)
        if ((id.startsWith("delivery/") && domain != RequestDomain.DELIVERY) ||
            (id.startsWith("drive/") && domain != RequestDomain.DRIVING))
            return failure(ContractError.STATE_CONFLICT)
        val previousBalance = readBalance(db)
        val balance = when (val money = change.money) {
            null -> previousBalance
            else -> when (money.direction) {
                MoneyDirection.CREDIT -> {
                    if (money.amount.units > Long.MAX_VALUE - previousBalance.units) return failure(ContractError.INVALID_INPUT)
                    GameMoney(previousBalance.units + money.amount.units)
                }
                MoneyDirection.DEBIT -> {
                    if (money.amount.units > previousBalance.units) return failure(ContractError.INSUFFICIENT_FUNDS)
                    GameMoney(previousBalance.units - money.amount.units)
                }
            }
        }
        var beforeGrowth: OwnedVehicle? = null
        val vehicle = when (val write = change.vehicle) {
            null -> null
            is VehicleWrite.Add -> {
                val initializedCapacity = initialization.capacityIfInitialized(db)
                val capacity = GachaLedger.capacityIfConfigured(db) ?: initializedCapacity
                if (capacity != null && readVehicles(db).count { it.lifecycle == VehicleLifecycle.OWNED } >= capacity)
                    return failure(ContractError.CAPACITY_EXCEEDED)
                if (findVehicle(db, write.vehicle.ownedVehicleId) != null) return failure(ContractError.STATE_CONFLICT)
                if (write.vehicle.revision != Revision(0) || write.vehicle.lifecycle != VehicleLifecycle.OWNED) return failure(ContractError.INVALID_INPUT)
                write.vehicle
            }
            is VehicleWrite.Update -> {
                val previous = findVehicle(db, write.ownedVehicleId) ?: return failure(ContractError.DATA_UNAVAILABLE)
                beforeGrowth = previous
                val activity = rental.activity(db, write.ownedVehicleId)
                if (activity == VehicleActivity.RENTED ||
                    activity == VehicleActivity.RETURNING ||
                    (activity == VehicleActivity.NEEDS_REPAIR &&
                        write.lifecycle !in setOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED)))
                    return failure(ContractError.STATE_CONFLICT)
                if (previous.revision != write.expectedRevision || previous.lifecycle != VehicleLifecycle.OWNED) return failure(ContractError.STATE_CONFLICT)
                if (previous.revision.value == Int.MAX_VALUE) return failure(ContractError.INVALID_INPUT)
                if (write.lifecycle != VehicleLifecycle.OWNED &&
                    (write.level != previous.level || write.levelProgressPercent != previous.levelProgressPercent)) return failure(ContractError.INVALID_INPUT)
                previous.copy(level = write.level, lifecycle = write.lifecycle, revision = Revision(previous.revision.value + 1),
                    levelProgressPercent = write.levelProgressPercent)
            }
        }
        val result = Settlement(
            SettlementId(UUID.randomUUID().toString()),
            Transaction(TransactionId(UUID.randomUUID().toString()), change.requestId, change.kind, change.source, change.ruleVersion, change.money),
            balance, vehicle, EpochMillis(clock()),
        )
        db.execSQL("UPDATE account SET balance = ? WHERE id = 1", arrayOf(balance.units))
        if (vehicle != null) writeVehicle(db, vehicle, change.vehicle is VehicleWrite.Add)
        insertSettlement(db, change.requestId, command, result)
        if(beforeGrowth!=null && vehicle!=null && vehicle.level>beforeGrowth.level &&
            (findVehicle(db,vehicle.ownedVehicleId)!=vehicle || readBalance(db)!=result.balance))
            throw SQLiteException("성장 실제 개체/재화 쓰기 불일치")
        val event = quests.onVehicleChanged(db, beforeGrowth, vehicle, result)
        if (event is ContractResult.Failure) return event
        return ContractResult.Success(result)
    }

    internal fun writeVehicle(db: SQLiteDatabase, vehicle: OwnedVehicle, add: Boolean = false) {
        val values = ContentValues().apply {
            put("owned_id", vehicle.ownedVehicleId.value)
            put("payload", LedgerJson.vehicle(vehicle).toString())
        }
        if (add) db.insertOrThrow("vehicles", null, values)
        else if (db.update("vehicles", values, "owned_id = ?", arrayOf(vehicle.ownedVehicleId.value)) != 1)
            throw SQLiteException("차량 갱신 실패")
    }

    internal fun insertSettlement(db: SQLiteDatabase, requestId: RequestId, command: String, result: Settlement) {
        db.insertOrThrow("settlements", null, ContentValues().apply {
            put("request_id", requestId.value)
            put("settlement_id", result.settlementId.value)
            put("command", command)
            put("result", LedgerJson.settlement(result).toString())
            put("viewed", 0)
        })
    }

    internal fun <T> read(query: (SQLiteDatabase) -> T): ContractResult<T> =
        readResult { db -> ContractResult.Success(query(db)) }

    @Synchronized
    internal fun <T> readResult(query: (SQLiteDatabase) -> ContractResult<T>): ContractResult<T> =
        storage { query(helper.readableDatabase) }

    private fun readVehicles(db: SQLiteDatabase): List<OwnedVehicle> =
        db.rawQuery("SELECT payload FROM vehicles ORDER BY owned_id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(LedgerJson.vehicle(JSONObject(cursor.getString(0)))) }
        }

    override fun listOwnedVehicles() = read { db -> readVehicles(db).filter { it.lifecycle == VehicleLifecycle.OWNED } }

    /** 처분된 개체도 마지막 상태와 취득 정보를 보존한다. */
    override fun listVehicleHistory() = read(::readVehicles)

    override fun discoveredVehicles(): ContractResult<Set<CollectionKey>> = read { db ->
        db.rawQuery("SELECT vehicle_id, rarity FROM discoveries", null).use { rows ->
            buildSet {
                while (rows.moveToNext()) add(CollectionKey(VehicleId(rows.getString(0)), Rarity.valueOf(rows.getString(1))))
            }
        }
    }

    /** 목록에서 실제로 본 칸만 기록한다. 재노출·재시도에는 같은 복합 키를 사용한다. */
    override fun recordDiscoveries(catalog: VehicleCatalogSnapshot, keys: Set<CollectionKey>): ContractResult<Unit> {
        val snapshot = keys.toSet()
        val known = catalog.vehicles.toList().mapTo(mutableSetOf()) { it.vehicleId }
        if (snapshot.any { it.vehicleId !in known }) return failure(ContractError.INVALID_INPUT)
        return atomic { db ->
            for (key in snapshot) db.execSQL("INSERT OR IGNORE INTO discoveries(vehicle_id, rarity) VALUES (?, ?)",
                arrayOf(key.vehicleId.value, key.rarity.name))
            ContractResult.Success(Unit)
        }
    }

    @Synchronized
    fun balance(): ContractResult<GameMoney> = storage { ContractResult.Success(readBalance(helper.readableDatabase)) }

    /** 결과를 읽는 동작은 지급을 수행하지 않는다. */
    @Synchronized
    fun listUnviewedSettlements(): ContractResult<List<Settlement>> = storage {
        val results = helper.readableDatabase.rawQuery("SELECT result FROM settlements WHERE viewed = 0 ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(LedgerJson.settlement(JSONObject(cursor.getString(0)))) }
        }
        ContractResult.Success(results)
    }

    @Synchronized
    fun markViewed(id: SettlementId): ContractResult<Unit> = atomic { db ->
        val previous = db.rawQuery("SELECT request_id,result,viewed FROM settlements WHERE settlement_id=?", arrayOf(id.value)).use {
            if (!it.moveToFirst()) return@atomic failure(ContractError.DATA_UNAVAILABLE)
            val receipt = LedgerJson.settlement(JSONObject(it.text(1)))
            if (receipt.settlementId != id || receipt.transaction.requestId.value != it.text(0) || receipt.transaction.source != source)
                throw SQLiteException("열람 결과 연결 불일치")
            receipt to (it.integer(2,0,1) == 1L)
        }
        if (!previous.second && db.update("settlements", ContentValues().apply { put("viewed",1) }, "settlement_id=? AND viewed=0", arrayOf(id.value)) != 1)
            throw SQLiteException("최초 열람 갱신 누락")
        // 최초0→1만 실제 현재 시각을 기록한다. 기존 boolean을 오늘 사건으로 backfill하지 않는다.
        quests.onResultViewed(db, previous.first, previous.second)
    }

    @Synchronized
    override fun close() = helper.close()

    internal fun readBalance(db: SQLiteDatabase): GameMoney = db.rawQuery("SELECT balance FROM account WHERE id = 1", null).use {
        if (!it.moveToFirst()) throw SQLiteException("거래 계정 누락")
        GameMoney(it.integer(0, 0, Long.MAX_VALUE))
    }

    internal fun findVehicle(db: SQLiteDatabase, id: OwnedVehicleId): OwnedVehicle? =
        db.rawQuery("SELECT payload FROM vehicles WHERE owned_id = ?", arrayOf(id.value)).use {
            if (it.moveToFirst()) LedgerJson.vehicle(JSONObject(it.getString(0))) else null
        }

    private fun <T> storage(block: () -> ContractResult<T>): ContractResult<T> = try {
        lastStorageFailure = null
        block()
    } catch (error: LedgerStorageException) { storageFailure(error.reason) }
    catch (_: SQLiteDatabaseCorruptException) { storageFailure(LedgerFailureReason.CORRUPT) }
    catch (_: SQLiteException) { storageFailure(LedgerFailureReason.SQLITE) }
    catch (_: JSONException) { storageFailure(LedgerFailureReason.INVALID_RECORD) }
    catch (_: IllegalArgumentException) { storageFailure(LedgerFailureReason.INVALID_RECORD) }

    private fun storageFailure(reason: LedgerFailureReason): ContractResult.Failure {
        lastStorageFailure = reason
        return failure(ContractError.STORAGE_FAILURE)
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)
}

internal enum class LedgerFailureReason { SQLITE, CORRUPT, UNSUPPORTED_SCHEMA, SOURCE_MISMATCH, INVALID_RECORD }
private class LedgerStorageException(val reason: LedgerFailureReason) : SQLiteException(reason.name)

private class LedgerHelper(context: Context, name: String, private val source: InputSource, private val initialGachaProgress: GachaProgress?) : SQLiteOpenHelper(
    context, name, null, 14,
    // Android 기본 핸들러의 손상 DB 삭제를 막고 원본을 보존한다.
    DatabaseErrorHandler { throw LedgerStorageException(LedgerFailureReason.CORRUPT) },
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'android_%'", null).use {
            if (it.moveToFirst()) throw LedgerStorageException(LedgerFailureReason.UNSUPPORTED_SCHEMA)
        }
        db.execSQL("CREATE TABLE account (id INTEGER PRIMARY KEY CHECK(id = 1), balance INTEGER NOT NULL CHECK(balance >= 0), source TEXT NOT NULL)")
        db.execSQL("INSERT INTO account VALUES (1, 0, ?)", arrayOf(source.name))
        db.execSQL("CREATE TABLE vehicles (owned_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE settlements (request_id TEXT PRIMARY KEY NOT NULL, settlement_id TEXT UNIQUE NOT NULL, command TEXT NOT NULL, result TEXT NOT NULL, viewed INTEGER NOT NULL CHECK(viewed IN (0,1)))")
        RentalLedger.createTables(db)
        createDiscoveries(db)
        GachaLedger.createTables(db)
        DeliveryLedger.createTables(db)
        DrivingLedger.createTables(db)
        MaterialLedger.createTables(db)
        RepairLedger.createTables(db)
        InitializationLedger.createTables(db)
        InitializationLedger.onNewDatabase(db, source)
        FirstRentalGiftLedger.createTables(db)
        HostCommandCheckpointLedger.createTables(db)
        QuestLedger.createTables(db)
        beginQuestTracking(db, true)
        DeliveryCheckpointStorage.addColumns(db)
        SqliteObservedDrivingJournal.createTables(db)
        RentalProductStorage.createTables(db)
        initialGachaProgress?.let { GachaLedger.insertInitialProgress(db, it) }
    }
    private fun createDiscoveries(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE discoveries (vehicle_id TEXT NOT NULL, rarity TEXT NOT NULL, PRIMARY KEY(vehicle_id, rarity))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 1..13 || newVersion != 14) throw LedgerStorageException(LedgerFailureReason.UNSUPPORTED_SCHEMA)
        verifySource(db)
        // 기존 원장과 개체 JSON은 그대로 보존한다.
        if (oldVersion == 1) RentalLedger.createTables(db)
        if (oldVersion < 3) createDiscoveries(db)
        if (oldVersion < 4) GachaLedger.createTables(db)
        if (oldVersion < 5) {
            DeliveryLedger.createTables(db)
            DrivingLedger.createTables(db)
        }
        if (oldVersion < 6) MaterialLedger.createTables(db)
        if (oldVersion < 7) RepairLedger.createTables(db)
        if (oldVersion < 8) {
            InitializationLedger.createTables(db)
            InitializationLedger.onExistingDatabase(db, source)
        }
        if (oldVersion < 9) FirstRentalGiftLedger.createTables(db)
        if (oldVersion < 10) HostCommandCheckpointLedger.createTables(db)
        if(oldVersion<11) {
            QuestLedger.createTables(db)
            beginQuestTracking(db, false)
        }
        if(oldVersion<12) DeliveryCheckpointStorage.addColumns(db)
        if(oldVersion<13)SqliteObservedDrivingJournal.createTables(db)
        RentalProductStorage.createTables(db)
    }
    /** 이 버전은 세 실제 사건 hook과 함께 공급한다. onCreate 재진입 없이 이미 발급한 profile을 전달한다. */
    private fun beginQuestTracking(db: SQLiteDatabase, fresh: Boolean) {
        val profile = db.rawQuery("SELECT profile_id,source,proof_id FROM initialization_profile WHERE id=1", null).use {
            if (!it.moveToFirst() || it.text(1) != source.name || (fresh && it.isNull(2)))
                throw LedgerStorageException(LedgerFailureReason.INVALID_RECORD)
            ProfileId(it.text(0))
        }
        if (QuestLedger.startTracking(db, profile, source, EpochMillis(System.currentTimeMillis()), fresh) is ContractResult.Failure)
            throw LedgerStorageException(LedgerFailureReason.INVALID_RECORD)
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw LedgerStorageException(LedgerFailureReason.UNSUPPORTED_SCHEMA)
    }
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        verifySource(db)
        db.rawQuery("PRAGMA quick_check", null).use {
            if (!it.moveToFirst() || it.getString(0) != "ok") throw LedgerStorageException(LedgerFailureReason.CORRUPT)
        }
    }
    private fun verifySource(db: SQLiteDatabase) {
        db.rawQuery("SELECT source FROM account WHERE id = 1", null).use {
            if (!it.moveToFirst() || it.getString(0) != source.name) throw LedgerStorageException(LedgerFailureReason.SOURCE_MISMATCH)
        }
    }
}

/** 필드 순서가 고정된 명령 표현을 저장해 요청 ID 재사용 시 전체 입력을 비교한다. */
internal object LedgerJson {
    fun vehicle(v: OwnedVehicle) = JSONObject().put("id", v.ownedVehicleId.value).put("vehicle", v.vehicleId.value)
        .put("level", v.level).put("revision", v.revision.value).put("lifecycle", v.lifecycle.name)
        .put("rarity", v.rarity.name).put("levelProgressPercent", v.levelProgressPercent)
        .put("acquisitionCost", v.acquisitionCost.units).put("acquisitionSource", v.acquisitionSource.name)
    fun vehicle(j: JSONObject) = OwnedVehicle(OwnedVehicleId(j.getString("id")), VehicleId(j.getString("vehicle")),
        integer(j, "level", 1, MAX_VEHICLE_LEVEL.toLong()).toInt(),
        Revision(integer(j, "revision", 0, Int.MAX_VALUE.toLong()).toInt()), VehicleLifecycle.valueOf(j.getString("lifecycle")),
        Rarity.valueOf(j.getString("rarity")), integer(j, "levelProgressPercent", 0, 99).toInt(),
        GameMoney(integer(j, "acquisitionCost", 0, Long.MAX_VALUE)),
        VehicleAcquisitionSource.valueOf(j.getString("acquisitionSource")))
    // writer의 JSON 정수만 허용한다. 좁은 타입 변환 전에 형식과 범위를 검사한다.
    fun integer(j: JSONObject, key: String, min: Long, max: Long): Long {
        val value = when (val raw = j.get(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw JSONException("$key 정수 형식 오류")
        }
        if (value !in min..max) throw JSONException("$key 정수 범위 오류")
        return value
    }
    private fun money(m: MoneyChange?) = m?.let { JSONObject().put("direction", it.direction.name).put("amount", it.amount.units) }
    private fun money(j: JSONObject?): MoneyChange? = j?.let { MoneyChange(MoneyDirection.valueOf(it.getString("direction")), GameMoney(integer(it, "amount", 0, Long.MAX_VALUE))) }
    private fun moneyObject(j: JSONObject): JSONObject? = when (val raw = j.get("money")) {
        JSONObject.NULL -> null
        is JSONObject -> raw
        else -> throw JSONException("재화 변경 형식 오류")
    }
    fun command(c: GameChange): JSONObject {
        val write = when (val v = c.vehicle) {
            null -> null
            is VehicleWrite.Add -> JSONObject().put("type", "add").put("vehicle", vehicle(v.vehicle))
            is VehicleWrite.Update -> JSONObject().put("type", "update").put("id", v.ownedVehicleId.value)
                .put("revision", v.expectedRevision.value).put("level", v.level).put("lifecycle", v.lifecycle.name)
                .put("levelProgressPercent", v.levelProgressPercent)
        }
        return JSONObject().put("request", c.requestId.value).put("kind", c.kind.name).put("source", c.source.name)
            .put("rule", c.ruleVersion.value).put("money", money(c.money) ?: JSONObject.NULL).put("write", write ?: JSONObject.NULL)
    }
    fun settlement(s: Settlement) = JSONObject().put("id", s.settlementId.value).put("transaction", s.transaction.transactionId.value)
        .put("request", s.transaction.requestId.value).put("kind", s.transaction.kind.name).put("source", s.transaction.source.name)
        .put("rule", s.transaction.ruleVersion.value).put("money", money(s.transaction.money) ?: JSONObject.NULL)
        .put("balance", s.balance.units).put("vehicle", s.changedVehicle?.let(::vehicle) ?: JSONObject.NULL).put("at", s.confirmedAt.value)
    fun settlement(j: JSONObject) = Settlement(SettlementId(j.getString("id")),
        Transaction(TransactionId(j.getString("transaction")), RequestId(j.getString("request")), TransactionKind.valueOf(j.getString("kind")),
            InputSource.valueOf(j.getString("source")), RuleVersion(j.getString("rule")), money(moneyObject(j))),
        GameMoney(j.getLong("balance")), j.optJSONObject("vehicle")?.let(::vehicle), EpochMillis(j.getLong("at")))
}
