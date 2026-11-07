package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GachaAward
import io.drivegrowth.core.contract.GachaChangeStore
import io.drivegrowth.core.contract.GachaCommand
import io.drivegrowth.core.contract.GachaTicketUse
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.progression.Gacha
import kotlin.random.Random

/**
 * 가챠 1회. 저장 측 권위 상태(`readGachaState`)와 이 콘텐츠를 대조해 추첨을 고정한 `GachaCommand`를 만들고,
 * 호출자가 `store.commitGacha(command)`로 차감·새 개체·카운터·무료권을 한 번에 확정한다(#126).
 *
 * - 응답을 잃으면 **보관한 같은 command를 다시 commitGacha**한다. 저장소가 현재 상태보다 먼저 최초 정산·진행 상태를 재생한다.
 * - 같은 requestId로 다른 입력(카운터·티켓·버전·결과)을 내면 저장소가 STATE_CONFLICT로 거절한다.
 * - 마지막 칸·마지막 무료권 경쟁은 저장소가 확정 순간에 다시 검사해 한 건만 성공한다.
 * 결과의 추첨·다음 카운터는 저장소가 돌려준 `GachaSettlement`만 사용한다.
 */
class GachaPull(private val content: GameContentSnapshot) {
    private val gacha = Gacha(content.gacha)
    private val garage = Garage(content.catalog)
    private val eligible: Set<Rarity> = Rarity.entries.filter { (content.gacha.weights[it] ?: 0) > 0 }.toSet()

    /** 저장된 상태로 이번 뽑기 명령을 만든다. 저장하지 않는다. 같은 requestId면 같은 추첨이 나온다(seed = requestId). */
    fun prepare(store: GachaChangeStore, requestId: RequestId, ticket: GachaTicketUse): ContractResult<GachaCommand> {
        val state = when (val result = store.readGachaState()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val listed = when (val result = store.listOwnedVehicles()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val owned = when (val result = garage.owned(listed)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val authority = state.authority
        val progress = state.progress
        val candidates = content.unlockedAt(authority.shopLevel)
        // 저장 측 규칙과 이 앱 콘텐츠가 같은 snapshot이어야 계산 결과를 믿을 수 있다.
        if (authority.contentVersion != content.catalog.contentVersion || authority.ruleVersion != content.ruleVersion ||
            authority.pity != content.gacha.pity || authority.guaranteedFrom != content.gacha.guaranteedFrom ||
            authority.eligibleRarities != eligible || authority.unlockedVehicles != candidates.map { it.vehicleId }.toSet()
        ) return fail(ContractError.STATE_CONFLICT)
        if (candidates.isEmpty()) return fail(ContractError.DATA_UNAVAILABLE)
        if (owned.size >= authority.garageCapacity) return fail(ContractError.CAPACITY_EXCEEDED)
        if (ticket == GachaTicketUse.FREE && progress.freeTickets == 0L) return fail(ContractError.INSUFFICIENT_FUNDS)

        // String.hashCode는 JVM 명세로 고정이라 재실행해도 같은 seed다.
        val random = Random(requestId.value.hashCode())
        val draw = gacha.drawRarity(progress.drawsSinceGuarantee, random)
        val model = candidates[random.nextInt(candidates.size)] // 해금 차종은 균등 확률(PRD에 차종별 가중치 없음)
        val cost = if (ticket == GachaTicketUse.PURCHASED) authority.purchasePrice else GameMoney(0)
        val vehicle = OwnedVehicle(
            OwnedVehicleId("gacha-${requestId.value}"), model.vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
            draw.rarity, 0, cost, VehicleAcquisitionSource.GACHA,
        )
        return ContractResult.Success(GachaCommand(
            requestId, authority.source, authority.contentVersion, authority.ruleVersion, progress.revision,
            authority.shopLevel, authority.garageCapacity, progress.drawsSinceGuarantee, ticket,
            GachaAward(vehicle, draw.drawsSinceGuarantee, draw.guaranteed),
        ))
    }

    private fun fail(error: ContractError) = ContractResult.Failure(error)
}
