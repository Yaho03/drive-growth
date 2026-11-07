package io.drivegrowth.core.contract

import java.util.Collections

/** 현재 저장 측이 소유하는 규칙·해금 snapshot. 호출자의 예상값이나 제품 기본값이 아니다. */
class GachaAuthority(
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val shopLevel: Int,
    val garageCapacity: Int,
    val purchasePrice: GameMoney,
    val pity: Int,
    val guaranteedFrom: Rarity,
    unlockedVehicles: Set<VehicleId>,
    eligibleRarities: Set<Rarity>,
) {
    val unlockedVehicles: Set<VehicleId> = Collections.unmodifiableSet(LinkedHashSet(unlockedVehicles))
    val eligibleRarities: Set<Rarity> = Collections.unmodifiableSet(LinkedHashSet(eligibleRarities))
    init {
        require(shopLevel > 0 && garageCapacity >= 0 && purchasePrice.units > 0)
        require(pity > 0 && guaranteedFrom >= Rarity.RARE)
        require(this.eligibleRarities.any { it >= guaranteedFrom })
    }
}

/** revision은 가챠 상태의 버전이다. 전체 원장의 조회 버전이 아니다. */
data class GachaProgress(val revision: Revision, val drawsSinceGuarantee: Int, val freeTickets: Long) {
    init { require(drawsSinceGuarantee >= 0 && freeTickets >= 0) }
}

data class GachaState(val authority: GachaAuthority, val progress: GachaProgress) {
    init { require(progress.drawsSinceGuarantee < authority.pity) }
}

enum class GachaTicketUse { PURCHASED, FREE }

/** 기능 계산이 이미 고정한 결과. 저장소는 재추첨하지 않고 상태/규칙과의 일관성만 검증한다. */
data class GachaAward(val vehicle: OwnedVehicle, val nextCounter: Int, val guaranteed: Boolean)

data class GachaCommand(
    val requestId: RequestId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val expectedRevision: Revision,
    val shopLevel: Int,
    val garageCapacity: Int,
    val drawsSinceGuarantee: Int,
    val ticket: GachaTicketUse,
    val award: GachaAward,
)

/** 최초 명령·당시 개체/잔액·추첨 결과·확정 진행 상태를 함께 재생한다. */
data class GachaSettlement(val command: GachaCommand, val settlement: Settlement, val progress: GachaProgress)

/**
 * 기존 단일 거래의 추가 capability다. SQLite 구현은 #122 후속이다.
 * 기존 요청/전체 입력 대조와 재생을 먼저 처리한 뒤, 현재 권위 상태·공간·티켓·잔액을 같은 확정에서 검사한다.
 * 다른 종류 명령의 ID 재사용도 STATE_CONFLICT다. 무료권 부족은 INSUFFICIENT_FUNDS이며 금액 변경은 없다.
 */
interface GachaChangeStore : GameChangeStore {
    fun readGachaState(): ContractResult<GachaState>
    fun commitGacha(command: GachaCommand): ContractResult<GachaSettlement>
}
