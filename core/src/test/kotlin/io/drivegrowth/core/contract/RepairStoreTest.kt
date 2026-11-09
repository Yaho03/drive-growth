package io.drivegrowth.core.contract

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepairStoreTest {
    private val content = GameContentFixtures.loaded()
    private val source = InputSource.SIMULATION
    private val car = RentalContractFixtures.secondVehicle.copy(vehicleId = VehicleId("hyundai_casper"))
    private val material = RentalContractFixtures.firstVehicle.copy(vehicleId = car.vehicleId)
    private val idle = ContractResult.Success(VehicleActivity.IDLE)
    private val needs = ContractResult.Success(VehicleActivity.NEEDS_REPAIR)
    private val activities = mapOf(car.ownedVehicleId to needs, material.ownedVehicleId to idle)
    private fun <T> ok(r: ContractResult<T>): T { assertTrue(r.toString(), r is ContractResult.Success); return (r as ContractResult.Success).value }
    private fun failure(e: ContractError) = ContractResult.Failure(e)
    private fun config(a: Map<OwnedVehicleId, ContractResult<VehicleActivity>> = activities,
        accidents: Map<OwnedVehicleId, ContractResult<Int>> = mapOf(car.ownedVehicleId to ContractResult.Success(2)),
        costs: Map<OwnedVehicleId, ContractResult<GameMoney>> = mapOf(car.ownedVehicleId to ContractResult.Success(GameMoney(440)))) =
        RepairFixtureConfiguration(source, content.catalog.contentVersion, content.ruleVersion, a, accidents, costs)
    private fun store(balance: Long = 1000, cars: List<OwnedVehicle> = listOf(car, material),
        c: RepairFixtureConfiguration = config(), a: Map<OwnedVehicleId, ContractResult<VehicleActivity>> = c.activities) =
        InMemoryGameChangeStore(cars, GameMoney(balance), gachaState = GachaState(
            GachaAuthority(source, content.catalog.contentVersion, content.ruleVersion,1,10,GameMoney(500),30,Rarity.RARE,setOf(car.vehicleId),Rarity.entries.toSet()),
            GachaProgress(Revision(0),0,1)),
            materialConfiguration = MaterialFixtureConfiguration(source,content.catalog.contentVersion,content.ruleVersion,a),repairConfiguration = c)
    private fun command(id: String = "repair") = RepairCommand(RequestId(id),source,content.catalog.contentVersion,content.ruleVersion,
        VehicleRef(car.ownedVehicleId,car.revision),2,GameMoney(440))
    private fun generic(id: String, sold: Boolean = false, revision: Revision = car.revision) = GameChange(RequestId(id),TransactionKind.GARAGE,source,content.ruleVersion,
        MoneyChange(MoneyDirection.CREDIT,GameMoney(10)),if(sold) VehicleWrite.Update(car.ownedVehicleId,revision,car.level,VehicleLifecycle.SOLD,car.levelProgressPercent) else null)
    private fun materialCommand(id: String = "material", revision: Revision = car.revision) = MaterialCommand(RequestId(id),source,content.catalog.contentVersion,content.ruleVersion,
        VehicleWrite.Update(car.ownedVehicleId,revision,5,VehicleLifecycle.OWNED,50),
        VehicleWrite.Update(material.ownedVehicleId,material.revision,material.level,VehicleLifecycle.CONSUMED,material.levelProgressPercent),GameMoney(0))
    private fun gacha(id: String) = GachaCommand(RequestId(id),source,content.catalog.contentVersion,content.ruleVersion,Revision(0),1,10,0,GachaTicketUse.FREE,
        GachaAward(material.copy(ownedVehicleId=OwnedVehicleId("draw"),revision=Revision(0),acquisitionSource=VehicleAcquisitionSource.GACHA),1,false))
    private fun state(s: RepairChangeStore) = ok(s.readRepairState(car.ownedVehicleId))

    @Test fun `기존 공용 메모리 원장이 수리 capability와 미지원 오류를 제공한다`() {
        val s: GameChangeStore = RentalContractFixtures.store()
        assertTrue("같은 메모리 원장이어야 한다",s is RepairChangeStore)
        val r=s as RepairChangeStore
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),r.readRepairState(car.ownedVehicleId))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),r.commitRepair(command()))
        assertNull(ok(r.repairSettlement(RequestId("absent"))))
    }
    @Test fun `한 정산이 비용 활동 revision과 최초 전체 결과를 확정하고 성장 취득 사고를 보존한다`() {
        val s=store();val c=command();val r=ok(s.commitRepair(c));val current=state(s)
        assertEquals(c,r.command);assertEquals(GameMoney(560),r.balance)
        assertEquals(car.copy(revision=Revision(4)),r.vehicle);assertEquals(VehicleActivity.IDLE,r.activity);assertEquals(2,r.accidentCount)
        assertEquals(MoneyChange(MoneyDirection.DEBIT,GameMoney(440)),r.transaction.money)
        assertEquals(TransactionKind.GARAGE,r.transaction.kind);assertEquals(c.requestId,r.transaction.requestId)
        assertEquals(source,r.transaction.source);assertEquals(content.ruleVersion,r.transaction.ruleVersion)
        assertEquals(RentalContractFixtures.time,r.confirmedAt);assertEquals(r,ok(s.repairSettlement(c.requestId)))
        assertEquals(r.vehicle,current.vehicle);assertEquals(r.balance,current.balance);assertEquals(VehicleActivity.IDLE,current.activity);assertEquals(2,current.accidentCount)
        assertEquals(r,ok(s.commitRepair(c)))
    }
    @Test fun `성장 판매 잔액 변경 뒤에도 최초 결과를 현재 검사보다 먼저 재생한다`() {
        val s=store();val c=command();val r=ok(s.commitRepair(c))
        ok(s.commitMaterial(materialCommand(revision=Revision(4))))
        ok(s.commitGameChange(GameChange(RequestId("sell"),TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(10)),
            VehicleWrite.Update(car.ownedVehicleId,Revision(5),5,VehicleLifecycle.SOLD,50))))
        assertEquals(GameMoney(570),state(s).balance);assertEquals(r,ok(s.commitRepair(c)));assertEquals(r,ok(s.repairSettlement(c.requestId)))
        assertEquals(VehicleLifecycle.SOLD,state(s).vehicle.lifecycle)
    }
    @Test fun `같은 ID 다른 전체 입력은 충돌하고 원결과가 유지된다`() {
        val s=store();val c=command();val first=ok(s.commitRepair(c));val before=state(s)
        for(other in listOf(c.copy(source=InputSource.VEHICLE),c.copy(contentVersion=ContentVersion("other")),c.copy(ruleVersion=RuleVersion("other")),
            c.copy(target=c.target.copy(revision=Revision(4))),c.copy(target=c.target.copy(ownedVehicleId=material.ownedVehicleId)),c.copy(expectedAccidentCount=3),c.copy(cost=GameMoney(0))))
            assertEquals(failure(ContractError.STATE_CONFLICT),s.commitRepair(other))
        assertEquals(first,ok(s.commitRepair(c)));assertEquals(before,state(s))
    }
    @Test fun `legacy 가챠 재료와 요청 ID가 양방향 충돌한다`() {
        for(kind in 0..2) for(repairFirst in listOf(false,true)) {
            val a=if(kind==2 && !repairFirst) activities + (car.ownedVehicleId to idle) else activities
            val s=store(c=config(a));val c=command("shared")
            fun other(): ContractResult<*> = when(kind) {0->s.commitGameChange(generic("shared"));1->s.commitGacha(gacha("shared"));else->s.commitMaterial(materialCommand("shared"))}
            if(repairFirst) {ok(s.commitRepair(c));assertEquals(failure(ContractError.STATE_CONFLICT),other())}
            else {assertTrue(other() is ContractResult.Success);assertEquals(failure(ContractError.STATE_CONFLICT),s.commitRepair(c))}
        }
    }
    @Test fun `커밋 직전 실패는 지갑 개체 활동 정산을 남기지 않고 같은 명령 재시도가 된다`() {
        val s=store();val before=state(s);s.nextCommitFailure=ContractError.STORAGE_FAILURE
        assertEquals(failure(ContractError.STORAGE_FAILURE),s.commitRepair(command()));assertEquals(before,state(s));assertNull(ok(s.repairSettlement(command().requestId)))
        val r=ok(s.commitRepair(command()));assertEquals(GameMoney(560),r.balance);assertEquals(r,ok(s.commitRepair(command())))
    }
    @Test fun `source 버전 사고 견적 revision 잔액 불일치는 원자 거절한다`() {
        for((c,e) in listOf(command().copy(source=InputSource.VEHICLE) to ContractError.INVALID_INPUT,
            command().copy(contentVersion=ContentVersion("other")) to ContractError.STATE_CONFLICT,command().copy(ruleVersion=RuleVersion("other")) to ContractError.STATE_CONFLICT,
            command().copy(expectedAccidentCount=-1) to ContractError.INVALID_INPUT,command().copy(expectedAccidentCount=3) to ContractError.STATE_CONFLICT,
            command().copy(cost=GameMoney(439)) to ContractError.STATE_CONFLICT,command().copy(target=VehicleRef(car.ownedVehicleId,Revision(2))) to ContractError.STATE_CONFLICT)) {
            val s=store();val before=state(s);assertEquals(failure(e),s.commitRepair(c));assertEquals(before,state(s));assertNull(ok(s.repairSettlement(c.requestId)))
        }
        val s=store(balance=439);val before=state(s);assertEquals(failure(ContractError.INSUFFICIENT_FUNDS),s.commitRepair(command()));assertEquals(before,state(s))
    }
    @Test fun `활동 사고 견적 누락과 명시 조회 실패는 기본값으로 보정하지 않는다`() {
        val missing=listOf(config(a=emptyMap()),config(accidents=emptyMap()),config(costs=emptyMap()))
        for(c in missing) {val s=store(c=c);assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.readRepairState(car.ownedVehicleId));assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.commitRepair(command()))}
        for(e in listOf(ContractError.DATA_UNAVAILABLE,ContractError.STORAGE_FAILURE,ContractError.PERMISSION_DENIED)) {
            for(c in listOf(config(a=activities+(car.ownedVehicleId to failure(e))),config(accidents=mapOf(car.ownedVehicleId to failure(e))),config(costs=mapOf(car.ownedVehicleId to failure(e))))) {
                val s=store(c=c);assertEquals(failure(e),s.readRepairState(car.ownedVehicleId));assertEquals(failure(e),s.commitRepair(command()));assertEquals(listOf(car,material),ok(s.listOwnedVehicles()))
            }
        }
        val s=store(c=config(accidents=mapOf(car.ownedVehicleId to ContractResult.Success(-1))))
        assertEquals(failure(ContractError.STORAGE_FAILURE),s.readRepairState(car.ownedVehicleId))
    }
    @Test fun `수리 불가 활동과 처분 개체는 확정하지 않는다`() {
        for(a in listOf(VehicleActivity.IDLE,VehicleActivity.RENTED,VehicleActivity.RETURNING)) {val s=store(c=config(activities+(car.ownedVehicleId to ContractResult.Success(a))));assertEquals(failure(ContractError.STATE_CONFLICT),s.commitRepair(command()))}
        for(l in VehicleLifecycle.entries.filter{it!=VehicleLifecycle.OWNED}) {val s=store(cars=listOf(car.copy(lifecycle=l),material));assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.commitRepair(command()))}
        val s=store(cars=listOf(material));assertEquals(failure(ContractError.DATA_UNAVAILABLE),s.commitRepair(command()))
    }
    @Test fun `수리 회복은 재료 조회와 공유되고 generic 갱신도 현재 활동 제한을 따른다`() {
        val s=store();assertEquals(failure(ContractError.STATE_CONFLICT),s.commitMaterial(materialCommand()))
        val update=GameChange(RequestId("grow"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Update(car.ownedVehicleId,car.revision,5,VehicleLifecycle.OWNED,50))
        assertEquals(failure(ContractError.STATE_CONFLICT),s.commitGameChange(update))
        ok(s.commitRepair(command()));assertEquals(VehicleActivity.IDLE,ok(s.readMaterialState(car.ownedVehicleId,material.ownedVehicleId)).target.activity)
        ok(s.commitMaterial(materialCommand(revision=Revision(4))));assertEquals(5,state(s).vehicle.level)
        for(a in listOf(VehicleActivity.RENTED,VehicleActivity.RETURNING)) {
            val occupied=store(c=config(activities+(car.ownedVehicleId to ContractResult.Success(a))))
            assertEquals(failure(ContractError.STATE_CONFLICT),occupied.commitGameChange(generic("sell",true)))
        }
    }
    @Test fun `비용0과 Long잔액 revision상한을 보존한다`() {
        val zero=store(balance=0,c=config(costs=mapOf(car.ownedVehicleId to ContractResult.Success(GameMoney(0)))))
        assertNull(ok(zero.commitRepair(command().copy(cost=GameMoney(0)))).transaction.money);assertEquals(GameMoney(0),state(zero).balance)
        val max=store(balance=Long.MAX_VALUE,c=config(costs=mapOf(car.ownedVehicleId to ContractResult.Success(GameMoney(Long.MAX_VALUE)))))
        assertEquals(GameMoney(0),ok(max.commitRepair(command().copy(cost=GameMoney(Long.MAX_VALUE)))).balance)
        val s=store(cars=listOf(car.copy(revision=Revision(Int.MAX_VALUE)),material));assertEquals(failure(ContractError.INVALID_INPUT),s.commitRepair(command().copy(target=VehicleRef(car.ownedVehicleId,Revision(Int.MAX_VALUE)))))
    }
    @Test fun `설정 map 외부 변경과 모순 활동 source가 공용 상태를 바꾸지 못한다`() {
        val a=activities.toMutableMap();val accidents=mutableMapOf(car.ownedVehicleId to ContractResult.Success(2));val costs=mutableMapOf(car.ownedVehicleId to ContractResult.Success(GameMoney(440)))
        val c=config(a,accidents,costs);a.clear();accidents.clear();costs.clear();val s=store(c=c);assertEquals(GameMoney(560),ok(s.commitRepair(command())).balance)
        try {store(c=config(),a=activities+(car.ownedVehicleId to idle));fail("모순 활동은 거절")} catch(_:IllegalArgumentException) {}
        try {InMemoryGameChangeStore(listOf(car),repairConfiguration=c,materialConfiguration=MaterialFixtureConfiguration(InputSource.VEHICLE,content.catalog.contentVersion,content.ruleVersion,activities));fail("source는 같아야 한다")} catch(_:IllegalArgumentException) {}
    }
    private fun race(first: ()->ContractResult<*>, second: ()->ContractResult<*>): List<ContractResult<*>> {
        val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {val tasks=listOf(first,second).map { f->pool.submit(Callable {gate.await();f()}) };gate.countDown();return tasks.map {it.get(5,TimeUnit.SECONDS)}} finally {pool.shutdownNow()}
    }
    @Test fun `수리 두 요청과 판매 경쟁은 같은 revision에서 한 건만 확정한다`() {
        for(sell in listOf(false,true)) {
            val s=store();val results=race({s.commitRepair(command())},{if(sell)s.commitGameChange(generic("other",true))else s.commitRepair(command("other"))})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==failure(ContractError.STATE_CONFLICT)})
            val current=state(s);assertEquals(Revision(4),current.vehicle.revision)
            if(current.vehicle.lifecycle==VehicleLifecycle.SOLD) {assertEquals(GameMoney(1010),current.balance);assertEquals(VehicleActivity.NEEDS_REPAIR,current.activity)}
            else {assertEquals(GameMoney(560),current.balance);assertEquals(VehicleActivity.IDLE,current.activity)}
        }
    }
    @Test fun `수리와 재료 경쟁은 수리 필요 제한과 같은 revision을 공유한다`() {
        val s=store();val results=race({s.commitRepair(command())},{s.commitMaterial(materialCommand())})
        assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==failure(ContractError.STATE_CONFLICT)})
        assertEquals(GameMoney(560),state(s).balance);assertEquals(car.copy(revision=Revision(4)),state(s).vehicle);assertEquals(2,ok(s.listOwnedVehicles()).size)
    }
}
