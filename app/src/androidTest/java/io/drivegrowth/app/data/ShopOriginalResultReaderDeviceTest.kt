package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import io.drivegrowth.core.rental.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.RoundingMode

/** 같은 실제 원장에 만든 원결과를 읽는다. 모든 reader 호출 전후 전체 사용자 표를 대조한다. */
@Suppress("DEPRECATION")
class ShopOriginalResultReaderDeviceTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private var issuedProfile: ProfileId? = null
    private val profile get() = checkNotNull(issuedProfile)
    private lateinit var definition: TrialDefinition
    private var available = true
    private var definitionReads = 0
    private var decorationReads = 0
    private val car = OwnedVehicle(OwnedVehicleId("original-car"), VehicleId("original-model"), 1, Revision(0),
        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
    private val reader get() = ShopOriginalResultReader(store)
    private fun open() = SqliteGameChangeStore(context, source, name).apply {
        trialDefinitions = TrialDefinitionSource { definitionReads++; if (available) ContractResult.Success(definition) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
        decorationDefinitions = { decorationReads++; if (available) ContractResult.Success(trialDecorationDefinitions()) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
    }
    private fun <T> ok(r: ContractResult<T>): T { assertTrue(r.toString(), r is ContractResult.Success); return (r as ContractResult.Success).value }
    private fun raw(f: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows(): List<String> = buildList { raw { db ->
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { r -> buildList { while (r.moveToNext()) add(r.getString(0)) } }
        for (t in tables) db.rawQuery("SELECT * FROM $t ORDER BY rowid", null).use { r ->
            while (r.moveToNext()) add(t + ":" + (0 until r.columnCount).joinToString("|") { if (r.isNull(it)) "<null>" else r.getString(it) })
        }
    } }
    private fun <T> unchanged(block: () -> T): T { val before = rows(); val result = block(); assertEquals(before, rows()); return result }
    private fun reopen() { store.close(); store = open() }
    override fun setUp() {
        super.setUp(); name = "shop-original-${System.nanoTime()}.db"
        definition = ok(AssetTrialDefinitionProvider(context.assets).load()); store = open(); issuedProfile = ok(store.localProfileId())
        ok(store.commitGameChange(GameChange(RequestId("fund"), TransactionKind.GARAGE, source, RuleVersion("fixture"),
            MoneyChange(MoneyDirection.CREDIT, GameMoney(1000)), VehicleWrite.Add(car))))
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun decoration(id: String = "free", paid: Boolean = false, revision: Int = 0, apply: Boolean = false) = DecorationCommand(
        RequestId(id), profile, source, if (paid) "blue-sign" else "neutral-sign",
        if (apply) DecorationAction.APPLY_OWNED else DecorationAction.BUY_AND_APPLY,
        trialDecorationDefinitions().ruleVersion, Revision(revision), GameMoney(if (paid && !apply) 100 else 0))
    private fun unlock(level: Int = 2) { raw { it.execSQL("UPDATE shop SET level=?,capacity=?", arrayOf(level, level * 2 + 1)) } } // 해금 후 표시 조건 fixture이며 제품 확장 성공이 아니다.
    private fun execute(c: HostCheckpointCommand): HostCheckpointResult { ok(store.hostCommands.save(profile, c)); val r = ok(store.hostCommands.execute(profile, c)); ok(store.hostCommands.finish(profile, c)); return r }
    private fun buy(c: DecorationCommand): DecorationSettlement = (execute(HostCheckpointCommand.Decoration(c)) as HostCheckpointResult.Decoration).value
    private fun count(id: RequestId): Long { var n = 0L; raw { db -> db.rawQuery("SELECT count(*) FROM settlements WHERE request_id=?", arrayOf(id.value)).use { it.moveToFirst(); n = it.getLong(0) } }; return n }
    private fun expandCommand(id: String = "expand") = HostCheckpointCommand.Expand(RequestId(id), source, definition.version, definition.expansions.offers.first())
    private fun prepareExpansion(): HostCheckpointCommand.Expand {
        // 중앙 첫 확장의 완료 고객3 조건을 기존 실제 assign/account/settle/return으로 충족한다.
        val catalog = VehicleCatalogSnapshot(ContentVersion("fixture"), listOf(VehicleDefinition(car.vehicleId, "시험차")))
        for (i in 0..2) {
            val id = RequestId("customer-$i"); val start = i * 4_500_000L
            val terms = RentalTerms(definition.rules, SimulatedCustomer(30, 36), 99, EpochMillis(start), 15, GameMoney(0), RoundingMode.DOWN)
            assertEquals(car.ownedVehicleId, ok(store.rental.assign(RentalRequest(id, car.vehicleId, 1), catalog,
                mapOf(car.vehicleId to RentalCategory.SEDAN), mapOf(car.vehicleId to GameMoney(100)), terms)))
            ok(store.rental.account(id, EpochMillis(start), EpochMillis(start + 3_600_000), null))
            ok(store.rental.settle(id)); ok(store.rental.finishReturn(id, EpochMillis(start + 4_500_000)))
        }
        return expandCommand()
    }
    private fun completeExpansion(): Pair<HostCheckpointCommand.Expand, Settlement> {
        val c = prepareExpansion(); val result = (execute(c) as HostCheckpointResult.Expand).value
        assertEquals(GameMoney(860), result.balance); return c to result
    }
    private fun stopDefinitions() { available = false; definitionReads = 0; decorationReads = 0 }
    private fun noDefinitions() { assertEquals(0, definitionReads); assertEquals(0, decorationReads) }
    private fun financialRaw(id: RequestId): String { var value = ""; raw { db -> db.rawQuery("SELECT result FROM settlements WHERE request_id=?", arrayOf(id.value)).use { assertTrue(it.moveToFirst()); value = it.getString(0) } }; return value }
    private fun writeFinancial(id: RequestId, value: String) { raw { it.execSQL("UPDATE settlements SET result=? WHERE request_id=?", arrayOf(value, id.value)) } }

    fun testMissingOldUiRequestDoesNotActivateOrCreateAnything() {
        stopDefinitions(); val unknown = RequestId("old-ui-only")
        unchanged { assertNull(ok(reader.decoration(profile, unknown))); assertNull(ok(reader.expansion(profile, unknown, expandCommand().offer))) }
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.decorations.state(profile)); noDefinitions()
    }
    fun testUnknownStartedDecorationStaysUnresolvedWithoutSlotCleanup() {
        ok(store.decorations.activate(profile)); val c = decoration(); ok(store.hostCommands.save(profile, HostCheckpointCommand.Decoration(c)))
        raw { db -> db.rawQuery("SELECT payload FROM host_command_checkpoint", null).use { it.moveToFirst(); val payload = it.getString(0)
            db.execSQL("UPDATE host_command_checkpoint SET phase=1,digest=?", arrayOf(HostCheckpointJson.digest(payload, 1))) } }
        stopDefinitions(); unchanged { assertNull(ok(reader.decoration(profile, c.requestId))) }; noDefinitions()
        assertEquals(HostCheckpointPhase.STARTED, ok(store.hostCommands.load(profile))!!.phase)
    }
    fun testFreeFinishedTypedResultSurvivesReopenWithoutFinancialRow() {
        ok(store.decorations.activate(profile)); val c = decoration(); val original = buy(c); assertNull(original.settlement)
        reopen(); stopDefinitions(); unchanged { assertEquals(original, ok(reader.decoration(profile, c.requestId))) }; noDefinitions(); assertEquals(0L, count(c.requestId))
        assertNull(ok(store.hostCommands.load(profile)))
    }
    fun testPaidFinishedResultKeepsOriginalBalanceAfterLaterCreditAndDefinitionFailure() {
        ok(store.decorations.activate(profile)); unlock(); val c = decoration("paid", true); val original = buy(c)
        assertEquals(GameMoney(900), original.state.balance)
        ok(store.commitGameChange(GameChange(RequestId("later"), TransactionKind.GARAGE, source, RuleVersion("later"), MoneyChange(MoneyDirection.CREDIT, GameMoney(50)))))
        reopen(); stopDefinitions(); unchanged { assertEquals(original, ok(reader.decoration(profile, c.requestId))) }; noDefinitions()
        assertEquals(GameMoney(950), ok(store.balance())); assertEquals(1L, count(c.requestId))
    }
    fun testOldPurchaseAndFreeReapplyDoNotOverwriteNewerSelection() {
        ok(store.decorations.activate(profile)); unlock(); val free = decoration(); val first = buy(free)
        val paid = decoration("paid", true, 1); buy(paid)
        val apply = decoration("apply", revision = 2, apply = true); val last = buy(apply)
        stopDefinitions(); unchanged { assertEquals(first, ok(reader.decoration(profile, free.requestId))); assertEquals(last, ok(reader.decoration(profile, apply.requestId))) }; noDefinitions()
        assertNull(last.settlement); assertEquals(0L, count(apply.requestId)); assertEquals("neutral-sign", ok(store.decorations.state(profile)).selectedId)
    }
    fun testAnotherSavedRequestIsPreservedWhileReadingFinishedResult() {
        ok(store.decorations.activate(profile)); unlock(); val c = decoration(); val original = buy(c)
        val next = HostCheckpointCommand.Decoration(decoration("next-paid", true, 1)); ok(store.hostCommands.save(profile, next))
        stopDefinitions(); unchanged { assertEquals(original, ok(reader.decoration(profile, c.requestId))) }; noDefinitions()
        assertEquals(next, ok(store.hostCommands.load(profile))!!.command)
    }
    fun testForeignProfileIsInvalidAndLookupDoesNotWrite() {
        val other = ProfileId("other-profile")
        unchanged { assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), reader.decoration(other, RequestId("unknown")))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), reader.expansion(other, RequestId("unknown"), expandCommand().offer)) }
    }
    fun testExistingForeignFinancialOwnerIsConflictForBothReaders() {
        stopDefinitions(); unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, RequestId("fund")))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, RequestId("fund"), expandCommand().offer)) }; noDefinitions()
    }
    fun testSameIdSavedForeignCommandIsConflictAndNotCancelled() {
        val id = RequestId("foreign-saved"); val c = HostCheckpointCommand.Garage(ContentVersion("fixture"),
            GameChange(id, TransactionKind.GARAGE, source, RuleVersion("fixture"), MoneyChange(MoneyDirection.CREDIT, GameMoney(1)),
                VehicleWrite.Update(car.ownedVehicleId, car.revision, car.level + 1, VehicleLifecycle.OWNED, car.levelProgressPercent)))
        ok(store.hostCommands.save(profile, c)); stopDefinitions()
        unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, id))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, id, expandCommand().offer)) }; noDefinitions()
        assertEquals(c, ok(store.hostCommands.load(profile))!!.command)
    }
    fun testLostDecorationModuleIsStorageFailureAndNotActivated() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c); raw { it.execSQL("DROP TABLE decoration_module") }
        unchanged { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.decoration(profile, c.requestId)) }
    }
    fun testLostTypedHistoryIsStorageFailureInsteadOfAbsentResult() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c); raw { it.execSQL("DELETE FROM decoration_requests") }
        unchanged { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.decoration(profile, c.requestId)) }
    }
    fun testPaidCanonicalBalanceAndTimeCorruptionAreStorageFailure() {
        ok(store.decorations.activate(profile)); unlock(); val c = decoration("paid", true); buy(c); val original = financialRaw(c.requestId)
        for (key in listOf("balance", "at")) {
            writeFinancial(c.requestId, JSONObject(original).let { it.put(key, it.getLong(key).toDouble() + 0.5).toString() })
            unchanged { assertEquals(key, ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.decoration(profile, c.requestId)) }
        }
        writeFinancial(c.requestId, original)
    }
    fun testFreeTypedResultRejectsUnexpectedFinancialRow() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c)
        raw { it.execSQL("INSERT INTO settlements SELECT ?,?,command,result,viewed FROM settlements WHERE request_id='fund'", arrayOf(c.requestId.value, "corrupt-extra")) }
        unchanged { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.decoration(profile, c.requestId)) }
    }
    fun testFinishedExpansionOriginalSurvivesReopenDefinitionsAndLaterBalanceChange() {
        val (c, original) = completeExpansion(); ok(store.commitGameChange(GameChange(RequestId("later"), TransactionKind.GARAGE, source,
            RuleVersion("later"), MoneyChange(MoneyDirection.CREDIT, GameMoney(50)))))
        reopen(); stopDefinitions(); unchanged { assertEquals(original, ok(reader.expansion(profile, c.requestId, c.offer))) }; noDefinitions()
        assertNull(ok(store.hostCommands.load(profile))); assertEquals(GameMoney(910), ok(store.balance()))
        assertEquals(1L, count(c.requestId))
    }
    fun testEveryDifferentExpansionOfferFieldIsConflictWithoutNewPurchase() {
        val (c, original) = completeExpansion(); val o = c.offer
        val changed = listOf(o.copy(ruleVersion = RuleVersion("other")), o.copy(trial = !o.trial), o.copy(fromLevel = o.fromLevel + 1),
            o.copy(currentCapacity = o.currentCapacity + 1), o.copy(nextCapacity = o.nextCapacity + 1),
            o.copy(requiredCustomers = o.requiredCustomers + 1), o.copy(price = GameMoney(o.price.units + 1)))
        stopDefinitions(); for (offer in changed) unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, c.requestId, offer)) }
        unchanged { assertEquals(original, ok(reader.expansion(profile, c.requestId, o))) }; noDefinitions()
    }
    fun testMissingExpansionResultDoesNotRunAnOtherwiseNewPurchasePath() {
        val c = expandCommand(); stopDefinitions(); unchanged { assertNull(ok(reader.expansion(profile, c.requestId, c.offer))) }; noDefinitions()
        assertEquals(GameMoney(1000), ok(store.balance())); assertEquals(3, ok(store.rental.shop()).capacity)
    }
    fun testFreeDecorationIdCannotBeUsedAsExpansionOrExpansionAsDecoration() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c)
        unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, c.requestId, expandCommand().offer)) }
        val (expand, _) = completeExpansion()
        unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, expand.requestId)) }
    }
    fun testExpansionCanonicalBalanceAndTimeCorruptionAreStorageFailure() {
        val (c, _) = completeExpansion(); val original = financialRaw(c.requestId)
        for (key in listOf("balance", "at")) { writeFinancial(c.requestId, JSONObject(original).let { it.put(key, it.getLong(key).toDouble() + 0.5).toString() })
            unchanged { assertEquals(key, ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.expansion(profile, c.requestId, c.offer)) } }
        writeFinancial(c.requestId, original)
    }
    fun testExpansionCommandFractionBooleanAndMissingFieldsAreStorageFailure() {
        val (c, _) = completeExpansion(); var original = ""
        raw { db -> db.rawQuery("SELECT command FROM settlements WHERE request_id=?", arrayOf(c.requestId.value)).use { it.moveToFirst(); original = it.getString(0) } }
        val bad = listOf(JSONArray(original).put(1, 1.5).toString(), JSONArray(original).put(5, "true").toString(), JSONArray(listOf("expand", 1)).toString())
        for (command in bad) { raw { it.execSQL("UPDATE settlements SET command=? WHERE request_id=?", arrayOf(command, c.requestId.value)) }
            unchanged { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.expansion(profile, c.requestId, c.offer)) } }
    }
    fun testExistingFacilityStrictPortIsReusedAfterFinishedSlot() {
        unlock(3); val c = HostCheckpointCommand.FacilityPurchase(RequestId("facility"), source, definition.version, definition.facilityOffers.getValue(Facility.CAR_WASH))
        val original = execute(c); reopen(); stopDefinitions()
        unchanged { assertEquals(original, ok(store.hostCommands.facilityResult(profile, c)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.hostCommands.facilityResult(profile, c.copy(definitionVersion = ContentVersion("other"))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, c.requestId))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, c.requestId, expandCommand().offer)) }; noDefinitions()
    }
    fun testAccountSourceCorruptionIsStorageFailureWithoutAnyRepair() {
        raw { it.execSQL("UPDATE account SET source='VEHICLE'") }
        unchanged { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.decoration(profile, RequestId("unknown")))
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.expansion(profile, RequestId("unknown"), expandCommand().offer)) }
    }
    fun testDecorationHistoryDigestCorruptionIsNotMissingOrSuccess() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c)
        raw { it.execSQL("UPDATE decoration_requests SET digest='corrupt'") }
        unchanged { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), reader.decoration(profile, c.requestId)) }
    }
    fun testUnknownStartedExpansionKeepsBalanceShopAndSlotUntouched() {
        val c = prepareExpansion(); ok(store.hostCommands.save(profile, c))
        raw { db -> db.rawQuery("SELECT payload FROM host_command_checkpoint", null).use { it.moveToFirst(); val payload = it.getString(0)
            db.execSQL("UPDATE host_command_checkpoint SET phase=1,digest=?", arrayOf(HostCheckpointJson.digest(payload, 1))) } }
        stopDefinitions(); unchanged { assertNull(ok(reader.expansion(profile, c.requestId, c.offer))) }; noDefinitions()
        assertEquals(GameMoney(1360), ok(store.balance())); assertEquals(3, ok(store.rental.shop()).capacity)
        assertEquals(HostCheckpointPhase.STARTED, ok(store.hostCommands.load(profile))!!.phase)
    }
    fun testDifferentWholeDecorationSlotCannotRebindAnOriginalResult() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c)
        val payload = HostCheckpointJson.encode(profile, HostCheckpointCommand.Decoration(c.copy(expectedPrice = GameMoney(1))))
        raw { it.execSQL("INSERT INTO host_command_checkpoint(id,payload,phase,digest) VALUES(1,?,0,?)", arrayOf(payload, HostCheckpointJson.digest(payload, 0))) }
        unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, c.requestId)) }
    }

    private fun addForeignVisitOwner(id: RequestId) {
        val catalog = VehicleCatalogSnapshot(ContentVersion("fixture"), listOf(VehicleDefinition(car.vehicleId, "시험차")))
        val command = RentalVisitCommand.encode(RentalRequest(id, car.vehicleId, 1), catalog,
            mapOf(car.vehicleId to RentalCategory.SEDAN), mapOf(car.vehicleId to GameMoney(100)),
            RentalTerms(definition.rules, SimulatedCustomer(30, 36), 99, EpochMillis(0), 15, GameMoney(0), RoundingMode.DOWN))
        raw { it.execSQL("INSERT INTO visits(id,command,assigned) VALUES(?,?,NULL)", arrayOf(id.value, command)) }
    }
    fun testExistingDecorationOriginalCannotHideAnotherDomainOwner() {
        ok(store.decorations.activate(profile)); val c = decoration(); buy(c); addForeignVisitOwner(c.requestId)
        unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, c.requestId)) }
    }
    fun testExistingExpansionOriginalCannotHideAnotherDomainOwner() {
        val (c, _) = completeExpansion(); addForeignVisitOwner(c.requestId)
        unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, c.requestId, c.offer)) }
    }

    fun testReservedPrefixesStayBlockedWithoutOriginals() {
        stopDefinitions()
        for (value in listOf("quest/unknown", "quest/claim/unknown", "delivery/unknown", "drive/unknown")) {
            val id = RequestId(value)
            unchanged { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.decoration(profile, id))
                assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reader.expansion(profile, id, expandCommand().offer)) }
        }
        noDefinitions()
    }
    fun testLegacyDefaultAndQuestClaimLookupKeepTheirOriginalBehavior() {
        val id = RequestId("quest/claim/not-recorded")
        unchanged {
            val values = ok(store.atomic { db -> ContractResult.Success(listOf(store.requestUnavailable(db, id),
                store.requestUnavailable(db, id, true),
                store.requestOwnedElsewhere(db, id, SqliteGameChangeStore.OriginalRequestOwner.EXPANSION))) })
            assertEquals(listOf(true, false, true), values)
        }
    }

}
