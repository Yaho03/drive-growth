package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import io.drivegrowth.core.garage.VehicleRef
import org.junit.Assert.*
import org.junit.Test

class DecorationCheckpointMappingTest {
    private val identity = GameIdentity(ProfileId("shop-owner"), InputSource.SIMULATION)
    private val command = DecorationCommand(RequestId("free-original"), identity.profileId, identity.source,
        "neutral-sign", DecorationAction.BUY_AND_APPLY, RuleVersion("old-definition"), Revision(9), GameMoney(0))

    @Test fun `실제 E typed 슬롯의 전체 명령을 보존하고 다른 프로필 출처 종류는 foreign이다`() {
        for (phase in HostCheckpointPhase.entries) {
            val saved = HostCommandCheckpoint(identity.profileId, HostCheckpointCommand.Decoration(command), phase)
            assertEquals(DecorationPending.Own(command), decorationPending(identity, saved))
            assertEquals(DecorationPending.Foreign, decorationPending(identity, saved.copy(profileId = ProfileId("other"))))
            assertEquals(DecorationPending.Foreign, decorationPending(identity, saved.copy(command = HostCheckpointCommand.Decoration(command.copy(source = InputSource.VEHICLE)))))
            assertEquals(DecorationPending.Foreign, decorationPending(identity, saved.copy(command = HostCheckpointCommand.VehicleResting(
                RequestId("rest"), identity.source, VehicleRef(OwnedVehicleId("v"), Revision(1)), false, true))))
        }
        assertEquals(DecorationPending.Empty, decorationPending(identity, null))
    }

    @Test fun `무료 typed 원결과는 금융 정산 null 그대로 소비하고 다른 command 결과는 거절한다`() {
        val state = decorationFacts().state.copy(revision = Revision(10), ownedIds = setOf("neutral-sign"), selectedId = "neutral-sign")
        val receipt = DecorationSettlement(command, state, null)
        assertSame(receipt, success(decorationReceipt(command, HostCheckpointResult.Decoration(receipt))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), decorationReceipt(command,
            HostCheckpointResult.Decoration(receipt.copy(command = command.copy(requestId = RequestId("other"))))))
        val other = Settlement(SettlementId("s"), Transaction(TransactionId("t"), command.requestId,
            TransactionKind.FACILITY, command.source, command.ruleVersion, null), state.balance, null, EpochMillis(1))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), decorationReceipt(command, HostCheckpointResult.Expand(other)))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), decorationReceipt(command,
            HostCheckpointResult.Decoration(receipt.copy(settlement = other))))
    }

    @Test fun `양수 E 원결과의 공통 돈과 잔액은 core 검증을 통과해야 한다`() {
        val paid = command.copy(decorationId = "blue-sign", expectedPrice = GameMoney(100))
        val state = decorationFacts().state.copy(revision = Revision(10), balance = GameMoney(900), ownedIds = setOf("blue-sign"), selectedId = "blue-sign")
        val settlement = Settlement(SettlementId("s"), Transaction(TransactionId("t"), paid.requestId,
            TransactionKind.FACILITY, paid.source, paid.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(100))), state.balance, null, EpochMillis(1))
        val receipt = DecorationSettlement(paid, state, settlement)
        assertSame(receipt, success(decorationReceipt(paid, HostCheckpointResult.Decoration(receipt))))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), decorationReceipt(paid, HostCheckpointResult.Decoration(receipt.copy(settlement = null))))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), decorationReceipt(paid,
            HostCheckpointResult.Decoration(receipt.copy(settlement = settlement.copy(balance = GameMoney(899))))))
    }
}
