package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOfferId
import io.drivegrowth.core.delivery.DestinationKey
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.garage.VehicleUpgrade
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 앱 assets의 실제 #148 계획을 기존 공용 DB에서 확정한다. 프로필/재화는 격리 시험 입력이다. */
@Suppress("DEPRECATION")
class MaterialLedgerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var content: GameContentSnapshot
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var versions: ContractResult<MaterialVersions>
    private val target = OwnedVehicle(OwnedVehicleId("target"), VehicleId("hyundai_casper"), 2, Revision(0),
        VehicleLifecycle.OWNED, Rarity.RARE, 50, GameMoney(1000), VehicleAcquisitionSource.GACHA)
    private val material = target.copy(ownedVehicleId = OwnedVehicleId("material"), level = 7, rarity = Rarity.ADVANCED,
        levelProgressPercent = 25, acquisitionCost = GameMoney(750), acquisitionSource = VehicleAcquisitionSource.GACHA)
    private fun open(provide: Boolean = true) = SqliteGameChangeStore(context, source, name,
        materialVersions = if (provide) ({ versions }) else null)
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun failed(e: ContractError) = ContractResult.Failure(e)
    private fun add(car: OwnedVehicle) = ok(store.commitGameChange(GameChange(RequestId("add/" + car.ownedVehicleId.value),
        TransactionKind.GARAGE, source, content.ruleVersion, vehicle = VehicleWrite.Add(car))))
    private fun command(id: String = "enhance") = ok(VehicleUpgrade(content).prepareMaterial(store, RequestId(id), source,
        VehicleRef(target.ownedVehicleId, target.revision), VehicleRef(material.ownedVehicleId, material.revision)))
    private fun reopen(provide: Boolean = true) { store.close(); store = open(provide) }
    private fun history() = ok(store.listVehicleHistory())
    private fun balance() = ok(store.balance())
    override fun setUp() {
        super.setUp()
        content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        versions = ContractResult.Success(MaterialVersions(content.catalog.contentVersion, content.ruleVersion))
        name = "material-" + System.nanoTime() + ".db"
        store = open()
        ok(store.commitGameChange(GameChange(RequestId("fund"), TransactionKind.GARAGE, source, content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(1000)))))
        add(target); add(material)
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun sql(vararg statements: String) = ok(store.atomic { db ->
        statements.forEach { db.execSQL(it) }; ContractResult.Success(Unit)
    })
    private fun assertUnchanged(c: MaterialCommand, error: ContractError) {
        val cars = history(); val money = balance(); val receipts = ok(store.listUnviewedSettlements())
        assertEquals(failed(error), store.commitMaterial(c))
        assertEquals(cars, history()); assertEquals(money, balance()); assertEquals(receipts, ok(store.listUnviewedSettlements()))
    }

    fun testActualPreparedCommandCommitsBothRolesAndReplaysAfterReopen() {
        val c = command(); assertEquals(0L, c.cost.units)
        val saved = ok(store.commitMaterial(c))
        assertEquals(target.copy(level = 4, levelProgressPercent = 0, revision = Revision(1)), saved.target)
        assertEquals(material.copy(lifecycle = VehicleLifecycle.CONSUMED, revision = Revision(1)), saved.material)
        assertEquals(GameMoney(1000), saved.balance); assertNull(saved.transaction.money)
        assertEquals(TransactionKind.GARAGE, saved.transaction.kind)
        assertEquals(listOf(saved.target), ok(store.listOwnedVehicles()))
        assertEquals(setOf(saved.target, saved.material), history().toSet())
        assertEquals(listOf(target.ownedVehicleId), ok(store.listOwnedVehicles()).map { it.ownedVehicleId })
        reopen(); assertEquals(saved, ok(store.commitMaterial(c)))
        assertEquals(1, ok(store.listUnviewedSettlements()).count { it.transaction.requestId == c.requestId })
        ok(store.markViewed(saved.settlementId)); assertEquals(saved, ok(store.commitMaterial(c)))
    }

    fun testReplayPrecedesAuthorityActivitiesAndCurrentVehiclesAfterSale() {
        val c = command().copy(cost = GameMoney(200)) // 계약의 양수 경계이며 제품 재료 비용 제안이 아니다.
        val first = ok(store.commitMaterial(c))
        ok(store.commitGameChange(GameChange(RequestId("grow"), TransactionKind.GARAGE, source, content.ruleVersion,
            vehicle = VehicleWrite.Update(target.ownedVehicleId, Revision(1), 5, VehicleLifecycle.OWNED, 0))))
        ok(store.commitGameChange(GameChange(RequestId("sell"), TransactionKind.GARAGE, source, content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(9)), VehicleWrite.Update(target.ownedVehicleId, Revision(2), 5, VehicleLifecycle.SOLD, 0))))
        reopen(false)
        assertEquals(failed(ContractError.DATA_UNAVAILABLE), store.readMaterialState(target.ownedVehicleId, material.ownedVehicleId))
        assertEquals(first, ok(store.commitMaterial(c))); assertEquals(GameMoney(809), balance())
        assertTrue(ok(store.listOwnedVehicles()).isEmpty())
    }

    fun testWholeCommandDifferencesConflictWithoutMutation() {
        val c = command(); val first = ok(store.commitMaterial(c))
        val changes = listOf(c.copy(source = InputSource.VEHICLE), c.copy(cost = GameMoney(1)),
            c.copy(contentVersion = ContentVersion("other")), c.copy(ruleVersion = RuleVersion("other")),
            c.copy(target = c.target.copy(level = 5)), c.copy(target = c.target.copy(expectedRevision = Revision(99))),
            c.copy(material = c.material.copy(levelProgressPercent = 0)), c.copy(target = c.material, material = c.target))
        changes.forEach { assertUnchanged(it, ContractError.STATE_CONFLICT) }
        assertEquals(first, ok(store.commitMaterial(c)))
    }

    fun testSourceVersionsMissingAuthorityAndExplicitFailureAreNotDefaulted() {
        val c = command()
        assertUnchanged(c.copy(source = InputSource.VEHICLE), ContractError.INVALID_INPUT)
        assertUnchanged(c.copy(contentVersion = ContentVersion("old")), ContractError.STATE_CONFLICT)
        assertUnchanged(c.copy(ruleVersion = RuleVersion("old")), ContractError.STATE_CONFLICT)
        versions = failed(ContractError.PERMISSION_DENIED)
        assertUnchanged(c, ContractError.PERMISSION_DENIED)
        reopen(false); assertUnchanged(c, ContractError.DATA_UNAVAILABLE)
    }

    fun testStaleEitherRevisionInsufficientFundsAndMissingVehiclePreserveEverything() {
        val c = command()
        assertUnchanged(c.copy(target = c.target.copy(expectedRevision = Revision(2))), ContractError.STATE_CONFLICT)
        assertUnchanged(c.copy(material = c.material.copy(expectedRevision = Revision(2))), ContractError.STATE_CONFLICT)
        assertUnchanged(c.copy(cost = GameMoney(1001)), ContractError.INSUFFICIENT_FUNDS)
        assertUnchanged(c.copy(material = c.material.copy(ownedVehicleId = OwnedVehicleId("absent"))), ContractError.DATA_UNAVAILABLE)
        assertEquals(failed(ContractError.DATA_UNAVAILABLE), store.readMaterialState(target.ownedVehicleId, OwnedVehicleId("absent")))
    }

    fun testInvalidRolesGrowthAndDifferentModelAreRejected() {
        val c = command()
        assertUnchanged(c.copy(material = c.material.copy(ownedVehicleId = target.ownedVehicleId)), ContractError.INVALID_INPUT)
        assertUnchanged(c.copy(target = c.target.copy(level = 2, levelProgressPercent = 50)), ContractError.INVALID_INPUT)
        assertUnchanged(c.copy(material = c.material.copy(level = 8)), ContractError.INVALID_INPUT)
        val other = material.copy(ownedVehicleId = OwnedVehicleId("other"), vehicleId = VehicleId("hyundai_sonata")); add(other)
        assertUnchanged(c.copy(material = c.material.copy(ownedVehicleId = other.ownedVehicleId)), ContractError.INVALID_INPUT)
    }

    fun testLiveRentalReturningAndRepairStateOfEitherRoleBlockPreparedCommand() {
        val c = command()
        for ((index, car) in listOf(target, material).withIndex()) {
            val other = if (car == target) material else target
            ok(store.rental.setResting(other.ownedVehicleId, true)); ok(store.rental.setResting(car.ownedVehicleId, false))
            val id = RequestId("visit-$index")
            val terms = RentalTerms(content.ruleVersion, SimulatedCustomer(30, 36), 0, EpochMillis(0), 15, GameMoney(0), RoundingMode.DOWN)
            assertEquals(car.ownedVehicleId, ok(assign(store, id, terms)))
            assertEquals(VehicleActivity.RENTED, ok(store.rental.activity(car.ownedVehicleId)))
            // revision을 현재값으로 맞춰도 live 활동만으로 거절되어야 한다.
            fun currentCommand(): MaterialCommand {
                val state=ok(store.readMaterialState(target.ownedVehicleId,material.ownedVehicleId))
                return c.copy(target=c.target.copy(expectedRevision=state.target.vehicle.revision),
                    material=c.material.copy(expectedRevision=state.material.vehicle.revision))
            }
            assertUnchanged(currentCommand(), ContractError.STATE_CONFLICT)
            ok(store.rental.account(id, EpochMillis(0), EpochMillis(3_600_000), null)); ok(store.rental.settle(id))
            assertEquals(VehicleActivity.RETURNING, ok(store.rental.activity(car.ownedVehicleId)))
            assertUnchanged(currentCommand(), ContractError.STATE_CONFLICT)
            assertEquals(VehicleActivity.NEEDS_REPAIR, ok(store.rental.finishReturn(id, EpochMillis(4_500_000))))
            assertUnchanged(currentCommand(), ContractError.STATE_CONFLICT)
            // 다음 역할의 격리 사례를 위해 이미 시험한 대여 행만 제거한다. 실제 수리 성공으로 표시하지 않는다.
            sql("DELETE FROM rentals", "DELETE FROM visits")
        }
    }

    fun testMalformedLiveActivityIsStorageFailureAndRestingFlagIsPreserved() {
        val c = command(); ok(store.rental.setResting(target.ownedVehicleId, true))
        val terms = RentalTerms(content.ruleVersion, SimulatedCustomer(30,36), 99, EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        ok(assign(store, RequestId("active"), terms))
        sql("UPDATE rentals SET phase='UNKNOWN_PHASE'")
        assertUnchanged(c, ContractError.STORAGE_FAILURE)
        sql("DELETE FROM rentals", "DELETE FROM visits")
        val state=ok(store.readMaterialState(target.ownedVehicleId,material.ownedVehicleId))
        ok(store.commitMaterial(c.copy(target=c.target.copy(expectedRevision=state.target.vehicle.revision),
            material=c.material.copy(expectedRevision=state.material.vehicle.revision))))
        assertTrue(target.ownedVehicleId in ok(store.rental.restingVehicles()))
    }

    private fun assign(s: SqliteGameChangeStore, id: RequestId, terms: RentalTerms): ContractResult<OwnedVehicleId?> =
        s.rental.assign(RentalRequest(id, target.vehicleId, 1), content.catalog,
            mapOf(target.vehicleId to RentalCategory.SEDAN), mapOf(target.vehicleId to GameMoney(100)), terms)

    fun testCommitFailureRollsBackBothVehiclesMoneyAndHistoryThenRetryWorks() {
        val c = command().copy(cost = GameMoney(200)); val cars = history()
        store.beforeCommit = { throw SQLiteException("commit failure") }
        assertEquals(failed(ContractError.STORAGE_FAILURE), store.commitMaterial(c))
        reopen(); assertEquals(cars, history()); assertEquals(GameMoney(1000), balance())
        assertEquals(0L, ok(store.read { db -> db.rawQuery("SELECT count(*) FROM material_requests", null).use { it.moveToFirst(); it.getLong(0) } }))
        assertEquals(GameMoney(800), ok(store.commitMaterial(c)).balance)
        reopen(); assertEquals(GameMoney(800), ok(store.commitMaterial(c)).balance)
    }

    fun testSecondVehicleWriteFailureRollsBackFirstVehicle() {
        val c = command(); val cars = history()
        sql("CREATE TRIGGER refuse_material BEFORE UPDATE ON vehicles WHEN NEW.owned_id='material' BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertUnchanged(c, ContractError.STORAGE_FAILURE)
        assertEquals(cars, history())
        sql("DROP TRIGGER refuse_material"); ok(store.commitMaterial(c))
    }

    fun testSameMaterialRacingThroughTwoConnectionsCommitsOnlyOnce() {
        val c = command().copy(cost = GameMoney(100))
        val third = target.copy(ownedVehicleId = OwnedVehicleId("third")); add(third)
        val otherCommand = c.copy(requestId = RequestId("other"), target = c.target.copy(ownedVehicleId = third.ownedVehicleId))
        open().use { other ->
            val results = race({ store.commitMaterial(c) }, { other.commitMaterial(otherCommand) })
            assertEquals(1, results.count { it is ContractResult.Success })
            assertEquals(1, results.count { it is ContractResult.Failure })
        }
        reopen(); assertEquals(GameMoney(900), balance())
        assertEquals(1, history().count { it.lifecycle == VehicleLifecycle.CONSUMED })
    }

    fun testConcurrentSameCommandReplaysOneReceipt() {
        val c = command().copy(cost = GameMoney(100))
        open().use { other ->
            val results = race({ store.commitMaterial(c) }, { other.commitMaterial(c) }).map { ok(it) }
            assertEquals(results[0], results[1]); assertEquals(GameMoney(900), balance())
        }
    }

    fun testRentalAssignmentRacingConsumptionCannotRentConsumedMaterial() {
        val c = command(); ok(store.rental.setResting(target.ownedVehicleId, true))
        val terms = RentalTerms(content.ruleVersion, SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        open().use { other ->
            val results = race({ store.commitMaterial(c) }, { assign(other, RequestId("rental-race"), terms) })
            val assigned = ok(results[1])
            if (assigned != null) {
                assertEquals(material.ownedVehicleId, assigned)
                assertEquals(failed(ContractError.STATE_CONFLICT), results[0])
                assertEquals(VehicleLifecycle.OWNED, history().single { it.ownedVehicleId == assigned }.lifecycle)
            } else assertTrue(results[0] is ContractResult.Success)
        }
    }

    private fun <T> race(a: () -> ContractResult<T>, b: () -> ContractResult<T>): List<ContractResult<T>> {
        val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
        try {
            val jobs = listOf(a,b).map { f -> pool.submit(Callable { gate.await(); f() }) }
            gate.countDown(); return jobs.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    fun testLegacyDeliveryVisitAndGachaIdsConflictInBothDirections() {
        val c = command(); ok(store.commitMaterial(c))
        val credit = GameChange(c.requestId, TransactionKind.GARAGE, source, content.ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(1)))
        assertEquals(failed(ContractError.STATE_CONFLICT), store.commitGameChange(credit))
        assertUnchanged(c.copy(requestId = RequestId("fund")), ContractError.STATE_CONFLICT)
        assertUnchanged(c.copy(requestId = RequestId("drive/new")), ContractError.STATE_CONFLICT)
        assertUnchanged(c.copy(requestId = RequestId("delivery/new/settle")), ContractError.STATE_CONFLICT)
        // 대여 방문과 가챠도 공용 요청 ID 공간을 검사한다. 후자는 권위 공급보다 먼저 충돌해야 한다.
        val terms = RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        assertEquals(failed(ContractError.STATE_CONFLICT), assign(store, c.requestId, terms))
        val authority = GachaAuthority(source,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),30,
            Rarity.RARE,setOf(target.vehicleId),Rarity.entries.toSet())
        val draw = GachaCommand(c.requestId,source,authority.contentVersion,authority.ruleVersion,Revision(0),1,3,0,
            GachaTicketUse.FREE,GachaAward(target.copy(ownedVehicleId=OwnedVehicleId("draw"),level=1,revision=Revision(0),
                levelProgressPercent=0,rarity=Rarity.COMMON,acquisitionCost=GameMoney(0),acquisitionSource=VehicleAcquisitionSource.GACHA),1,false))
        assertEquals(failed(ContractError.STATE_CONFLICT),store.commitGacha(draw))
    }

    fun testMissingMaterialHistoryFailsWithoutChangingCorruptedFile() {
        val c = command().copy(cost = GameMoney(200))
        val saved = ok(store.commitMaterial(c))
        val cars = history(); val money = balance()
        store.close()
        raw { db ->
            assertEquals(1L, db.rawQuery("SELECT count(*) FROM settlements WHERE request_id=?",
                arrayOf(c.requestId.value)).use { it.moveToFirst(); it.getLong(0) })
            db.execSQL("DELETE FROM material_requests WHERE request_id=?", arrayOf(c.requestId.value))
        }
        val file = context.getDatabasePath(name); val before = file.readBytes()
        store = open(provide = false) // 현재 권위 없이도 자기 이력 손실을 먼저 식별한다.
        for (retry in listOf(c, c.copy(cost = GameMoney(201)))) {
            assertEquals(failed(ContractError.STORAGE_FAILURE), store.commitMaterial(retry))
            assertEquals(cars, history()); assertEquals(money, balance())
            assertEquals(setOf(saved.target, saved.material), history().toSet())
        }
        store.close(); assertTrue(before.contentEquals(file.readBytes())); store = open()
    }

    fun testMissingCommonReceiptFailsWithoutChangingCorruptedFile() {
        val c = command(); ok(store.commitMaterial(c)); store.close()
        raw { it.execSQL("DELETE FROM settlements WHERE request_id='enhance'") }
        val file = context.getDatabasePath(name); val before = file.readBytes(); store = open()
        assertEquals(failed(ContractError.STORAGE_FAILURE), store.commitMaterial(c))
        store.close(); assertTrue(before.contentEquals(file.readBytes())); store = open()
    }

    fun testCorruptOriginalCommandStateSnapshotsAndRawMoneyFailWithFilePreservation() {
        val c = command().copy(cost = GameMoney(200)); ok(store.commitMaterial(c))
        val columns = listOf("material_requests" to "command", "material_requests" to "original_state", "material_requests" to "result", "settlements" to "result")
        for ((table,column) in columns) {
            val original = ok(store.read { db -> db.rawQuery("SELECT $column FROM $table WHERE request_id='enhance'",null).use { it.moveToFirst(); it.getString(0) } })
            val badValues = when (column) {
                "command" -> listOf(original.replace("\"cost\":200", "\"cost\":201"), original.replace("\"cost\":200", "\"cost\":\"200\""))
                "original_state" -> listOf(original.replace("\"balance\":1000", "\"balance\":1001"), original.replace("\"activity\":\"IDLE\"", "\"activity\":\"RENTED\""))
                else -> listOf(original.replace("\"kind\":\"GARAGE\"", "\"kind\":\"DELIVERY\"")) +
                    listOf("200.75", "200.0", "\"200\"", "2e2", "92233720368547758070").map { original.replace("\"amount\":200", "\"amount\":$it") } +
                    listOf("800.5", "\"800\"").map { original.replace("\"balance\":800", "\"balance\":$it") } +
                    if (table == "material_requests") listOf(original.replace("\"lifecycle\":\"CONSUMED\"", "\"lifecycle\":\"OWNED\""),
                        original.replace("\"revision\":1", "\"revision\":99")) else emptyList()
            }
            for (bad in badValues) {
                assertTrue("손상 주입 실패 $table/$column", bad != original)
                store.close(); raw { db -> db.execSQL("UPDATE $table SET $column=? WHERE request_id='enhance'", arrayOf(bad)) }
                val file=context.getDatabasePath(name); val before=file.readBytes(); store=open()
                assertEquals("$table/$column: $bad", failed(ContractError.STORAGE_FAILURE), store.commitMaterial(c))
                assertEquals(GameMoney(800),balance()); assertEquals(2,history().size)
                store.close(); assertTrue(before.contentEquals(file.readBytes()))
                raw { db -> db.execSQL("UPDATE $table SET $column=? WHERE request_id='enhance'", arrayOf(original)) };store=open()
            }
        }
    }

    fun testV5MigrationPreservesAllExistingTablesAndReplaysLegacyThenMaterial() {
        val c = command(); val cars = history(); val receipts = ok(store.listUnviewedSettlements())
        sql("INSERT INTO discoveries VALUES ('hyundai_casper','RARE')", "INSERT INTO gacha_progress VALUES(1,2,3,4)")
        store.close(); raw { db -> db.execSQL("DROP TABLE material_requests"); db.execSQL("DROP TABLE repair_requests"); db.execSQL("DROP TABLE initialization_requests"); db.execSQL("DROP TABLE initialization_profile"); db.execSQL("DROP TABLE first_gift_requests"); db.execSQL("DROP TABLE first_gift_markers");listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};db.execSQL("DROP TABLE host_command_checkpoint");stripDeliveryCheckpointFixture(db); db.version = 5 }
        store = open(); assertEquals(cars,history()); assertEquals(receipts,ok(store.listUnviewedSettlements()))
        assertEquals(GameMoney(1000),balance()); assertEquals(setOf(CollectionKey(target.vehicleId,Rarity.RARE)),ok(store.discoveredVehicles()))
        assertEquals(3,ok(store.rental.shop()).capacity)
        assertEquals(4L,ok(store.read { db -> db.rawQuery("SELECT tickets FROM gacha_progress",null).use { it.moveToFirst();it.getLong(0) } }))
        val funding=receipts.single { it.transaction.requestId==RequestId("fund") }
        assertEquals(funding,ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(1000))))))
        val saved=ok(store.commitMaterial(c));reopen();assertEquals(saved,ok(store.commitMaterial(c)))
    }

    fun testV5MigrationFailureKeepsOriginalBytes() {
        store.close();raw { db -> db.version=5 } // 기존 표와의 충돌로 이전 실패를 주입한다.
        val file=context.getDatabasePath(name);val before=file.readBytes();store=open()
        assertEquals(failed(ContractError.STORAGE_FAILURE),store.balance());store.close()
        assertTrue(before.contentEquals(file.readBytes()));raw { it.version=17 };store=open()
    }

    fun testIncomeSelectionAndEmptyVisitReserveIdsBeforeMaterialAndViceVersa() {
        val c = command()
        val offer = io.drivegrowth.core.delivery.DeliveryOffer.Income(DeliveryOfferId("offer"), target.vehicleId, GameMoney(400))
        val list = io.drivegrowth.core.delivery.DeliveryList(1, source, java.time.LocalDate.of(2026,11,9),
            content.catalog.contentVersion, content.ruleVersion, listOf(offer), 3)
        val proposed = io.drivegrowth.core.delivery.DeliveryProposed(DestinationKey("dest"), EstimatedRouteDistance(1000,source),list,list.offers)
        ok(store.delivery.saveList(list)); ok(store.delivery.select(proposed,offer.id,RequestId("income")))
        assertUnchanged(c.copy(requestId=RequestId("income")),ContractError.STATE_CONFLICT)
        ok(store.commitMaterial(c))
        assertEquals(failed(ContractError.STATE_CONFLICT),store.delivery.select(proposed,offer.id,c.requestId))
        ok(store.rental.setResting(target.ownedVehicleId,true))
        val terms=RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        assertNull(ok(assign(store,RequestId("empty-visit"),terms)))
        assertUnchanged(c.copy(requestId=RequestId("empty-visit")),ContractError.STATE_CONFLICT)
    }

    fun testExistingGachaRequestCannotBecomeMaterial() {
        val c=command("draw")
        sql("INSERT INTO gacha_progress VALUES(1,0,0,1)")
        val authority=GachaAuthority(source,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),30,
            Rarity.RARE,setOf(target.vehicleId),Rarity.entries.toSet())
        store.close();store=SqliteGameChangeStore(context,source,name,gachaAuthority={ContractResult.Success(authority)},materialVersions={versions})
        val draw=GachaCommand(c.requestId,source,authority.contentVersion,authority.ruleVersion,Revision(0),1,3,0,GachaTicketUse.FREE,
            GachaAward(target.copy(ownedVehicleId=OwnedVehicleId("drawn"),level=1,levelProgressPercent=0,revision=Revision(0),rarity=Rarity.COMMON,
                acquisitionCost=GameMoney(0),acquisitionSource=VehicleAcquisitionSource.GACHA),1,false))
        ok(store.commitGacha(draw));assertUnchanged(c,ContractError.STATE_CONFLICT)
        assertEquals(0L,ok(store.readGachaState()).progress.freeTickets)
    }

    fun testV1UpgradePreservesOriginalLedgerAndAddsMaterialCapability() {
        val c=command();val cars=history();val receipts=ok(store.listUnviewedSettlements());store.close()
        raw { db ->
            val tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use { rows ->
                buildList { while(rows.moveToNext()) add(rows.getString(0)) }
            }
            tables.filter { it !in setOf("account","vehicles","settlements","android_metadata","sqlite_sequence") }
                .forEach { db.execSQL("DROP TABLE $it") }
            db.version=1
        }
        store=open();assertEquals(cars,history());assertEquals(receipts,ok(store.listUnviewedSettlements()))
        val saved=ok(store.commitMaterial(c));reopen();assertEquals(saved,ok(store.commitMaterial(c)))
    }

    fun testFailedV4UpgradeRollsBackTablesCreatedBeforeFailure() {
        store.close()
        val tables=listOf("delivery_lists","delivery_requests","delivery_active","delivery_routes","delivery_outcomes",
            "delivery_held","delivery_changes","driving_days","driving_drives","delivery_route_history","delivery_hold_outcomes")
        raw { db -> tables.forEach { db.execSQL("DROP TABLE $it") };db.version=4 }
        val file=context.getDatabasePath(name);val before=file.readBytes();store=open()
        assertEquals(failed(ContractError.STORAGE_FAILURE),store.balance());store.close()
        assertTrue(before.contentEquals(file.readBytes()))
        raw { db ->
            assertEquals(4,db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='delivery_active'",null).use { assertFalse(it.moveToFirst()) }
            db.execSQL("DROP TABLE material_requests"); db.execSQL("DROP TABLE repair_requests"); db.execSQL("DROP TABLE initialization_requests"); db.execSQL("DROP TABLE initialization_profile"); db.execSQL("DROP TABLE first_gift_requests"); db.execSQL("DROP TABLE first_gift_markers");listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};db.execSQL("DROP TABLE host_command_checkpoint");stripDeliveryCheckpointFixture(db)
        }
        store=open();ok(store.commitMaterial(command()))
    }

    fun testRawBalanceCannotBeTruncatedBeforeMaterialCommit() {
        val c=command();store.close();raw { it.execSQL("UPDATE account SET balance=1000.5 WHERE id=1") }
        val file=context.getDatabasePath(name);val before=file.readBytes();store=open()
        assertEquals(failed(ContractError.STORAGE_FAILURE),store.readMaterialState(target.ownedVehicleId,material.ownedVehicleId))
        assertEquals(failed(ContractError.STORAGE_FAILURE),store.commitMaterial(c))
        store.close();assertTrue(before.contentEquals(file.readBytes()));raw { it.execSQL("UPDATE account SET balance=1000 WHERE id=1") };store=open()
    }

    fun testLongMaximumCostAndZeroBalanceReplayWithoutLoss() {
        val c=command().copy(cost=GameMoney(Long.MAX_VALUE))
        ok(store.commitGameChange(GameChange(RequestId("maximum-fund"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(Long.MAX_VALUE-1000)))))
        val saved=ok(store.commitMaterial(c));assertEquals(GameMoney(0),saved.balance)
        assertEquals(MoneyChange(MoneyDirection.DEBIT,GameMoney(Long.MAX_VALUE)),saved.transaction.money)
        reopen();assertEquals(saved,ok(store.commitMaterial(c)));assertEquals(GameMoney(0),balance())
    }

    private fun raw(action: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,
        SQLiteDatabase.OPEN_READWRITE).use(action)
}
