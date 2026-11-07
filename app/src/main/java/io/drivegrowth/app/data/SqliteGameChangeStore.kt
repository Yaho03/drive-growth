package io.drivegrowth.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import io.drivegrowth.core.contract.*
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
    private val clock: () -> Long = System::currentTimeMillis,
) : GameChangeStore, VehicleHistoryStore, Closeable {
    private val helper = LedgerHelper(context.applicationContext, databaseName, source)

    /** 디스크 쓰기 후 COMMIT 이전의 실패를 재현하기 위한 모듈 내부 시험 지점. */
    internal var beforeCommit: (() -> Unit)? = null
    internal var lastStorageFailure: LedgerFailureReason? = null
        private set

    @Synchronized
    override fun commitGameChange(change: GameChange): ContractResult<Settlement> {
        if (change.source != source) return failure(ContractError.INVALID_INPUT)
        return atomic { db -> record(db, change, LedgerJson.command(change).toString()) }
    }

    val rental = RentalLedger(this)

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

    internal fun record(db: SQLiteDatabase, change: GameChange, command: String): ContractResult<Settlement> {
        val existing = db.rawQuery("SELECT command, result FROM settlements WHERE request_id = ?", arrayOf(change.requestId.value)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        if (existing != null) {
            return if (existing.first == command) ContractResult.Success(LedgerJson.settlement(JSONObject(existing.second)))
            else failure(ContractError.STATE_CONFLICT)
        }
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
        val vehicle = when (val write = change.vehicle) {
            null -> null
            is VehicleWrite.Add -> {
                if (findVehicle(db, write.vehicle.ownedVehicleId) != null) return failure(ContractError.STATE_CONFLICT)
                if (write.vehicle.revision != Revision(0) || write.vehicle.lifecycle != VehicleLifecycle.OWNED) return failure(ContractError.INVALID_INPUT)
                write.vehicle
            }
            is VehicleWrite.Update -> {
                val previous = findVehicle(db, write.ownedVehicleId) ?: return failure(ContractError.DATA_UNAVAILABLE)
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
        if (vehicle != null) {
            val values = ContentValues().apply {
                put("owned_id", vehicle.ownedVehicleId.value)
                put("payload", LedgerJson.vehicle(vehicle).toString())
            }
            if (change.vehicle is VehicleWrite.Add) db.insertOrThrow("vehicles", null, values)
            else if (db.update("vehicles", values, "owned_id = ?", arrayOf(vehicle.ownedVehicleId.value)) != 1) throw SQLiteException("차량 갱신 실패")
        }
        db.insertOrThrow("settlements", null, ContentValues().apply {
            put("request_id", change.requestId.value)
            put("settlement_id", result.settlementId.value)
            put("command", command)
            put("result", LedgerJson.settlement(result).toString())
            put("viewed", 0)
        })
        return ContractResult.Success(result)
    }

    @Synchronized
    internal fun <T> read(query: (SQLiteDatabase) -> T): ContractResult<T> =
        storage { ContractResult.Success(query(helper.readableDatabase)) }

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
        val known = catalog.vehicles.mapTo(mutableSetOf()) { it.vehicleId }
        if (keys.any { it.vehicleId !in known }) return failure(ContractError.INVALID_INPUT)
        return atomic { db ->
            for (key in keys) db.execSQL("INSERT OR IGNORE INTO discoveries(vehicle_id, rarity) VALUES (?, ?)",
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
    fun markViewed(id: SettlementId): ContractResult<Unit> = storage {
        val count = helper.writableDatabase.update("settlements", ContentValues().apply { put("viewed", 1) }, "settlement_id = ?", arrayOf(id.value))
        if (count == 0) failure(ContractError.DATA_UNAVAILABLE) else ContractResult.Success(Unit)
    }

    @Synchronized
    override fun close() = helper.close()

    internal fun readBalance(db: SQLiteDatabase): GameMoney = db.rawQuery("SELECT balance FROM account WHERE id = 1", null).use {
        if (!it.moveToFirst()) throw SQLiteException("거래 계정 누락")
        GameMoney(it.getLong(0))
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

private class LedgerHelper(context: Context, name: String, private val source: InputSource) : SQLiteOpenHelper(
    context, name, null, 3,
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
    }
    private fun createDiscoveries(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE discoveries (vehicle_id TEXT NOT NULL, rarity TEXT NOT NULL, PRIMARY KEY(vehicle_id, rarity))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 1..2 || newVersion != 3) throw LedgerStorageException(LedgerFailureReason.UNSUPPORTED_SCHEMA)
        verifySource(db)
        // 기존 원장과 개체 JSON은 그대로 보존한다.
        if (oldVersion == 1) RentalLedger.createTables(db)
        createDiscoveries(db)
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
    private fun integer(j: JSONObject, key: String, min: Long, max: Long): Long {
        val value = when (val raw = j.get(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw JSONException("$key 정수 형식 오류")
        }
        if (value !in min..max) throw JSONException("$key 정수 범위 오류")
        return value
    }
    private fun money(m: MoneyChange?) = m?.let { JSONObject().put("direction", it.direction.name).put("amount", it.amount.units) }
    private fun money(j: JSONObject?): MoneyChange? = j?.let { MoneyChange(MoneyDirection.valueOf(it.getString("direction")), GameMoney(it.getLong("amount"))) }
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
            InputSource.valueOf(j.getString("source")), RuleVersion(j.getString("rule")), money(j.optJSONObject("money"))),
        GameMoney(j.getLong("balance")), j.optJSONObject("vehicle")?.let(::vehicle), EpochMillis(j.getLong("at")))
}
