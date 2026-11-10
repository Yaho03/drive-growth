package io.drivegrowth.app.host.garage

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleAction

/** E의 실제 helper에 F table 등록된 통합본 전용. 누락 표를 시험이 생성해서 가리지 않는다. */
@Suppress("DEPRECATION")
class GarageCheckpointStorageTest : InstrumentationTestCase() {
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return (value as ContractResult.Success).value}
    private fun scenario(funded:Boolean) {
        val context=instrumentation.targetContext;val name="garage-checkpoint-${System.nanoTime()}.db"
        val content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        val car=OwnedVehicle(OwnedVehicleId("target"),VehicleId("hyundai_casper"),2,Revision(0),VehicleLifecycle.OWNED,
            Rarity.RARE,25,GameMoney(250),VehicleAcquisitionSource.GACHA)
        try {
            SqliteGameChangeStore(context,InputSource.SIMULATION,name).use { store ->
                ok(store.localProfileId()) // helper가 실제 만든 같은 DB ID만 사용한다.
                if(funded) ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,InputSource.SIMULATION,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
                ok(store.commitGameChange(GameChange(RequestId("add"),TransactionKind.GARAGE,InputSource.SIMULATION,content.ruleVersion,vehicle=VehicleWrite.Add(car))))
            }
            val host=CheckpointGarageCommandAdapter(context,name)
            assertNull(ok(host.load())) // checkpoint table 등록 누락은 여기서 실제 오류로 검출한다.
            val plan=(ok(host.prepare(GarageEvent.RequestAction(car.ownedVehicleId,car.revision,VehicleAction.LEVEL_UP),RequestId("grow"))) as GaragePreparation.Ready).pending
            ok(host.save(plan))
            val recreated=CheckpointGarageCommandAdapter(context,name)
            val restored=ok(recreated.load())!!
            assertEquals(plan.command,restored.command);assertEquals(plan.contentVersion,restored.contentVersion)
            assertNull(restored.balance);assertNull(restored.vehicle)
            if(funded) {
                val receipt=ok(recreated.commit(restored.command)) as GarageReceipt.Single
                assertEquals(GameMoney(956),receipt.value.balance);assertEquals(3,receipt.value.changedVehicle!!.level)
                assertEquals(receipt,ok(CheckpointGarageCommandAdapter(context,name).commit(restored.command)))
                ok(recreated.clear(restored));assertNull(ok(host.load()))
            } else {
                assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),recreated.commit(restored.command))
                assertEquals(restored.command,ok(host.load())!!.command)
                ok(host.cancelUnstarted(restored));assertNull(ok(host.load()))
                SqliteGameChangeStore(context,InputSource.SIMULATION,name).use { store ->
                    assertEquals(GameMoney(0),ok(store.balance()));assertEquals(listOf(car),ok(store.listOwnedVehicles()))
                }
            }
        } finally {context.deleteDatabase(name)}
    }
    fun testSaveExecuteReopenReplayAndConfirmedFinishUseSameActualDatabase() = scenario(true)
    fun testInsufficientFundsRemainsSavedUntilExplicitVerifiedCancellation() = scenario(false)
}
