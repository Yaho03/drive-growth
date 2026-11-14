package io.drivegrowth.app.host

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Looper
import android.os.SystemClock
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.DeliveryDistanceSession
import io.drivegrowth.app.data.InitializationAuthority
import io.drivegrowth.app.data.MaterialVersions
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.io.Closeable

data class GameIdentity(val profileId: ProfileId, val source: InputSource)

internal fun chooseGameSource(existing: Map<InputSource, ContractResult<InputSource>>, newSource: InputSource): ContractResult<InputSource> {
    for ((expected, read) in existing) when (read) {
        is ContractResult.Failure -> return read
        is ContractResult.Success -> if (read.value != expected) return ContractResult.Failure(ContractError.INVALID_INPUT)
    }
    return when (existing.size) {
        0 -> ContractResult.Success(newSource)
        1 -> ContractResult.Success(existing.keys.single())
        else -> ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
}

/** 기존 PRD의500 시험 규칙과 같은 콘텐츠·실제 shop 조회를 사용한다. 시안 공간/레벨을 넣지 않는다. */
internal fun gameGachaAuthority(content: GameContentSnapshot, source: InputSource, level: Int, capacity: Int): ContractResult<GachaAuthority> {
    if (level < 1 || capacity < 1) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val ids = content.unlockedAt(level).map { it.vehicleId }.toSet()
    if (ids.isEmpty()) {
        val validLockedCandidates = content.vehicles.isNotEmpty() &&
            content.vehicles.all { candidate -> content.catalog.vehicles.any { it.vehicleId == candidate.vehicleId } }
        val detail = if (validLockedCandidates) ContractFailureDetail.GACHA_NO_UNLOCKED_CANDIDATES else null
        return ContractResult.Failure(ContractError.DATA_UNAVAILABLE, detail)
    }
    if (ids.any { id -> content.catalog.vehicles.none { it.vehicleId == id } })
        return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    return ContractResult.Success(GachaAuthority(source, content.catalog.contentVersion, content.ruleVersion, level, capacity,
        GameMoney(500), content.gacha.pity, content.gacha.guaranteedFrom, ids,
        Rarity.entries.filter { (content.gacha.weights[it] ?: 0) > 0 }.toSet()))
}

class GameSession internal constructor(val identity: GameIdentity, val store: SqliteGameChangeStore,
    val content: ContractResult<GameContentSnapshot>) : Closeable {
    override fun close() = store.close()
}

/** worker 전용. 기존 모드를 보존하고 NEW 여부는 helper가 발급한 근거에만 맡긴다. */
class GameSessionFactory internal constructor(context: Context, private val databaseNames: Map<InputSource, String>,
    private val newProfileSource: InputSource,
    private val questDefinitions: (() -> ContractResult<io.drivegrowth.core.quest.QuestDefinitions>)?,
    private val clock: () -> Long) {
    internal constructor(context: Context, databaseNames: Map<InputSource, String>, newProfileSource: InputSource,
        questDefinitions: (() -> ContractResult<io.drivegrowth.core.quest.QuestDefinitions>)? = null) :
        this(context, databaseNames, newProfileSource, questDefinitions, System::currentTimeMillis)
    constructor(context: Context, newProfileSource: InputSource = InputSource.VEHICLE,
        questDefinitions: (() -> ContractResult<io.drivegrowth.core.quest.QuestDefinitions>)? = null) : this(context,
        InputSource.entries.associateWith { "game-ledger-${it.name.lowercase()}.db" }, newProfileSource, questDefinitions, System::currentTimeMillis)
    private val context = context.applicationContext
    private var bound: GameIdentity? = null
    // 현재 호스트에서 수락한 수신자만 거리 조회에 공급한다. 새 호스트는 과거 수신자를 상속하지 않는다.
    @Volatile internal var deliveryDistanceSession: DeliveryDistanceSession? = null
    internal var deliveryDistanceElapsedRealtime: () -> Long = SystemClock::elapsedRealtime
    private var achievementTracking = false
    internal var onFirstCollectionOwnership: ((io.drivegrowth.app.data.FirstCollectionOwnership) -> Unit)? = null

    internal fun enableAchievements() { achievementTracking = true }

    @Synchronized
    fun open(): ContractResult<GameSession> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "GameSessionFactory는 worker에서 사용" }
        return try {
            val existing = databaseNames.filterValues { context.getDatabasePath(it).exists() }
                .mapValues { (source, name) -> readExistingSource(name, source) }
            val source = when (val selected = chooseGameSource(existing, newProfileSource)) {
                is ContractResult.Failure -> return selected
                is ContractResult.Success -> selected.value
            }
            if (bound != null && (bound!!.source != source || source !in existing))
                return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            val name = databaseNames[source] ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            val content = when (val loaded = AssetGameContent(context.assets).load()) {
                is ContentLoad.Loaded -> ContractResult.Success(loaded.snapshot)
                is ContentLoad.Rejected -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            }
            lateinit var store: SqliteGameChangeStore
            store = SqliteGameChangeStore(context, source, name, clock = clock,
                questDefinitions = questDefinitions,
                gachaAuthority = {
                    when (content) {
                        is ContractResult.Failure -> content
                        is ContractResult.Success -> when (val shop = store.rental.shop()) {
                            is ContractResult.Failure -> shop
                            is ContractResult.Success -> gameGachaAuthority(content.value, source, shop.value.progress.shopLevel, shop.value.capacity)
                        }
                    }
                },
                // 실제 onCreate만 소비한다. 기존/이전 DB의 missing 진행에는 삽입하지 않는다.
                initialGachaProgress = GachaProgress(Revision(0), 0, 0),
                initializationAuthority = {
                    when (content) {
                        is ContractResult.Failure -> content
                        is ContractResult.Success -> ContractResult.Success(InitializationAuthority(source, content.value,
                            FirstGrant.CANDIDATES.filter { id -> content.value.catalog.vehicles.any { it.vehicleId == id } &&
                                content.value.vehicles.any { it.vehicleId == id } }.toSet()))
                    }
                }, firstGiftContent = { content }, repairContent = { content },
                materialVersions = {
                    when (content) {
                        is ContractResult.Failure -> content
                        is ContractResult.Success -> ContractResult.Success(MaterialVersions(content.value.catalog.contentVersion, content.value.ruleVersion))
                    }
                })
            store.currentDeliveryDistanceSession = { deliveryDistanceSession }
            store.onFirstCollectionOwnership = onFirstCollectionOwnership
            val achievementAuthority by lazy {
                when (content) {
                    is ContractResult.Failure -> content
                    is ContractResult.Success -> when (val definition = store.trialDefinitions.load()) {
                        is ContractResult.Failure -> definition
                        is ContractResult.Success -> when (val achievements = definition.value.achievementDefinitions) {
                            is ContractResult.Failure -> achievements
                            is ContractResult.Success -> ContractResult.Success(io.drivegrowth.app.data.AchievementAuthority(
                                achievements.value, content.value.catalog))
                        }
                    }
                }
            }
            if (achievementTracking) store.achievements.authority = { achievementAuthority }
            when (val profile = store.localProfileId()) {
                is ContractResult.Failure -> { store.close(); profile }
                is ContractResult.Success -> {
                    val identity = GameIdentity(profile.value, source)
                    if (bound != null && bound != identity) { store.close(); ContractResult.Failure(ContractError.INVALID_INPUT) }
                    else { bound = identity; ContractResult.Success(GameSession(identity, store, content)) }
                }
            }
        } catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
          catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
    }

    private fun readExistingSource(name: String, expected: InputSource): ContractResult<InputSource> =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY,
            DatabaseErrorHandler { throw SQLiteException("기존 프로필 원본 손상") }).use database@ { db ->
            if (db.version <= 0) return@database ContractResult.Failure(ContractError.STORAGE_FAILURE)
            db.rawQuery("PRAGMA quick_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok") return@database ContractResult.Failure(ContractError.STORAGE_FAILURE)
            }
            db.rawQuery("SELECT source FROM account WHERE id=1", null).use {
                if (!it.moveToFirst() || it.getType(0) != Cursor.FIELD_TYPE_STRING)
                    return@use ContractResult.Failure(ContractError.STORAGE_FAILURE)
                val source = InputSource.entries.firstOrNull { s -> s.name == it.getString(0) }
                if (source != expected || it.moveToNext()) ContractResult.Failure(ContractError.INVALID_INPUT)
                else ContractResult.Success(source)
            }
        }
}
