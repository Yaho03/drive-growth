package io.drivegrowth.core.decoration

import io.drivegrowth.core.contract.*
import java.util.Collections

/** 간판/색 한 묶음의 승인된 표시 키. 이미지·ARGB·좌표·경제 효과를 만들지 않는다. */
data class DecorationDefinition(
    val id: String,
    val themeKey: String,
    val displayName: String,
    val requiredShopLevel: Int,
    val price: GameMoney,
) {
    init { require(id.isNotBlank() && themeKey.isNotBlank() && displayName.isNotBlank() && requiredShopLevel >= 1) }
}

/** 공급자가 버전과 시험 여부를 명시한다. 입력/노출 목록 변경은 권위 정의를 바꾸지 못한다. */
class DecorationDefinitions(val ruleVersion: RuleVersion, val trial: Boolean, items: List<DecorationDefinition>) {
    val items: List<DecorationDefinition> = Collections.unmodifiableList(ArrayList(items))
    init { require(this.items.isNotEmpty() && this.items.map { it.id }.distinct().size == this.items.size) }
}

enum class DecorationAction { BUY_AND_APPLY, APPLY_OWNED }

/** 재시도에는 전체 원명령을 보존한다. UI attempt나 미리보기 상태는 경제 명령에 넣지 않는다. */
data class DecorationCommand(
    val requestId: RequestId,
    val profileId: ProfileId,
    val source: InputSource,
    val decorationId: String,
    val action: DecorationAction,
    val ruleVersion: RuleVersion,
    val expectedRevision: Revision,
    val expectedPrice: GameMoney,
) {
    init { require(decorationId.isNotBlank()) }
}

/**
 * 공통 account 잔액·실제 shopLevel과 같은 거래에서 읽은 꾸미기 snapshot이다. 새 지갑이 아니다.
 * empty/null은 확인된 실제 초기 상태만 뜻한다. 누락/legacy/조회 실패를 이 값으로 보정하지 않는다.
 */
class DecorationState(
    val profileId: ProfileId,
    val source: InputSource,
    val revision: Revision,
    val balance: GameMoney,
    val shopLevel: Int,
    ownedIds: Set<String>,
    val selectedId: String?,
) {
    val ownedIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ownedIds))
    init {
        require(shopLevel >= 1 && this.ownedIds.all { it.isNotBlank() })
        require(selectedId == null || selectedId in this.ownedIds)
    }

    fun copy(
        profileId: ProfileId = this.profileId,
        source: InputSource = this.source,
        revision: Revision = this.revision,
        balance: GameMoney = this.balance,
        shopLevel: Int = this.shopLevel,
        ownedIds: Set<String> = this.ownedIds,
        selectedId: String? = this.selectedId,
    ) =
        DecorationState(profileId, source, revision, balance, shopLevel, ownedIds, selectedId)

    override fun equals(other: Any?): Boolean = other is DecorationState && profileId == other.profileId &&
        source == other.source && revision == other.revision && balance == other.balance && shopLevel == other.shopLevel &&
        ownedIds == other.ownedIds && selectedId == other.selectedId

    override fun hashCode(): Int = arrayOf(profileId, source, revision, balance, shopLevel, ownedIds, selectedId).contentHashCode()
}

/** 순수 예정 결과이며 구매/적용 성공이나 저장 예약이 아니다. */
data class DecorationPreparedChange(val command: DecorationCommand, val state: DecorationState) {
    val money: MoneyChange? get() = command.expectedPrice.takeIf { it.units > 0 }?.let { MoneyChange(MoneyDirection.DEBIT, it) }
}

/** 양수 구매만 공통 FACILITY 정산을 가진다. 무료 구매/적용은 typed 결과만 보존하며 가짜 0원 정산을 만들지 않는다. */
data class DecorationSettlement(val command: DecorationCommand, val state: DecorationState, val settlement: Settlement?)

/**
 * 저장소가 전역 요청 ID/원결과를 먼저 대조한 뒤 같은 거래에서 다시 호출한다.
 * preview/cancel은 호출 결과를 저장하지 않는다. 소유/선택/revision·공통 잔액·원명령/결과는 전부 확정하거나 전부 거절한다.
 */
fun prepareDecorationChange(
    command: DecorationCommand,
    current: DecorationState,
    definitions: DecorationDefinitions,
): ContractResult<DecorationPreparedChange> {
    if (command.profileId != current.profileId || command.source != current.source)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.ruleVersion != definitions.ruleVersion || command.expectedRevision != current.revision)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val definition = definitions.items.find { it.id == command.decorationId }
        ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    when (command.action) {
        DecorationAction.BUY_AND_APPLY -> if (definition.id in current.ownedIds || current.shopLevel < definition.requiredShopLevel ||
            command.expectedPrice != definition.price) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        // 소유 권한은 구매 당시 확정됐다. 현재 구매 해금 레벨을 다시 요구하거나 재구매하지 않는다.
        DecorationAction.APPLY_OWNED -> if (definition.id !in current.ownedIds || current.selectedId == definition.id ||
            command.expectedPrice.units != 0L) return ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
    if (current.revision.value == Int.MAX_VALUE) return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.expectedPrice.units > current.balance.units) return ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
    return ContractResult.Success(DecorationPreparedChange(command, current.copy(
        revision = Revision(current.revision.value + 1),
        balance = GameMoney(current.balance.units - command.expectedPrice.units),
        ownedIds = current.ownedIds + definition.id,
        selectedId = definition.id,
    )))
}

/**
 * confirmed null만 미확정이다. 전역 다른 명령 종류/조회 실패는 저장소가 이 함수 전에 거절한다.
 * 같은 전체 명령의 원결과는 현재 정의·해금·잔액을 읽거나 재계산하지 않는다.
 * decoder의 전체 원문·이전 상태·DB 연결 정합성 검증을 대체하지 않는다.
 */
fun replayDecorationChange(command: DecorationCommand, recorded: DecorationSettlement?): ContractResult<DecorationSettlement?> {
    if (recorded == null) return ContractResult.Success(null)
    if (recorded.command != command) return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val state = recorded.state
    if (command.expectedRevision.value == Int.MAX_VALUE || state.profileId != command.profileId || state.source != command.source ||
        state.revision.value != command.expectedRevision.value + 1 || state.selectedId != command.decorationId ||
        command.decorationId !in state.ownedIds || (command.action == DecorationAction.APPLY_OWNED && command.expectedPrice.units != 0L))
        return ContractResult.Failure(ContractError.STORAGE_FAILURE)
    val settlement = recorded.settlement
    if (command.expectedPrice.units == 0L) {
        if (settlement != null) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
    } else {
        if (settlement == null) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
        val transaction = settlement.transaction
        if (settlement.balance != state.balance || settlement.changedVehicle != null || transaction.requestId != command.requestId ||
            transaction.source != command.source || transaction.ruleVersion != command.ruleVersion || transaction.kind != TransactionKind.FACILITY ||
            transaction.money != MoneyChange(MoneyDirection.DEBIT, command.expectedPrice))
            return ContractResult.Failure(ContractError.STORAGE_FAILURE)
    }
    return ContractResult.Success(recorded)
}
