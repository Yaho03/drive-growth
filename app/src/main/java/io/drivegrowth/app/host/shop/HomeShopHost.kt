package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.HostCheckpointResult
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.requestId
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.*
import io.drivegrowth.app.host.shop.decoration.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.replayDecorationChange
import java.util.concurrent.Executor

internal data class HomeShopState(val screen: ShopScreenState = ShopScreenState.Loading,
    val activationRequired: Boolean = false, val decoration: DecorationHostState? = null,
    val error: ContractError? = null, val original: HostCheckpointResult? = null)

/** HOME 한 화면의 표시 세대·원 UI 요청·수명을 소유한다. DB 검증/원자 확정은 기존 저장소 책임이다. */
internal class HomeShopHost(private val source: HomeShopPort, private val facility: FacilityHostController,
    private val worker: Executor, private val reply: Executor, initialUi: ShopUiState = ShopUiState(),
    restoredFacility: FacilityCommand.Buy? = null, restoredIdentity: GameIdentity? = null) {
    private val display = HomeShopDisplay(initialUi.latestRevision)
    private var released = false
    private var generation = 0L
    private var reading = false
    private var requested = false
    var identity: GameIdentity? = restoredIdentity
        private set
    private var recoveredOriginal: HostCheckpointResult? = null
    private var facilityCommand = restoredFacility
    val facilityOriginalCommand: FacilityCommand.Buy? get() = facilityCommand?.takeIf { command ->
        uiState.pending?.let { it.requestId == command.requestId &&
            (it.selection.option.command as? ShopCommand.BuyFacility)?.offer == command.offer } == true
    }
    private var expansion: ShopExpansionHostController? = null
    private var expansionFrame: ShopScreenState.Content? = null
    private var expansionLoading = false
    private var waitingForExpansion = false
    private var expansionInstance = 0L
    private var decoration: DecorationHostController? = null
    private var decorationGeneration = -1L
    @Volatile private var currentFacts: HomeShopFacts? = null
    private var activationRequested = false
    private var lastFacility = facility.state
    private var facilityAccess = false
    private var garageFacilityAccess = false
    val facilityControlsEnabled get() = display.enabled && facilityAccess
    private val originals = linkedMapOf<ShopRequest, ShopRequest>()
    private var onState: ((HomeShopState, Boolean) -> Unit)? = null
    private var onComplete: ((ShopRequest, ContractResult<Unit>) -> Unit)? = null
    private var onRetry: (() -> Unit)? = null
    private var onInstallation: ((ShopRequest, FacilityReceipt.Purchased) -> Unit)? = null
    @Volatile var uiState = initialUi
        private set
    var state = HomeShopState()
        private set

    init { facility.changed = { value, _ -> facilityChanged(value) } }
    fun attach(changed: (HomeShopState, Boolean) -> Unit,
        complete: (ShopRequest, ContractResult<Unit>) -> Unit, retry: () -> Unit,
        installation: ((ShopRequest, FacilityReceipt.Purchased) -> Unit)? = null) {
        onState = changed; onComplete = complete; onRetry = retry; onInstallation = installation
        changed(state, display.enabled)
    }
    fun remember(value: ShopUiState) { uiState = value }
    fun detach() { setGarageFacilityAccess(false); setControlsEnabled(false); onState = null; onComplete = null; onRetry = null; onInstallation = null }
    fun release() { detach(); released = true; decoration?.release(); expansion?.release(); facility.changed = null }

    fun setControlsEnabled(value: Boolean) {
        if (released || value == display.enabled) return
        display.setControlsEnabled(value); generation++; requested = false; activationRequested = false
        stopDecoration()
        expansion?.release(); expansion = null; expansionFrame = null; expansionLoading = false; expansionInstance++
        publish(HomeShopState(decoration = decoration?.state))
        if (!value) setFacilityAccess(false)
        if (value) refresh()
    }
    fun setGarageFacilityAccess(value: Boolean) {
        if (released || value == garageFacilityAccess) return
        garageFacilityAccess = value
        facility.setControlsEnabled(facilityAccess || value)
        onState?.invoke(state, display.enabled)
    }

    private fun setFacilityAccess(value: Boolean) {
        facilityAccess = value
        facility.setControlsEnabled(value || garageFacilityAccess)
    }
    private fun publish(next: HomeShopState) { state = next.copy(original = recoveredOriginal); onState?.invoke(state, display.enabled && !released) }
    private fun show(value: ShopScreenState, error: ContractError? = null, inactive: Boolean = false,
        routes: Map<HomeShopAction, Revision> = emptyMap()) {
        if (!display.enabled || released) return
        display.adopt(display.epoch, value, routes)
        publish(HomeShopState(display.screen, inactive, decoration?.state, error))
    }
    private fun fail(error: ContractError) {
        show(ShopScreenState.Failure("가게 상태 확인 실패: $error. 원래 요청을 보존하고 다시 확인해 주세요."), error)
        onRetry?.invoke()
    }

    /** 실제 읽기만 요청한다. 같은 슬롯의 원명령이 있으면 등록된 소비자 하나로만 복구한다. */
    fun refresh(): Boolean {
        if (!display.enabled || released) return false
        generation++; requested = true
        show(ShopScreenState.Loading)
        launch()
        return true
    }
    private fun launch() {
        if (reading || !requested || !display.enabled || released) return
        val restoring = uiState.pending?.takeIf { originals.isEmpty() }
        if (restoring != null && identity == null) {
            requested = false
            fail(ContractError.DATA_UNAVAILABLE)
            return
        }
        reading = true; requested = false
        val token = generation
        worker.execute {
            val result = if (restoring != null) source.pendingIdentity() else source.load()
            reply.execute {
                reading = false
                if (!released && display.enabled && token == generation) when (result) {
                    is ContractResult.Failure -> fail(result.error)
                    is ContractResult.Success -> accept(result.value, restoring)
                }
                launch()
            }
        }
    }
    private fun accept(load: HomeShopLoad, restoring: ShopRequest?) {
        if (identity != null && identity != load.identity) { fail(ContractError.STATE_CONFLICT); return }
        identity = load.identity
        val restored = uiState.pending
        if (load.pending != null && restored != null && !matches(restored, load.pending, load.identity)) {
            stopDecoration(); setFacilityAccess(false)
            if (restoring == restored) readOriginal(load.identity, restored, conflictingSlot = true)
            else fail(ContractError.STATE_CONFLICT)
            return
        }
        if (load.pending == null && restoring != null) {
            if (uiState.pending == restoring) readOriginal(load.identity, restoring) else fail(ContractError.STATE_CONFLICT)
            return
        }
        when (load.pending) {
            is HostCheckpointCommand.Decoration -> {
                setFacilityAccess(false)
                ensureDecoration(load.identity)
                startDecorationRead()
            }
            is HostCheckpointCommand.Expand -> {
                setFacilityAccess(false)
                stopDecoration()
                waitingForExpansion = true
                loadExpansion()
            }
            is HostCheckpointCommand.FacilityPurchase, is HostCheckpointCommand.VehicleResting -> {
                stopDecoration()
                // 시설 controller가 원결과/명시 확인·취소를 소유한다. 여기서 execute/finish하지 않는다.
                show(ShopScreenState.Loading)
                if (load.pending is HostCheckpointCommand.FacilityPurchase && uiState.pending != null) {
                    val command = load.pending
                    val actual = FacilityCommand.Buy(load.identity, command.requestId, command.definitionVersion, command.offer)
                    val known = facilityOriginalCommand
                    if (known != null && known != actual) {
                        setFacilityAccess(false); fail(ContractError.STATE_CONFLICT); return
                    }
                    facilityCommand = actual
                }
                setFacilityAccess(true)
                completeFacility(facility.state)
                if (!facility.state.busy && (facility.state.receipt != null || facility.state.error != null)) onRetry?.invoke()
            }
            null -> {
                val facts = load.facts
                if (facts == null || facts.identity != load.identity || load.decorationActive == null) {
                    fail(ContractError.DATA_UNAVAILABLE); return
                }
                currentFacts = facts
                setFacilityAccess(true)
                if (expansion == null) loadExpansion()
                if (load.decorationActive) { ensureDecoration(load.identity); startDecorationRead() }
                else {
                    stopDecoration()
                    showCurrent(facts.screen, facts, null, inactive = true)
                    onRetry?.invoke()
                }
            }
            else -> {
                stopDecoration(); setFacilityAccess(false)
                val facts = load.facts?.takeIf { it.identity == load.identity }
                if (facts == null) {
                    fail(ContractError.DATA_UNAVAILABLE)
                    return
                }
                val content = load.decorationFacts?.let { decorationMapper.map(it, facts.screen.revision) }
                    ?: ContractResult.Success(facts.screen)
                when (content) {
                    is ContractResult.Failure -> fail(content.error)
                    is ContractResult.Success -> {
                        show(withSource(content.value, facts.identity))
                        onRetry?.invoke()
                    }
                }
            }
        }
    }
    private fun readOriginal(owner: GameIdentity, request: ShopRequest, conflictingSlot: Boolean = false) {
        reading = true
        val token = generation
        val facility = facilityOriginalCommand
        worker.execute {
            val original = source.original(owner, request, facility)
            reply.execute {
                reading = false
                if (!released && display.enabled && generation == token && uiState.pending == request) when (original) {
                    is ContractResult.Failure -> fail(if (conflictingSlot && original.error == ContractError.DATA_UNAVAILABLE)
                        ContractError.STATE_CONFLICT else original.error)
                    is ContractResult.Success -> {
                        val value = original.value
                        if (value == null) fail(if (conflictingSlot) ContractError.STATE_CONFLICT else ContractError.DATA_UNAVAILABLE)
                        else if (!originalMatches(owner, request, value, facility)) fail(ContractError.STATE_CONFLICT)
                        else {
                            recoveredOriginal = value
                            if (value is HostCheckpointResult.Decoration) {
                                stopDecoration(); decoration?.release(); decoration = null
                            }
                            completeRequest(request, ContractResult.Success(Unit))
                            refresh()
                        }
                    }
                }
                launch()
            }
        }
    }
    private fun originalMatches(owner: GameIdentity, request: ShopRequest, result: HostCheckpointResult,
        facility: FacilityCommand.Buy?): Boolean = when (val command = request.selection.option.command) {
        is ShopCommand.Decorate -> result is HostCheckpointResult.Decoration &&
            matches(request, HostCheckpointCommand.Decoration(result.value.command), owner) &&
            replayDecorationChange(result.value.command, result.value) is ContractResult.Success
        is ShopCommand.Expand -> result is HostCheckpointResult.Expand && financialMatches(owner, request, result.value)
        is ShopCommand.BuyFacility -> result is HostCheckpointResult.FacilityPurchase && facility?.identity == owner &&
            facility.requestId == request.requestId && facility.offer == command.offer && financialMatches(owner, request, result.value)
        null -> false
    }
    private fun financialMatches(owner: GameIdentity, request: ShopRequest, receipt: Settlement): Boolean =
        receipt.transaction.source == owner.source && receipt.transaction.requestId == request.requestId &&
            receipt.transaction.kind == TransactionKind.FACILITY && receipt.transaction.ruleVersion == request.selection.option.ruleVersion &&
            receipt.transaction.money == request.selection.option.price?.let { MoneyChange(MoneyDirection.DEBIT, it) }

    private val decorationMapper = DecorationMapper(mapOf(
        "neutral" to ShopAppearance("기본 간판 · 시연", 0xff8fbcff.toInt()),
        "blue-sign" to ShopAppearance("간판 색상 · 시연", 0xff8fbcff.toInt()),
    ))

    private fun ensureDecoration(owner: GameIdentity) {
        if (decoration != null) return
        val backend = source.decoration(owner) { currentFacts = it }
        decoration = DecorationHostController(owner, backend, decorationMapper, worker, reply, ::completeMapped, { onRetry?.invoke() },
            restoredRequest = uiState.pending?.takeIf { it.selection.option.command is ShopCommand.Decorate })
            .also { host -> host.changed = { value, _ -> decorationChanged(value) } }
    }
    private fun startDecorationRead() {
        val host = decoration ?: return
        decorationGeneration = generation
        val wasDisabled = !decorationEnabled
        decorationEnabled = true
        host.setControlsEnabled(true)
        if (!wasDisabled && !host.refresh()) requestedAfterDecoration = true
    }
    private fun stopDecoration() { decorationEnabled = false; decoration?.setControlsEnabled(false) }
    private var decorationEnabled = false
    private var requestedAfterDecoration = false
    private fun decorationChanged(value: DecorationHostState) {
        if (!display.enabled || released || !decorationEnabled) return
        if (value.busy) {
            if (decorationGeneration == generation) show(ShopScreenState.Loading)
            return
        }
        if (activationRequested) {
            activationRequested = false
            decorationGeneration = generation
            if (decoration?.activate() != true) fail(ContractError.STATE_CONFLICT)
            return
        }
        if (requestedAfterDecoration || decorationGeneration != generation) {
            requestedAfterDecoration = false
            refresh(); return
        }
        val content = value.screen as? ShopScreenState.Content
        if (content != null) {
            val facts = currentFacts
            if (facts == null || facts.identity != identity || value.current?.profileId != identity?.profileId ||
                value.current?.source != identity?.source) { fail(ContractError.STATE_CONFLICT); return }
            showCurrent(content, facts, value.displayRevision)
        } else show(value.screen, value.error)
    }
    private fun expansionMatches(content: ShopScreenState.Content, latest: ShopScreenState.Content) =
        content.level == latest.level && content.ownedCount == latest.ownedCount && content.capacity == latest.capacity &&
            content.balance == latest.balance && content.vehicles == latest.vehicles && content.expansion == latest.expansion

    /** F가 같은 session에서 읽은 전체 Content만 채택한다. 별도 controller의 오래된 화면을 덧붙이지 않는다. */
    private fun showCurrent(content: ShopScreenState.Content, facts: HomeShopFacts, local: Revision?, inactive: Boolean = false) {
        setFacilityAccess(true)
        val routes = linkedMapOf<HomeShopAction, Revision>()
        if (local != null) routes[HomeShopAction.DECORATION] = local
        expansionFrame?.takeIf { expansionMatches(it, facts.expansion.screen) }?.let { routes[HomeShopAction.EXPANSION] = it.revision }
        val facilities = facility.state
        if (facilities.facts == facts.facility && !facilities.busy && facilities.pending == null &&
            facilities.receipt == null && facilities.error == null && !facilities.foreign && !facilities.unsupported)
            routes[HomeShopAction.FACILITY] = Revision(facilities.version)
        show(withSource(content, facts.identity), inactive = inactive, routes = routes)
        if (HomeShopAction.EXPANSION !in routes && !expansionLoading) loadExpansion()
        if (facilities.facts != facts.facility && !facilities.busy && facilities.pending == null &&
            facilities.receipt == null && facilityRefreshFor != facts.facility) {
            facilityRefreshFor = facts.facility
            facility.refresh()
        }
    }
    private fun withSource(content: ShopScreenState.Content, identity: GameIdentity) =
        content.copy(name = content.name + when (identity.source) {
            InputSource.SIMULATION -> " · 모의 원장"
            InputSource.VEHICLE -> " · 차량 원장"
        })

    private var facilityRefreshFor: FacilityFacts? = null
    @Volatile private var expansionCommand: HostCheckpointCommand.Expand? = null
    private fun loadExpansion() {
        if (expansionLoading || !display.enabled || released) return
        if (expansion == null) {
            val instance = ++expansionInstance
            val owner = identity ?: return
            val checked = object : ShopExpansionHostSource by source.expansion {
                override fun pending(): ContractResult<HostCheckpointCommand.Expand?> = when (val loaded = source.expansion.pending()) {
                    is ContractResult.Failure -> loaded
                    is ContractResult.Success -> {
                        val command = loaded.value
                        val request = uiState.pending
                        if (command != null && request != null && !matches(request, command, owner))
                            ContractResult.Failure(ContractError.STATE_CONFLICT)
                        else { expansionCommand = command; loaded }
                    }
                }
            }
            expansion = ShopExpansionHostController(checked, { worker.execute(it) }, { reply.execute(it) },
                { value, _ -> if (display.enabled && !released && instance == expansionInstance) {
                    when (value) {
                        is ShopScreenState.Content -> {
                            expansionLoading = false; expansionFrame = value
                            if (waitingForExpansion || state.screen is ShopScreenState.Content) {
                                waitingForExpansion = false; refresh()
                            }
                        }
                        is ShopScreenState.Failure -> { expansionLoading = false; show(value); onRetry?.invoke() }
                        else -> Unit
                    }
                } }, ::completeMapped, {}, committed = ::completeExpansion)
            expansion!!.setControlsEnabled(true)
        }
        expansionLoading = true
        if (expansion?.load() != true) expansionLoading = false
    }
    private fun facilityChanged(value: FacilityHostState) {
        if (released || value == lastFacility) return
        val resolved = lastFacility.pending != null && value.pending == null && value.facts != null
        lastFacility = value
        if (!display.enabled) {
            if (garageFacilityAccess) onState?.invoke(state, false)
            return
        }
        completeFacility(value)
        if (!value.busy && (state.screen is ShopScreenState.Content || resolved)) refresh()
        else onState?.invoke(state, display.enabled)
        if (!value.busy && (value.receipt != null || value.error != null)) onRetry?.invoke()
    }
    private fun completeFacility(value: FacilityHostState) {
        val mapped = originals.keys.firstOrNull { it.selection.option.command is ShopCommand.BuyFacility }
        val original = mapped?.let(originals::get) ?: uiState.pending?.takeIf { it.selection.option.command is ShopCommand.BuyFacility } ?: return
        val owner = identity ?: return
        val receipt = value.receipt as? FacilityReceipt.Purchased
        if (receipt != null) {
            val expected = (original.selection.option.command as ShopCommand.BuyFacility).offer
            if (receipt.command.identity != owner || receipt.command.requestId != original.requestId || receipt.command.offer != expected) {
                fail(ContractError.STATE_CONFLICT); return
            }
            if (mapped != null) {
                if (receipt == value.newInstallation && financialMatches(owner, original, receipt.settlement))
                    onInstallation?.invoke(original, receipt)
                completeMapped(mapped, ContractResult.Success(Unit))
            } else completeRequest(original, ContractResult.Success(Unit))
        } else if (mapped != null && !value.busy && value.error != null) completeMapped(mapped, ContractResult.Failure(value.error))
    }
    private fun matches(request: ShopRequest, command: HostCheckpointCommand, owner: GameIdentity): Boolean {
        if (request.requestId != command.requestId) return false
        return when (command) {
            is HostCheckpointCommand.Expand -> command.source == owner.source && (request.selection.option.command as? ShopCommand.Expand)?.offer == command.offer
            is HostCheckpointCommand.FacilityPurchase -> command.source == owner.source && (request.selection.option.command as? ShopCommand.BuyFacility)?.offer == command.offer
            is HostCheckpointCommand.Decoration -> command.value.profileId == owner.profileId && command.value.source == owner.source &&
                (request.selection.option.command as? ShopCommand.Decorate)?.decorationId == command.value.decorationId &&
                request.selection.option.price == command.value.expectedPrice && request.selection.option.ruleVersion == command.value.ruleVersion
            else -> false
        }
    }
    private fun completeExpansion(receipt: Settlement) {
        val command = expansionCommand ?: return
        val request = uiState.pending ?: return
        val owner = identity ?: return
        if (!matches(request, command, owner) || receipt.transaction.requestId != command.requestId ||
            receipt.transaction.source != owner.source || receipt.transaction.kind != TransactionKind.FACILITY ||
            receipt.transaction.ruleVersion != command.offer.ruleVersion ||
            receipt.transaction.money != MoneyChange(MoneyDirection.DEBIT, command.offer.price)) {
            fail(ContractError.STATE_CONFLICT); return
        }
        originals.entries.removeAll { it.value == request }
        completeRequest(request, ContractResult.Success(Unit))
    }
    fun submit(request: ShopRequest, presentInstallation: Boolean = false): Boolean {
        if (originals.values.any { it == request } ||
            (uiState.pending == request && display.screen !is ShopScreenState.Content)) return false
        val mapped = when (val result = display.map(request)) {
            is ContractResult.Failure -> { completeRequest(request, result); return false }
            is ContractResult.Success -> result.value
        }
        originals[mapped] = request // 동기 거절 callback 전에 대응을 보관한다.
        val accepted = when (mapped.selection.option.command) {
            is ShopCommand.Decorate -> {
                setFacilityAccess(false)
                decorationGeneration = generation
                decoration?.submit(mapped) == true
            }
            is ShopCommand.Expand -> { setFacilityAccess(false); waitingForExpansion = true; expansion?.submit(mapped) == true }
            is ShopCommand.BuyFacility -> {
                val facts = facility.state.facts
                if (facts == null) false else {
                    facilityCommand = FacilityCommand.Buy(facts.identity, mapped.requestId, facts.definitionVersion,
                        (mapped.selection.option.command as ShopCommand.BuyFacility).offer)
                    facility.submit(mapped, presentInstallation)
                }
            }
            null -> false
        }
        if (!accepted && originals.containsKey(mapped)) completeMapped(mapped, ContractResult.Failure(ContractError.STATE_CONFLICT))
        return accepted
    }
    private fun completeMapped(mapped: ShopRequest, result: ContractResult<Unit>) {
        val original = originals.remove(mapped) ?: uiState.pending?.takeIf { it == mapped } ?: return
        completeRequest(original, result)
    }
    private fun completeRequest(request: ShopRequest, result: ContractResult<Unit>) {
        uiState = ShopInteraction({}).also { it.restoreUiState(uiState); it.complete(request, result) }.uiState
        onComplete?.invoke(request, result)
    }
    fun activateDecoration(): Boolean {
        if (!display.enabled || released || !state.activationRequired) return false
        val owner = identity ?: return false
        ensureDecoration(owner)
        activationRequested = true; decorationGeneration = generation
        if (!decorationEnabled) { decorationEnabled = true; decoration!!.setControlsEnabled(true) }
        else { activationRequested = false; return decoration!!.activate() }
        return true
    }
    fun cancelDecorationSaved() = if (display.enabled && !released) decoration?.cancelSaved() == true else false
    fun cancelExpansionSaved() = if (display.enabled && !released) expansion?.cancelSaved() == true else false
}
