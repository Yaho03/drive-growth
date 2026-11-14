package io.drivegrowth.app.ui.garage

import io.drivegrowth.app.host.garage.GarageCommand
import io.drivegrowth.app.host.garage.GarageCommandState
import io.drivegrowth.app.host.garage.GaragePending
import io.drivegrowth.app.host.garage.GarageReceipt
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GarageCareMotionTest {
    private val before = OwnedVehicle(OwnedVehicleId("vehicle"), VehicleId("casper"), 3, Revision(4),
        VehicleLifecycle.OWNED, Rarity.RARE, 25, GameMoney(500), VehicleAcquisitionSource.GACHA)
    private val source = InputSource.SIMULATION
    private val version = ContentVersion("content")
    private val rules = RuleVersion("rules")
    private val settlementId = SettlementId("settlement")
    private val time = EpochMillis(100)
    private val balance = GameMoney(1_000)

    @Test fun `다섯 가지 새 확정에서 당시 개체와 거래 값을 한 번만 사용한다`() {
        for (action in VehicleAction.entries) {
            val motion = GarageCareMotion()
            val confirmed = result(action)
            val review = GarageCommandState.Review(confirmed.pending, canConfirm = true)
            motion.arm(review, allowed = true)
            assertNull(motion.observe(GarageCommandState.Running(confirmed.pending), allowed = true))
            val effect = checkNotNull(motion.observe(confirmed, allowed = true))
            assertEquals(before, effect.before)
            assertEquals(action, effect.outcome.action)
            assertEquals(Revision(5), effect.outcome.vehicle.revision)
            when (action) {
                VehicleAction.LEVEL_UP -> assertEquals(4, effect.outcome.vehicle.level)
                VehicleAction.USE_MATERIAL -> {
                    assertEquals(75, effect.outcome.vehicle.levelProgressPercent)
                    assertEquals(VehicleLifecycle.CONSUMED, effect.outcome.material?.lifecycle)
                }
                VehicleAction.REPAIR -> assertEquals(3, effect.outcome.vehicle.level)
                VehicleAction.SELL -> assertEquals(GameMoney(300), effect.outcome.credit)
                VehicleAction.SCRAP -> assertNull(effect.outcome.credit)
            }
            assertNull(motion.observe(confirmed, allowed = true))
            motion.arm(review, allowed = true)
            assertNull(motion.observe(GarageCommandState.Running(confirmed.pending), allowed = true))
            assertNull(motion.observe(confirmed, allowed = true))
        }
    }

    @Test fun `복원 및 재진입에는 확정 내용만 표시할 수 있다`() {
        val motion = GarageCareMotion()
        val confirmed = result(VehicleAction.SELL)
        val pending = confirmed.pending.copy(vehicle = null)
        val recovered = confirmed.copy(pending = pending)
        assertNotNull(GarageCareOutcome.from(recovered))
        assertNull(motion.observe(recovered, allowed = true))
        motion.arm(GarageCommandState.Review(pending, canConfirm = true, recovering = true), allowed = true)
        assertNull(motion.observe(GarageCommandState.Running(pending), allowed = true))
        assertNull(motion.observe(recovered, allowed = true))
    }

    @Test fun `실패 후 같은 명령을 다시 확인해도 효과를 실행하지 않는다`() {
        val motion = GarageCareMotion()
        val confirmed = result(VehicleAction.LEVEL_UP)
        val pending = confirmed.pending
        motion.arm(GarageCommandState.Review(pending, canConfirm = true), allowed = true)
        assertNull(motion.observe(GarageCommandState.Running(pending), allowed = true))
        assertNull(motion.observe(GarageCommandState.Failure(ContractError.STORAGE_FAILURE, pending), allowed = true))
        assertNull(motion.observe(GarageCommandState.Running(pending), allowed = true))
        assertNull(motion.observe(confirmed, allowed = true))

        val newResult = result(VehicleAction.LEVEL_UP, RequestId("new-level-up"))
        motion.arm(GarageCommandState.Review(newResult.pending, canConfirm = true), allowed = true)
        assertNull(motion.observe(GarageCommandState.Running(newResult.pending), allowed = true))
        assertNotNull(motion.observe(newResult, allowed = true))
    }

    @Test fun `화면 이탈 또는 제한 이후 재표시 시 다시 시작하지 않는다`() {
        val confirmed = result(VehicleAction.REPAIR)
        val pending = confirmed.pending
        for (hiddenBeforeResult in listOf(true, false)) {
            val motion = GarageCareMotion()
            motion.arm(GarageCommandState.Review(pending, canConfirm = true), allowed = true)
            assertNull(motion.observe(GarageCommandState.Running(pending), allowed = true))
            if (hiddenBeforeResult) motion.clear() else assertNull(motion.observe(confirmed, allowed = false))
            assertNull(motion.observe(confirmed, allowed = true))
        }
        val reduced = GarageCareMotion()
        reduced.arm(GarageCommandState.Review(pending, canConfirm = true), allowed = false)
        assertNull(reduced.observe(GarageCommandState.Running(pending), allowed = true))
        assertNull(reduced.observe(confirmed, allowed = true))
    }

    @Test fun `다른 요청 결과와 처리 전 확정 화면을 효과로 처리하지 않는다`() {
        val confirmed = result(VehicleAction.SELL)
        val pending = confirmed.pending
        val review = GarageCommandState.Review(pending, canConfirm = true)
        val noRunning = GarageCareMotion()
        noRunning.arm(review, allowed = true)
        assertNull(noRunning.observe(confirmed, allowed = true))
        val wrongReceipt = (confirmed.receipt as GarageReceipt.Single).value.let {
            GarageReceipt.Single(it.copy(transaction = it.transaction.copy(requestId = RequestId("other"))))
        }
        val motion = GarageCareMotion()
        motion.arm(review, allowed = true)
        assertNull(motion.observe(GarageCommandState.Running(pending), allowed = true))
        assertNull(motion.observe(confirmed.copy(receipt = wrongReceipt), allowed = true))
        assertNull(motion.observe(confirmed, allowed = true))
    }

    private fun result(action: VehicleAction, id: RequestId = RequestId("care-$action")): GarageCommandState.Confirmed {
        val request = GarageEvent.RequestAction(before.ownedVehicleId, before.revision, action)
        val write = VehicleWrite.Update(before.ownedVehicleId, before.revision, before.level, before.lifecycle, before.levelProgressPercent)
        fun tx(money: MoneyChange? = null) = Transaction(TransactionId("transaction"), id, TransactionKind.GARAGE, source, rules, money)
        fun result(command: GarageCommand, receipt: GarageReceipt) =
            GarageCommandState.Confirmed(GaragePending(request, version, command, vehicle = before), receipt)
        val next = before.copy(revision = Revision(5))
        return when (action) {
            VehicleAction.USE_MATERIAL -> {
                val material = next.copy(ownedVehicleId = OwnedVehicleId("material"), lifecycle = VehicleLifecycle.CONSUMED)
                val target = next.copy(level = 4, levelProgressPercent = 75)
                val command = MaterialCommand(id, source, version, rules, write.copy(level = 4, levelProgressPercent = 75),
                    write.copy(ownedVehicleId = material.ownedVehicleId, lifecycle = VehicleLifecycle.CONSUMED), GameMoney(0))
                result(GarageCommand.Material(command), GarageReceipt.Material(MaterialSettlement(command,
                    settlementId, tx(), balance, target, material, time)))
            }
            VehicleAction.REPAIR -> {
                val command = RepairCommand(id, source, version, rules, VehicleRef(before.ownedVehicleId, before.revision), 1, GameMoney(50))
                val receipt = RepairSettlement(command, settlementId, tx(MoneyChange(MoneyDirection.DEBIT, GameMoney(50))),
                    balance, next, VehicleActivity.IDLE, 1, time)
                result(GarageCommand.Repair(command), GarageReceipt.Repair(receipt))
            }
            VehicleAction.LEVEL_UP, VehicleAction.SELL, VehicleAction.SCRAP -> {
                val (vehicle, money) = when (action) {
                    VehicleAction.LEVEL_UP -> next.copy(level = 4) to MoneyChange(MoneyDirection.DEBIT, GameMoney(20))
                    VehicleAction.SELL -> next.copy(lifecycle = VehicleLifecycle.SOLD) to MoneyChange(MoneyDirection.CREDIT, GameMoney(300))
                    else -> next.copy(lifecycle = VehicleLifecycle.SCRAPPED) to MoneyChange(MoneyDirection.CREDIT, GameMoney(0))
                }
                val command = GameChange(id, TransactionKind.GARAGE, source, rules, money,
                    write.copy(level = vehicle.level, lifecycle = vehicle.lifecycle))
                result(GarageCommand.Single(command), GarageReceipt.Single(Settlement(settlementId, tx(money), balance, vehicle, time)))
            }
        }
    }
}
