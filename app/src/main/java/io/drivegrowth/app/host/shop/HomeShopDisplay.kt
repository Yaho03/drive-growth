package io.drivegrowth.app.host.shop

import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import java.util.Collections

internal enum class HomeShopAction { EXPANSION, FACILITY, DECORATION }

/** HOME의 표시 세대만 소유한다. 하위 표시 revision과 실제 개체/꾸미기 revision은 조립자가 구분한다. */
internal class HomeShopDisplay(initialRevision: Revision? = null) {
    var epoch: Long = 0
        private set
    var screen: ShopScreenState = ShopScreenState.Loading
        private set
    var enabled: Boolean = false
        private set
    private var revision = initialRevision?.value ?: 0
    private var routes: Map<HomeShopAction, Revision> = emptyMap()

    fun setControlsEnabled(value: Boolean): Long {
        if (enabled != value) {
            enabled = value; epoch++
            screen = ShopScreenState.Loading; routes = emptyMap()
        }
        return epoch
    }

    fun adopt(token: Long, value: ShopScreenState, routes: Map<HomeShopAction, Revision> = emptyMap()): Boolean {
        if (!enabled || token != epoch) return false
        this.routes = emptyMap()
        if (value !is ShopScreenState.Content) { screen = value; return true }
        if (revision == Int.MAX_VALUE) {
            screen = ShopScreenState.Failure("가게 표시 세대가 상한에 도달했어요. 새 표시 요청을 처리할 수 없어요.")
            return false
        }
        this.routes = LinkedHashMap(routes)
        fun supported(option: ShopOption): ShopOption {
            val action = action(option.command)
            return if (option.availability == ShopAvailability.AVAILABLE && (action == null || action !in this.routes))
                option.copy(availability = ShopAvailability.UNKNOWN, command = null) else option
        }
        screen = value.copy(revision = Revision(++revision),
            vehicles = immutable(value.vehicles),
            facilities = immutable(value.facilities.map { it.copy(option = supported(it.option)) }),
            expansion = value.expansion?.let(::supported), decorations = immutable(value.decorations.map(::supported)))
        return true
    }

    fun map(request: ShopRequest): ContractResult<ShopRequest> {
        if (!enabled) return ContractResult.Failure(ContractError.UX_RESTRICTED)
        val current = screen as? ShopScreenState.Content ?: return conflict()
        val selected = request.selection
        if (selected.revision != current.revision) return conflict()
        val option = (listOfNotNull(current.expansion) + current.facilities.map { it.option } + current.decorations)
            .singleOrNull { it == selected.option } ?: return conflict()
        if (option.availability != ShopAvailability.AVAILABLE || option.command == null || option.price == null || option.ruleVersion == null)
            return conflict()
        val action = action(option.command) ?: return conflict()
        val local = routes[action] ?: return conflict()
        return ContractResult.Success(request.copy(selection = selected.copy(revision = local)))
    }

    private fun action(command: ShopCommand?): HomeShopAction? = when (command) {
        is ShopCommand.Expand -> HomeShopAction.EXPANSION
        is ShopCommand.BuyFacility -> HomeShopAction.FACILITY
        is ShopCommand.Decorate -> HomeShopAction.DECORATION
        null -> null
    }
    private fun conflict() = ContractResult.Failure(ContractError.STATE_CONFLICT)
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
