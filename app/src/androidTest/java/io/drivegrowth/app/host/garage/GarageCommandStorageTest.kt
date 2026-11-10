package io.drivegrowth.app.host.garage

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleAction

/** 고유 SIMULATION 시험 DB의 source→실제 store 통합. 제품 checkpoint/Activity 성공 시험은 아니다. */
@Suppress("DEPRECATION")
class GarageCommandStorageTest : InstrumentationTestCase() {
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return (value as ContractResult.Success).value}
    fun testRealLevelUpSaleAndOriginalCommandReplayAfterReopen() {
        val context=instrumentation.targetContext;val name="garage-host-${System.nanoTime()}.db"
        val content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        val car=OwnedVehicle(OwnedVehicleId("target"),VehicleId("hyundai_casper"),2,Revision(0),VehicleLifecycle.OWNED,
            Rarity.RARE,25,GameMoney(250),VehicleAcquisitionSource.GACHA)
        try {
            SqliteGameChangeStore(context,InputSource.SIMULATION,name).use { store ->
                ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,InputSource.SIMULATION,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
                ok(store.commitGameChange(GameChange(RequestId("add"),TransactionKind.GARAGE,InputSource.SIMULATION,content.ruleVersion,vehicle=VehicleWrite.Add(car))))
            }
            val source=StoredGarageCommandSource(context,name)
            val level=(ok(source.prepare(GarageEvent.RequestAction(car.ownedVehicleId,car.revision,VehicleAction.LEVEL_UP),RequestId("grow"))) as GaragePreparation.Ready).pending
            val first=ok(source.commit(level.command)) as GarageReceipt.Single
            val grown=first.value.changedVehicle!!;assertEquals(3,grown.level);assertEquals(GameMoney(250),grown.acquisitionCost)
            val sale=(ok(source.prepare(GarageEvent.RequestAction(grown.ownedVehicleId,grown.revision,VehicleAction.SELL),RequestId("sell"))) as GaragePreparation.Ready).pending
            val sold=ok(source.commit(sale.command));assertEquals(first,ok(StoredGarageCommandSource(context,name).commit(level.command)))
            assertEquals(sold,ok(source.commit(sale.command)))
            SqliteGameChangeStore(context,InputSource.SIMULATION,name).use { store ->
                assertTrue(ok(store.listOwnedVehicles()).isEmpty())
                assertEquals(VehicleLifecycle.SOLD,ok(store.listVehicleHistory()).single().lifecycle)
            }
        } finally {context.deleteDatabase(name)}
    }
    fun testActualMaterialSelectionCommitsBothRolesAndReplays() {
        val context=instrumentation.targetContext;val name="garage-material-host-${System.nanoTime()}.db"
        val content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        val target=OwnedVehicle(OwnedVehicleId("target"),VehicleId("hyundai_casper"),2,Revision(0),VehicleLifecycle.OWNED,
            Rarity.RARE,50,GameMoney(250),VehicleAcquisitionSource.GACHA)
        val material=target.copy(ownedVehicleId=OwnedVehicleId("material"),rarity=Rarity.ADVANCED)
        try {
            SqliteGameChangeStore(context,InputSource.SIMULATION,name).use { store ->
                for(car in listOf(target,material)) ok(store.commitGameChange(GameChange(RequestId("add-${car.ownedVehicleId.value}"),TransactionKind.GARAGE,
                    InputSource.SIMULATION,content.ruleVersion,vehicle=VehicleWrite.Add(car))))
            }
            val source=StoredGarageCommandSource(context,name)
            val request=GarageEvent.RequestAction(target.ownedVehicleId,target.revision,VehicleAction.USE_MATERIAL)
            val choices=ok(source.prepare(request,RequestId("choose"))) as GaragePreparation.Materials
            assertEquals(listOf(material),choices.candidates)
            val plan=(ok(source.prepare(request,RequestId("enhance"),VehicleRef(material.ownedVehicleId,material.revision))) as GaragePreparation.Ready).pending
            val result=ok(source.commit(plan.command)) as GarageReceipt.Material
            assertEquals(4,result.value.target.level);assertEquals(0,result.value.target.levelProgressPercent)
            assertEquals(VehicleLifecycle.CONSUMED,result.value.material.lifecycle)
            assertEquals(result,ok(StoredGarageCommandSource(context,name).commit(plan.command)))
        } finally {context.deleteDatabase(name)}
    }
}
