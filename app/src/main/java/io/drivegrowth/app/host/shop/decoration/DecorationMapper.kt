package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import java.util.Collections

class DecorationMapper(appearances: Map<String, ShopAppearance>) {
    private val appearances = Collections.unmodifiableMap(LinkedHashMap(appearances))

    /** 가격/해금은 core의 순수 판정을 표시한다. 이 preview 명령은 backend에 전달하거나 저장하지 않는다. */
    private fun candidate(facts: DecorationFacts, id: String, requestId: RequestId): DecorationCommand {
        val owned = id in facts.state.ownedIds
        val definition = facts.definitions.items.single { it.id == id }
        return DecorationCommand(requestId, facts.state.profileId, facts.state.source, id,
            if (owned) DecorationAction.APPLY_OWNED else DecorationAction.BUY_AND_APPLY,
            facts.definitions.ruleVersion, facts.state.revision, if (owned) GameMoney(0) else definition.price)
    }

    fun map(facts: DecorationFacts, displayRevision: Revision): ContractResult<ShopScreenState.Content> {
        val state = facts.state
        if (facts.shop.balance != state.balance || facts.shop.level != state.shopLevel)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val appearance = if (state.selectedId == null) facts.shop.appearance else {
            val definition = facts.definitions.items.singleOrNull { it.id == state.selectedId }
                ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            appearances[definition.themeKey] ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
        val options = facts.definitions.items.map { item ->
            val shown = appearances[item.themeKey]
            val command = candidate(facts, item.id, RequestId("decoration/preview"))
            val check = prepareDecorationChange(command, state, facts.definitions)
            val selected = state.selectedId == item.id
            val availability = when {
                shown == null -> ShopAvailability.UNKNOWN
                selected -> ShopAvailability.INSTALLED
                check is ContractResult.Success -> ShopAvailability.AVAILABLE
                check is ContractResult.Failure && check.error in setOf(ContractError.STATE_CONFLICT, ContractError.INSUFFICIENT_FUNDS) -> ShopAvailability.LOCKED
                else -> ShopAvailability.UNKNOWN
            }
            val condition = when {
                shown == null -> "표시 정보가 아직 준비되지 않았어요"
                selected -> "현재 선택한 간판이에요"
                item.id in state.ownedIds -> "소유 중 · 다시 적용은 무료예요"
                check is ContractResult.Failure && check.error == ContractError.INSUFFICIENT_FUNDS -> "잔액이 부족해요"
                else -> "가게 레벨 ${item.requiredShopLevel}부터 구매할 수 있어요"
            }
            ShopOption("decoration:${item.id}", item.displayName, condition,
                "간판·가게 표시 변경",
                availability, shown?.let { ShopCommand.Decorate(item.id, it) }, command.expectedPrice,
                facts.definitions.ruleVersion, facts.definitions.trial)
        }
        return ContractResult.Success(facts.shop.copy(revision = displayRevision, appearance = appearance,
            vehicles = Collections.unmodifiableList(ArrayList(facts.shop.vehicles)),
            facilities = Collections.unmodifiableList(ArrayList(facts.shop.facilities)),
            decorations = Collections.unmodifiableList(options)))
    }

    /** 표시 세대/전체 옵션을 대조한 뒤 실제 꾸미기 revision을 원명령에 넣는다. */
    fun command(facts: DecorationFacts, displayRevision: Revision, request: ShopRequest): ContractResult<DecorationCommand> {
        if (request.selection.revision != displayRevision) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val screen = when (val mapped = map(facts, displayRevision)) {
            is ContractResult.Failure -> return mapped
            is ContractResult.Success -> mapped.value
        }
        val option = screen.decorations.singleOrNull { it == request.selection.option && it.availability == ShopAvailability.AVAILABLE }
            ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val id = (option.command as? ShopCommand.Decorate)?.decorationId
            ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val command = candidate(facts, id, request.requestId)
        return when (val checked = prepareDecorationChange(command, facts.state, facts.definitions)) {
            is ContractResult.Failure -> checked
            is ContractResult.Success -> ContractResult.Success(command)
        }
    }
}
