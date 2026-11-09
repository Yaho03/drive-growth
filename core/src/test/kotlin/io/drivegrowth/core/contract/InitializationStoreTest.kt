package io.drivegrowth.core.contract

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InitializationStoreTest {
    private val content=GameContentFixtures.loaded()
    private val profile=ProfileId("initialization-simulation")
    private val source=InputSource.SIMULATION
    private val evidence=NewProfileEvidence(profile,source,"explicit-simulation-proof",Revision(2))
    private val fresh=InitializationState(profile,source,content.catalog.contentVersion,content.ruleVersion,
        ProfileInitializationStatus.New(evidence),GameMoney(0),0,GameMoney(1000),3,FirstGrant.CANDIDATES.toSet())
    private val car=OwnedVehicle(OwnedVehicleId("initial-grant"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,
        Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value}
    private fun failure(e:ContractError)=ContractResult.Failure(e)
    private fun command(chosen:VehicleId=car.vehicleId)=InitializationCommand(profile,evidence,content.catalog.contentVersion,
        GameChange(FirstGrant.REQUEST_ID,TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)),
            VehicleWrite.Add(car.copy(vehicleId=chosen))),3)
    private fun store(state:ContractResult<InitializationState> = ContractResult.Success(fresh), marker:InitializationMarker?=null,
        balance:Long=0,cars:List<OwnedVehicle> = emptyList())=InMemoryGameChangeStore(cars,GameMoney(balance),
        initializationConfiguration=InitializationFixtureConfiguration(state,marker))
    private fun read(s:InitializationChangeStore)=ok(s.readInitialization(profile))
    private fun original():InitializationSettlement=ok(store().commitInitialization(command()))
    private fun generic(id:String="balance",amount:Long=1)=GameChange(RequestId(id),TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(amount)))
    @Test fun `초기화 capability는 같은 원장에 있고 미구성 읽기는 신규로 추론하지 않는다`() {
        val s:GameChangeStore=RentalContractFixtures.store();assertTrue(s is InitializationChangeStore)
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),(s as InitializationChangeStore).readInitialization(profile))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.commitInitialization(command()))
    }
    @Test fun `기존 FirstGrant 어댑터로 재화 첫차 공간 마커와 전체 결과를 한 번 확정한다`() {
        val s=store();val result=ok(commitFirstGrantInitialization(s,evidence,content,car.vehicleId,3))
        assertEquals(command(),result.command);assertEquals(GameMoney(1000),result.grant.balance);assertEquals(car,result.grant.changedVehicle)
        assertEquals(MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)),result.grant.transaction.money)
        assertEquals(FirstGrant.REQUEST_ID,result.grant.transaction.requestId);assertEquals(source,result.grant.transaction.source)
        assertEquals(content.ruleVersion,result.grant.transaction.ruleVersion);assertEquals(TransactionKind.GARAGE,result.grant.transaction.kind)
        assertEquals(3,result.garageCapacity);assertEquals(InitializationMarker(profile,FirstGrant.REQUEST_ID,evidence),result.marker)
        val state=read(s);assertEquals(GameMoney(1000),state.balance);assertEquals(1,state.ownedVehicleCount)
        assertEquals(result,(state.status as ProfileInitializationStatus.Existing).original)
        assertEquals(result,ok(s.commitInitialization(command())));assertEquals(listOf(car),ok(s.listOwnedVehicles()))
    }
    @Test fun `UNKNOWN legacy와 조회 실패는 빈 차고에서도 지급하지 않는다`() {
        for(status in listOf(ProfileInitializationStatus.Unknown(MissingReason.NO_DATA),ProfileInitializationStatus.Existing(null))) {
            val s=store(ContractResult.Success(fresh.copy(status=status)))
            assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.commitInitialization(command()));assertEquals(GameMoney(0),read(s).balance);assertTrue(ok(s.listOwnedVehicles()).isEmpty())
        }
        for(e in listOf(ContractError.STORAGE_FAILURE,ContractError.DATA_UNAVAILABLE,ContractError.PERMISSION_DENIED)) {
            val s=store(failure(e));assertEquals(failure(e),s.readInitialization(profile));assertEquals(failure(e),s.commitInitialization(command()))
        }
    }
    @Test fun `프로필 source 근거 revision과 현재 버전 후보 공간을 검사한다`() {
        val c=command()
        val cases=listOf(c.copy(profileId=ProfileId("other")) to ContractError.INVALID_INPUT,
            c.copy(grant=c.grant.copy(source=InputSource.VEHICLE)) to ContractError.INVALID_INPUT,
            c.copy(evidence=evidence.copy(revision=Revision(1))) to ContractError.STATE_CONFLICT,
            c.copy(contentVersion=ContentVersion("other")) to ContractError.STATE_CONFLICT,
            c.copy(grant=c.grant.copy(ruleVersion=RuleVersion("other"))) to ContractError.STATE_CONFLICT,
            c.copy(garageCapacity=4) to ContractError.STATE_CONFLICT,
            c.copy(grant=c.grant.copy(money=MoneyChange(MoneyDirection.CREDIT,GameMoney(999)))) to ContractError.INVALID_INPUT)
        for((bad,e) in cases) {val s=store();assertEquals(failure(e),s.commitInitialization(bad));assertEquals(GameMoney(0),read(s).balance);assertTrue(ok(s.listOwnedVehicles()).isEmpty())}
        val unavailable=store(ContractResult.Success(fresh.copy(availableCandidates=emptySet())))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),unavailable.commitInitialization(c))
    }
    @Test fun `실제 공용 잔액과 차량이 있으면 NEW 입력의 빈 snapshot으로 덮어쓰지 않는다`() {
        for(s in listOf(store(balance=1),store(cars=listOf(RentalContractFixtures.secondVehicle)))) {
            assertEquals(failure(ContractError.STATE_CONFLICT),s.commitInitialization(command()))
        }
    }
    @Test fun `마커가 있는데 원래 결과가 없거나 연결이 손상되면 저장 오류다`() {
        val r=original()
        val broken=listOf(r.copy(marker=r.marker.copy(profileId=ProfileId("wrong"))),r.copy(garageCapacity=4),
            r.copy(grant=r.grant.copy(balance=GameMoney(999))),r.copy(grant=r.grant.copy(changedVehicle=car.copy(revision=Revision(1)))),
            r.copy(grant=r.grant.copy(transaction=r.grant.transaction.copy(source=InputSource.VEHICLE))),
            r.copy(grant=r.grant.copy(transaction=r.grant.transaction.copy(requestId=RequestId("wrong")))))
        for(result in broken) {
            val s=store(ContractResult.Success(fresh.copy(status=ProfileInitializationStatus.Existing(result))),r.marker,balance=1000,cars=listOf(car))
            assertEquals(failure(ContractError.STORAGE_FAILURE),s.readInitialization(profile));assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitInitialization(command()))
        }
        for(status in listOf(ProfileInitializationStatus.Existing(null),ProfileInitializationStatus.New(evidence))) {
            val s=store(ContractResult.Success(fresh.copy(status=status)),r.marker)
            assertEquals(failure(ContractError.STORAGE_FAILURE),s.readInitialization(profile))
        }
        val missingMarker=store(ContractResult.Success(fresh.copy(status=ProfileInitializationStatus.Existing(r))))
        assertEquals(failure(ContractError.STORAGE_FAILURE),missingMarker.readInitialization(profile))
    }
    @Test fun `후속 성장 판매 잔액 변경 뒤 원래 전체 명령은 과거 결과를 재생한다`() {
        val s=store();val c=command();val first=ok(s.commitInitialization(c))
        ok(s.commitGameChange(GameChange(RequestId("grow"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Update(car.ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0))))
        ok(s.commitGameChange(GameChange(RequestId("sell"),TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(9)),VehicleWrite.Update(car.ownedVehicleId,Revision(1),2,VehicleLifecycle.SOLD,0))))
        assertEquals(first,ok(s.commitInitialization(c)));assertEquals(GameMoney(1009),read(s).balance);assertEquals(0,read(s).ownedVehicleCount)
        assertEquals(failure(ContractError.STATE_CONFLICT),s.commitInitialization(command(VehicleId("hyundai_avante"))))
    }
    @Test fun `현재 콘텐츠 규칙 후보가 바뀐 EXISTING에서도 원명령은 최초 결과를 재생한다`() {
        val r=original();val changed=fresh.copy(contentVersion=ContentVersion("new-content"),ruleVersion=RuleVersion("new-rule"),
            availableCandidates=emptySet(),status=ProfileInitializationStatus.Existing(r))
        val s=store(ContractResult.Success(changed),r.marker,balance=17)
        assertEquals(r,ok(s.commitInitialization(r.command)));assertEquals(GameMoney(17),read(s).balance)
        assertEquals(failure(ContractError.STATE_CONFLICT),s.commitInitialization(r.command.copy(contentVersion=changed.contentVersion)))
        assertEquals(failure(ContractError.STATE_CONFLICT),s.commitInitialization(command(VehicleId("hyundai_avante"))))
    }
    @Test fun `실패 주입은 재화 차량 공간 마커 결과를 남기지 않고 같은 명령 재시도가 된다`() {
        val s=store();s.nextCommitFailure=ContractError.STORAGE_FAILURE
        assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitInitialization(command()))
        assertTrue(read(s).status is ProfileInitializationStatus.New);assertEquals(GameMoney(0),read(s).balance);assertTrue(ok(s.listOwnedVehicles()).isEmpty())
        val first=ok(s.commitInitialization(command()));assertEquals(GameMoney(1000),first.grant.balance);assertEquals(first,ok(s.commitInitialization(command())))
    }
    private fun gachaState()=GachaState(GachaAuthority(source,content.catalog.contentVersion,content.ruleVersion,1,10,GameMoney(500),30,Rarity.RARE,setOf(car.vehicleId),Rarity.entries.toSet()),GachaProgress(Revision(0),0,1))
    private fun gacha(id:String)=GachaCommand(RequestId(id),source,content.catalog.contentVersion,content.ruleVersion,Revision(0),1,10,0,GachaTicketUse.FREE,
        GachaAward(RentalContractFixtures.firstVehicle.copy(ownedVehicleId=OwnedVehicleId("draw"),vehicleId=car.vehicleId,revision=Revision(0),acquisitionSource=VehicleAcquisitionSource.GACHA),1,false))
    @Test fun `초기 공간3은 일반 Add와 가챠 권위에 공유되고 무료권은 새로 만들지 않는다`() {
        val s=InMemoryGameChangeStore(emptyList(),gachaState=gachaState(),initializationConfiguration=InitializationFixtureConfiguration(ContractResult.Success(fresh),null))
        ok(s.commitInitialization(command()));val gs=ok(s.readGachaState());assertEquals(3,gs.authority.garageCapacity);assertEquals(GachaProgress(Revision(0),0,1),gs.progress)
        for(i in 1..3) {
            val add=GameChange(RequestId("add$i"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Add(car.copy(ownedVehicleId=OwnedVehicleId("extra$i"))))
            if(i<=2)ok(s.commitGameChange(add)) else assertEquals(failure(ContractError.CAPACITY_EXCEEDED),s.commitGameChange(add))
        }
        val plain=store();ok(plain.commitInitialization(command()));assertEquals(failure(ContractError.DATA_UNAVAILABLE),plain.readGachaState())
        for(i in 1..3) {val add=GameChange(RequestId("plain$i"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Add(car.copy(ownedVehicleId=OwnedVehicleId("p$i"))))
            if(i<=2)ok(plain.commitGameChange(add))else assertEquals(failure(ContractError.CAPACITY_EXCEEDED),plain.commitGameChange(add))}
    }
    @Test fun `초기화 실패는 기존 가챠 공간과 진행을 보존하고 재시도만 공간3을 확정한다`() {
        val s=InMemoryGameChangeStore(emptyList(),gachaState=gachaState(),initializationConfiguration=InitializationFixtureConfiguration(ContractResult.Success(fresh),null))
        s.nextCommitFailure=ContractError.STORAGE_FAILURE
        assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitInitialization(command()))
        val before=ok(s.readGachaState());assertEquals(10,before.authority.garageCapacity);assertEquals(GachaProgress(Revision(0),0,1),before.progress)
        assertEquals(GameMoney(0),read(s).balance);assertTrue(read(s).status is ProfileInitializationStatus.New)
        ok(s.commitInitialization(command()));assertEquals(3,ok(s.readGachaState()).authority.garageCapacity)
        assertEquals(before.progress,ok(s.readGachaState()).progress)
    }
    @Test fun `일반 가챠 재료 수리 요청 ID가 초기화와 양방향 충돌한다`() {
        val target=RentalContractFixtures.firstVehicle.copy(vehicleId=car.vehicleId);val material=RentalContractFixtures.secondVehicle.copy(vehicleId=car.vehicleId)
        for(kind in 0..3) for(initialFirst in listOf(false,true)) {
            val a=mapOf(target.ownedVehicleId to ContractResult.Success(if(kind==3)VehicleActivity.NEEDS_REPAIR else VehicleActivity.IDLE),material.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE))
            val s=InMemoryGameChangeStore(if(initialFirst)emptyList()else listOf(target,material),GameMoney(if(initialFirst)0 else 1000),gachaState=gachaState(),
                materialConfiguration=MaterialFixtureConfiguration(source,content.catalog.contentVersion,content.ruleVersion,a),
                repairConfiguration=RepairFixtureConfiguration(source,content.catalog.contentVersion,content.ruleVersion,a,mapOf(target.ownedVehicleId to ContractResult.Success(1)),mapOf(target.ownedVehicleId to ContractResult.Success(GameMoney(20)))),
                initializationConfiguration=InitializationFixtureConfiguration(ContractResult.Success(fresh),null))
            fun other():ContractResult<*> = when(kind) {
                0->s.commitGameChange(generic("initial-grant"))
                1->s.commitGacha(gacha("initial-grant"))
                2->s.commitMaterial(MaterialCommand(FirstGrant.REQUEST_ID,source,content.catalog.contentVersion,content.ruleVersion,
                    VehicleWrite.Update(target.ownedVehicleId,target.revision,3,VehicleLifecycle.OWNED,50),VehicleWrite.Update(material.ownedVehicleId,material.revision,material.level,VehicleLifecycle.CONSUMED,material.levelProgressPercent),GameMoney(0)))
                else->s.commitRepair(RepairCommand(FirstGrant.REQUEST_ID,source,content.catalog.contentVersion,content.ruleVersion,VehicleRef(target.ownedVehicleId,target.revision),1,GameMoney(20)))
            }
            if(initialFirst) {ok(s.commitInitialization(command()));assertEquals(failure(ContractError.STATE_CONFLICT),other());assertEquals(failure(ContractError.STATE_CONFLICT),s.repairSettlement(FirstGrant.REQUEST_ID))}
            else {assertTrue(other() is ContractResult.Success);assertEquals(failure(ContractError.STATE_CONFLICT),s.commitInitialization(command()))}
        }
    }
    @Test fun `같은 프로필 동시 두 선택은 한 개체와 한 지급만 확정한다`() {
        val s=store();val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val tasks=FirstGrant.CANDIDATES.map {id->pool.submit(Callable {gate.await();s.commitInitialization(command(id))})};gate.countDown()
            val results=tasks.map{it.get(5,TimeUnit.SECONDS)};assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==failure(ContractError.STATE_CONFLICT)})
            assertEquals(GameMoney(1000),read(s).balance);assertEquals(1,ok(s.listOwnedVehicles()).size)
        } finally {pool.shutdownNow()}
    }
}
