package io.drivegrowth.app.ui.results

import io.drivegrowth.core.contract.*

/** debug 전용 결정적 입력. 금액·시각·이름은 제품 규칙이나 실제 저장 조회가 아니다. */
object ResultsPreviewStates {
    private val freeVehicle = OwnedVehicle(OwnedVehicleId("fixture-free-1"), VehicleId("hyundai_casper"),
        2, Revision(0), VehicleLifecycle.OWNED, Rarity.RARE, 25, GameMoney(0), VehicleAcquisitionSource.GACHA)
    val normal = ResultsScreenState.Content(TransactionKind.entries.mapIndexed { index, kind ->
        ResultPresentation(Settlement(SettlementId("fixture-result-$index"),
            Transaction(TransactionId("fixture-transaction-$index"), RequestId("fixture-request-$index"), kind,
                InputSource.SIMULATION, RuleVersion("ui-fixture-v1"),
                when (index) {
                    0 -> MoneyChange(MoneyDirection.CREDIT, GameMoney(120))
                    1 -> MoneyChange(MoneyDirection.DEBIT, GameMoney(350))
                    2 -> MoneyChange(MoneyDirection.CREDIT, GameMoney(75))
                    3 -> MoneyChange(MoneyDirection.CREDIT, GameMoney(0))
                    else -> null
                }),
            GameMoney(9_000L + index),
            if (index == 1 || index == 4) freeVehicle.copy(ownedVehicleId = OwnedVehicleId("fixture-free-$index")) else null,
            EpochMillis(1_793_750_400_000L + index * 60_000)),
            // 같은 차종도 별도 개체 ID다. 일부 차종명은 미제공을 재현한다.
            if (index == 1 || index == 4) "캐스퍼" else null)
    }, ResultsOrigin.FIXTURE)
    val many = normal.copy(results = (0 until 30).map { index ->
        val value = normal.results[index % normal.results.size]
        value.copy(settlement = value.settlement.copy(settlementId = SettlementId("fixture-many-$index"),
            transaction = value.settlement.transaction.copy(transactionId = TransactionId("fixture-tx-$index"),
                requestId = RequestId("fixture-request-$index"))))
    })
    val missingVehicleName = normal.copy(results = listOf(normal.results[1].copy(vehicleName = null)))
    val longText = normal.copy(results = listOf(normal.results[1].copy(
        vehicleName = "긴 차종명과 추가 설명을 줄바꿈하여 표시하는 고정 시험 입력 · 캐스퍼",
        settlement = normal.results[1].settlement.copy(transaction = normal.results[1].settlement.transaction.copy(
            ruleVersion = RuleVersion("fixture-long-rule-" + "긴규칙식별자".repeat(25)))))))
    val empty = normal.copy(results = emptyList())
    val loading: ResultsScreenState = ResultsScreenState.Loading
    val error = ResultsScreenState.Error("결과 목록을 읽지 못했어요. 다시 조회할 수 있어요.")
    val missing = ResultsScreenState.Missing("확정 결과를 받지 못했어요. 도착 확인 대기와 성공을 구분해요.")
    val unsupported = ResultsScreenState.Unsupported("현재 연결에서 결과 목록 조회를 지원하지 않아요.")
    val readFailure: ContractResult<Unit> = ContractResult.Failure(ContractError.STORAGE_FAILURE)
    val readSuccess: ContractResult<Unit> = ContractResult.Success(Unit)
}
