package io.drivegrowth.app.host.onboarding

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.UUID

/** 실제 helper/같은 DB 경로. 시험 입력으로 영업만 공급하며 SDK·제품 프로세스 시험으로 표시하지 않는다. */
@Suppress("DEPRECATION")
class OnboardingLiveSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource,String>
    private fun factory() = GameSessionFactory(context,names,InputSource.VEHICLE)
    private fun <T> ok(r:ContractResult<T>):T { assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value }
    override fun setUp(){super.setUp();val prefix="onboarding-live-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"}}
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun initialize(factory:GameSessionFactory):Pair<StoredOnboardingHostSource,InitializationSettlement> {
        val source=StoredOnboardingHostSource(factory);val state=ok(source.read())
        return source to ok(source.choose(state.identity,FirstGrant.CANDIDATES.first()))
    }
    fun testNewVehicleProfileInitializesAndReopensOriginalResult(){
        val f=factory();val source=StoredOnboardingHostSource(f);val first=ok(source.read())
        assertEquals(InputSource.VEHICLE,first.identity.source);assertTrue(first.initialization.status is ProfileInitializationStatus.New)
        val saved=ok(source.choose(first.identity,FirstGrant.CANDIDATES.first()))
        assertEquals(GameMoney(1000),saved.grant.balance);assertEquals(Rarity.COMMON,saved.grant.changedVehicle!!.rarity);assertEquals(3,saved.garageCapacity)
        val reopened=StoredOnboardingHostSource(factory());val latest=ok(reopened.read())
        assertEquals(first.identity,latest.identity);assertEquals(saved,(latest.initialization.status as ProfileInitializationStatus.Existing).original)
        assertEquals(saved,ok(reopened.choose(latest.identity,FirstGrant.CANDIDATES.first())))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),reopened.choose(latest.identity,FirstGrant.CANDIDATES.last()))
    }
    fun testExistingSimulationModeAndMissingProgressArePreserved(){
        SqliteGameChangeStore(context,InputSource.SIMULATION,names.getValue(InputSource.SIMULATION)).use{ok(it.localProfileId())}
        val f=factory();val source=StoredOnboardingHostSource(f);val snapshot=ok(source.read());assertEquals(InputSource.SIMULATION,snapshot.identity.source)
        ok(source.choose(snapshot.identity,FirstGrant.CANDIDATES.first()))
        ok(f.open()).use{assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),it.store.readGachaState());assertEquals(GameMoney(1000),ok(it.store.balance()))}
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testLegacyMissingInitializationStaysUnknownAndDoesNotSeedProgress(){
        val name=names.getValue(InputSource.SIMULATION)
        SqliteGameChangeStore(context,InputSource.SIMULATION,name).use{ok(it.localProfileId())}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            for(table in listOf("initialization_profile","initialization_requests","first_gift_markers","first_gift_requests","host_command_checkpoint"))db.execSQL("DROP TABLE $table")
            db.version=7
        }
        val f=factory();val source=StoredOnboardingHostSource(f);val snapshot=ok(source.read());assertTrue(snapshot.initialization.status is ProfileInitializationStatus.Unknown)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.choose(snapshot.identity,FirstGrant.CANDIDATES.first()))
        ok(f.open()).use{assertEquals(GameMoney(0),ok(it.store.balance()));assertTrue(ok(it.store.listOwnedVehicles()).isEmpty());assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),it.store.readGachaState())}
    }
    fun testBothExistingModesFailWithoutChangingEitherDatabase(){
        for(source in InputSource.entries)SqliteGameChangeStore(context,source,names.getValue(source)).use{ok(it.localProfileId())}
        val before=names.mapValues{context.getDatabasePath(it.value).readBytes()}
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),factory().open())
        assertTrue(names.all{(source,name)->before.getValue(source).contentEquals(context.getDatabasePath(name).readBytes())})
    }
    fun testWrongSourceFileIsPreservedAndNoAlternativeProfileIsCreated(){
        val name=names.getValue(InputSource.VEHICLE)
        SqliteGameChangeStore(context,InputSource.SIMULATION,name).use{ok(it.localProfileId())}
        val before=context.getDatabasePath(name).readBytes();assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),factory().open())
        assertTrue(before.contentEquals(context.getDatabasePath(name).readBytes()));assertFalse(context.getDatabasePath(names.getValue(InputSource.SIMULATION)).exists())
    }
    fun testCorruptExistingFileIsPreservedWithoutDefaultHandlerDeletion(){
        val path=context.getDatabasePath(names.getValue(InputSource.SIMULATION));path.parentFile!!.mkdirs();path.writeBytes(byteArrayOf(1,2,3,4,5))
        val before=path.readBytes();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),factory().open())
        assertTrue(path.exists());assertTrue(before.contentEquals(path.readBytes()));assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testOtherProfileAndSourceAreRejectedBeforeInitialization(){
        val source=StoredOnboardingHostSource(factory());val first=ok(source.read())
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source.choose(first.identity.copy(profileId=ProfileId("other")),FirstGrant.CANDIDATES.first()))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source.choose(first.identity.copy(source=InputSource.SIMULATION),FirstGrant.CANDIDATES.first()))
        assertEquals(GameMoney(0),ok(source.read()).initialization.balance)
    }
    fun testBoundFactoryDoesNotRecreateDeletedDatabase(){
        val f=factory();ok(f.open()).close();context.deleteDatabase(names.getValue(InputSource.VEHICLE))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),f.open());assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testFirstRentalGiftIsOnceAndCurrentTicketAfterSpendIsSeparate(){
        val f=factory();val(source,initial)=initialize(f);val identity=ok(source.read()).identity
        val waiting=ok(source.read()).gift as ContractResult.Success;assertNull(waiting.value.firstCompletedRental)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.claimGift(identity))
        ok(f.open()).use{session->
            val content=(session.content as ContractResult.Success).value;val car=initial.grant.changedVehicle!!
            val id=RequestId("fixture-first-rental")
            ok(session.store.rental.assign(RentalRequest(id,car.vehicleId,1),content.catalog,mapOf(car.vehicleId to RentalCategory.SMALL),mapOf(car.vehicleId to GameMoney(100)),
                RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)))
            ok(session.store.rental.account(id,EpochMillis(0),EpochMillis(3600000),null));ok(session.store.rental.settle(id))
        }
        val gift=ok(source.claimGift(identity));assertEquals(1L,gift.progress.freeTickets);assertEquals(gift,ok(source.claimGift(identity)))
        ok(f.open()).use{session->val content=(session.content as ContractResult.Success).value
            val command=ok(GachaPull(content).prepare(session.store,RequestId("fixture-use-gift"),GachaTicketUse.FREE));ok(session.store.commitGacha(command))}
        val fresh=StoredOnboardingHostSource(factory());val current=(ok(fresh.read()).gift as ContractResult.Success).value
        assertEquals(gift,current.granted);assertEquals(1L,current.granted!!.progress.freeTickets);assertEquals(0L,current.progress.freeTickets)
        assertEquals(gift,ok(fresh.claimGift(identity)))
    }
    fun testGachaAuthorityFollowsSameStoredShopLevelAndCapacity(){
        val f=factory();initialize(f)
        ok(f.open()).use{session->val before=ok(session.store.readGachaState());assertEquals(1,before.authority.shopLevel);assertEquals(3,before.authority.garageCapacity)}
        SQLiteDatabase.openDatabase(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE shop SET level=2,capacity=7 WHERE id=1")}
        ok(f.open()).use{session->val state=ok(session.store.readGachaState());val content=(session.content as ContractResult.Success).value
            assertEquals(2,state.authority.shopLevel);assertEquals(7,state.authority.garageCapacity);assertEquals(content.unlockedAt(2).map{it.vehicleId}.toSet(),state.authority.unlockedVehicles)}
    }
    fun testUiBundleContainsNoNewProofOrCompletionAndIdentityIsPreserved(){
        val identity=GameIdentity(ProfileId("bound"),InputSource.SIMULATION)
        val value=OnboardingHostUiState(identity,OnboardingStep.CONFIRMING,FirstGrant.CANDIDATES.first(),FirstGrant.CANDIDATES.first())
        assertEquals(value,OnboardingStateBundle.decode(OnboardingStateBundle.encode(value)))
        assertEquals(OnboardingHostUiState(),OnboardingStateBundle.decode(null))
    }
}
