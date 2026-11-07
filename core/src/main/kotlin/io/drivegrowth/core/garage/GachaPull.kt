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

/** 저장 전의 불변 계획. 응답 유실 시 이 값을 그대로 다시 submit한다. draw.drawsSinceGuarantee는 저장 성공 후에만 새 카운터로 쓴다. */
data class GachaPlan(val change: GameChange, val vehicle: OwnedVehicle, val draw: GachaDraw)

data class GachaResult(val settlement: Settlement, val draw: GachaDraw)

/**
 * 희귀도 추첨 → 해금 차종 선택 → 차감과 새 개체 지급을 거래 한 건으로 저장한다.
 * `prepare`로 불변 계획(거래 + 추첨 결과)을 만들고 `submit`으로 저장한다. 응답을 잃으면 **보관한 같은 계획을 다시 submit**한다.
 * 재제출은 현재 공간·후보·보유 조회를 다시 하지 않으므로, 그사이 차고가 차거나 콘텐츠가 바뀌어도 처음 정산과 추첨 결과를 돌려준다.
 * 공간·후보·입력 오류는 계획 단계에서 거절하고, 잔액 부족·저장 실패는 저장소가 거래 전체를 거절한다.
 * ponytail: 아래 두 경계는 공통 계약·저장 확장이 필요해 아직 보장하지 않는다(#33·#34 조율, PR #111 본문).
 *  - 같은 requestId로 카운터 등 입력만 바꾼 새 계획: GameChange에 가챠 입력이 없어 같은 명령으로 재생된다.
 *  - 마지막 칸의 동시 요청: 공간 검사가 저장과 원자적이지 않다.
 *  보장 카운터·무료권 저장도 같은 확장 대상이며, 지금은 저장 성공 뒤의 다음 카운터 값만 돌려준다.
 */
class GachaPull(private val content: GameContentSnapshot) {
    private val gacha = Gacha(content.gacha)
    private val garage = Garage(content.catalog)

    /** 계획을 저장한다. 같은 계획의 재제출은 저장소가 처음 정산을 재생하고, 추첨 결과는 계획에 보관된 것을 돌려준다. */
    fun submit(store: GameChangeStore, plan: GachaPlan): ContractResult<GachaResult> =
        when (val result = store.commitGameChange(plan.change)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(GachaResult(result.value, plan.draw))
        }

    /** owned는 `store.listOwnedVehicles()` 결과. 조회 실패는 호출자가 그대로 전달한다. */
    fun prepare(owned: List<OwnedVehicle>, request: GachaRequest): ContractResult<GachaPlan> {
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
