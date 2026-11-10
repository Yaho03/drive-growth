package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.math.BigDecimal
import java.time.ZoneId

/** 실제 같은 helper/단일 DB journal. 관측은 SIMULATION fixture이며 실차·FGS 시험이 아니다. */
@Suppress("DEPRECATION")
class ObservedDrivingJournalTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var rules:DrivingIncomeRules
    private val journal=SqliteObservedDrivingJournal()
    private val actor get()=GameIdentity(ok(store.localProfileId()),source)
    private fun open()=SqliteGameChangeStore(context,source,name)
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();name="observed-sqlite-${System.nanoTime()}.db";store=open();ok(store.localProfileId());rules=ok(ok(AssetTrialDefinitionProvider(context.assets).load()).drivingIncomeRules())}
    override fun tearDown(){store.close();context.deleteDatabase(name);context.deleteDatabase(name+"-vehicle");super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","settlements","driving_days","driving_drives","observed_driving_marker","observed_driving_state","observed_driving_receipts"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}}
    private fun observation(elapsed:Long=0,speed:Double=10.0,gear:Gear=Gear.DRIVE)=VehicleObservation(source,elapsed,EpochMillis(1794279600000+elapsed),Observation.Available(speed),Observation.Available(gear),Observation.Available(gear!=Gear.PARK))
    private fun trip()=ObservedTrip("observed-trip",observation(),Observation.Available(ZoneId.of("UTC")),ContractResult.Success(rules),BigDecimal("1000.0"),listOf(DrivingSegment(observation(),observation(100000),BigDecimal("1000.0"))))
    private fun read()=ok(store.atomic{journal.read(it,actor)})
    private fun save(next:RecordingState,revision:Long=next.revision-1)=store.atomic{journal.save(it,actor,revision,next)}
    private fun pending():ObservedTrip {ok(store.activateObservedDriving(actor.profileId));val t=trip();ok(save(read().copy(revision=1,outbox=listOf(t))));return t}
    private fun drain():ContractResult<ObservedDrivingReceipt> = store.atomic{db->
        val state=when(val r=journal.read(db,actor)){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        val t=state.outbox.single();val drive=t.confirmed(source)!!;val result=when(val r=store.driving.settle(rules,drive)){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value};val receipt=ObservedDrivingReceipt(t,result)
        when(val r=journal.recordCommit(db,actor,receipt)){is ContractResult.Failure->return@atomic r;else->Unit}
        when(val r=journal.save(db,actor,state.revision,state.copy(revision=state.revision+1,outbox=emptyList()))){is ContractResult.Failure->r;else->ContractResult.Success(receipt)}
    }
    fun testMissingModuleIsNotEmptyAndActivationIsExplicitIdempotent() {
        val before=rows();assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.atomic{journal.read(it,actor)});assertEquals(before,rows())
        val first=ok(store.activateObservedDriving(actor.profileId));assertEquals(0L,first.revision);assertEquals(first,ok(store.activateObservedDriving(actor.profileId)));assertEquals(GameMoney(0),ok(store.balance()))
    }
    fun testMarkerWithMissingStateAndStateWithoutMarkerAreNotReinitialized() {
        ok(store.activateObservedDriving(actor.profileId));raw{it.execSQL("DELETE FROM observed_driving_state")};val missing=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.activateObservedDriving(actor.profileId));assertEquals(missing,rows())
        raw{it.execSQL("DELETE FROM observed_driving_marker")};ok(store.activateObservedDriving(actor.profileId));raw{it.execSQL("DELETE FROM observed_driving_marker")};val orphan=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.activateObservedDriving(actor.profileId));assertEquals(orphan,rows())
    }
    fun testRevisionCasWrongIdentityAndIgnoredStateWritePreserveWholeState() {
        ok(store.activateObservedDriving(actor.profileId));val next=read().copy(revision=1,receiver="epoch");val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),save(next,99));assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.atomic{journal.save(it,actor.copy(source=InputSource.VEHICLE),0,next)});assertEquals(before,rows())
        raw{it.execSQL("CREATE TRIGGER ignore_state BEFORE UPDATE ON observed_driving_state BEGIN SELECT RAISE(IGNORE); END")}
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),save(next));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_state")};ok(save(next))
    }
    fun testReopenPreservesWholeOutboxAndOriginalDepartureZoneAndDecimalScale() {
        val t=pending();val before=rows();store.close();store=open();assertEquals(t,read().outbox.single());assertEquals(1,read().outbox.single().meters.scale());assertEquals(before,rows())
        val result=ok(drain());assertEquals(t,result.trip);assertEquals(t.confirmed(source)!!.departedOn,result.commit.day.day);assertEquals(result,ok(store.observedDrivingReceipt(t.id)));assertTrue(read().outbox.isEmpty());assertEquals(GameMoney(10),ok(store.balance()))
        val original=rows();ok(store.driving.settle(rules,t.confirmed(source)!!));ok(store.atomic{journal.recordCommit(it,actor,result)});assertEquals(original,rows())
    }
    fun testIgnoredMoneyDayDriveProvenanceAndAckRollBackAsOneTransaction() {
        pending()
        for((table,event)in listOf("account" to "UPDATE","driving_days" to "INSERT","driving_drives" to "INSERT","observed_driving_receipts" to "INSERT","observed_driving_state" to "UPDATE")) {
            raw{it.execSQL("CREATE TRIGGER ignore_observed BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),drain());assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_observed")}
        }
        ok(drain());assertEquals(GameMoney(10),ok(store.balance()))
    }
    fun testOutboxCannotBeDroppedOrOriginalTripReplacedWithoutVerifiedReceipt() {
        pending();val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),save(read().copy(revision=2,outbox=emptyList())));assertEquals(before,rows())
        val changed=read().outbox.single().copy(zone=Observation.Available(ZoneId.of("Asia/Seoul")))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),save(read().copy(revision=2,outbox=listOf(changed))));assertEquals(before,rows())
    }
    fun testOldLocalDateOnlySettlementCannotAcquireNewCalendarProvenance() {
        val t=trip();ok(store.driving.settle(rules,t.confirmed(source)!!));ok(store.activateObservedDriving(actor.profileId));val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),save(read().copy(revision=1,outbox=listOf(t))));assertEquals(before,rows());assertNull(ok(store.observedDrivingReceipt(t.id)))
    }
    fun testRawRevisionAndReceiptDamageAreRejectedWithoutRepair() {
        val t=pending();val result=ok(drain());raw{it.execSQL("UPDATE observed_driving_state SET revision=2.5")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.atomic{journal.read(it,actor)});assertEquals(before,rows());raw{it.execSQL("UPDATE observed_driving_state SET revision=2");it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(result.commit.settlement!!.transaction.requestId.value))};val damaged=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.observedDrivingReceipt(t.id));assertEquals(damaged,rows())
    }
    fun testActualSessionSourceUsesSameJournalAndCentralRulesAcrossOpenAndPause() {
        ok(store.activateObservedDriving(actor.profileId));val games=GameSessionFactory(context,mapOf(InputSource.SIMULATION to name,InputSource.VEHICLE to name+"-vehicle"),source)
        val input=SessionObservedDrivingSource(games,journal,ObservedDrivingReducer(RecordingPolicy(200000,200000,1000,3000,0.1,0.5)){"source-drive"}){ok(AssetTrialDefinitionProvider(context.assets).load()).drivingIncomeRules()}
        fun send(at:Long,speed:Double=10.0,gear:Gear=Gear.DRIVE)=input.observe("epoch",DrivingFrame(observation(at,speed,gear),Observation.Available(ZoneId.of("UTC"))),RecordingClock(at,observation(at).receivedAt))
        ok(send(0));ok(send(100000));ok(send(100001,0.0,Gear.PARK));val result=ok(send(103001,0.0,Gear.PARK));assertEquals(1,result.committed.size);assertEquals(1000L,result.committed.single().commit.recordedMeters);assertTrue(result.state.outbox.isEmpty());assertEquals(GameMoney(10),ok(store.balance()))
        val before=rows();assertTrue(ok(send(103001,0.0,Gear.PARK)).committed.isEmpty());assertEquals(before,rows())
    }
    fun testV12UpgradeAddsUnsupportedModuleWithoutBackfillingExistingDrivingDays() {
        val t=trip();val original=ok(store.driving.settle(rules,t.confirmed(source)!!));store.close();raw{db->stripRentalProductFixture(db);listOf("observed_driving_marker","observed_driving_state","observed_driving_receipts").forEach{db.execSQL("DROP TABLE $it")};db.version=12};store=open()
        assertEquals(original,ok(store.driving.settle(rules,t.confirmed(source)!!)));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.atomic{journal.read(it,actor)});assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.observedDrivingReceipt(t.id));raw{assertEquals(16,it.version)}
    }
    fun testRawNanGapBitsSurviveReopenAndCannotChangeUnderSameOutboxId() {
        ok(store.activateObservedDriving(actor.profileId));fun gap(bits:Long)=DrivingGap(DrivingGapReason.INVALID_SIGNAL,null,RecordingClock(200000,EpochMillis(1794279800000)),observation(200000,Double.fromBits(bits)))
        val t=trip().copy(gaps=listOf(gap(0x7ff8000000000042L)));ok(save(read().copy(revision=1,outbox=listOf(t))));store.close();store=open()
        assertEquals(0x7ff8000000000042L,(read().outbox.single().gaps.single().observation!!.speedMps as Observation.Available).value.toRawBits())
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),save(read().copy(revision=2,outbox=listOf(t.copy(gaps=listOf(gap(0x7ff8000000000043L)))))));assertEquals(before,rows())
    }

    fun testOriginalMissingRulesAndZoneWaitAcrossReopenWithoutCurrentBackfill() {
        ok(store.activateObservedDriving(actor.profileId));val missing=trip().copy(zone=Observation.Missing(MissingReason.NO_DATA),rules=ContractResult.Failure(ContractError.DATA_UNAVAILABLE));ok(save(read().copy(revision=1,outbox=listOf(missing))));store.close();store=open()
        val games=GameSessionFactory(context,mapOf(InputSource.SIMULATION to name,InputSource.VEHICLE to name+"-vehicle"),source)
        val input=SessionObservedDrivingSource(games,journal,ObservedDrivingReducer(RecordingPolicy(200000,200000,1000,3000,0.1,0.5)){"new-drive"}){ContractResult.Success(rules)}
        val result=ok(input.pause("new-epoch",RecordingClock(200000,EpochMillis(1794279800000))));assertEquals(ContractError.DATA_UNAVAILABLE,result.waiting);assertEquals(missing,result.state.outbox.single());assertTrue(result.committed.isEmpty());assertNull(ok(store.observedDrivingReceipt(missing.id)));assertEquals(GameMoney(0),ok(store.balance()))
    }
    fun testFailedV12MigrationPreservesOriginalVersionAndWholeFile() {
        ok(store.activateObservedDriving(actor.profileId));store.close();raw{it.version=12};val file=context.getDatabasePath(name);val original=file.readBytes();store=open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.balance());store.close();assertTrue(original.contentEquals(file.readBytes()));raw{assertEquals(12,it.version);it.version=16};store=open()
    }

    fun testIndependentReceiptWithoutMarkerIsCorruptionNotInactiveModule() {
        val t=pending();ok(drain());raw{it.execSQL("DELETE FROM observed_driving_marker")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.observedDrivingReceipt(t.id))
        assertEquals(before,rows())
    }
    fun testIndependentChangedRawNanOutboxCannotAckDifferentOriginalReceipt() {
        ok(store.activateObservedDriving(actor.profileId))
        fun gap(bits:Long)=DrivingGap(DrivingGapReason.INVALID_SIGNAL,null,RecordingClock(200000,EpochMillis(1794279800000)),observation(200000,Double.fromBits(bits)))
        val t=trip().copy(gaps=listOf(gap(0x7ff8000000000042L)))
        ok(save(read().copy(revision=1,outbox=listOf(t))));val original=ok(drain())
        val changed=t.copy(gaps=listOf(gap(0x7ff8000000000043L)))
        assertFalse(ObservedDrivingJson.trip(original.trip).toString()==ObservedDrivingJson.trip(changed).toString())
        val altered=read().copy(outbox=listOf(changed));val payload=ObservedDrivingJson.state(altered).toString()
        raw{it.execSQL("UPDATE observed_driving_state SET payload=?,digest=?",arrayOf(payload,ObservedDrivingJson.digest(payload)))}
        val loaded=read();assertEquals(0x7ff8000000000043L,(loaded.outbox.single().gaps.single().observation!!.speedMps as Observation.Available).value.toRawBits())
        val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),save(loaded.copy(revision=loaded.revision+1,outbox=emptyList())))
        assertEquals(before,rows())
    }

    fun testIndependentReceiptReadbackMustCompareRawNanBitsExactly() {
        ok(store.activateObservedDriving(actor.profileId))
        fun gap(bits:Long)=DrivingGap(DrivingGapReason.INVALID_SIGNAL,null,RecordingClock(200000,EpochMillis(1794279800000)),observation(200000,Double.fromBits(bits)))
        val t=trip().copy(gaps=listOf(gap(0x7ff8000000000042L)));ok(save(read().copy(revision=1,outbox=listOf(t))))
        val before=rows()
        val result=store.atomic { db ->
            val paid=when(val r=store.driving.settle(rules,t.confirmed(source)!!)){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
            val receipt=ObservedDrivingReceipt(t,paid)
            val altered=ObservedDrivingJson.receipt(receipt.copy(trip=t.copy(gaps=listOf(gap(0x7ff8000000000043L))))).toString()
            fun sql(value:String)="'"+value.replace("'","''")+"'"
            db.execSQL("CREATE TRIGGER alter_observed_receipt AFTER INSERT ON observed_driving_receipts BEGIN UPDATE observed_driving_receipts SET payload="+sql(altered)+",digest="+sql(ObservedDrivingJson.digest(altered))+" WHERE drive_id=NEW.drive_id; END")
            journal.recordCommit(db,actor,receipt)
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),result)
        assertEquals(before,rows())
    }

}
