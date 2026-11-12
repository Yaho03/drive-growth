package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.achievement.AchievementClaimCommand
import io.drivegrowth.core.achievement.AchievementDefinition
import io.drivegrowth.core.achievement.AchievementMetric
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Historical DDL and hand-authored old-writer rows. Each database starts with its historical schema. */
@Suppress("DEPRECATION")
class Schema20UpgradeTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private val model = VehicleId("hyundai_casper")
    private val oldRule = RuleVersion("upgrade-fixture")
    private val rentalId = RequestId("old-rental")
    private val retiredId = OwnedVehicleId("retired")
    private val purchaseId = RequestId("achievement/claim/old-purchase")
    private val upgradeTime = 6_000_000L
    private val priorQuestTrackingTime = 5_000_000L
    private val offer = ShopExpansion(oldRule, true, 1, 3, 5, 1, GameMoney(50))
    private lateinit var name: String

    override fun setUp() {
        super.setUp()
        name = "schema20-upgrade-${UUID.randomUUID()}.db"
    }

    override fun tearDown() {
        context.deleteDatabase(name)
        super.tearDown()
    }

    fun testV2V10V12PreserveOriginalLedgerThroughSchema20AndReopen() {
        for (version in listOf(2, 10, 12)) {
            try {
                createHistoricalDatabase(version)
                val originalColumns = raw(::columns)
                val before = raw { snapshot(it, originalColumns) }
                SqliteGameChangeStore(context, source, name, clock = { upgradeTime }).use { store ->
                    assertEquals("v$version balance", GameMoney(434), ok(store.balance()))
                    val profile = ok(store.localProfileId())
                    if (version >= 10) assertEquals(ProfileId("local-older-profile"), profile)
                    val owned = ok(store.listOwnedVehicles()).single()
                    assertEquals(OwnedVehicleId("remaining"), owned.ownedVehicleId)
                    assertEquals(GameMoney(0), owned.acquisitionCost)
                    val history = ok(SettlementHistoryReader(store).read())
                    assertEquals(source, history.identity.source)
                    assertEquals(profile, history.identity.profileId)
                    assertEquals(4, history.entries.size)
                    val disposed = history.entries.single { it.settlement.transaction.requestId == RequestId("old-sale") }
                    assertEquals(retiredId, disposed.settlement.changedVehicle?.ownedVehicleId)
                    assertEquals(VehicleLifecycle.SOLD, disposed.settlement.changedVehicle?.lifecycle)
                    assertEquals(35, disposed.settlement.changedVehicle?.levelProgressPercent)
                    assertEquals(GameMoney(500), disposed.settlement.changedVehicle?.acquisitionCost)
                    assertEquals(1, ok(store.rental.completedRentalCount()))
                    val rented = history.entries.single { it.rental != null }
                    assertTrue(rented.viewed)
                    assertEquals(retiredId, rented.rental?.ownedVehicleId)
                    assertEquals(rented.rental, ok(store.rental.completedRental(rentalId)))
                    assertEquals(Observation.Missing(MissingReason.NO_DATA), rented.rentalBasis)
                }
                raw { db ->
                    assertEquals(20, db.version)
                    assertEquals("v$version historical columns", before, snapshot(db, originalColumns))
                    assertEquals("v$version visits have no host command",
                        0, count(db, "visits", "host_command IS NOT NULL"))
                    val routeColumns = columns(db).getValue("delivery_routes")
                    assertEquals(listOf("request_id", "route", "started_at", "observed_binding"), routeColumns)
                    assertEquals(0, count(db, "delivery_routes"))
                    assertEquals(1, count(db, "initialization_profile", "proof_id IS NULL AND marker_request IS NULL"))
                    val since = if (version == 12) priorQuestTrackingTime else upgradeTime
                    assertEquals(1, count(db, "quest_tracking", "complete_since = $since"))
                    assertEquals(0, count(db, "achievements"))
                }
                SqliteGameChangeStore(context, source, name).use { reopened ->
                    assertEquals(GameMoney(434), ok(reopened.balance()))
                    assertEquals(4, ok(SettlementHistoryReader(reopened).read()).entries.size)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    fun testLateV12UpgradeFailureRollsBackEarlierDdlAndPreservesOriginalBytes() {
        createHistoricalDatabase(12)
        // An invalid v20 column fails the final step after the helper has attempted v13 through v19.
        raw { it.execSQL("ALTER TABLE delivery_routes ADD COLUMN observed_binding INTEGER") }
        val before = raw { snapshot(it) }
        val originalSchema = raw(::schema)
        val file = context.getDatabasePath(name)
        val bytes = file.readBytes()
        SqliteGameChangeStore(context, source, name, clock = { upgradeTime }).use { store ->
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.balance())
        }
        assertTrue(bytes.contentEquals(file.readBytes()))
        raw {
            assertEquals(12, it.version)
            assertEquals(originalSchema, schema(it))
            assertEquals(before, snapshot(it))
        }
    }

    fun testMissingFieldsOnDisposedVehicleAreNotBackfilledByV2Upgrade() {
        createHistoricalDatabase(2)
        raw { db ->
            val oldPayload = vehicle("retired", 2, 2, "SOLD", "ADVANCED", 35, 500).apply {
                for (field in listOf("rarity", "levelProgressPercent", "acquisitionCost", "acquisitionSource")) remove(field)
            }
            db.execSQL("UPDATE vehicles SET payload=? WHERE owned_id=?", arrayOf(oldPayload.toString(), retiredId.value))
        }
        val originalColumns = raw(::columns)
        val before = raw { snapshot(it, originalColumns) }
        SqliteGameChangeStore(context, source, name, clock = { upgradeTime }).use { store ->
            assertEquals(GameMoney(434), ok(store.balance()))
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.listVehicleHistory())
            assertEquals(LedgerFailureReason.INVALID_RECORD, store.lastStorageFailure)
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), SettlementHistoryReader(store).read())
        }
        raw { db ->
            assertEquals(20, db.version)
            assertEquals(before, snapshot(db, originalColumns))
            assertEquals(1, count(db, "initialization_profile", "proof_id IS NULL AND marker_request IS NULL"))
        }
    }

    fun testLegacyPurchaseAndNewAchievementRejectCrossOwnerReuseAfterUpgrade() {
        createHistoricalDatabase(12)
        raw { db ->
            // v12 RentalLedger.purchase used the full array and did not reserve achievement/.
            db.execSQL("UPDATE shop SET level=2,capacity=5 WHERE id=1")
            db.execSQL("UPDATE account SET balance=384 WHERE id=1")
            receipt(db, purchaseId.value, "FACILITY", "DEBIT", 50, 384, null,
                JSONArray(listOf("expand", 1, 3, 5, 1, true, oldRule.value, 50)).toString())
        }
        val (originalPurchase, claim, committedClaim) = SqliteGameChangeStore(context, source, name, clock = { upgradeTime }).use { store ->
            val profile = ok(store.localProfileId())
            store.achievements.authority = {
                val catalog = VehicleCatalogSnapshot(ContentVersion("upgrade-fixture"), listOf(VehicleDefinition(model, "캐스퍼")))
                val definitions = AchievementMetric.entries.associateWith { metric ->
                    AchievementDefinition(metric, oldRule, if (metric == AchievementMetric.SHOP) 2 else 1, GameMoney(100), true)
                }
                ContractResult.Success(AchievementAuthority(definitions, catalog))
            }
            val originalPurchase = ok(SettlementHistoryReader(store).read()).entries.single {
                it.settlement.transaction.requestId == purchaseId
            }.settlement
            val milestone = checkNotNull(ok(store.achievements.read(profile)).single {
                it.definition.metric == AchievementMetric.SHOP
            }.milestone)
            val claim = AchievementClaimCommand(RequestId("achievement/claim/new-claim"), milestone)
            val before = raw { snapshot(it) }
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                store.achievements.claim(claim.copy(requestId = purchaseId)))
            assertEquals(before, raw { snapshot(it) })

            // Keep the first response as the test oracle and reopen without acknowledging it.
            val committedClaim = ok(store.achievements.claim(claim))
            Triple(originalPurchase, claim, committedClaim)
        }
        val committed = raw { snapshot(it) }
        SqliteGameChangeStore(context, source, name).use { reopened ->
            assertEquals(originalPurchase, ok(reopened.rental.expand(purchaseId, offer)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                reopened.rental.expand(purchaseId, offer.copy(price = GameMoney(51))))
            val currentOffer = ShopExpansion(oldRule, true, 2, 5, 7, 1, GameMoney(50))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reopened.rental.expand(claim.requestId, currentOffer))
            val general = GameChange(claim.requestId, TransactionKind.GARAGE, source, oldRule,
                MoneyChange(MoneyDirection.CREDIT, GameMoney(100)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reopened.commitGameChange(general))
            val originalClaim = checkNotNull(ok(reopened.achievements.settlement(claim.requestId)))
            assertEquals(committedClaim, originalClaim)
            assertEquals(committedClaim, ok(reopened.achievements.claim(claim)))
            assertEquals(GameMoney(484), ok(reopened.balance()))
            assertEquals(committed, raw { snapshot(it) })

            // Keep the offer and ledger state identical. Only the request ID is fresh.
            val controlId = RequestId("schema20-new-expansion")
            val control = ok(reopened.rental.expand(controlId, currentOffer))
            assertEquals(controlId, control.transaction.requestId)
            assertEquals(MoneyChange(MoneyDirection.DEBIT, currentOffer.price), control.transaction.money)
            assertEquals(GameMoney(434), ok(reopened.balance()))
            val shop = ok(reopened.rental.shop())
            assertEquals(3 to 7, shop.progress.shopLevel to shop.capacity)
        }
    }

    private fun createHistoricalDatabase(version: Int) {
        val file = context.getDatabasePath(name)
        check(!file.exists())
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val layers = listOf(2, 10, 12).filter { it <= version }
            for (layer in layers) {
                instrumentation.context.assets.open("historical-ledger-upgrade/v$layer.sql").bufferedReader().useLines { lines ->
                    lines.filterNot { it.isBlank() || it.startsWith("--") }.forEach { db.execSQL(it) }
                }
            }
            // The rows below model old Add, assign/account/settle/return, disposal and another Add.
            // JSON keys and order are from d3dd4b3 LedgerJson; Android JSON handles string escaping.
            db.execSQL("INSERT INTO account VALUES (1,434,'SIMULATION')")
            db.execSQL("INSERT INTO shop VALUES (1,1,3)")
            val original = vehicle("retired", 2, 0, "OWNED", "ADVANCED", 35, 500)
            val sold = vehicle("retired", 2, 2, "SOLD", "ADVANCED", 35, 500)
            val remaining = vehicle("remaining", 1, 0, "OWNED", "COMMON", 0, 0)
            db.execSQL("INSERT INTO vehicles VALUES (?,?),(?,?)", arrayOf("retired", sold.toString(), "remaining", remaining.toString()))
            val visit = JSONArray("""["hyundai_casper",1,"old-catalog","upgrade-fixture",30,48,99,0,15,0,"DOWN",null,[["hyundai_casper","SMALL"]],[["hyundai_casper",100]]]""")
            db.execSQL("INSERT INTO visits (id,command,assigned) VALUES (?,?,?)", arrayOf(rentalId.value, visit.toString(), "retired"))
            db.execSQL("""INSERT INTO rentals VALUES
                ('old-rental','retired','upgrade-fixture',0,3600000,4500000,3600000,NULL,'3600000','134.64000000',0,
                'DOWN',0,1,0,'IDLE','rental/settle/old-rental',NULL)""")
            receipt(db, "old-add", "GARAGE", null, 0, 0, original,
                generalCommand("old-add", null, 0, JSONObject().put("type", "add").put("vehicle", original)))
            receipt(db, "rental/settle/old-rental", "RENTAL", "CREDIT", 134, 134, null,
                JSONArray(listOf("settle", rentalId.value)).toString(), viewed = 1)
            val sell = JSONObject().put("type", "update").put("id", "retired").put("revision", 1)
                .put("level", 2).put("lifecycle", "SOLD").put("levelProgressPercent", 35)
            receipt(db, "old-sale", "GARAGE", "CREDIT", 300, 434, sold, generalCommand("old-sale", "CREDIT", 300, sell))
            receipt(db, "old-add-remaining", "GARAGE", null, 0, 434, remaining,
                generalCommand("old-add-remaining", null, 0, JSONObject().put("type", "add").put("vehicle", remaining)))
            if (version >= 10) {
                // Existing profile from the v2 upgrade path, with no NEW proof.
                db.execSQL("INSERT INTO initialization_profile VALUES (1,'local-older-profile','SIMULATION',NULL,NULL,NULL)")
            }
            if (version == 12) db.execSQL("INSERT INTO quest_tracking VALUES ('local-older-profile','SIMULATION',?)",
                arrayOf(priorQuestTrackingTime))
            val historicalColumns = columns(db)
            assertEquals(mapOf(2 to 8, 10 to 29, 12 to 32).getValue(version), historicalColumns.size)
            assertFalse("achievements" in historicalColumns)
            if (version >= 10) {
                assertEquals(listOf("request_id", "route", "started_at"), historicalColumns.getValue("delivery_routes"))
            }
            db.version = version
        }
    }

    private fun vehicle(id: String, level: Int, revision: Int, lifecycle: String, rarity: String, progress: Int, cost: Int) =
        JSONObject().put("id", id).put("vehicle", model.value).put("level", level).put("revision", revision)
            .put("lifecycle", lifecycle).put("rarity", rarity).put("levelProgressPercent", progress)
            .put("acquisitionCost", cost).put("acquisitionSource", "GACHA")

    private fun money(direction: String?, amount: Int): Any =
        direction?.let { JSONObject().put("direction", it).put("amount", amount) } ?: JSONObject.NULL

    private fun generalCommand(id: String, direction: String?, amount: Int, write: JSONObject) =
        JSONObject().put("request", id).put("kind", "GARAGE").put("source", source.name)
            .put("rule", oldRule.value).put("money", money(direction, amount)).put("write", write).toString()

    private fun receipt(db: SQLiteDatabase, id: String, kind: String, direction: String?, amount: Int, balance: Int,
                        vehicle: JSONObject?, command: String, viewed: Int = 0) {
        val settlement = UUID.randomUUID().toString()
        val value = JSONObject().put("id", settlement).put("transaction", UUID.randomUUID().toString())
            .put("request", id).put("kind", kind).put("source", source.name).put("rule", oldRule.value)
            .put("money", money(direction, amount)).put("balance", balance).put("vehicle", vehicle ?: JSONObject.NULL).put("at", 4_500_000)
        db.execSQL("INSERT INTO settlements VALUES (?,?,?,?,?)", arrayOf(id, settlement, command, value.toString(), viewed))
    }

    private fun <T> raw(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun count(db: SQLiteDatabase, table: String, condition: String = "1"): Int =
        db.rawQuery("SELECT count(*) FROM $table WHERE $condition", null).use { check(it.moveToFirst()); it.getInt(0) }

    private fun columns(db: SQLiteDatabase): Map<String, List<String>> {
        val names = db.rawQuery("""SELECT name FROM sqlite_master WHERE type='table'
            AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'""", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return names.associateWith { table ->
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
        }
    }

    private fun snapshot(db: SQLiteDatabase, projection: Map<String, List<String>> = columns(db)) =
        projection.mapValues { (table, fields) ->
            db.rawQuery("SELECT ${fields.joinToString()} FROM $table ORDER BY rowid", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getType(it) to cursor.getString(it) })
                }
            }
        }

    private fun schema(db: SQLiteDatabase) =
        db.rawQuery("SELECT type,name,sql FROM sqlite_master ORDER BY type,name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) }) }
        }

    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }
}
