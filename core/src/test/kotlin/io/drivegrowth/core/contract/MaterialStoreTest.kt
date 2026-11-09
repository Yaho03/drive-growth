package io.drivegrowth.core.contract

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.garage.VehicleUpgrade
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MaterialStoreTest {
    private val content = GameContentFixtures.loaded()
    private val source = InputSource.SIMULATION
    private val target = RentalContractFixtures.firstVehicle.copy(vehicleId = VehicleId("hyundai_casper"))
    private val material = RentalContractFixtures.secondVehicle.copy(vehicleId = target.vehicleId)
    private fun <T> ok(r: ContractResult<T>): T {
        assertTrue(r.toString(), r is ContractResult.Success)
        return (r as ContractResult.Success).value
    }
    private fun failed(error: ContractError) = ContractResult.Failure(error)
    private fun store(balance: Long = 1000, cars: List<OwnedVehicle> = listOf(target, material),
                      activities: Map<OwnedVehicleId, ContractResult<VehicleActivity>> = cars.associate { it.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE) },
                      gacha: Boolean = false): InMemoryGameChangeStore = InMemoryGameChangeStore(cars, GameMoney(balance),
        gachaState = if (gacha) GachaState(GachaAuthority(source, content.catalog.contentVersion, content.ruleVersion,
            1, 10, GameMoney(500), 30, Rarity.RARE, setOf(target.vehicleId), Rarity.entries.toSet()), GachaProgress(Revision(0),0,1)) else null,
        materialConfiguration = MaterialFixtureConfiguration(source, content.catalog.contentVersion, content.ruleVersion, activities))
    private fun command(id: String = "material-1", cost: Long = 0) = MaterialCommand(RequestId(id), source,
        content.catalog.contentVersion, content.ruleVersion,
        VehicleWrite.Update(target.ownedVehicleId,target.revision,3,VehicleLifecycle.OWNED,50),
        VehicleWrite.Update(material.ownedVehicleId,material.revision,material.level,VehicleLifecycle.CONSUMED,material.levelProgressPercent),GameMoney(cost))
    private fun balance(s: MaterialChangeStore) = ok(s.readMaterialState(target.ownedVehicleId,target.ownedVehicleId)).balance
    private fun vehicles(s: GameChangeStore) = ok(s.listOwnedVehicles())
    private fun gacha(id: String) = GachaCommand(RequestId(id), source, content.catalog.contentVersion, content.ruleVersion,
        Revision(0),1,10,0,GachaTicketUse.FREE,GachaAward(target.copy(ownedVehicleId=OwnedVehicleId("gacha-$id"),revision=Revision(0),
            acquisitionSource=VehicleAcquisitionSource.GACHA),1,false))

    @Test fun `같은 메모리 원장이 재료 capability를 제공하고 미공급 상태는 명시 오류다`() {
        val store: GameChangeStore = RentalContractFixtures.store()
        assertTrue("공유 메모리 원장의 재료 확정 capability가 필요하다", store is MaterialChangeStore)
        val capability = store as MaterialChangeStore
        assertEquals(failed(ContractError.DATA_UNAVAILABLE),capability.readMaterialState(target.ownedVehicleId,material.ownedVehicleId))
        assertEquals(failed(ContractError.DATA_UNAVAILABLE),capability.commitMaterial(command()))
    }

    @Test fun `실제 VehicleUpgrade 계획을 두 개체와 한 정산으로 확정하고 비용0은 money가 없다`() {
        val s = store()
        val plan = ok(VehicleUpgrade(content).planMaterial(vehicles(s),VehicleRef(target.ownedVehicleId,target.revision),
            VehicleRef(material.ownedVehicleId,material.revision)) { ContractResult.Success(VehicleActivity.IDLE) })
        val c = command().copy(target=plan.target,material=plan.material)
        val result = ok(s.commitMaterial(c))
        assertEquals(c,result.command)
        assertEquals(target.copy(level=3,levelProgressPercent=50,revision=Revision(4)),result.target)
        assertEquals(material.copy(lifecycle=VehicleLifecycle.CONSUMED,revision=Revision(4)),result.material)
        assertEquals(listOf(result.target),vehicles(s))
        assertEquals(GameMoney(1000),result.balance)
        assertNull(result.transaction.money)
        assertEquals(c.requestId,result.transaction.requestId)
        assertEquals(TransactionKind.GARAGE,result.transaction.kind)
        assertEquals(c.source,result.transaction.source)
        assertEquals(c.ruleVersion,result.transaction.ruleVersion)
        assertEquals(RentalContractFixtures.time,result.confirmedAt)
        assertEquals(result,ok(s.commitMaterial(c)))
    }

    @Test fun `양수 비용은 공유 잔액에서 한 번 차감하고 두 역할 취득정보를 보존한다`() {
        val s=store();val c=command(cost=200);val first=ok(s.commitMaterial(c))
        assertEquals(GameMoney(800),balance(s))
        assertEquals(MoneyChange(MoneyDirection.DEBIT,GameMoney(200)),first.transaction.money)
        assertEquals(target.acquisitionCost,first.target.acquisitionCost)
        assertEquals(material.acquisitionCost,first.material.acquisitionCost)
        assertEquals(target.rarity,first.target.rarity);assertEquals(material.rarity,first.material.rarity)
        assertEquals(target.acquisitionSource,first.target.acquisitionSource);assertEquals(material.acquisitionSource,first.material.acquisitionSource)
        assertEquals(first,ok(s.commitMaterial(c)));assertEquals(GameMoney(800),balance(s))
    }

    @Test fun `성장 처분과 후속 성공 이후에도 최초 전체 결과를 그대로 재생한다`() {
        val s=store();val c=command(cost=200);val first=ok(s.commitMaterial(c))
        ok(s.commitGameChange(GameChange(RequestId("grow"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(5)),VehicleWrite.Update(target.ownedVehicleId,Revision(4),4,VehicleLifecycle.OWNED,0))))
        ok(s.commitGameChange(GameChange(RequestId("sell"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(20)),VehicleWrite.Update(target.ownedVehicleId,Revision(5),4,VehicleLifecycle.SOLD,0))))
        assertEquals(first,ok(s.commitMaterial(c)));assertEquals(GameMoney(825),balance(s));assertTrue(vehicles(s).isEmpty())
    }

    @Test fun `같은 ID의 전체 입력이 하나라도 다르면 충돌하고 원결과는 유지한다`() {
        val s=store();val c=command(cost=100);val first=ok(s.commitMaterial(c));val before=vehicles(s)
        val changes=listOf(c.copy(source=InputSource.VEHICLE),c.copy(contentVersion=ContentVersion("other")),
            c.copy(ruleVersion=RuleVersion("other")),c.copy(cost=GameMoney(0)),c.copy(target=c.target.copy(level=4)),
            c.copy(target=c.target.copy(expectedRevision=Revision(0))),c.copy(material=c.material.copy(ownedVehicleId=target.ownedVehicleId)),
            c.copy(material=c.material.copy(levelProgressPercent=0)),c.copy(target=c.material,material=c.target))
        changes.forEach { assertEquals(failed(ContractError.STATE_CONFLICT),s.commitMaterial(it)) }
        assertEquals(first,ok(s.commitMaterial(c)));assertEquals(before,vehicles(s));assertEquals(GameMoney(900),balance(s))
    }

    @Test fun `legacy와 가챠 ID는 재료와 양방향 충돌하며 같은 원장을 공유한다`() {
        for(gacha in listOf(false,true)) for(materialFirst in listOf(false,true)) {
            val s=store(gacha=true);val c=command("shared",100)
            val other:()->ContractResult<*> = if(gacha) {{s.commitGacha(gacha("shared"))}} else {{s.commitGameChange(
                GameChange(c.requestId,TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1))))}}
            if(materialFirst) { ok(s.commitMaterial(c));assertEquals(failed(ContractError.STATE_CONFLICT),other()) }
            else { assertTrue(other() is ContractResult.Success);assertEquals(failed(ContractError.STATE_CONFLICT),s.commitMaterial(c)) }
        }
    }

    @Test fun `한쪽 revision과 잔액 부족은 모든 금액과 개체를 보존한다`() {
        val cases=listOf(command(cost=1001) to ContractError.INSUFFICIENT_FUNDS,
            command().let { it.copy(target=it.target.copy(expectedRevision=Revision(2))) } to ContractError.STATE_CONFLICT,
            command().let { it.copy(material=it.material.copy(expectedRevision=Revision(2))) } to ContractError.STATE_CONFLICT)
        for((c,error) in cases) {
            val s=store();val before=vehicles(s);assertEquals(failed(error),s.commitMaterial(c))
            assertEquals(before,vehicles(s));assertEquals(GameMoney(1000),balance(s));assertTrue(s.commitMaterial(command()) is ContractResult.Success)
        }
    }

    @Test fun `활동 누락과 조회 실패는 IDLE로 보정하지 않고 대상 재료 모두 금지한다`() {
        for(id in listOf(target.ownedVehicleId,material.ownedVehicleId)) {
            for(activity in listOf(VehicleActivity.RENTED,VehicleActivity.RETURNING,VehicleActivity.NEEDS_REPAIR)) {
                val states=mapOf(target.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE),material.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE)).toMutableMap()
                states[id]=ContractResult.Success(activity);val s=store(activities=states);val before=vehicles(s)
                assertEquals(failed(ContractError.STATE_CONFLICT),s.commitMaterial(command( cost=100)))
                assertEquals(before,vehicles(s))
            }
        }
        val missing=store(activities=emptyMap());assertEquals(failed(ContractError.DATA_UNAVAILABLE),missing.commitMaterial(command()))
        val error=store(activities=mapOf(target.ownedVehicleId to failed(ContractError.PERMISSION_DENIED)))
        assertEquals(failed(ContractError.PERMISSION_DENIED),error.commitMaterial(command()))
    }

    @Test fun `같은 개체 다른 차종과 잘못된 성장 역할은 원자적으로 거절한다`() {
        val invalid=listOf(command().let { it.copy(material=it.material.copy(ownedVehicleId=target.ownedVehicleId)) },
            command().let { it.copy(target=it.target.copy(level=target.level,levelProgressPercent=0)) },
            command().let { it.copy(material=it.material.copy(level=5)) })
        for(c in invalid) {val s=store();assertEquals(failed(ContractError.INVALID_INPUT),s.commitMaterial(c));assertEquals(listOf(target,material),vehicles(s))}
        val s=store(cars=listOf(target,material.copy(vehicleId=VehicleId("other"))))
        assertEquals(failed(ContractError.INVALID_INPUT),s.commitMaterial(command()))
    }

    @Test fun `최대 재료 성장과 revision Long잔액 경계를 보존한다`() {
        val high=material.copy(level=20,levelProgressPercent=0)
        val s=store(balance=Long.MAX_VALUE,cars=listOf(target,high))
        val c=command(cost=Long.MAX_VALUE).let { it.copy(material=it.material.copy(level=20,levelProgressPercent=0)) }
        val r=ok(s.commitMaterial(c));assertEquals(GameMoney(0),r.balance);assertEquals(20,r.material.level)
        assertEquals(high.acquisitionCost,r.material.acquisitionCost)
        for(isTarget in listOf(false,true)) {
            val max=if(isTarget) target.copy(revision=Revision(Int.MAX_VALUE)) else material.copy(revision=Revision(Int.MAX_VALUE))
            val s2=store(cars=if(isTarget) listOf(max,material) else listOf(target,max))
            val c2=command().let { if(isTarget) it.copy(target=it.target.copy(expectedRevision=max.revision)) else it.copy(material=it.material.copy(expectedRevision=max.revision)) }
            assertEquals(failed(ContractError.INVALID_INPUT),s2.commitMaterial(c2))
        }
    }

    @Test fun `커밋 직전 주입 실패는 두 개체 잔액 요청기록을 남기지 않고 같은 명령 재시도가 된다`() {
        val s=store();s.nextCommitFailure=ContractError.STORAGE_FAILURE;val c=command(cost=200)
        assertEquals(failed(ContractError.STORAGE_FAILURE),s.commitMaterial(c));assertEquals(listOf(target,material),vehicles(s));assertEquals(GameMoney(1000),balance(s))
        val receipt=ok(s.commitMaterial(c));assertEquals(GameMoney(800),receipt.balance);assertEquals(receipt,ok(s.commitMaterial(c)))
    }

    @Test fun `명시 활동 입력은 원본 Map을 바꿔도 설정 당시 값으로 고정된다`() {
        val states=mutableMapOf<OwnedVehicleId,ContractResult<VehicleActivity>>(target.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE),material.ownedVehicleId to ContractResult.Success(VehicleActivity.IDLE))
        val s=store(activities=states);states.clear();assertTrue(s.commitMaterial(command()) is ContractResult.Success)
    }

    @Test fun `같은 명령의 동시 재전송은 첫 정산 하나와 두 revision만 확정한다`() {
        val s=store();val c=command(cost=100)
        val results=race((1..8).map { { s.commitMaterial(c) } }).map { ok(it) }
        assertTrue(results.all { it==results.first() });assertEquals(GameMoney(900),balance(s));assertEquals(Revision(4),vehicles(s).single().revision)
    }

    @Test fun `같은 재료를 쓰는 서로 다른 두 요청은 한 쪽만 확정한다`() {
        val third=target.copy(ownedVehicleId=OwnedVehicleId("third"));val s=store(cars=listOf(target,material,third));val a=command(cost=100)
        val b=command("other",100).copy(target=a.target.copy(ownedVehicleId=third.ownedVehicleId))
        val results=race(listOf({s.commitMaterial(a)},{s.commitMaterial(b)}))
        assertEquals(1,results.count { it is ContractResult.Success });assertEquals(GameMoney(900),balance(s));assertEquals(2,vehicles(s).size)
        assertEquals(1,vehicles(s).count { it.revision==Revision(4) })
    }

    @Test fun `가챠와 재료가 같은 원장에 다른 출처를 구성할 수 없다`() {
        val authority = GachaAuthority(InputSource.VEHICLE, content.catalog.contentVersion, content.ruleVersion,
            1,10,GameMoney(500),30,Rarity.RARE,setOf(target.vehicleId),Rarity.entries.toSet())
        val configuration = MaterialFixtureConfiguration(source,content.catalog.contentVersion,content.ruleVersion,emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryGameChangeStore(listOf(target,material),gachaState=GachaState(authority,GachaProgress(Revision(0),0,1)),
                materialConfiguration=configuration)
        }
    }

    @Test fun `미일치 출처 버전과 누락 개체는 변경 없이 명시 오류를 돌려준다`() {
        val s=store();val before=vehicles(s)
        assertEquals(failed(ContractError.INVALID_INPUT),s.commitMaterial(command().copy(source=InputSource.VEHICLE)))
        assertEquals(failed(ContractError.STATE_CONFLICT),s.commitMaterial(command().copy(ruleVersion=RuleVersion("old"))))
        assertEquals(failed(ContractError.STATE_CONFLICT),s.commitMaterial(command().copy(contentVersion=ContentVersion("old"))))
        assertEquals(failed(ContractError.DATA_UNAVAILABLE),s.readMaterialState(target.ownedVehicleId,OwnedVehicleId("missing")))
        assertEquals(failed(ContractError.INVALID_INPUT),s.commitGameChange(GameChange(RequestId("mixed-source"),TransactionKind.GARAGE,
            InputSource.VEHICLE,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))))
        assertEquals(before,vehicles(s));assertEquals(GameMoney(1000),balance(s))
    }

    @Test fun `후속 재료 확정과 가챠를 거친 뒤에도 이전 정산과 공유 잔액은 정확하다`() {
        val second=material.copy(ownedVehicleId=OwnedVehicleId("material-next"));val s=store(cars=listOf(target,material,second),gacha=true)
        val a=ok(s.commitMaterial(command(cost=100)))
        val b=command("next",50).copy(target=VehicleWrite.Update(target.ownedVehicleId,a.target.revision,6,VehicleLifecycle.OWNED,0),
            material=VehicleWrite.Update(second.ownedVehicleId,second.revision,second.level,VehicleLifecycle.CONSUMED,second.levelProgressPercent))
        val later=ok(s.commitMaterial(b));ok(s.commitGacha(gacha("free-after-material")))
        assertEquals(a,ok(s.commitMaterial(command(cost=100))));assertEquals(later,ok(s.commitMaterial(b)))
        assertEquals(GameMoney(850),balance(s));assertEquals(2,vehicles(s).size)
    }

    @Test fun `legacy 갱신과 재료 확정이 같은 revision에서 경쟁하면 한 쪽만 반영된다`() {
        val s=store();val c=command(cost=100)
        val legacy=GameChange(RequestId("legacy-race"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT,GameMoney(100)),VehicleWrite.Update(target.ownedVehicleId,target.revision,2,VehicleLifecycle.OWNED,0))
        val results=race<ContractResult<*>>(listOf({s.commitMaterial(c)},{s.commitGameChange(legacy)}))
        assertEquals(1,results.count { it is ContractResult.Success });assertEquals(GameMoney(900),balance(s))
        val current=ok(s.readMaterialState(target.ownedVehicleId,material.ownedVehicleId))
        if(results.first() is ContractResult.Success) {
            assertEquals(3,current.target.vehicle.level);assertEquals(VehicleLifecycle.CONSUMED,current.material.vehicle.lifecycle)
        } else {
            assertEquals(2,current.target.vehicle.level);assertEquals(material,current.material.vehicle)
        }
    }

    private fun <T> race(actions: List<()->T>): List<T> {
        val pool=Executors.newFixedThreadPool(actions.size);val gate=CountDownLatch(1)
        return try {val jobs=actions.map { action->pool.submit(Callable {gate.await();action()}) };gate.countDown();jobs.map {it.get(10,TimeUnit.SECONDS)}} finally {pool.shutdownNow()}
    }
}
