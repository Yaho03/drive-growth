package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.progression.Gacha
import io.drivegrowth.core.progression.GachaDraw
import kotlin.random.Random

/**
 * 뽑기권 종류. PURCHASED는 게임머니로 사서 바로 뽑는다(가격은 GachaRules.ticketPrice).
 * FREE는 개업 선물 등 무료권이며 차량 취득 기준액은 0이다.
 * ponytail: 무료권 보유·차감 저장은 계약에 자리가 없어 호출자가 확인한다. 할인권은 가격이 정해지면 추가.
 */
enum class GachaTicket { PURCHASED, FREE }

data class GachaRequest(
    val requestId: RequestId,
    val source: InputSource,
    val ticket: GachaTicket,
    val shopLevel: Int,
    /** 차고 칸 수. 가게 확장(#45) 결과를 호출자가 넘긴다. */
    val garageCapacity: Int,
    /** 마지막 희귀 이상 이후 뽑은 횟수(저장된 값). */
    val drawsSinceGuarantee: Int,
)

/** change를 저장하기 전의 결과. draw.drawsSinceGuarantee는 저장 성공 후에만 새 카운터로 쓴다. */
data class GachaPlan(val change: GameChange, val vehicle: OwnedVehicle, val draw: GachaDraw)

data class GachaResult(val settlement: Settlement, val draw: GachaDraw)

/**
 * 희귀도 추첨 → 해금 차종 선택 → 차감과 새 개체 지급을 거래 한 건으로 저장한다.
 * 추첨 seed와 새 개체 ID를 requestId로 정해 같은 요청을 다시 보내도 같은 거래가 되고, 저장소가 이미 확정한 결과를 돌려준다.
 * 공간·후보·입력 오류는 저장 전에 거절하고, 잔액 부족·저장 실패는 저장소가 거래 전체를 거절한다.
 * ponytail: 보장 카운터 저장은 계약에 자리가 없어 결과로만 돌려준다. 같은 거래에 넣으려면 #33·#34 확장 필요.
 */
class GachaPull(private val content: GameContentSnapshot) {
    private val gacha = Gacha(content.gacha)
    private val garage = Garage(content.catalog)

    fun pull(store: GameChangeStore, request: GachaRequest): ContractResult<GachaResult> {
        val owned = when (val result = store.listOwnedVehicles()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val plan = when (val result = prepare(request, owned)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        return when (val result = store.commitGameChange(plan.change)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(GachaResult(result.value, plan.draw))
        }
    }

    internal fun prepare(request: GachaRequest, owned: List<OwnedVehicle>): ContractResult<GachaPlan> {
        if (request.shopLevel < 1 || request.garageCapacity < 0 ||
            request.drawsSinceGuarantee !in 0 until content.gacha.pity) return fail(ContractError.INVALID_INPUT)
        val current = when (val result = garage.owned(owned)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (current.size >= request.garageCapacity) return fail(ContractError.CAPACITY_EXCEEDED)
        val candidates = content.unlockedAt(request.shopLevel)
        if (candidates.isEmpty()) return fail(ContractError.DATA_UNAVAILABLE)

        // String.hashCode는 JVM 명세로 고정이라 재실행해도 같은 seed다.
        val random = Random(request.requestId.value.hashCode())
        val draw = gacha.drawRarity(request.drawsSinceGuarantee, random)
        val model = candidates[random.nextInt(candidates.size)] // 해금 차종은 균등 확률(PRD에 차종별 가중치 없음)
        val cost = if (request.ticket == GachaTicket.PURCHASED) content.gacha.ticketPrice else GameMoney(0)
        val vehicle = OwnedVehicle(
            OwnedVehicleId("gacha-${request.requestId.value}"), model.vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
            draw.rarity, 0, cost, VehicleAcquisitionSource.GACHA,
        )
        val change = GameChange(
            request.requestId, TransactionKind.GARAGE, request.source, content.ruleVersion,
            money = if (cost.units > 0) MoneyChange(MoneyDirection.DEBIT, cost) else null,
            vehicle = VehicleWrite.Add(vehicle),
        )
        return ContractResult.Success(GachaPlan(change, vehicle, draw))
    }

    private fun fail(error: ContractError) = ContractResult.Failure(error)
}
