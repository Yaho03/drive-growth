package io.drivegrowth.core.decoration

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class DecorationContractTest {
    private val profile = ProfileId("decoration-profile")
    private val rule = RuleVersion("explicit-decoration-test-v3")
    private val definitions = DecorationDefinitions(rule, true, listOf(
        DecorationDefinition("neutral-sign", "neutral", "기본 간판", 1, GameMoney(0)),
        DecorationDefinition("blue-sign", "blue-sign", "간판 표시", 3, GameMoney(250)),
    ))
    private val initial = DecorationState(profile, InputSource.VEHICLE, Revision(4), GameMoney(1000), 3, emptySet(), null)
    private val buy = DecorationCommand(RequestId("decoration/buy/one"), profile, InputSource.VEHICLE,
        "blue-sign", DecorationAction.BUY_AND_APPLY, rule, Revision(4), GameMoney(250))

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue("성공 예정이어야 함: $result", result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun fails(error: ContractError, command: DecorationCommand = buy, state: DecorationState = initial) {
        assertEquals(ContractResult.Failure(error), prepareDecorationChange(command, state, definitions))
    }
    private fun original(): DecorationSettlement {
        val after = initial.copy(revision = Revision(5), balance = GameMoney(750), ownedIds = setOf("blue-sign"), selectedId = "blue-sign")
        val settlement = Settlement(SettlementId("decoration/settlement/original"),
            Transaction(TransactionId("decoration/transaction/original"), buy.requestId, TransactionKind.FACILITY,
                InputSource.VEHICLE, rule, MoneyChange(MoneyDirection.DEBIT, GameMoney(250))),
            GameMoney(750), null, EpochMillis(10000))
        return DecorationSettlement(buy, after, settlement)
    }

    @Test fun `구매 적용 예정은 한 차감과 소유 선택 revision만 만들고 입력을 바꾸지 않는다`() {
        val prepared = ok(prepareDecorationChange(buy, initial, definitions))
        assertEquals(buy, prepared.command)
        assertEquals(profile, prepared.state.profileId)
        assertEquals(InputSource.VEHICLE, prepared.state.source)
        assertEquals(3, prepared.state.shopLevel)
        assertEquals(GameMoney(750), prepared.state.balance)
        assertEquals(Revision(5), prepared.state.revision)
        assertEquals(setOf("blue-sign"), prepared.state.ownedIds)
        assertEquals("blue-sign", prepared.state.selectedId)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(250)), prepared.money)
        assertEquals(GameMoney(1000), initial.balance)
        assertEquals(Revision(4), initial.revision)
        assertTrue(initial.ownedIds.isEmpty())
        assertNull(initial.selectedId)
    }

    @Test fun `무료 구매도 소유와 선택 예정이지만 가짜 영원 차감은 없다`() {
        val command = buy.copy(decorationId = "neutral-sign", expectedPrice = GameMoney(0))
        val result = ok(prepareDecorationChange(command, initial.copy(balance = GameMoney(0)), definitions))
        assertNull(result.money)
        assertEquals(GameMoney(0), result.state.balance)
        assertEquals(setOf("neutral-sign"), result.state.ownedIds)
        assertEquals("neutral-sign", result.state.selectedId)
        assertEquals(Revision(5), result.state.revision)
    }

    @Test fun `잔액이 정확히 비용이면 영이고 한 단위 부족하면 전부 거절한다`() {
        val exact = ok(prepareDecorationChange(buy, initial.copy(balance = GameMoney(250)), definitions))
        assertEquals(GameMoney(0), exact.state.balance)
        val short = initial.copy(balance = GameMoney(249))
        fails(ContractError.INSUFFICIENT_FUNDS, state = short)
        assertEquals(GameMoney(249), short.balance)
        assertTrue(short.ownedIds.isEmpty())
        assertNull(short.selectedId)
        assertEquals(Revision(4), short.revision)
    }

    @Test fun `구매 해금 전에는 돈이 있어도 소유나 선택을 만들지 않는다`() {
        fails(ContractError.STATE_CONFLICT, state = initial.copy(shopLevel = 2))
    }

    @Test fun `현재 정의에 없는 품목을 무료 기본품으로 채우지 않는다`() {
        fails(ContractError.DATA_UNAVAILABLE, buy.copy(decorationId = "missing", expectedPrice = GameMoney(0)))
    }

    @Test fun `다른 프로필과 출처는 같은 잔액과 요청이어도 거절한다`() {
        fails(ContractError.INVALID_INPUT, buy.copy(profileId = ProfileId("foreign")))
        fails(ContractError.INVALID_INPUT, buy.copy(source = InputSource.SIMULATION))
    }

    @Test fun `선택 당시 규칙 revision 가격 중 하나라도 오래되면 거절한다`() {
        listOf(buy.copy(ruleVersion = RuleVersion("old")), buy.copy(expectedRevision = Revision(3)),
            buy.copy(expectedPrice = GameMoney(249)), buy.copy(expectedPrice = GameMoney(251)))
            .forEach { fails(ContractError.STATE_CONFLICT, it) }
    }

    @Test fun `이미 소유한 품목의 새 구매는 재차감하지 않고 충돌이다`() {
        fails(ContractError.STATE_CONFLICT, state = initial.copy(ownedIds = setOf("blue-sign")))
        fails(ContractError.STATE_CONFLICT, state = initial.copy(ownedIds = setOf("blue-sign"), selectedId = "blue-sign"))
    }

    @Test fun `소유 품목 재적용은 잔액과 구매 해금 레벨 없이 무료로 선택만 바꾼다`() {
        val current = initial.copy(balance = GameMoney(0), shopLevel = 1,
            ownedIds = setOf("neutral-sign", "blue-sign"), selectedId = "neutral-sign")
        val command = buy.copy(action = DecorationAction.APPLY_OWNED, expectedPrice = GameMoney(0))
        val result = ok(prepareDecorationChange(command, current, definitions))
        assertEquals(GameMoney(0), result.state.balance)
        assertEquals(setOf("neutral-sign", "blue-sign"), result.state.ownedIds)
        assertEquals("blue-sign", result.state.selectedId)
        assertEquals(Revision(5), result.state.revision)
        assertNull(result.money)
        assertEquals("neutral-sign", current.selectedId)
    }

    @Test fun `미소유 적용과 이미 선택한 품목의 새 적용과 유료 적용은 충돌이다`() {
        val apply = buy.copy(action = DecorationAction.APPLY_OWNED, expectedPrice = GameMoney(0))
        fails(ContractError.STATE_CONFLICT, apply)
        val selected = initial.copy(ownedIds = setOf("blue-sign"), selectedId = "blue-sign")
        fails(ContractError.STATE_CONFLICT, apply, selected)
        fails(ContractError.STATE_CONFLICT, apply.copy(expectedPrice = GameMoney(1)), initial.copy(ownedIds = setOf("blue-sign")))
    }

    @Test fun `revision 상한은 구매와 무료 적용 모두 넘기지 않는다`() {
        val command = buy.copy(expectedRevision = Revision(Int.MAX_VALUE))
        val current = initial.copy(revision = Revision(Int.MAX_VALUE))
        fails(ContractError.INVALID_INPUT, command, current)
        fails(ContractError.INVALID_INPUT, command.copy(action = DecorationAction.APPLY_OWNED, expectedPrice = GameMoney(0)),
            current.copy(ownedIds = setOf("blue-sign")))
    }

    @Test fun `최대 금액도 포화나 오버플로 없이 정확히 차감한다`() {
        val full = DecorationDefinitions(rule, true, listOf(DecorationDefinition("blue-sign", "blue-sign", "간판", 1, GameMoney(Long.MAX_VALUE))))
        val result = ok(prepareDecorationChange(buy.copy(expectedPrice = GameMoney(Long.MAX_VALUE)),
            initial.copy(balance = GameMoney(Long.MAX_VALUE)), full))
        assertEquals(GameMoney(0), result.state.balance)
        assertEquals(GameMoney(Long.MAX_VALUE), result.money?.amount)
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), prepareDecorationChange(
            buy.copy(expectedPrice = GameMoney(Long.MAX_VALUE)), initial.copy(balance = GameMoney(Long.MAX_VALUE - 1)), full))
    }

    @Test fun `같은 전체 요청은 원래 전체 결과 객체를 재계산 없이 재생한다`() {
        val recorded = original()
        assertSame(recorded, ok(replayDecorationChange(buy, recorded)))
        assertEquals(GameMoney(750), recorded.state.balance)
        assertEquals(Revision(5), recorded.state.revision)
        assertNull(ok(replayDecorationChange(buy, null)))
    }

    @Test fun `같은 요청 ID의 다른 내용은 모두 충돌이다`() {
        val recorded = original()
        listOf(buy.copy(profileId = ProfileId("other")), buy.copy(source = InputSource.SIMULATION),
            buy.copy(decorationId = "neutral-sign"), buy.copy(action = DecorationAction.APPLY_OWNED),
            buy.copy(ruleVersion = RuleVersion("new")), buy.copy(expectedRevision = Revision(9)),
            buy.copy(expectedPrice = GameMoney(0)), buy.copy(requestId = RequestId("other")))
            .forEach { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), replayDecorationChange(it, recorded)) }
    }

    @Test fun `원결과 상태의 프로필 출처 revision 선택 잔액 손상은 정상 재생이 아니다`() {
        val recorded = original()
        listOf(recorded.state.copy(profileId = ProfileId("other")), recorded.state.copy(source = InputSource.SIMULATION),
            recorded.state.copy(revision = Revision(4)), recorded.state.copy(selectedId = null),
            recorded.state.copy(balance = GameMoney(751)))
            .forEach { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replayDecorationChange(buy, recorded.copy(state = it))) }
    }

    @Test fun `공통 정산의 요청 출처 규칙 종류 돈 잔액 차량 손상은 거절한다`() {
        val recorded = original()
        val tx = recorded.settlement!!.transaction
        val vehicle = OwnedVehicle(OwnedVehicleId("unrelated"), VehicleId("hyundai_casper"), 1, Revision(0),
            VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        val badTransactions = listOf(tx.copy(requestId = RequestId("wrong")), tx.copy(source = InputSource.SIMULATION),
            tx.copy(ruleVersion = RuleVersion("wrong")), tx.copy(kind = TransactionKind.GARAGE),
            tx.copy(money = null), tx.copy(money = MoneyChange(MoneyDirection.CREDIT, GameMoney(250))),
            tx.copy(money = MoneyChange(MoneyDirection.DEBIT, GameMoney(249))))
        val bad = badTransactions.map { recorded.settlement!!.copy(transaction = it) } +
            listOf(recorded.settlement!!.copy(balance = GameMoney(749)), recorded.settlement!!.copy(changedVehicle = vehicle))
        bad.forEach { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replayDecorationChange(buy, recorded.copy(settlement = it))) }
    }

    @Test fun `무료 구매와 소유 적용 원결과는 공통 정산 없이 그대로 재생한다`() {
        for (action in DecorationAction.entries) {
            val command = buy.copy(action = action, expectedPrice = GameMoney(0))
            val recorded = original().copy(command = command, settlement = null)
            assertSame(recorded, ok(replayDecorationChange(command, recorded)))
            assertNull(recorded.settlement)
        }
    }

    @Test fun `양수 구매 정산 누락과 무료 요청의 가짜 정산은 저장 손상이다`() {
        val recorded = original()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replayDecorationChange(buy, recorded.copy(settlement = null)))
        for (action in DecorationAction.entries) {
            val command = buy.copy(action = action, expectedPrice = GameMoney(0))
            val fake = recorded.copy(command = command, settlement = recorded.settlement!!.copy(
                transaction = recorded.settlement.transaction.copy(money = MoneyChange(MoneyDirection.DEBIT, GameMoney(0)))))
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replayDecorationChange(command, fake))
            val noMoney = fake.copy(settlement = fake.settlement!!.copy(transaction = fake.settlement.transaction.copy(money = null)))
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replayDecorationChange(command, noMoney))
        }
    }

    @Test fun `재생의 최대 revision이나 유료 적용 같은 불가 명령은 저장 손상이다`() {
        val recorded = original()
        listOf(buy.copy(expectedRevision = Revision(Int.MAX_VALUE)), buy.copy(action = DecorationAction.APPLY_OWNED))
            .forEach { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replayDecorationChange(it, recorded.copy(command = it))) }
    }
}
