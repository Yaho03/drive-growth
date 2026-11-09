package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import java.math.BigDecimal

/** UI가 경제 규칙을 계산하지 않도록 기존 Gacha의 같은 후보/가중치 조건을 표시 입력으로 만든다. */
fun mapGachaOffers(content: GameContentSnapshot, state: GachaState, balance: GameMoney, owned: Int,
    paid: RequestId, free: RequestId, origin: GachaOrigin): ContractResult<GachaOffers> {
    val a = state.authority
    val p = state.progress
    val candidates = content.unlockedAt(a.shopLevel).map { it.vehicleId }
    val eligible = Rarity.entries.filter { (content.gacha.weights[it] ?: 0) > 0 }.toSet()
    if (a.contentVersion != content.catalog.contentVersion || a.ruleVersion != content.ruleVersion ||
        a.pity != content.gacha.pity || a.guaranteedFrom != content.gacha.guaranteedFrom ||
        a.unlockedVehicles != candidates.toSet() || a.eligibleRarities != eligible)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    if (owned < 0 || paid == free) return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (p.freeTickets > Int.MAX_VALUE) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val guaranteed = p.drawsSinceGuarantee + 1 == a.pity
    val weights = Rarity.entries.associateWith { if (!guaranteed || it >= a.guaranteedFrom) content.gacha.weights[it] ?: 0 else 0 }
    val total = weights.values.sumOf { it.toLong() }
    if (total <= 0) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    // 유한소수는 기존 표시를 유지하고 순환소수는 원시 가중치/분모를 직접 표시한다.
    val rates = try { weights.mapValues { BigDecimal.valueOf(it.value.toLong()).multiply(BigDecimal(100)).divide(BigDecimal(total)) } }
        catch (_: ArithmeticException) { null }
    val availability = when { candidates.isEmpty() -> GachaAvailability.EMPTY_CANDIDATES
        owned >= a.garageCapacity -> GachaAvailability.NO_SPACE; else -> GachaAvailability.READY }
    return ContractResult.Success(GachaOffers(content, candidates, rates,
        GachaGuarantee(a.pity - p.drawsSinceGuarantee, guaranteed, candidates.isNotEmpty(), "확정된 가챠 진행 상태"),
        GachaSpace(owned, a.garageCapacity, availability), balance, a.purchasePrice, listOf(
            GachaOption("paid", "게임머니로 뽑기", GachaOptionKind.PAID, a.purchasePrice, 0, 0, paid,
                if (balance.units < a.purchasePrice.units) GachaAvailability.INSUFFICIENT_MONEY else GachaAvailability.READY,
                "뽑기권 구입과 사용을 함께 확정합니다."),
            GachaOption("free", "무료권으로 뽑기", GachaOptionKind.FREE, GameMoney(0), 1, p.freeTickets.toInt(), free,
                if (p.freeTickets == 0L) GachaAvailability.INSUFFICIENT_TICKETS else GachaAvailability.READY,
                "저장된 무료권 1장을 사용합니다.")), availability, origin, GachaRatio(weights.mapValues { it.value.toLong() }, total)))
}

/** callback 자체가 아닌 저장소의 전체 결과만 표시한다. 현재 잔액/개체로 원래 결과를 덮지 않는다. */
internal fun resultFrame(input: GachaDrawInput, command: GachaCommand, result: GachaSettlement): ContractResult<GachaScreen.Result> {
    val tx = result.settlement.transaction
    val money = command.award.vehicle.acquisitionCost.takeIf { it.units > 0 }?.let { MoneyChange(MoneyDirection.DEBIT, it) }
    if (result.command != command || result.settlement.changedVehicle != command.award.vehicle ||
        tx.requestId != command.requestId || tx.source != command.source || tx.ruleVersion != command.ruleVersion ||
        tx.kind != TransactionKind.GARAGE || tx.money != money || command.expectedRevision.value == Int.MAX_VALUE ||
        result.progress.revision.value != command.expectedRevision.value + 1 || result.progress.drawsSinceGuarantee != command.award.nextCounter)
        return ContractResult.Failure(ContractError.STORAGE_FAILURE)
    val name = input.offers.content?.catalog?.vehicles?.singleOrNull { it.vehicleId == command.award.vehicle.vehicleId }?.displayName
    return ContractResult.Success(GachaScreen.Result(GachaConfirmedResult(result.settlement, name,
        "저장된 차량 지급 결과", input.offers.origin)))
}
