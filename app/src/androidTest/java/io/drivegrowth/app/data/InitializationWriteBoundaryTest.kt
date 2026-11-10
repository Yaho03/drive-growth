package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.util.UUID

/** C 독립 반례의 기대값을 보존한 회귀. 실제 helper를 사용하며 실행 근거는 외부 검증 기록을 따른다. */
@Suppress("DEPRECATION")
class InitializationWriteBoundaryTest : InstrumentationTestCase() {
    private lateinit var store: SqliteGameChangeStore
    private lateinit var ledger: InitializationLedger
    private lateinit var name: String
    private lateinit var command: InitializationCommand
    private val context get() = instrumentation.targetContext
    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp()
        name = "independent-initialization-${UUID.randomUUID()}.db"
        val content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        store = SqliteGameChangeStore(context, InputSource.SIMULATION, name)
        ledger = InitializationLedger(store) { ContractResult.Success(InitializationAuthority(InputSource.SIMULATION, content, FirstGrant.CANDIDATES.toSet())) }
        val state = ok(ledger.readInitialization(ok(ledger.profileId())))
        val proof = (state.status as ProfileInitializationStatus.New).evidence
        command = InitializationCommand(state.profileId, proof, state.contentVersion,
            GameChange(FirstGrant.REQUEST_ID, TransactionKind.GARAGE, state.source, state.ruleVersion,
                MoneyChange(MoneyDirection.CREDIT, FirstGrant.INITIAL_MONEY), VehicleWrite.Add(
                    OwnedVehicle(OwnedVehicleId("initial-grant"), VehicleId("hyundai_casper"), 1, Revision(0),
                        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT))), 3)
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rows(): List<String> = buildList {
        raw { db -> for (table in listOf("account", "vehicles", "shop", "initialization_profile", "initialization_requests", "settlements")) {
            db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c -> while (c.moveToNext()) add(table + ":" +
                (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "<null>" else c.getString(it) }) }
        } }
    }
    private fun ignoredWrite(trigger: String) {
        val before = rows()
        raw { it.execSQL(trigger) }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.commitInitialization(command))
        assertEquals(before, rows())
        raw { it.execSQL("DROP TRIGGER review_ignore") }
        val first = ok(ledger.commitInitialization(command))
        assertEquals(GameMoney(1000), ok(store.balance()))
        assertEquals(first.grant.changedVehicle, ok(store.listOwnedVehicles()).single())
        assertEquals(first, ok(ledger.commitInitialization(command)))
    }
    fun testIgnoredAccountCreditCannotFinalizeInitialization() = ignoredWrite(
        "CREATE TRIGGER review_ignore BEFORE UPDATE ON account BEGIN SELECT RAISE(IGNORE); END")
    fun testIgnoredFirstCarInsertCannotFinalizeInitialization() = ignoredWrite(
        "CREATE TRIGGER review_ignore BEFORE INSERT ON vehicles BEGIN SELECT RAISE(IGNORE); END")
    fun testMissingMarkerCannotRemoveCapacityLimitForGenericAdd() = missingMarker(false)
    fun testConfiguredGachaDoesNotSkipInitializationIntegrity() = missingMarker(true)
    fun testOnlyCommonReceiptCannotBypassCapacity() = missingMarker(false, true)
    private fun missingMarker(configuredGacha: Boolean, onlyCommonReceipt: Boolean = false) {
        val first = ok(ledger.commitInitialization(command))
        val car = first.grant.changedVehicle!!
        fun extra(id: String) = GameChange(RequestId(id), TransactionKind.GARAGE, command.grant.source, command.grant.ruleVersion,
            vehicle = VehicleWrite.Add(car.copy(ownedVehicleId = OwnedVehicleId(id))))
        ok(store.commitGameChange(extra("extra1"))); ok(store.commitGameChange(extra("extra2")))
        raw { db ->
            db.execSQL("DELETE FROM gacha_progress")
            if (configuredGacha) db.execSQL("INSERT INTO gacha_progress VALUES(1,0,0,0)")
            db.execSQL("UPDATE initialization_profile SET marker_request=NULL WHERE id=1")
            if (onlyCommonReceipt) db.execSQL("DELETE FROM initialization_requests")
        }
        val before = rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.readInitialization(command.profileId))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGameChange(extra("extra3")))
        assertEquals(before, rows())
    }
    fun testHistoricalReplaySurvivesGrowthAndSpending() {
        val first = ok(ledger.commitInitialization(command))
        val car = first.grant.changedVehicle!!
        ok(store.commitGameChange(GameChange(RequestId("grow-after-initialization"), TransactionKind.GARAGE,
            command.grant.source, command.grant.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(30)),
            VehicleWrite.Update(car.ownedVehicleId, car.revision, 2, VehicleLifecycle.OWNED, 0))))
        assertEquals(first, ok(ledger.commitInitialization(command)))
        assertEquals(GameMoney(970), ok(store.balance()))
        assertEquals(2, ok(store.listOwnedVehicles()).single().level)
    }
    fun testLegacyOtherDomainReceiptAtFixedIdDoesNotBecomeInitialization() {
        raw { db -> db.execSQL("INSERT INTO settlements(request_id,settlement_id,command,result,viewed) VALUES(?,?,?,?,?)",
            arrayOf(FirstGrant.REQUEST_ID.value, "legacy-other-domain", "[]", "{}", 1)) }
        val car = (command.grant.vehicle as VehicleWrite.Add).vehicle.copy(ownedVehicleId = OwnedVehicleId("ordinary-car"))
        ok(store.commitGameChange(GameChange(RequestId("ordinary-add"), TransactionKind.GARAGE,
            command.grant.source, command.grant.ruleVersion, vehicle = VehicleWrite.Add(car))))
        assertEquals(car, ok(store.listOwnedVehicles()).single())
    }
    fun testWhitespaceInitializationOrphanFailsAndPreservesWholeFile() {
        val car = ok(ledger.commitInitialization(command)).grant.changedVehicle!!
        fun add(id: String) = GameChange(RequestId(id), TransactionKind.GARAGE, command.grant.source,
            command.grant.ruleVersion, vehicle = VehicleWrite.Add(car.copy(ownedVehicleId = OwnedVehicleId(id))))
        ok(store.commitGameChange(add("fill-one"))); ok(store.commitGameChange(add("fill-two")))
        raw { db ->
            db.execSQL("UPDATE initialization_profile SET marker_request=NULL WHERE id=1")
            db.execSQL("DELETE FROM initialization_requests")
            db.execSQL("UPDATE settlements SET command=? || command WHERE request_id=?",
                arrayOf(" \n\t", command.grant.requestId.value))
        }
        store.close()
        val file = context.getDatabasePath(name); val before = file.readBytes()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.readInitialization(command.profileId))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGameChange(add("fourth-car")))
        store.close(); assertTrue(before.contentEquals(file.readBytes()))
    }
    fun testWhitespaceLegacyArrayIsNotAnInitializationOrphan() {
        raw { db -> db.execSQL("INSERT INTO settlements(request_id,settlement_id,command,result,viewed) VALUES(?,?,?,?,?)",
            arrayOf(FirstGrant.REQUEST_ID.value, "legacy-array-with-space", " \n[]", "{}", 1)) }
        val car = (command.grant.vehicle as VehicleWrite.Add).vehicle.copy(ownedVehicleId = OwnedVehicleId("legacy-array-car"))
        ok(store.commitGameChange(GameChange(RequestId("legacy-array-add"), TransactionKind.GARAGE,
            command.grant.source, command.grant.ruleVersion, vehicle = VehicleWrite.Add(car))))
        assertEquals(car, ok(store.listOwnedVehicles()).single())
    }
}
