package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.*
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/** 같은 실제 helper/factory의 저장 시험. 시각·고객은 SIMULATION이며 제품 수명/실차 증명이 아니다. */
@Suppress("DEPRECATION")
class RentalProductJournalTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var games:GameSessionFactory
    private lateinit var store:SqliteGameChangeStore
    private lateinit var identity:GameIdentity
    private lateinit var definition:RentalVisitDefinition
    private lateinit var catalog:VehicleCatalogSnapshot
    private lateinit var now:RentalClock
    private var definitionsAvailable=true
    private var origin:Observation<RentalClock> = Observation.Missing(MissingReason.NO_DATA)
    private fun at(ms:Long)=RentalClock("test-boot",EpochMillis(1_000_000_000L+ms),ms)
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun journal()=SqliteRentalProductJournal(games,{if(definitionsAvailable)ContractResult.Success(definition)else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},{ContractResult.Success(now)},{ContractResult.Success(origin)})
    override fun setUp() {
        super.setUp();name="rental-product-${System.nanoTime()}.db";now=at(0);origin=Observation.Available(now)
        definition=ok(ok(AssetTrialDefinitionProvider(context.assets).load()).rentalVisitDefinition())
        games=GameSessionFactory(context,mapOf(source to name,InputSource.VEHICLE to name+"-vehicle"),source)
        ok(games.open()).use{identity=it.identity;catalog=ok(it.content).catalog}
        store=SqliteGameChangeStore(context,source,name);ok(store.localProfileId())
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);context.deleteDatabase(name+"-vehicle");super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows(tables:List<String> = listOf("account","vehicles","settlements","rentals","visits","rental_product_marker","rental_product_state","rental_product_inactive","host_command_checkpoint","quest_events")):List<String> = buildList {
        raw{db->for(t in tables)db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}
    }
    private fun prepare(id:String="product-visit"):PreparedRentalVisit {
        val cursor=ok(journal().activate(identity));return checkNotNull(ok(RentalVisitPlanner().prepare(identity,catalog,definition,cursor,now,Random(17)){RequestId(id)}))
    }
    private fun add(id:VehicleId):OwnedVehicle {
        val car=OwnedVehicle(OwnedVehicleId("product-car"),id,1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
        ok(store.commitGameChange(GameChange(RequestId("add-car"),TransactionKind.GARAGE,source,definition.rules,null,VehicleWrite.Add(car))));return car
    }
    private fun manual(car:OwnedVehicle)=HostCheckpointCommand.Garage(catalog.contentVersion,GameChange(RequestId("manual-command"),TransactionKind.GARAGE,source,definition.rules,null,VehicleWrite.Update(car.ownedVehicleId,car.revision,2,VehicleLifecycle.OWNED,0)))
    private fun directRental():RequestId {
        val id=RequestId("inactive-rental");val car=add(catalog.vehicles.first().vehicleId)
        assertEquals(car.ownedVehicleId,ok(store.rental.assign(RentalRequest(id,car.vehicleId,6),catalog,definition.categories,definition.rates,
            RentalTerms(definition.rules,SimulatedCustomer(30,48),99,at(0).epoch,60,GameMoney(0),RoundingMode.DOWN,false))))
        ok(journal().activate(identity));return id
    }
    private fun weighted(id:RequestId):BigDecimal {var value=BigDecimal.ZERO;raw{db->db.rawQuery("SELECT weighted FROM rentals WHERE id=?",arrayOf(id.value)).use{assertTrue(it.moveToFirst());value=it.getString(0).toBigDecimal()}};return value}
    private fun equalNumber(expected:String,actual:BigDecimal)=assertEquals(0,BigDecimal(expected).compareTo(actual))
    fun testReadDoesNotActivateAndExplicitActivationIsIdempotent() {
        val before=rows();assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().pending(identity));assertEquals(before,rows())
        val cursor=ok(journal().activate(identity));now=at(1000);assertEquals(cursor,ok(journal().activate(identity)));assertEquals(at(0),cursor.nextAt)
    }
    fun testWholePreparedReopensAndOriginalAssignmentPrecedesChangedDefinitionAndClock() {
        val p=prepare();val car=add(p.visit.request.desired);assertEquals(p,ok(journal().save(p)));assertEquals(p.expected,ok(journal().cursor(identity)))
        val fresh=GameSessionFactory(context,mapOf(source to name,InputSource.VEHICLE to name+"-vehicle"),source)
        val reopened=SqliteRentalProductJournal(fresh,{ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},{ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},{ContractResult.Success(origin)})
        assertEquals(p,ok(reopened.pending(identity)));assertEquals(car.ownedVehicleId,ok(reopened.execute(p)))
        assertEquals(p.next,ok(reopened.cursor(identity)));val paidRows=rows();assertEquals(car.ownedVehicleId,ok(reopened.execute(p)));assertEquals(paidRows,rows())
        ok(reopened.finish(p));assertNull(ok(reopened.pending(identity)));assertEquals(car.ownedVehicleId,ok(reopened.execute(p)))
        assertEquals(Revision(1),ok(store.listOwnedVehicles()).single().revision)
    }
    fun testRevisitIsAnOriginalResultAndCannotAssignNewlyAddedVehicle() {
        val p=prepare();ok(journal().save(p));assertNull(ok(journal().execute(p)));ok(journal().finish(p));add(p.visit.request.desired)
        val before=rows();assertNull(ok(journal().execute(p)));assertEquals(before,rows())
    }
    fun testDifferentWholeInputAndStaleCursorNeverReplacePreparedVisit() {
        val p=prepare();ok(journal().save(p));val before=rows()
        val changed=p.copy(visit=RentalVisit(source,p.visit.request,p.visit.catalog,p.visit.categories,p.visit.hourlyRates,p.visit.terms.copy(roll=(p.visit.terms.roll+1)%100)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().save(changed));assertEquals(before,rows())
        ok(journal().execute(p));ok(journal().finish(p));val stable=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().save(p.copy(visit=RentalVisit(source,p.visit.request.copy(id=RequestId("stale")),p.visit.catalog,p.visit.categories,p.visit.hourlyRates,p.visit.terms))));assertEquals(stable,rows())
    }
    fun testManualFinancialSlotBlocksNewExecutionWithoutClearingEitherCommand() {
        val p=prepare();val car=add(p.visit.request.desired);ok(journal().save(p));val block=manual(car);ok(store.hostCommands.save(identity.profileId,block));val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().execute(p));assertEquals(p,ok(journal().pending(identity)));assertEquals(block,ok(store.hostCommands.load(identity.profileId))!!.command);assertEquals(before,rows())
        ok(store.hostCommands.cancelUnstarted(identity.profileId,block));assertEquals(car.ownedVehicleId,ok(journal().execute(p)))
    }
    fun testIgnoredAssignmentAndCursorWritesRollBackAllEconomicRows() {
        val p=prepare();add(p.visit.request.desired);ok(journal().save(p))
        for((table,event) in listOf("visits" to "INSERT","rentals" to "INSERT","vehicles" to "UPDATE","visits" to "UPDATE","rental_product_state" to "UPDATE")) {
            raw{it.execSQL("CREATE TRIGGER ignore_product BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(table+event,ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().execute(p));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_product")}
        }
        assertNotNull(ok(journal().execute(p)))
    }
    fun testAfterInsertCannotReplaceFrozenCustomerAccidentOrRounding() {
        val p=prepare();add(p.visit.request.desired);ok(journal().save(p))
        for(change in listOf("rounding='UP'","accident=1-NEW.accident","match_review=1-NEW.match_review","wash_review=1-NEW.wash_review","regular_customer=1-NEW.regular_customer")) {
            raw{it.execSQL("CREATE TRIGGER alter_rental AFTER INSERT ON rentals BEGIN UPDATE rentals SET $change WHERE id=NEW.id; END")};val before=rows()
            assertEquals(change,ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().execute(p));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER alter_rental")}
        }
    }
    fun testInactiveUsesOriginalDrivingEndWithoutResetOrFakePhysicalParking() {
        val id=directRental();ok(store.rental.account(id,at(0).epoch,at(3600000).epoch,at(0).epoch));equalNumber("2340000",weighted(id))
        now=at(3600000);val marker=RequestId("inactive-one");ok(journal().beginInactive(identity,marker,now));val first=RentalInactiveInterval(marker,now,now,at(5400000));now=first.to;ok(journal().advanceInactive(identity,first));equalNumber("2880000",weighted(id))
        val before=rows();ok(journal().advanceInactive(identity,first));assertEquals(before,rows())
        val marker2=RequestId("inactive-two");ok(journal().beginInactive(identity,marker2,now));val second=RentalInactiveInterval(marker2,now,now,at(7200000));now=second.to;ok(journal().advanceInactive(identity,second));equalNumber("3420000",weighted(id))
        assertEquals(at(0).epoch,ok(store.rental.pending()).single().parkedSince)
    }
    fun testInactiveCannotBackfillUnobservedGapBeforeStop() {
        directRental();now=at(3600000);val marker=RequestId("inactive-gap");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(5400000));now=interval.to
        val economic=rows(listOf("account","rentals","vehicles","settlements","quest_events"));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().advanceInactive(identity,interval));assertEquals(economic,rows(listOf("account","rentals","vehicles","settlements","quest_events")));assertEquals(interval,ok(journal().pendingInactive(identity)))
    }
    fun testMissingPolicyOriginIsFrozenAndDoesNotBecomeCurrentStopOrLaterOrigin() {
        directRental();origin=Observation.Missing(MissingReason.NO_DATA);val marker=RequestId("inactive-missing");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(1000));now=interval.to
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().advanceInactive(identity,interval));origin=Observation.Available(at(0));val before=rows();assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().advanceInactive(identity,interval));assertEquals(before,rows())
    }
    fun testPreparedResumeSurvivesForeignSlotAndRejectsNewResumeTime() {
        val id=directRental();val car=ok(store.listOwnedVehicles()).single();val block=manual(car);ok(store.hostCommands.save(identity.profileId,block));val marker=RequestId("inactive-block");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(1000));now=interval.to
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().advanceInactive(identity,interval));assertEquals(interval,ok(journal().pendingInactive(identity)));now=at(2000)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().advanceInactive(identity,interval.copy(to=now)));ok(store.hostCommands.cancelUnstarted(identity.profileId,block));ok(journal().advanceInactive(identity,interval));assertEquals(at(1000).epoch,ok(store.rental.pending()).single{it.id==id}.cursor)
    }
    fun testWrongBootAndCalibrationCannotCreateInactiveAccrual() {
        directRental();val marker=RequestId("inactive-clock");ok(journal().beginInactive(identity,marker,now));val before=rows()
        for(end in listOf(at(1000).copy(bootId="other"),at(1000).copy(elapsedMs=1001))) {
            now=end;assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().advanceInactive(identity,RentalInactiveInterval(marker,at(0),at(0),end)));assertEquals(before,rows())
        }
    }
    fun testUnknownInactiveAuthorityIsCorruptionAndOriginalRowsStayUntouched() {
        directRental();val marker=RequestId("inactive-corrupt");ok(journal().beginInactive(identity,marker,now))
        raw{db->val raw=db.rawQuery("SELECT payload FROM rental_product_inactive",null).use{it.moveToFirst();it.getString(0)};val broken=JSONObject(raw).put("origin",JSONObject().put("missing","UNKNOWN_REASON")).toString();db.execSQL("UPDATE rental_product_inactive SET payload=?,digest=?",arrayOf(broken,ObservedDrivingJson.digest(broken)))}
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().pendingInactive(identity));assertEquals(before,rows())
    }
    fun testIgnoredInactiveAccountingPreservesFinancialRowsAndPreparedResumeForRetry() {
        val id=directRental();val marker=RequestId("inactive-ignored");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(3600000));now=interval.to
        raw{it.execSQL("CREATE TRIGGER ignore_inactive BEFORE UPDATE ON rentals BEGIN SELECT RAISE(IGNORE); END")}
        val before=rows(listOf("account","rentals","vehicles","settlements","quest_events"))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().advanceInactive(identity,interval));assertEquals(before,rows(listOf("account","rentals","vehicles","settlements","quest_events")));assertEquals(interval,ok(journal().pendingInactive(identity)))
        raw{it.execSQL("DROP TRIGGER ignore_inactive")};ok(journal().advanceInactive(identity,interval));equalNumber("2340000",weighted(id));assertNull(ok(store.rental.pending()).single().parkedSince)
    }
    fun testWeightedTimeCannotExceedActualConfirmedCursor() {
        directRental();raw{it.execSQL("UPDATE rentals SET weighted='9999999'")};val marker=RequestId("inactive-bad-weight");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(1000));now=interval.to
        val before=rows(listOf("account","rentals","vehicles","settlements","quest_events"));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().advanceInactive(identity,interval));assertEquals(before,rows(listOf("account","rentals","vehicles","settlements","quest_events")))
    }
    fun testIgnoredSettlementOrReturnRollsBackEntireInactiveWindowAndReplaysOnce() {
        directRental();val marker=RequestId("inactive-complete");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(7*3600000));now=interval.to
        for(phase in listOf("RETURNING","IDLE")) {
            raw{it.execSQL("CREATE TRIGGER ignore_inactive_phase BEFORE UPDATE ON rentals WHEN NEW.phase='$phase' BEGIN SELECT RAISE(IGNORE); END")}
            val before=rows(listOf("account","rentals","vehicles","settlements","quest_events"))
            assertEquals(phase,ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().advanceInactive(identity,interval));assertEquals(before,rows(listOf("account","rentals","vehicles","settlements","quest_events")));raw{it.execSQL("DROP TRIGGER ignore_inactive_phase")}
        }
        val result=ok(journal().advanceInactive(identity,interval));assertTrue(result.pending.isEmpty());assertNotNull(result.firstCompleted)
        val before=rows();ok(journal().advanceInactive(identity,interval));assertEquals(before,rows())
    }
    fun testOriginalVisitCannotHideMismatchedRentalLink() {
        val p=prepare();add(p.visit.request.desired);ok(journal().save(p));ok(journal().execute(p))
        raw{it.execSQL("UPDATE rentals SET owned_id='wrong-instance'")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().execute(p));assertEquals(before,rows())
    }
    fun testMissingDriverMarkerWithOriginalStateIsCorruptionNotNewActivation() {
        prepare();raw{it.execSQL("DELETE FROM rental_product_marker")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().activate(identity));assertEquals(before,rows())
    }
    fun testSchema13UpgradeKeepsOriginalVisitAndDoesNotActivateDriver() {
        val p=prepare();add(p.visit.request.desired);ok(journal().save(p));val assigned=ok(journal().execute(p));ok(journal().finish(p));val old=rows(listOf("account","vehicles","rentals","settlements"));store.close()
        raw{stripRentalProductFixture(it);it.version=13};store=SqliteGameChangeStore(context,source,name);ok(store.localProfileId());assertEquals(old,rows(listOf("account","vehicles","rentals","settlements")));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().pending(identity))
        assertEquals(assigned,ok(store.rental.assign(p.visit.request,p.visit.catalog,p.visit.categories,p.visit.hourlyRates,p.visit.terms)));raw{assertEquals(17,it.version)}
    }
    fun testFailedSchema13UpgradePreservesOriginalWholeFileAndVersion() {
        prepare();store.close();raw{it.version=13};val file=context.getDatabasePath(name);val before=file.readBytes();store=SqliteGameChangeStore(context,source,name)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.balance());store.close();assertTrue(before.contentEquals(file.readBytes()))
        raw{assertEquals(13,it.version);it.version=17};store=SqliteGameChangeStore(context,source,name)
    }

    // 독립 검토: 원 visits가 driver의 과거 활성화를 입증하면 metadata 전체소실도 NEW가 아니다.
    fun testIndependentOriginalVisitPreventsReactivationAfterAllDriverMetadataLost() {
        val p=prepare();ok(journal().save(p));ok(journal().execute(p));ok(journal().finish(p))
        raw { db->db.execSQL("DELETE FROM rental_product_state");db.execSQL("DELETE FROM rental_product_inactive");db.execSQL("DELETE FROM rental_product_marker") }
        now=at(3600000);val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().activate(identity))
        assertEquals(before,rows())
    }
    fun testIndependentIgnoredInactiveCreditCannotCompleteWindow() {
        directRental();val marker=RequestId("independent-ignore-money");ok(journal().beginInactive(identity,marker,now))
        val interval=RentalInactiveInterval(marker,now,now,at(7*3600000));now=interval.to
        raw{it.execSQL("CREATE TRIGGER ignore_rental_credit BEFORE UPDATE ON account WHEN NEW.balance!=OLD.balance BEGIN SELECT RAISE(IGNORE); END")}
        val economic=listOf("account","rentals","vehicles","settlements","quest_events");val before=rows(economic)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().advanceInactive(identity,interval))
        assertEquals(before,rows(economic));assertEquals(interval,ok(journal().pendingInactive(identity)))
    }
    fun testIndependentOriginalAssignmentRejectsChangedFrozenRounding() {
        val p=prepare();add(p.visit.request.desired);ok(journal().save(p));ok(journal().execute(p))
        raw{it.execSQL("UPDATE rentals SET rounding='UP' WHERE id=?",arrayOf(p.visit.request.id.value))};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().execute(p));assertEquals(before,rows())
    }
    fun testIndependentOriginalAssignmentRejectsChangedFrozenCustomer() {
        val p=prepare();add(p.visit.request.desired);ok(journal().save(p));ok(journal().execute(p))
        raw{it.execSQL("UPDATE rentals SET regular_customer=1-regular_customer WHERE id=?",arrayOf(p.visit.request.id.value))};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().execute(p));assertEquals(before,rows())
    }

    fun testPolicyDrivingExitStartsAtActualStopWithoutFakeParkingOrPhysicalEnd() {
        val id=directRental();ok(store.rental.account(id,at(0).epoch,at(3600000).epoch,null));origin=Observation.Missing(MissingReason.NO_DATA)
        now=at(3600000);val marker=RequestId("policy-driving-exit");ok(journal().beginInactiveWhileDriving(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(5400000));now=interval.to
        ok(journal().advanceInactive(identity,interval));equalNumber("5400000",weighted(id));assertNull(ok(store.rental.pending()).single().parkedSince)
        raw{db->db.rawQuery("SELECT payload FROM rental_product_inactive",null).use{assertTrue(it.moveToFirst());val j=JSONObject(it.getString(0));assertEquals("APPLICATION_EXIT_DRIVING",j.getString("basis"));assertFalse(j.getJSONObject("origin").has("clock"));assertEquals(at(3600000),RentalProductJson.clock(j.getJSONObject("origin").getJSONObject("applicationExit")))}}
    }
    fun testPolicyReconnectKeepsFirstExitOriginAndDoesNotRestartNormalThirtyMinutes() {
        val id=directRental();origin=Observation.Missing(MissingReason.NO_DATA);val first=RequestId("policy-first");ok(journal().beginInactiveWhileDriving(identity,first,now));val a=RentalInactiveInterval(first,now,now,at(900000));now=a.to;ok(journal().advanceInactive(identity,a));equalNumber("900000",weighted(id))
        val second=RequestId("policy-second");ok(journal().beginInactiveWhileDriving(identity,second,now));val b=RentalInactiveInterval(second,now,now,at(3600000));now=b.to;ok(journal().advanceInactive(identity,b));equalNumber("2340000",weighted(id))
        val before=rows();ok(journal().advanceInactive(identity,b));assertEquals(before,rows());assertNull(ok(store.rental.pending()).single().parkedSince)
    }
    fun testPolicyAlreadyParkedKeepsActualDriveEndInsteadOfNewStop() {
        val id=directRental();ok(store.rental.account(id,at(0).epoch,at(3600000).epoch,at(0).epoch));now=at(3600000);val marker=RequestId("policy-already-parked");ok(journal().beginInactive(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(5400000));now=interval.to;ok(journal().advanceInactive(identity,interval));equalNumber("2880000",weighted(id));assertEquals(at(0).epoch,ok(store.rental.pending()).single().parkedSince)
    }
    fun testPolicyEightHourCapUsesRetainedExitEvenForLaterRental() {
        origin=Observation.Missing(MissingReason.NO_DATA);ok(journal().activate(identity));val first=RequestId("policy-no-rental-yet");ok(journal().beginInactiveWhileDriving(identity,first,now));val firstWindow=RentalInactiveInterval(first,now,now,at(5*3600000));now=firstWindow.to;ok(journal().advanceInactive(identity,firstWindow))
        val car=add(catalog.vehicles.first().vehicleId);val id=RequestId("policy-later-rental");ok(store.rental.assign(RentalRequest(id,car.vehicleId,6),catalog,definition.categories,definition.rates,RentalTerms(definition.rules,SimulatedCustomer(30,48),99,now.epoch,60,GameMoney(0),RoundingMode.DOWN,false)))
        val next=RequestId("policy-cap");ok(journal().beginInactiveWhileDriving(identity,next,now));val interval=RentalInactiveInterval(next,now,now,at(9*3600000));now=interval.to;ok(journal().advanceInactive(identity,interval));equalNumber("3240000",weighted(id));assertNull(ok(store.rental.pending()).single().parkedSince)
    }
    fun testPolicyOnlyActualNewDrivingEndAllowsFollowingCycleToStartAgain() {
        val id=directRental();origin=Observation.Missing(MissingReason.NO_DATA);val first=RequestId("policy-cycle-one");ok(journal().beginInactiveWhileDriving(identity,first,now));val a=RentalInactiveInterval(first,now,now,at(1800000));now=a.to;ok(journal().advanceInactive(identity,a))
        ok(store.rental.account(id,at(1800000).epoch,at(7200000).epoch,null));origin=Observation.Available(at(3600000));now=at(7200000)
        val second=RequestId("policy-cycle-two");ok(journal().beginInactiveWhileDriving(identity,second,now));val b=RentalInactiveInterval(second,now,now,at(9000000));now=b.to;ok(journal().advanceInactive(identity,b));equalNumber("9000000",weighted(id))
    }
    fun testPolicyOldMissingAuthorityIsNotReinterpretedOrBackfilledByNewExitMode() {
        directRental();origin=Observation.Missing(MissingReason.NO_DATA);val old=RequestId("policy-old-missing");ok(journal().beginInactive(identity,old,now))
        raw{db->val raw=db.rawQuery("SELECT payload FROM rental_product_inactive",null).use{it.moveToFirst();it.getString(0)};val legacy=JSONObject(raw).apply{remove("basis")}.toString();db.execSQL("UPDATE rental_product_inactive SET payload=?,digest=?",arrayOf(legacy,ObservedDrivingJson.digest(legacy)))}
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().beginInactiveWhileDriving(identity,old,now));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().beginInactiveWhileDriving(identity,RequestId("new-cannot-repair-old"),now));assertEquals(before,rows())
        val interval=RentalInactiveInterval(old,now,now,at(1000));now=interval.to;assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().advanceInactive(identity,interval))
    }
    fun testPolicyExitDoesNotFillEarlierObservationGapAndBootOrClockMustStillMatch() {
        directRental();origin=Observation.Missing(MissingReason.NO_DATA);now=at(3600000);val key=RequestId("policy-gap");ok(journal().beginInactiveWhileDriving(identity,key,now));val interval=RentalInactiveInterval(key,now,now,at(5400000));now=interval.to
        val economic=rows(listOf("account","rentals","vehicles","settlements","quest_events"));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().advanceInactive(identity,interval));assertEquals(economic,rows(listOf("account","rentals","vehicles","settlements","quest_events")));assertEquals(interval,ok(journal().pendingInactive(identity)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().advanceInactive(identity,interval.copy(to=interval.to.copy(bootId="other"))))
    }
    fun testPolicyExitCreditFailureKeepsOriginalWindowAndRollsBackLikeVerifiedParking() {
        directRental();origin=Observation.Missing(MissingReason.NO_DATA);val marker=RequestId("policy-credit-ignored");ok(journal().beginInactiveWhileDriving(identity,marker,now));val interval=RentalInactiveInterval(marker,now,now,at(7*3600000));now=interval.to
        raw{it.execSQL("CREATE TRIGGER ignore_policy_credit BEFORE UPDATE ON account WHEN NEW.balance!=OLD.balance BEGIN SELECT RAISE(IGNORE); END")};val economic=rows(listOf("account","rentals","vehicles","settlements","quest_events"))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().advanceInactive(identity,interval));assertEquals(economic,rows(listOf("account","rentals","vehicles","settlements","quest_events")));assertEquals(interval,ok(journal().pendingInactive(identity)))
    }

    fun testInactiveRecoveryDoesNotActivateAndOnlyTrueAbsenceIsNull() {
        val before=rows();assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),journal().currentInactive(identity));assertEquals(before,rows())
        ok(journal().activate(identity));val active=rows();assertNull(ok(journal().currentInactive(identity)));assertEquals(active,rows())
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),journal().currentInactive(identity.copy(profileId=ProfileId("foreign"))));assertEquals(active,rows())
    }
    fun testInactiveRecoveryFindsUnpreparedExitAfterReopenWithoutAnySupplierRead() {
        ok(journal().activate(identity));now=at(1000);val marker=RequestId("recovery-unprepared");ok(journal().beginInactiveWhileDriving(identity,marker,now));val before=rows()
        val fresh=GameSessionFactory(context,mapOf(source to name,InputSource.VEHICLE to name+"-vehicle"),source)
        val read=SqliteRentalProductJournal(fresh,{error("조회에서 정의 공급자를 호출하면 안 됨")},{error("조회에서 현재 시각을 호출하면 안 됨")},{error("조회에서 원점을 다시 만들면 안 됨")})
        val result=ok(read.currentInactive(identity))!!;assertEquals(identity,result.identity);assertEquals(marker,result.marker);assertEquals(at(1000),result.since)
        assertEquals(RentalInactiveBasis.APPLICATION_EXIT_DRIVING,result.basis);assertEquals(ContractResult.Success(Observation.Available(at(1000))),result.policyOrigin);assertEquals(RentalInactivePhase.RECORDED,result.phase);assertNull(result.interval);assertEquals(before,rows())
    }
    fun testInactiveRecoveryKeepsLegacyPhysicalOriginDistinctFromActualStop() {
        ok(journal().activate(identity));origin=Observation.Available(at(0));now=at(60000);val marker=RequestId("recovery-legacy-physical");ok(journal().beginInactive(identity,marker,now))
        raw{db->val old=db.rawQuery("SELECT payload FROM rental_product_inactive",null).use{it.moveToFirst();it.getString(0)};val legacy=JSONObject(old).apply{remove("basis")}.toString();db.execSQL("UPDATE rental_product_inactive SET payload=?,digest=?",arrayOf(legacy,ObservedDrivingJson.digest(legacy)))}
        val before=rows();now=at(90000);val r=ok(journal().currentInactive(identity))!!
        assertEquals(RentalInactiveBasis.DRIVING_END,r.basis);assertEquals(at(60000),r.since);assertEquals(ContractResult.Success(Observation.Available(at(0))),r.policyOrigin);assertEquals(before,rows())
    }
    fun testInactiveRecoveryReturnsPreparedOriginalEndDespiteForeignSlotAndNewClock() {
        directRental();val car=ok(store.listOwnedVehicles()).single();val block=manual(car);ok(store.hostCommands.save(identity.profileId,block));val marker=RequestId("recovery-prepared");ok(journal().beginInactive(identity,marker,now))
        val original=RentalInactiveInterval(marker,now,now,at(1000));now=original.to;assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),journal().advanceInactive(identity,original))
        now=at(2000);definitionsAvailable=false;val before=rows();val r=ok(journal().currentInactive(identity))!!
        assertEquals(RentalInactivePhase.PREPARED,r.phase);assertEquals(original,r.interval);assertEquals(before,rows());assertEquals(block,ok(store.hostCommands.load(identity.profileId))!!.command)
    }
    fun testInactiveRecoverySeparatesCompletedWindowAndNextCurrentMarkerWithoutRepaying() {
        directRental();val marker=RequestId("recovery-completed");ok(journal().beginInactiveWhileDriving(identity,marker,now));val original=RentalInactiveInterval(marker,now,now,at(7*3600000));now=original.to
        val paid=ok(journal().advanceInactive(identity,original));assertTrue(paid.pending.isEmpty());assertNotNull(paid.firstCompleted);val before=rows()
        val r=ok(journal().currentInactive(identity))!!;assertEquals(RentalInactivePhase.COMPLETED,r.phase);assertEquals(original,r.interval);assertEquals(before,rows())
        now=at(7*3600000+1000);val next=RequestId("recovery-next");ok(journal().beginInactiveWhileDriving(identity,next,now));val after=rows();val current=ok(journal().currentInactive(identity))!!
        assertEquals(next,current.marker);assertEquals(RentalInactivePhase.RECORDED,current.phase);assertNull(current.interval);assertEquals(ContractResult.Success(Observation.Available(at(0))),current.policyOrigin);assertEquals(after,rows())
    }
    fun testInactiveRecoveryPreservesMissingOriginInsteadOfCurrentStopOrP() {
        ok(journal().activate(identity));origin=Observation.Missing(MissingReason.PERMISSION_DENIED);val marker=RequestId("recovery-missing");ok(journal().beginInactive(identity,marker,now));val before=rows();origin=Observation.Available(at(0));now=at(1000)
        val r=ok(journal().currentInactive(identity))!!;assertEquals(ContractResult.Success(Observation.Missing(MissingReason.PERMISSION_DENIED)),r.policyOrigin);assertEquals(RentalInactiveBasis.DRIVING_END,r.basis);assertEquals(before,rows())
    }
    fun testInactiveRecoveryPreservesRecordedOriginFailureInsideValidMetadata() {
        ok(journal().activate(identity));val failing=SqliteRentalProductJournal(games,{ContractResult.Success(definition)},{ContractResult.Success(now)},{ContractResult.Failure(ContractError.STORAGE_FAILURE)})
        ok(failing.beginInactive(identity,RequestId("recovery-origin-error"),now));val before=rows();val r=ok(journal().currentInactive(identity))!!
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),r.policyOrigin);assertEquals(RentalInactivePhase.RECORDED,r.phase);assertEquals(before,rows())
    }
    fun testInactiveRecoveryRejectsMissingDigestForeignSourceAndUnknownBasisWithoutRepair() {
        ok(journal().activate(identity));ok(journal().beginInactiveWhileDriving(identity,RequestId("recovery-damaged"),now))
        var original="";raw{db->original=db.rawQuery("SELECT payload FROM rental_product_inactive",null).use{it.moveToFirst();it.getString(0)}}
        for(kind in listOf("missing","digest","source","basis")) {
            raw{db->when(kind){"missing"->db.execSQL("DELETE FROM rental_product_inactive");"digest"->db.execSQL("UPDATE rental_product_inactive SET digest='changed'");"source"->db.execSQL("UPDATE rental_product_inactive SET source='VEHICLE'");else->{val broken=JSONObject(original).put("basis","UNKNOWN").toString();db.execSQL("UPDATE rental_product_inactive SET payload=?,digest=?",arrayOf(broken,ObservedDrivingJson.digest(broken)))}}}
            val before=rows();assertEquals(kind,ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().currentInactive(identity));assertEquals(kind,before,rows())
            raw{db->db.execSQL("DELETE FROM rental_product_inactive");db.execSQL("INSERT INTO rental_product_inactive(marker,profile,source,payload,digest) VALUES(?,?,?,?,?)",arrayOf("recovery-damaged",identity.profileId.value,source.name,original,ObservedDrivingJson.digest(original)))}
        }
    }
    fun testInactiveRecoveryDoesNotTreatLostCurrentReferenceAsTrueAbsence() {
        ok(journal().activate(identity));ok(journal().beginInactiveWhileDriving(identity,RequestId("recovery-dangling"),now))
        raw{db->val old=db.rawQuery("SELECT payload FROM rental_product_state",null).use{it.moveToFirst();it.getString(0)};val broken=JSONObject(old).put("inactive",JSONObject.NULL).toString();db.execSQL("UPDATE rental_product_state SET payload=?,digest=?",arrayOf(broken,ObservedDrivingJson.digest(broken)))}
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),journal().currentInactive(identity));assertEquals(before,rows())
    }

}
