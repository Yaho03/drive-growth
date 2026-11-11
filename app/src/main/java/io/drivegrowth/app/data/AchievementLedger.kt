package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.Garage
import org.json.JSONObject
import java.util.UUID

internal data class AchievementAuthority(
    val definitions: Map<AchievementMetric, AchievementDefinition>,
    val catalog: VehicleCatalogSnapshot,
) {
    init { require(definitions.keys == AchievementMetric.entries.toSet() && definitions.all { it.key == it.value.metric }) }
}

/** 달성·수령 마커만 소유한다. 돈과 금융 원문은 기존 account·settlements에 보관한다. */
class AchievementLedger internal constructor(private val store: SqliteGameChangeStore) {
    internal var authority: (() -> ContractResult<AchievementAuthority>)? = null

    companion object {
        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS achievements (
                profile TEXT NOT NULL, source TEXT NOT NULL, id TEXT NOT NULL, milestone TEXT NOT NULL,
                request_id TEXT UNIQUE, balance_before INTEGER,
                PRIMARY KEY(profile, source, id),
                CHECK((request_id IS NULL AND balance_before IS NULL) OR
                      (request_id IS NOT NULL AND balance_before IS NOT NULL AND balance_before >= 0)))""")
        }
    }

    private data class Row(val milestone: AchievementMilestone, val requestId: RequestId?, val before: GameMoney?)

    /** 원거래 안에서 실제 저장된 상태만 읽는다. 미연결 원장은 기존 기능을 그대로 수행한다. */
    internal fun observe(db: SQLiteDatabase, metrics: Set<AchievementMetric>): ContractResult<Unit> {
        val provider = authority ?: return ContractResult.Success(Unit)
        check(db.inTransaction())
        val profile = when (val read = store.localProfileId()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val pending = metrics.filter { row(db, profile, it) == null }.toSet()
        if (pending.isEmpty()) return ContractResult.Success(Unit)
        val input = when (val loaded = provider()) {
            is ContractResult.Failure -> return loaded
            is ContractResult.Success -> loaded.value
        }
        val values = when (val read = measure(db, input.catalog, pending)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        recordMilestones(db, profile, input, values)
        return ContractResult.Success(Unit)
    }

    fun read(profile: ProfileId): ContractResult<List<AchievementProgress>> = store.atomic { db ->
        val identity = identity(profile)
        if (identity is ContractResult.Failure) return@atomic identity
        verifyClaims(db)
        val existing = AchievementMetric.entries.associateWith { row(db, profile, it) }
        val pending = existing.filterValues { it == null }.keys
        val input = if (pending.isEmpty()) null else when (val supplied = authority?.invoke()) {
            null -> return@atomic failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return@atomic supplied
            is ContractResult.Success -> supplied.value
        }
        val values = if (input == null) emptyMap() else when (val read = measure(db, input.catalog, pending)) {
            is ContractResult.Failure -> return@atomic read
            is ContractResult.Success -> read.value
        }
        if (input != null) recordMilestones(db, profile, input, values)
        ContractResult.Success(AchievementMetric.entries.map { metric ->
            val saved = existing[metric] ?: row(db, profile, metric)
            if (saved == null) AchievementProgress(checkNotNull(input).definitions.getValue(metric), values[metric])
            else {
                saved.requestId?.let { if (recorded(db, it) == null) corrupt("업적 수령 원문 누락") }
                AchievementProgress(saved.milestone.definition, saved.milestone.measure, saved.milestone, saved.requestId != null)
            }
        })
    }

    private fun recordMilestones(db: SQLiteDatabase, profile: ProfileId, input: AchievementAuthority,
        values: Map<AchievementMetric, AchievementMeasure>) {
        for ((metric, measure) in values) {
            val definition = input.definitions.getValue(metric)
            if (!definition.reached(measure)) continue
            val milestone = AchievementMilestone(profile, store.source, definition, measure, store.now())
            db.insertOrThrow("achievements", null, ContentValues().apply {
                put("profile", profile.value)
                put("source", store.source.name)
                put("id", metric.id)
                put("milestone", AchievementJson.milestone(milestone).toString())
            })
            if (row(db, profile, metric) != Row(milestone, null, null)) corrupt("업적 달성 실제 쓰기 불일치")
        }
    }

    private fun measure(db: SQLiteDatabase, catalog: VehicleCatalogSnapshot,
        metrics: Set<AchievementMetric>): ContractResult<Map<AchievementMetric, AchievementMeasure>> {
        val values = mutableMapOf<AchievementMetric, AchievementMeasure>()
        if (AchievementMetric.OWNED in metrics || AchievementMetric.COLLECTION in metrics) {
            val history = store.readVehicles(db)
            val owned = when (val checked = Garage(catalog).owned(history)) {
                is ContractResult.Failure -> return checked
                is ContractResult.Success -> checked.value
            }
            if (AchievementMetric.OWNED in metrics) values[AchievementMetric.OWNED] = AchievementMeasure(owned.size)
            if (AchievementMetric.COLLECTION in metrics) {
                val cells = history.mapTo(mutableSetOf()) { CollectionKey(it.vehicleId, it.rarity) }
                values[AchievementMetric.COLLECTION] = AchievementMeasure(cells.size,
                    Math.multiplyExact(catalog.vehicles.size, Rarity.entries.size), catalog.contentVersion)
            }
        }
        if (AchievementMetric.CUSTOMERS in metrics)
            values[AchievementMetric.CUSTOMERS] = AchievementMeasure(store.rental.completedRentalCount(db))
        if (AchievementMetric.SHOP in metrics) {
            val level = db.rawQuery("SELECT level FROM shop WHERE id=1", null).use {
                if (!it.moveToFirst()) corrupt("업적 가게 레벨 누락")
                it.integer(0, 1, Int.MAX_VALUE.toLong()).toInt()
            }
            values[AchievementMetric.SHOP] = AchievementMeasure(level)
        }
        return ContractResult.Success(values)
    }

    fun validateUnstarted(command: AchievementClaimCommand): ContractResult<Unit> = store.atomic { db ->
        when (val checked = identity(command.profileId, command.source)) {
            is ContractResult.Failure -> checked
            is ContractResult.Success -> {
                verifyClaims(db)
                eligible(db, command)
            }
        }
    }

    fun claim(command: AchievementClaimCommand): ContractResult<AchievementClaimSettlement> = store.atomic { db ->
        val checked = identity(command.profileId, command.source)
        if (checked is ContractResult.Failure) return@atomic checked
        recorded(db, command.requestId)?.let { original ->
            return@atomic if (original.command == command) ContractResult.Success(original) else failure(ContractError.STATE_CONFLICT)
        }
        verifyClaims(db)
        val allowed = eligible(db, command)
        if (allowed is ContractResult.Failure) return@atomic allowed
        val before = store.readBalance(db)
        val balance = when (val result = achievementBalance(command, before)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val definition = command.milestone.definition
        val receipt = Settlement(SettlementId(UUID.randomUUID().toString()),
            Transaction(TransactionId(UUID.randomUUID().toString()), command.requestId, TransactionKind.GARAGE,
                command.source, definition.version, MoneyChange(MoneyDirection.CREDIT, definition.reward)),
            balance, null, store.now())
        if (db.update("account", ContentValues().apply { put("balance", balance.units) }, "id=1", null) != 1)
            corrupt("업적 보상 계좌 누락")
        store.insertSettlement(db, command.requestId, AchievementJson.command(command).toString(), receipt)
        val touched = db.update("achievements", ContentValues().apply {
            put("request_id", command.requestId.value)
            put("balance_before", before.units)
        }, "profile=? AND source=? AND id=? AND request_id IS NULL",
            arrayOf(command.profileId.value, command.source.name, definition.metric.id))
        val result = AchievementClaimSettlement(command, receipt)
        if (touched != 1 || store.readBalance(db) != balance || recorded(db, command.requestId) != result)
            corrupt("업적 보상·마커 실제 쓰기 불일치")
        ContractResult.Success(result)
    }

    fun settlement(id: RequestId): ContractResult<AchievementClaimSettlement?> = store.atomic { db ->
        if (!id.value.startsWith(ACHIEVEMENT_CLAIM_PREFIX)) return@atomic failure(ContractError.INVALID_INPUT)
        ContractResult.Success(recorded(db, id))
    }

    /** 미확정 거래를 제외하고 분류할 수 있는 검증된 업적 원결과만 반환한다. */
    internal fun confirmed(db: SQLiteDatabase, id: RequestId): AchievementClaimSettlement? = recorded(db, id)

    private fun eligible(db: SQLiteDatabase, command: AchievementClaimCommand): ContractResult<Unit> {
        val saved = row(db, command.profileId, command.milestone.definition.metric)
        return if (saved != null && saved.milestone == command.milestone && saved.requestId == null &&
            !store.requestUnavailable(db, command.requestId, achievementClaim = true)) ContractResult.Success(Unit)
        else failure(ContractError.STATE_CONFLICT)
    }

    private fun row(db: SQLiteDatabase, profile: ProfileId, metric: AchievementMetric): Row? =
        db.rawQuery("SELECT profile,source,id,milestone,request_id,balance_before FROM achievements WHERE id=?",
            arrayOf(metric.id)).use { rows ->
            if (!rows.moveToFirst()) return@use null
            val raw = rows.text(3)
            val evidence = AchievementJson.milestone(JSONObject(raw))
            if (rows.text(0) != profile.value || rows.text(1) != store.source.name || rows.text(2) != metric.id ||
                evidence.profileId != profile || evidence.source != store.source || evidence.definition.metric != metric ||
                AchievementJson.milestone(evidence).toString() != raw) corrupt("업적 달성 원문 불일치")
            val request = if (rows.isNull(4)) null else RequestId(rows.text(4))
            val before = if (rows.isNull(5)) null else GameMoney(rows.integer(5, 0, Long.MAX_VALUE))
            if ((request == null) != (before == null) || rows.moveToNext()) corrupt("업적 수령 키 불일치")
            Row(evidence, request, before)
        }

    private fun verifyClaims(db: SQLiteDatabase) {
        val prefix = ACHIEVEMENT_CLAIM_PREFIX
        db.rawQuery("SELECT request_id,command FROM settlements WHERE request_id GLOB ?",
            arrayOf(prefix + "*")).use { rows ->
            while (rows.moveToNext()) {
                val raw = rows.text(1)
                if (raw.startsWith("{") && JSONObject(raw).optString("type") == AchievementJson.TYPE &&
                    recorded(db, RequestId(rows.text(0))) == null) corrupt("업적 고아 정산")
            }
        }
    }

    private fun recorded(db: SQLiteDatabase, id: RequestId): AchievementClaimSettlement? {
        val marker = db.rawQuery("SELECT profile,id FROM achievements WHERE request_id=?", arrayOf(id.value)).use {
            if (!it.moveToFirst()) null else ProfileId(it.text(0)) to (AchievementMetric.entries.singleOrNull { metric -> metric.id == it.text(1) } ?: corrupt("업적 원마커 ID"))
        }
        return db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?", arrayOf(id.value)).use { rows ->
            if (!rows.moveToFirst()) {
                if (marker != null) corrupt("업적 원정산 누락")
                return@use null
            }
            val raw = rows.text(1)
            if (marker == null) {
                if (raw.startsWith("{") && JSONObject(raw).optString("type") == AchievementJson.TYPE) corrupt("업적 원마커 누락")
                return@use null
            }
            val (profile, metric) = marker
            val saved = row(db, profile, metric) ?: corrupt("업적 원마커 누락")
            val command = AchievementJson.command(JSONObject(raw))
            val receiptRaw = rows.text(2)
            val receipt = LedgerJson.settlement(JSONObject(receiptRaw))
            val tx = receipt.transaction
            val definition = saved.milestone.definition
            if (identity(profile) is ContractResult.Failure || saved.requestId != id || command.requestId != id ||
                command.milestone != saved.milestone || AchievementJson.command(command).toString() != raw ||
                receipt.settlementId.value != rows.text(0) || LedgerJson.settlement(receipt).toString() != receiptRaw ||
                tx.requestId != id || tx.source != store.source || tx.kind != TransactionKind.GARAGE ||
                tx.ruleVersion != definition.version || tx.money != MoneyChange(MoneyDirection.CREDIT, definition.reward) ||
                receipt.changedVehicle != null ||
                achievementBalance(command, saved.before ?: corrupt("업적 원잔액 누락")) != ContractResult.Success(receipt.balance))
                corrupt("업적 원정산 불일치")
            AchievementClaimSettlement(command, receipt)
        }
    }

    private fun identity(profile: ProfileId, source: InputSource = store.source): ContractResult<Unit> =
        when (val actual = store.localProfileId()) {
            is ContractResult.Failure -> actual
            is ContractResult.Success -> if (actual.value == profile && source == store.source) ContractResult.Success(Unit)
                else failure(ContractError.INVALID_INPUT)
        }

    private fun failure(error: ContractError) = ContractResult.Failure(error)
    private fun corrupt(message: String): Nothing = throw SQLiteException(message)
}

internal object AchievementJson {
    const val TYPE = "achievement-claim-v1"

    fun milestone(value: AchievementMilestone): JSONObject {
        val d = value.definition
        val m = value.measure
        return JSONObject().put("profile", value.profileId.value).put("source", value.source.name)
            .put("id", d.metric.id).put("version", d.version.value).put("goal", d.goal)
            .put("reward", d.reward.units).put("trial", d.trial).put("count", m.count)
            .put("total", m.total ?: JSONObject.NULL).put("content", m.contentVersion?.value ?: JSONObject.NULL)
            .put("observed_at", value.observedAt.value)
    }

    fun milestone(j: JSONObject): AchievementMilestone {
        val metric = AchievementMetric.entries.singleOrNull { it.id == j.getString("id") }
            ?: throw IllegalArgumentException("업적 ID")
        val definition = AchievementDefinition(metric, RuleVersion(j.getString("version")), int(j, "goal"),
            GameMoney(LedgerJson.integer(j, "reward", 1, Long.MAX_VALUE)),
            j.get("trial") as? Boolean ?: throw IllegalArgumentException("업적 시험값"))
        val measure = AchievementMeasure(int(j, "count"), if (j.isNull("total")) null else int(j, "total"),
            if (j.isNull("content")) null else ContentVersion(j.getString("content")))
        return AchievementMilestone(ProfileId(j.getString("profile")), InputSource.valueOf(j.getString("source")),
            definition, measure, EpochMillis(LedgerJson.integer(j, "observed_at", 0, Long.MAX_VALUE)))
    }

    fun command(c: AchievementClaimCommand): JSONObject = JSONObject().put("type", TYPE)
        .put("request", c.requestId.value).put("milestone", milestone(c.milestone))

    fun command(j: JSONObject): AchievementClaimCommand {
        require(j.getString("type") == TYPE)
        return AchievementClaimCommand(RequestId(j.getString("request")), milestone(j.getJSONObject("milestone")))
    }

    private fun int(j: JSONObject, key: String) = LedgerJson.integer(j, key, 0, Int.MAX_VALUE.toLong()).toInt()
}
