package io.drivegrowth.core.contract

import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.CompletedRental
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FirstRentalGiftStoreTest {
    private val f=ProfileContractFixtures
    private val profile=f.profile
    private val source=f.source
    private val progress=GachaProgress(Revision(7),2,4)
    private val car=f.vehicle
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value}
    private fun failure(e:ContractError)=ContractResult.Failure(e)
    private fun config(rental:ContractResult<CompletedRental?> = ContractResult.Success(f.completedRental),
        result:FirstRentalGiftSettlement?=null, marker:FirstRentalGiftMarker?=null, original:FirstRentalGiftState?=null,
        version:ContentVersion=f.contentVersion, rule:RuleVersion=f.ruleVersion)=
        FirstRentalGiftFixtureConfiguration(profile,source,version,rule,rental,result,marker,original)
    private fun gacha(p:GachaProgress=progress)=GachaState(GachaAuthority(source,f.contentVersion,f.ruleVersion,
        1,10,GameMoney(500),30,Rarity.RARE,setOf(car.vehicleId),Rarity.entries.toSet()),p)
    private fun store(c:FirstRentalGiftFixtureConfiguration=config(),p:GachaProgress=progress,balance:Long=25)=
        InMemoryGameChangeStore(listOf(car),GameMoney(balance),gachaState=gacha(p),
            materialConfiguration=MaterialFixtureConfiguration(source,f.contentVersion,f.ruleVersion,mapOf(car.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE))),
            firstRentalGiftConfiguration=c)
    private fun command(id:String="gift")=FirstRentalGiftCommand(RequestId(id),profile,source,f.contentVersion,f.ruleVersion,
        FirstRentalGiftCause(f.completedRental.rentalId,f.completedRental.receipt.settlement.settlementId),Revision(7))
    private fun read(s:FirstRentalGiftStore)=ok(s.readFirstRentalGift(profile))
    private fun draw(id:String="draw",p:GachaProgress=progress)=GachaCommand(RequestId(id),source,f.contentVersion,f.ruleVersion,p.revision,1,10,p.drawsSinceGuarantee,GachaTicketUse.FREE,
        GachaAward(car.copy(ownedVehicleId=OwnedVehicleId("draw-$id"),rarity=Rarity.COMMON,acquisitionSource=VehicleAcquisitionSource.GACHA),p.drawsSinceGuarantee+1,false))
    @Test fun `같은 공용 원장이 선물 capability를 제공하고 누락을 완료로 만들지 않는다`() {
        val s:GameChangeStore=RentalContractFixtures.store();assertTrue(s is FirstRentalGiftStore)
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),(s as FirstRentalGiftStore).readFirstRentalGift(profile))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.commitFirstRentalGift(command()))
        val missing=InMemoryGameChangeStore(emptyList(),firstRentalGiftConfiguration=config())
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),missing.commitFirstRentalGift(command()))
    }
    @Test fun `선물 출처만 구성된 원장도 다른 출처 일반 거래를 거절한다`() {
        val s=InMemoryGameChangeStore(emptyList(),firstRentalGiftConfiguration=config())
        val change=GameChange(RequestId("foreign"),TransactionKind.GARAGE,InputSource.VEHICLE,f.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))
        assertEquals(failure(ContractError.INVALID_INPUT),s.commitGameChange(change))
        assertEquals(GameMoney(1),ok(s.commitGameChange(change.copy(source=source))).balance)
    }
    @Test fun `수익0 최초 완료 근거로 무료권과 revision만 한 번 늘고 재화 차량 활동을 보존한다`() {
        val s=store();val cars=ok(s.listOwnedVehicles());val activity=ok(s.readMaterialState(car.ownedVehicleId,car.ownedVehicleId)).target.activity
        val r=ok(s.commitFirstRentalGift(command()))
        assertEquals(command(),r.command);assertEquals(GameMoney(25),r.balance);assertEquals(GachaProgress(Revision(8),2,5),r.progress)
        assertNull(r.transaction.money);assertEquals(TransactionKind.GARAGE,r.transaction.kind);assertEquals(command().requestId,r.transaction.requestId)
        assertEquals(source,r.transaction.source);assertEquals(f.ruleVersion,r.transaction.ruleVersion)
        assertEquals(FirstRentalGiftMarker(profile,command().requestId,command().cause),r.marker);assertEquals(RentalContractFixtures.time,r.confirmedAt)
        assertEquals(r,read(s).granted);assertEquals(r.progress,ok(s.readGachaState()).progress);assertEquals(cars,ok(s.listOwnedVehicles()))
        assertEquals(activity,ok(s.readMaterialState(car.ownedVehicleId,car.ownedVehicleId)).target.activity)
        assertEquals(r,ok(s.commitFirstRentalGift(command())));assertEquals(GachaProgress(Revision(8),2,5),ok(s.readGachaState()).progress)
    }
    @Test fun `잔액과 차량 존재는 완료 근거가 아니며 실패는 원래 오류다`() {
        val absent=store(config(ContractResult.Success(null)),balance=1000)
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),absent.commitFirstRentalGift(command()));assertEquals(progress,ok(absent.readGachaState()).progress)
        for(e in listOf(ContractError.DATA_UNAVAILABLE,ContractError.STORAGE_FAILURE,ContractError.PERMISSION_DENIED)) {
            val s=store(config(failure(e)));assertEquals(failure(e),s.readFirstRentalGift(profile));assertEquals(failure(e),s.commitFirstRentalGift(command()));assertEquals(progress,ok(s.readGachaState()).progress)
        }
    }
    @Test fun `프로필 출처 버전 원인 revision 불일치는 지급하지 않는다`() {
        val c=command()
        for((bad,e) in listOf(c.copy(profileId=ProfileId("other")) to ContractError.INVALID_INPUT,c.copy(source=InputSource.VEHICLE) to ContractError.INVALID_INPUT,
            c.copy(contentVersion=ContentVersion("other")) to ContractError.STATE_CONFLICT,c.copy(ruleVersion=RuleVersion("other")) to ContractError.STATE_CONFLICT,
            c.copy(cause=c.cause.copy(rentalId=RequestId("other"))) to ContractError.STATE_CONFLICT,c.copy(cause=c.cause.copy(settlementId=SettlementId("other"))) to ContractError.STATE_CONFLICT,
            c.copy(expectedProgressRevision=Revision(6)) to ContractError.STATE_CONFLICT)) {
            val s=store();assertEquals(failure(e),s.commitFirstRentalGift(bad));assertNull(read(s).granted);assertEquals(progress,ok(s.readGachaState()).progress)
        }
        assertEquals(failure(ContractError.INVALID_INPUT),store().readFirstRentalGift(ProfileId("other")))
    }
    @Test fun `최초 완료 정산의 종류 출처 요청 연결 손상은 저장 오류다`() {
        val receipt=f.completedRental.receipt
        for(tx in listOf(receipt.settlement.transaction.copy(kind=TransactionKind.GARAGE),receipt.settlement.transaction.copy(source=InputSource.VEHICLE),receipt.settlement.transaction.copy(requestId=RequestId("wrong")))) {
            val rental=f.completedRental.copy(receipt=receipt.copy(settlement=receipt.settlement.copy(transaction=tx)))
            val s=store(config(ContractResult.Success(rental)));assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitFirstRentalGift(command()));assertEquals(progress,ok(s.readGachaState()).progress)
        }
    }
    @Test fun `다른 요청과 원인은 이미 지급된 프로필에서 다시 무료권을 늘리지 않는다`() {
        val s=store();val first=ok(s.commitFirstRentalGift(command()))
        for(c in listOf(command("different"),command().copy(cause=FirstRentalGiftCause(RequestId("later"),SettlementId("later"))),command().copy(expectedProgressRevision=Revision(8)),
            command().copy(contentVersion=ContentVersion("new")),command().copy(ruleVersion=RuleVersion("new"))))
            assertEquals(failure(ContractError.STATE_CONFLICT),s.commitFirstRentalGift(c))
        assertEquals(first,ok(s.commitFirstRentalGift(command())));assertEquals(first.progress,ok(s.readGachaState()).progress)
    }
    @Test fun `무료권 소비와 잔액 변경 뒤에도 원래 전체 결과를 재생한다`() {
        val s=store();val first=ok(s.commitFirstRentalGift(command()))
        ok(s.commitGacha(draw(p=first.progress)));ok(s.commitGameChange(GameChange(RequestId("income"),TransactionKind.RENTAL,source,f.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(9)))))
        assertEquals(first,ok(s.commitFirstRentalGift(command())));assertEquals(GachaProgress(Revision(9),3,4),ok(s.readGachaState()).progress);assertEquals(GameMoney(34),read(s).balance)
    }
    @Test fun `현재 권위 완료 조회가 없어도 저장된 최초 명령 결과가 먼저 재생된다`() {
        val original=store();val before=read(original);val result=ok(original.commitFirstRentalGift(command()))
        val c=config(failure(ContractError.STORAGE_FAILURE),result,result.marker,before,ContentVersion("new"),RuleVersion("new"))
        val s=InMemoryGameChangeStore(emptyList(),GameMoney(99),firstRentalGiftConfiguration=c)
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.readFirstRentalGift(profile))
        assertEquals(result,ok(s.commitFirstRentalGift(command())))
        assertEquals(failure(ContractError.STATE_CONFLICT),s.commitFirstRentalGift(command("again")))
    }
    @Test fun `저장된 마커 전체결과 진행과 당시 상태 손상은 오류다`() {
        val original=store();val before=read(original);val r=ok(original.commitFirstRentalGift(command()))
        val damaged=listOf(r.copy(balance=GameMoney(26)),r.copy(progress=r.progress.copy(freeTickets=6)),r.copy(progress=r.progress.copy(drawsSinceGuarantee=3)),
            r.copy(marker=r.marker.copy(profileId=ProfileId("other"))),r.copy(transaction=r.transaction.copy(money=MoneyChange(MoneyDirection.CREDIT,GameMoney(0)))),r.copy(transaction=r.transaction.copy(requestId=RequestId("other"))))
        for(result in damaged) {
            val s=store(config(result=result,marker=r.marker,original=before));assertEquals(failure(ContractError.STORAGE_FAILURE),s.readFirstRentalGift(profile));assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitFirstRentalGift(command()))
        }
        for(c in listOf(config(result=r,original=before),config(marker=r.marker,original=before),config(result=r,marker=r.marker)))
            assertEquals(failure(ContractError.STORAGE_FAILURE),store(c).commitFirstRentalGift(command()))
    }
    @Test fun `MAX 무료권과 revision은 overflow없이 전체 거절하고 경계 직전은 성공한다`() {
        for(p in listOf(progress.copy(revision=Revision(Int.MAX_VALUE)),progress.copy(freeTickets=Long.MAX_VALUE))) {
            val s=store(p=p);assertEquals(failure(ContractError.INVALID_INPUT),s.commitFirstRentalGift(command().copy(expectedProgressRevision=p.revision)));assertEquals(p,ok(s.readGachaState()).progress);assertNull(read(s).granted)
        }
        val p=progress.copy(revision=Revision(Int.MAX_VALUE-1),freeTickets=Long.MAX_VALUE-1);val s=store(p=p)
        assertEquals(GachaProgress(Revision(Int.MAX_VALUE),2,Long.MAX_VALUE),ok(s.commitFirstRentalGift(command().copy(expectedProgressRevision=p.revision))).progress)
    }
    @Test fun `커밋 직전 실패는 진행 마커 결과 재화 차량을 남기지 않고 재시도 한 번만 지급한다`() {
        val s=store();val before=read(s);val cars=ok(s.listOwnedVehicles());s.nextCommitFailure=ContractError.STORAGE_FAILURE
        assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitFirstRentalGift(command()));assertEquals(before,read(s));assertEquals(cars,ok(s.listOwnedVehicles()));assertEquals(progress,ok(s.readGachaState()).progress)
        val first=ok(s.commitFirstRentalGift(command()));assertEquals(GachaProgress(Revision(8),2,5),first.progress);assertEquals(first,ok(s.commitFirstRentalGift(command())))
    }
    @Test fun `legacy 가챠 재료 수리 초기화 ID가 선물과 양방향 충돌한다`() {
        val target=RentalContractFixtures.firstVehicle;val material=RentalContractFixtures.secondVehicle
        for(kind in 0..4) for(giftFirst in listOf(false,true)) {
            val id=if(kind==4)FirstGrant.REQUEST_ID.value else "shared"
            val acts=mapOf(target.ownedVehicleId to ContractResult.Success(if(kind==3)VehicleActivity.NEEDS_REPAIR else VehicleActivity.IDLE),material.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE))
            val init=ProfileContractFixtures.newState
            val s=InMemoryGameChangeStore(if(kind==4)emptyList()else listOf(target,material),GameMoney(if(kind==4)0 else 1000),gachaState=gacha(),
                materialConfiguration=MaterialFixtureConfiguration(source,f.contentVersion,f.ruleVersion,acts),
                repairConfiguration=RepairFixtureConfiguration(source,f.contentVersion,f.ruleVersion,acts,mapOf(target.ownedVehicleId to ContractResult.Success(1)),mapOf(target.ownedVehicleId to ContractResult.Success(GameMoney(10)))),
                initializationConfiguration=InitializationFixtureConfiguration(ContractResult.Success(init),null),firstRentalGiftConfiguration=config())
            fun other():ContractResult<*> = when(kind) {
                0->s.commitGameChange(GameChange(RequestId(id),TransactionKind.GARAGE,source,f.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1))))
                1->s.commitGacha(draw(id,ok(s.readGachaState()).progress))
                2->s.commitMaterial(MaterialCommand(RequestId(id),source,f.contentVersion,f.ruleVersion,VehicleWrite.Update(target.ownedVehicleId,target.revision,3,VehicleLifecycle.OWNED,50),VehicleWrite.Update(material.ownedVehicleId,material.revision,material.level,VehicleLifecycle.CONSUMED,material.levelProgressPercent),GameMoney(0)))
                3->s.commitRepair(RepairCommand(RequestId(id),source,f.contentVersion,f.ruleVersion,VehicleRef(target.ownedVehicleId,target.revision),1,GameMoney(10)))
                else->s.commitInitialization(f.initialization)
            }
            if(giftFirst) {ok(s.commitFirstRentalGift(command(id)));assertEquals(failure(ContractError.STATE_CONFLICT),other())}
            else {val result=other();assertTrue(result.toString(),result is ContractResult.Success);assertEquals(failure(ContractError.STATE_CONFLICT),s.commitFirstRentalGift(command(id).copy(expectedProgressRevision=read(s).progress.revision)))}
        }
    }
    private fun race(a:()->ContractResult<*>,b:()->ContractResult<*>):List<ContractResult<*>> {
        val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {val tasks=listOf(a,b).map{f->pool.submit(Callable{gate.await();f()})};gate.countDown();return tasks.map{it.get(5,TimeUnit.SECONDS)}} finally{pool.shutdownNow()}
    }
    @Test fun `두 선물과 무료권 소비 경쟁은 같은 progress revision에서 한 건만 확정한다`() {
        for(withDraw in listOf(false,true)) {
            val s=store();val results=race({s.commitFirstRentalGift(command())},{if(withDraw)s.commitGacha(draw())else s.commitFirstRentalGift(command("second"))})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==failure(ContractError.STATE_CONFLICT)})
            val current=read(s)
            if(current.granted!=null) {assertEquals(GachaProgress(Revision(8),2,5),current.progress);assertEquals(1,ok(s.listOwnedVehicles()).size)}
            else {assertEquals(GachaProgress(Revision(8),3,3),current.progress);assertEquals(2,ok(s.listOwnedVehicles()).size)}
            assertEquals(GameMoney(25),current.balance)
        }
    }
}
