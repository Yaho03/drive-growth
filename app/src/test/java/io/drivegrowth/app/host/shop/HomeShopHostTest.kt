package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.facility.*
import io.drivegrowth.app.host.shop.decoration.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

/** 실제 SQLite/SDK와 분리된 HOME 조립 fixture다. F의 실제 controller/mapper/순수 계약을 소비한다. */
private class HomeFixture : HomeShopPort {
    val identity = GameIdentity(ProfileId("home-profile"), InputSource.SIMULATION)
    var active = false
    var level = 1
    var completedCustomers = 0
    var offers: Map<Facility,FacilityOffer> = emptyMap()
    val installed = linkedSetOf<Facility>()
    var money = GameMoney(1000)
    var module = DecorationState(identity.profileId,identity.source,Revision(0),money,level,emptySet(),null)
    var pending: HostCheckpointCommand? = null
    var loadFailure: ContractError? = null
    var currentLoadFailure: ContractError? = null
    var originalResultOverride: ContractResult<HostCheckpointResult?>? = null
    var originalReads = 0; var identityReads = 0
    val originalCalls = mutableListOf<Triple<GameIdentity,ShopRequest,FacilityCommand.Buy?>>()
    var readFailure: ContractError? = null
    var finishFailure: ContractError? = null
    var activates = 0; var saves = 0; var executes = 0; var finishes = 0; var prepares = 0; var loads = 0
    var facilitySaves = 0; var facilityExecutes = 0; var facilityFinishes = 0
    val expansionOriginals = linkedMapOf<RequestId, Pair<HostCheckpointCommand.Expand, Settlement>>()
    val facilityOriginals = linkedMapOf<RequestId, FacilityReceipt.Purchased>()
    val originals = linkedMapOf<RequestId,DecorationSettlement>()
    private val definitions = trialDecorationDefinitions()
    fun facts(): HomeShopFacts {
        val shop=ShopState(ShopProgress(0,0,level),if(level==1)3 else 5,installed.toSet())
        val expansion=mapShopExpansion(identity.profileId,identity.source,
            (parseShopExpansionDefinition(expansionDefinitionFixture) as ContractResult.Success).value,shop,completedCustomers,money,emptyList())
        return HomeShopFacts(identity,expansion,FacilityFacts(identity,ContentVersion("fixture"),shop,money,emptyList(),emptyMap(),emptySet(),offers))
    }
    override val expansion = object : ShopExpansionHostSource {
        override fun read()=ContractResult.Success(facts().expansion)
        override fun pending():ContractResult<HostCheckpointCommand.Expand?> = when(val p=pending) {
            null->ContractResult.Success(null);is HostCheckpointCommand.Expand->ContractResult.Success(p)
            else->ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        override fun prepare(snapshot:ShopExpansionSnapshot,request:ShopRequest):ContractResult<HostCheckpointCommand.Expand> {
            prepares++
            if (pending != null || request.selection.option != snapshot.screen.expansion ||
                snapshot.copy(screen=snapshot.screen.copy(revision=Revision(0))) != facts().expansion)
                return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val offer=(request.selection.option.command as? ShopCommand.Expand)?.offer
                ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val checked=offer.preview(facts().facility.shop.progress.copy(servedCustomers=completedCustomers),facts().facility.shop.capacity,money)
            if(checked is ContractResult.Failure)return checked
            val command=HostCheckpointCommand.Expand(request.requestId,identity.source,snapshot.definitionVersion,offer)
            saves++;pending=command;return ContractResult.Success(command)
        }
        override fun execute(command:HostCheckpointCommand.Expand):ContractResult<Settlement> {
            executes++
            expansionOriginals[command.requestId]?.let { return if(it.first==command) ContractResult.Success(it.second)
                else ContractResult.Failure(ContractError.STATE_CONFLICT) }
            if(pending!=command)return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val checked=command.offer.preview(facts().facility.shop.progress.copy(servedCustomers=completedCustomers),facts().facility.shop.capacity,money)
            if(checked is ContractResult.Failure)return checked
            money=GameMoney(money.units-command.offer.price.units);level=command.offer.fromLevel+1
            val result=financial(command.requestId,command.offer.ruleVersion,command.offer.price,money)
            expansionOriginals[command.requestId]=command to result
            return ContractResult.Success(result)
        }
        override fun finish(command:HostCheckpointCommand.Expand):ContractResult<Settlement> {
            finishes++
            val original=expansionOriginals[command.requestId]
            if(pending!=command || original==null || original.first!=command)return ContractResult.Failure(ContractError.STATE_CONFLICT)
            pending=null;return ContractResult.Success(original.second)
        }
        override fun cancelSaved(command:HostCheckpointCommand.Expand):ContractResult<Unit> = ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
    override fun load(): ContractResult<HomeShopLoad> {
        loads++
        return (loadFailure ?: currentLoadFailure)?.let { ContractResult.Failure(it) }
            ?: ContractResult.Success(HomeShopLoad(identity,pending,if(pending==null)facts() else null,if(pending==null)active else null))
    }
    override fun pendingIdentity():ContractResult<HomeShopLoad> {
        identityReads++
        return loadFailure?.let { ContractResult.Failure(it) } ?: ContractResult.Success(HomeShopLoad(identity,pending))
    }
    override fun original(identity:GameIdentity,request:ShopRequest,facility:FacilityCommand.Buy?):ContractResult<HostCheckpointResult?> {
        originalReads++;originalCalls+=Triple(identity,request,facility)
        originalResultOverride?.let{return it}
        if(identity!=this.identity)return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val option=request.selection.option
        val result:HostCheckpointResult?=when(val command=option.command) {
            is ShopCommand.Decorate -> {
                val value=originals[request.requestId] ?: return ContractResult.Success(null)
                if(value.command.profileId!=identity.profileId || value.command.source!=identity.source ||
                    value.command.decorationId!=command.decorationId || value.command.expectedPrice!=option.price ||
                    value.command.ruleVersion!=option.ruleVersion)return ContractResult.Failure(ContractError.STATE_CONFLICT)
                HostCheckpointResult.Decoration(value)
            }
            is ShopCommand.Expand -> {
                val value=expansionOriginals[request.requestId] ?: return ContractResult.Success(null)
                if(value.first.source!=identity.source || value.first.offer!=command.offer || option.price!=command.offer.price ||
                    option.ruleVersion!=command.offer.ruleVersion)return ContractResult.Failure(ContractError.STATE_CONFLICT)
                HostCheckpointResult.Expand(value.second)
            }
            is ShopCommand.BuyFacility -> {
                if(facility==null)return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                val value=facilityOriginals[request.requestId] ?: return ContractResult.Success(null)
                if(value.command!=facility || facility.identity!=identity || facility.offer!=command.offer ||
                    option.price!=command.offer.price || option.ruleVersion!=command.offer.ruleVersion)
                    return ContractResult.Failure(ContractError.STATE_CONFLICT)
                HostCheckpointResult.FacilityPurchase(value.settlement)
            }
            null -> return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        return ContractResult.Success(result)
    }
    fun financial(id:RequestId,rule:RuleVersion,price:GameMoney,balance:GameMoney)=Settlement(
        SettlementId("fixture-${id.value}"),Transaction(TransactionId("fixture-${id.value}"),id,
            TransactionKind.FACILITY,identity.source,rule,MoneyChange(MoneyDirection.DEBIT,price)),balance,null,EpochMillis(20))
    /** 이미 확정된 원문/결과와 응답 유실을 명시하는 fixture이며 실제 저장 성공 근거가 아니다. */
    fun seedExpansion(id:String="restored-expand"):ShopRequest {
        completedCustomers=3
        val snapshot=facts().expansion;val option=snapshot.screen.expansion!!
        val request=ShopRequest(RequestId(id),ShopSelection(Revision(41),option),"restored-attempt")
        val offer=(option.command as ShopCommand.Expand).offer
        val command=HostCheckpointCommand.Expand(request.requestId,identity.source,snapshot.definitionVersion,offer)
        money=GameMoney(500);level=2
        expansionOriginals[command.requestId]=command to financial(command.requestId,offer.ruleVersion,offer.price,money)
        pending=command
        return request
    }
    fun seedFreeDecoration(id:String="finished-decoration"):ShopRequest {
        active=true
        val facts=DecorationFacts(module.copy(balance=money,shopLevel=level),definitions,facts().screen)
        val mapper=DecorationMapper(mapOf("neutral" to ShopAppearance("기본 간판 · 시연",0xff8fbcff.toInt()),
            "blue-sign" to ShopAppearance("간판 색상 · 시연",0xff8fbcff.toInt())))
        val revision=Revision(63)
        val screen=(mapper.map(facts,revision) as ContractResult.Success).value
        val option=screen.decorations.single{(it.command as? ShopCommand.Decorate)?.decorationId=="neutral-sign"}
        val request=ShopRequest(RequestId(id),ShopSelection(revision,option),"finished-attempt")
        val command=(mapper.command(facts,revision,request) as ContractResult.Success).value
        val next=(prepareDecorationChange(command,facts.state,definitions) as ContractResult.Success).value
        module=next.state
        originals[request.requestId]=DecorationSettlement(command,next.state,null)
        pending=null
        return request
    }
    fun seedFacility(id:String="restored-facility"):ShopRequest {
        val offer=FacilityOffer(Facility.FUEL_STATION,RuleVersion("old-facility-rule"),true,GameMoney(70))
        val request=ShopRequest(RequestId(id),ShopSelection(Revision(52),ShopOption("facility-FUEL_STATION","주유소",
            "당시 확인한 조건","당시 확인한 효과",ShopAvailability.AVAILABLE,ShopCommand.BuyFacility(offer),offer.price,offer.ruleVersion,offer.trial)),"restored-attempt")
        val command=FacilityCommand.Buy(identity,request.requestId,ContentVersion("old-facility-definition"),offer)
        money=GameMoney(930)
        facilityOriginals[command.requestId]=FacilityReceipt.Purchased(command,financial(command.requestId,offer.ruleVersion,offer.price,money))
        pending=HostCheckpointCommand.FacilityPurchase(command.requestId,identity.source,command.definitionVersion,offer)
        return request
    }
    override fun decoration(identity:GameIdentity,currentFacts:(HomeShopFacts)->Unit) = object : DecorationBackend {
        override fun activate():ContractResult<Unit> { activates++;active=true;return ContractResult.Success(Unit) }
        override fun pending():ContractResult<DecorationPending> = ContractResult.Success(when(val p=pending) {
            null->DecorationPending.Empty;is HostCheckpointCommand.Decoration->DecorationPending.Own(p.value);else->DecorationPending.Foreign
        })
        override fun read():ContractResult<DecorationFacts> {
            readFailure?.let{return ContractResult.Failure(it)}
            if(!active)return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            val f=facts();currentFacts(f)
            return ContractResult.Success(DecorationFacts(module.copy(balance=money,shopLevel=level),definitions,f.screen))
        }
        override fun save(command:DecorationCommand):ContractResult<Unit> { saves++;pending=HostCheckpointCommand.Decoration(command);return ContractResult.Success(Unit) }
        override fun execute(command:DecorationCommand):ContractResult<DecorationSettlement> {
            executes++;originals[command.requestId]?.let{return if(it.command==command)ContractResult.Success(it) else ContractResult.Failure(ContractError.STATE_CONFLICT)}
            val before=module.copy(balance=money,shopLevel=level)
            val next=when(val r=prepareDecorationChange(command,before,definitions)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
            val financial=if(command.expectedPrice.units==0L)null else Settlement(SettlementId("fixture-${command.requestId.value}"),
                Transaction(TransactionId("fixture-${command.requestId.value}"),command.requestId,TransactionKind.FACILITY,identity.source,
                    command.ruleVersion,MoneyChange(MoneyDirection.DEBIT,command.expectedPrice)),next.state.balance,null,EpochMillis(20))
            val result=DecorationSettlement(command,next.state,financial)
            module=next.state;money=module.balance;originals[command.requestId]=result;return ContractResult.Success(result)
        }
        override fun finish(command:DecorationCommand):ContractResult<Unit> { finishes++;finishFailure?.let{return ContractResult.Failure(it)};pending=null;return ContractResult.Success(Unit) }
        override fun cancelSaved(command:DecorationCommand):ContractResult<Unit> { pending=null;return ContractResult.Success(Unit) }
    }
}
private class HomeHarness(val source:HomeFixture=HomeFixture(),queued:Boolean=false,initialUi:ShopUiState=ShopUiState(),
    restoredFacility:FacilityCommand.Buy?=null,restoredIdentity:GameIdentity?=null) {
    val work=ArrayDeque<Runnable>();val replies=ArrayDeque<Runnable>()
    private val worker=Executor { if(queued)work.add(it) else it.run() }
    private val reply=Executor { if(queued)replies.add(it) else it.run() }
    private val facilitySource=object:FacilityHostSource {
        override fun pending():ContractResult<FacilityPending> = ContractResult.Success(when(val saved=source.pending) {
            null->FacilityPending.Empty
            is HostCheckpointCommand.FacilityPurchase->FacilityPending.Own(FacilityCommand.Buy(source.identity,saved.requestId,saved.definitionVersion,saved.offer))
            else->FacilityPending.Foreign
        })
        override fun read()=ContractResult.Success(source.facts().facility)
        override fun save(command:FacilityCommand):ContractResult<Unit> {
            source.facilitySaves++
            val buy=command as? FacilityCommand.Buy ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
            if(source.pending!=null || buy.identity!=source.identity)return ContractResult.Failure(ContractError.STATE_CONFLICT)
            source.pending=HostCheckpointCommand.FacilityPurchase(buy.requestId,buy.identity.source,buy.definitionVersion,buy.offer)
            return ContractResult.Success(Unit)
        }
        override fun execute(command:FacilityCommand):ContractResult<FacilityReceipt> {
            source.facilityExecutes++
            val original=source.facilityOriginals[command.requestId]
            if(original!=null)return if(original.command==command)ContractResult.Success(original) else ContractResult.Failure(ContractError.STATE_CONFLICT)
            val buy=command as? FacilityCommand.Buy ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val ready=buy.offer.planPurchase(source.facts().facility.shop.progress,source.installed,source.money)
            if(ready is ContractResult.Failure)return ready
            source.money=GameMoney(source.money.units-buy.offer.price.units);source.installed+=buy.offer.facility
            val receipt=FacilityReceipt.Purchased(buy,source.financial(buy.requestId,buy.offer.ruleVersion,buy.offer.price,source.money))
            source.facilityOriginals[buy.requestId]=receipt;return ContractResult.Success(receipt)
        }
        override fun finish(command:FacilityCommand):ContractResult<Unit> {
            source.facilityFinishes++
            val original=source.facilityOriginals[command.requestId]
            if(original?.command!=command)return ContractResult.Failure(ContractError.STATE_CONFLICT)
            source.pending=null;return ContractResult.Success(Unit)
        }
        override fun cancelSaved(command:FacilityCommand):ContractResult<Unit> = ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
    val facility=FacilityHostController(facilitySource,worker,reply)
    val completed=mutableListOf<Pair<ShopRequest,ContractResult<Unit>>>()
    val host=HomeShopHost(source,facility,worker,reply,initialUi,restoredFacility,restoredIdentity)
    val interaction:ShopInteraction=ShopInteraction({event->
        host.remember(interactionState())
        when(event) { is ShopEvent.Submit->host.submit(event.request); ShopEvent.Retry->host.refresh() }
    })
    private fun interactionState()=interaction.uiState
    init {
        interaction.restoreUiState(initialUi)
        host.attach({s,e->interaction.render(s.screen,e)}, {q,r->completed.add(q to r);interaction.complete(q,r)}, interaction::completeRetry)
    }
    fun rememberPending(request:ShopRequest) {
        val ui=interaction.uiState.copy(pending=request,latestRevision=request.selection.revision)
        interaction.restoreUiState(ui);host.remember(ui)
    }
    fun requestDecoration(id:String="neutral-sign"):ShopRequest {
        val content=host.state.screen as ShopScreenState.Content
        val option=content.decorations.single{(it.command as? ShopCommand.Decorate)?.decorationId==id}
        return ShopRequest(RequestId("queued-$id"),ShopSelection(content.revision,option),"first-attempt")
    }
    fun start(){host.setControlsEnabled(true);drain()}
    fun drain(){var n=0;while(work.isNotEmpty()||replies.isNotEmpty()){check(++n<200);if(work.isNotEmpty())work.removeFirst().run();if(replies.isNotEmpty())replies.removeFirst().run()}}
    fun buy(id:String,request:String="request-$id"):ShopRequest {
        val c=host.state.screen as ShopScreenState.Content;val o=c.decorations.single{(it.command as? ShopCommand.Decorate)?.decorationId==id}
        val q=ShopRequest(RequestId(request),ShopSelection(c.revision,o),"attempt-$request")
        host.remember(ShopUiState(pending=q,latestRevision=c.revision));assertTrue(host.submit(q));drain();return q
    }
}
class HomeShopHostTest {
    @Test fun restoredKnownProfileCannotCompleteUiFromAnotherActualProfile() {
        val source=HomeFixture();val request=source.seedFreeDecoration()
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),
            restoredIdentity=GameIdentity(ProfileId("previous-profile"),InputSource.SIMULATION));h.start()
        assertEquals(ContractError.STATE_CONFLICT,h.host.state.error)
        assertEquals(request,h.host.uiState.pending);assertTrue(h.completed.isEmpty());assertEquals(0,source.originalReads)
        assertNoRecoveryWrites(source)
    }
    @Test fun confirmedOldRequestCanBeReadWhileAnotherFeaturesNewSlotIsPreserved() {
        val source=HomeFixture();val request=source.seedFreeDecoration()
        val foreign=HostCheckpointCommand.Garage(ContentVersion("fixture"),GameChange(RequestId("foreign-garage"),
            TransactionKind.GARAGE,InputSource.SIMULATION,RuleVersion("fixture"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1))))
        source.pending=foreign
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertEquals(foreign,source.pending);assertEquals(1,source.originalReads)
        assertEquals(HostCheckpointResult.Decoration(source.originals[request.requestId]!!),h.host.state.original)
        assertTrue(h.host.state.screen is ShopScreenState.Unsupported)
        assertNoRecoveryWrites(source)
    }
    @Test fun paidDecorationRefreshesFacilityFactsAndExistingEligiblePurchaseWorksInSameHome() {
        val source=HomeFixture().apply {
            active=true;level=3;offers=mapOf(Facility.CAR_WASH to FacilityOffer(Facility.CAR_WASH,RuleVersion("fixture-facility"),true,GameMoney(70)))
        }
        val h=HomeHarness(source);h.start();h.buy("blue-sign")
        assertEquals(GameMoney(900),source.money)
        val screen=h.host.state.screen as ShopScreenState.Content
        val option=screen.facilities.single{it.facility==Facility.CAR_WASH}.option
        assertEquals(ShopAvailability.AVAILABLE,option.availability)
        assertEquals(GameMoney(900),h.facility.state.facts!!.balance)
        val request=ShopRequest(RequestId("facility-after-decoration"),ShopSelection(screen.revision,option),"attempt")
        h.rememberPending(request);assertTrue(h.host.submit(request));h.drain()
        assertEquals(1,source.facilitySaves);assertEquals(1,source.facilityExecutes)
        assertEquals(GameMoney(830),source.money);assertEquals(setOf(Facility.CAR_WASH),source.installed)
        assertTrue(h.completed.last().second is ContractResult.Success)
        assertNotNull(source.pending) // 사용자 확인 전 원결과를 자동 finish하지 않는다.
        assertEquals(0,source.facilityFinishes)
    }
    @Test fun inactiveReadAndPreviewDoNotActivateOrWriteAndExplicitActivationAllowsFreePurchase() {
        val h=HomeHarness();h.start();assertTrue(h.host.state.activationRequired);assertEquals(0,h.source.activates)
        assertEquals(0,h.source.saves);assertEquals(GameMoney(1000),h.source.money)
        assertTrue(h.host.activateDecoration());h.drain();assertEquals(1,h.source.activates)
        val q=h.buy("neutral-sign");assertEquals(q,h.completed.single().first);assertTrue(h.completed.single().second is ContractResult.Success)
        assertEquals(GameMoney(1000),h.source.money);assertEquals(setOf("neutral-sign"),h.source.module.ownedIds)
        assertNull(h.source.originals[q.requestId]!!.settlement);assertEquals(1,h.source.saves)
    }
    @Test fun paidPurchaseUsesActualDecorationRevisionAndCurrentScreenAfterFreePurchase() {
        val s=HomeFixture().apply{active=true;level=2};val h=HomeHarness(s);h.start()
        h.buy("neutral-sign");val paid=h.buy("blue-sign")
        assertEquals(GameMoney(900),s.money);assertEquals(Revision(1),s.originals[paid.requestId]!!.command.expectedRevision)
        val c=h.host.state.screen as ShopScreenState.Content;assertEquals(GameMoney(900),c.balance)
        assertEquals("blue-sign",s.module.selectedId);assertEquals("간판 색상 · 시연",c.appearance.sign)
        assertEquals(2,s.saves);assertEquals(2,s.finishes)
    }
    @Test fun confirmedOriginalSurvivesReadFailureAndRetryDoesNotBuyAgain() {
        val s=HomeFixture().apply{active=true;finishFailure=ContractError.STORAGE_FAILURE};val h=HomeHarness(s);h.start()
        h.buy("neutral-sign");assertNotNull(h.host.state.decoration?.receipt);assertTrue(h.host.state.screen is ShopScreenState.Failure)
        assertEquals(1,s.saves);s.finishFailure=null;s.readFailure=ContractError.DATA_UNAVAILABLE;h.host.refresh();h.drain()
        assertTrue(h.host.state.screen is ShopScreenState.Failure);assertNotNull(h.host.state.decoration?.receipt)
        s.readFailure=null;h.host.refresh();h.drain();assertEquals(1,s.saves);assertEquals(GameMoney(1000),s.money)
    }
    @Test fun hiddenOldLookupCannotBecomeNewContentAfterGateRegain() {
        val h=HomeHarness(queued=true);h.host.setControlsEnabled(true)
        h.work.removeFirst().run();h.host.setControlsEnabled(false);h.source.money=GameMoney(777)
        h.host.setControlsEnabled(true);h.drain()
        assertEquals(GameMoney(777),(h.host.state.screen as ShopScreenState.Content).balance);assertEquals(0,h.source.saves)
    }
    @Test fun definitionOrSourceFailureIsFailureAndDoesNotInventActivationOrEmptyOwnership() {
        val s=HomeFixture().apply{active=true;readFailure=ContractError.DATA_UNAVAILABLE};val h=HomeHarness(s);h.start()
        assertTrue(h.host.state.screen is ShopScreenState.Failure);assertFalse(h.host.state.activationRequired)
        assertEquals(0,s.activates);assertEquals(0,s.saves)
    }
    @Test fun alteredWholeOptionAndForeignRequestAreRejectedWithoutSaving() {
        val s=HomeFixture().apply{active=true};val h=HomeHarness(s);h.start();val c=h.host.state.screen as ShopScreenState.Content
        val option=c.decorations.first().copy(price=GameMoney(8))
        assertFalse(h.host.submit(ShopRequest(RequestId("tampered"),ShopSelection(c.revision,option),"attempt")))
        assertEquals(0,s.saves);assertTrue(h.completed.last().second is ContractResult.Failure)
    }

    @Test fun failedHomeLoadCompletesRetryLockAndAllowsAnotherExplicitRetry() {
        val source=HomeFixture().apply{loadFailure=ContractError.STORAGE_FAILURE}
        val h=HomeHarness(source);h.start()
        assertTrue(h.interaction.state is ShopScreenState.Failure)
        val before=source.loads
        h.interaction.retry();h.drain()
        assertEquals(before+1,source.loads)
        assertFalse("실패한 재조회도 Retry 잠금을 해제해야 한다",h.interaction.uiState.retryPending)
        h.interaction.retry();h.drain()
        assertEquals(before+2,source.loads)
        assertFalse(h.interaction.uiState.retryPending)
        source.loadFailure=null
        h.interaction.retry();h.drain()
        assertTrue(h.host.state.screen is ShopScreenState.Content)
        assertFalse(h.interaction.uiState.retryPending)
        assertEquals(0,source.saves)
    }

    @Test fun identicalQueuedRequestIsIgnoredWithoutCompletingOrClearingOriginalPending() {
        val h=HomeHarness(HomeFixture().apply{active=true},queued=true);h.start()
        val request=h.requestDecoration();h.rememberPending(request)
        assertTrue(h.host.submit(request))
        assertFalse(h.host.submit(request))
        assertTrue("동일 진행 요청의 재전달은 완료 응답이 아니다",h.completed.isEmpty())
        assertEquals(request,h.interaction.uiState.pending)
        assertEquals(request,h.host.uiState.pending)
        assertEquals(0,h.source.saves)
        h.drain()
        assertEquals(1,h.source.saves);assertEquals(1,h.source.executes)
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertNull(h.interaction.uiState.pending)
    }

    @Test fun conflictingOptionOrAttemptWithSameIdNeverCompletesTheInFlightOriginal() {
        for(changeOption in listOf(false,true)) {
            val h=HomeHarness(HomeFixture().apply{active=true;level=2},queued=true);h.start()
            val request=h.requestDecoration();val content=h.host.state.screen as ShopScreenState.Content
            val conflict=if(changeOption)request.copy(selection=request.selection.copy(option=content.decorations.single {
                (it.command as? ShopCommand.Decorate)?.decorationId=="blue-sign"
            })) else request.copy(attemptId="different-attempt")
            h.rememberPending(request);assertTrue(h.host.submit(request));assertFalse(h.host.submit(conflict))
            assertEquals(request,h.interaction.uiState.pending);assertEquals(request,h.host.uiState.pending)
            assertFalse(h.completed.any{it.first==request})
            assertEquals(listOf(conflict to ContractResult.Failure(ContractError.STATE_CONFLICT)),h.completed)
            h.drain()
            assertEquals(1,h.source.saves);assertEquals(1,h.source.executes)
            assertEquals(1,h.completed.count{it.first==request && it.second is ContractResult.Success})
            assertNull(h.interaction.uiState.pending)
        }
    }

    @Test fun restoredExpansionConsumesOriginalWithoutPreparingAndCompletesMatchingUiRequest() {
        val source=HomeFixture();val request=source.seedExpansion();val original=source.expansionOriginals[request.requestId]!!
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(0,source.prepares);assertEquals(0,source.saves)
        assertEquals(1,source.executes);assertEquals(1,source.finishes)
        assertEquals(original,source.expansionOriginals[request.requestId]);assertNull(source.pending)
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertNull(h.host.uiState.pending);assertNull(h.interaction.uiState.pending)
        assertEquals(GameMoney(500),(h.host.state.screen as ShopScreenState.Content).balance)
    }

    @Test fun restoredFacilityConsumesOriginalWithoutSavingAndCompletesOnlyMatchingUiRequest() {
        val source=HomeFixture();val request=source.seedFacility();val original=source.facilityOriginals[request.requestId]
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(0,source.prepares);assertEquals(0,source.saves);assertEquals(0,source.facilitySaves)
        assertEquals(1,source.facilityExecutes);assertEquals(original,h.facility.state.receipt)
        assertNotNull("시설 원결과는 기존 명시 확인까지 durable 슬롯에 남는다",source.pending)
        assertEquals(0,source.facilityFinishes)
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertNull(h.host.uiState.pending);assertNull(h.interaction.uiState.pending)
        assertEquals(GameMoney(930),source.money)
    }

    @Test fun restoredExpansionWithDifferentIdOrWholeOfferIsConflictAndPreservesBothPendingInputs() {
        for(changeOffer in listOf(false,true)) {
            val source=HomeFixture();val originalRequest=source.seedExpansion();val durable=source.pending
            val originalOption=originalRequest.selection.option
            val offer=(originalOption.command as ShopCommand.Expand).offer
            val pending=if(changeOffer)originalRequest.copy(selection=originalRequest.selection.copy(option=originalOption.copy(
                command=ShopCommand.Expand(offer.copy(price=GameMoney(501))),price=GameMoney(501))))
                else originalRequest.copy(requestId=RequestId("different-restored-id"))
            val h=HomeHarness(source,initialUi=ShopUiState(pending=pending,latestRevision=pending.selection.revision),restoredIdentity=source.identity);h.start()
            assertEquals(pending,h.host.uiState.pending);assertEquals(pending,h.interaction.uiState.pending)
            assertEquals(durable,source.pending);assertTrue(h.completed.isEmpty())
            assertEquals(ContractError.STATE_CONFLICT,h.host.state.error)
            assertEquals(0,source.prepares);assertEquals(0,source.saves);assertEquals(0,source.executes);assertEquals(0,source.finishes)
            assertEquals(GameMoney(500),source.money)
        }
    }

    @Test fun restoredFacilityWithDifferentIdOrWholeOfferCannotCompleteOrConsumeAnotherOriginal() {
        for(changeOffer in listOf(false,true)) {
            val source=HomeFixture();val originalRequest=source.seedFacility();val durable=source.pending
            val originalOption=originalRequest.selection.option
            val offer=(originalOption.command as ShopCommand.BuyFacility).offer
            val pending=if(changeOffer)originalRequest.copy(selection=originalRequest.selection.copy(option=originalOption.copy(
                command=ShopCommand.BuyFacility(offer.copy(price=GameMoney(71))),price=GameMoney(71))))
                else originalRequest.copy(requestId=RequestId("different-restored-id"))
            val h=HomeHarness(source,initialUi=ShopUiState(pending=pending,latestRevision=pending.selection.revision),restoredIdentity=source.identity);h.start()
            assertEquals(pending,h.host.uiState.pending);assertEquals(pending,h.interaction.uiState.pending)
            assertEquals(durable,source.pending);assertTrue(h.completed.isEmpty())
            assertEquals(ContractError.STATE_CONFLICT,h.host.state.error)
            assertEquals(0,source.facilitySaves);assertEquals(0,source.facilityFinishes)
            assertEquals(GameMoney(930),source.money)
        }
    }

    @Test fun expansionSavedBeforeGateLossRecoversOriginalOnceAfterGateRegain() {
        val source=HomeFixture().apply{completedCustomers=3};val h=HomeHarness(source,queued=true);h.start()
        val content=h.host.state.screen as ShopScreenState.Content
        val request=ShopRequest(RequestId("gate-expansion"),ShopSelection(content.revision,content.expansion!!),"first-attempt")
        assertEquals(ShopAvailability.AVAILABLE,request.selection.option.availability)
        h.rememberPending(request);assertTrue(h.host.submit(request))
        h.work.removeFirst().run() // durable prepare는 끝났지만 그 UI reply/execute는 아직 전달하지 않는다.
        val saved=source.pending;assertTrue(saved is HostCheckpointCommand.Expand)
        assertEquals(1,source.prepares);assertEquals(1,source.saves);assertEquals(0,source.executes)
        h.host.setControlsEnabled(false);h.host.setControlsEnabled(true);h.drain()
        assertEquals(1,source.prepares);assertEquals(1,source.saves);assertEquals(1,source.executes);assertEquals(1,source.finishes)
        assertEquals(saved,source.expansionOriginals[request.requestId]!!.first);assertNull(source.pending)
        assertEquals(GameMoney(500),source.money);assertEquals(2,source.level)
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertNull(h.host.uiState.pending);assertNull(h.interaction.uiState.pending)
    }

    private fun assertNoRecoveryWrites(source:HomeFixture) {
        assertEquals(0,source.prepares);assertEquals(0,source.saves);assertEquals(0,source.executes)
        assertEquals(0,source.finishes);assertEquals(0,source.activates)
        assertEquals(0,source.facilitySaves);assertEquals(0,source.facilityExecutes);assertEquals(0,source.facilityFinishes)
    }
    private fun assertOriginalLookupFailedWithoutCompleting(h:HomeHarness,request:ShopRequest) {
        assertEquals(request,h.host.uiState.pending);assertEquals(request,h.interaction.uiState.pending)
        assertTrue(h.completed.isEmpty());assertTrue(h.host.state.screen is ShopScreenState.Failure)
        assertNull(h.host.state.original);assertNull(h.source.pending);assertNoRecoveryWrites(h.source)
    }

    @Test fun finishedFreeDecorationUsesNullableFinancialOriginalAndFreshCurrentMoneyAndAppearance() {
        val source=HomeFixture();val request=source.seedFreeDecoration();val original=source.originals[request.requestId]!!
        source.money=GameMoney(777);source.level=2
        source.module=source.module.copy(revision=Revision(2),balance=source.money,shopLevel=source.level,
            ownedIds=setOf("neutral-sign","blue-sign"),selectedId="blue-sign")
        val current=source.module
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(1,source.originalReads);assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertEquals(HostCheckpointResult.Decoration(original),h.host.state.original)
        assertNull(original.settlement);assertEquals(GameMoney(1000),original.state.balance)
        val shown=h.host.state.screen as ShopScreenState.Content
        assertEquals(GameMoney(777),shown.balance);assertEquals("간판 색상 · 시연",shown.appearance.sign)
        assertEquals(current,source.module);assertEquals(GameMoney(777),source.money)
        assertNull(h.host.uiState.pending);assertNull(h.interaction.uiState.pending);assertNoRecoveryWrites(source)
    }

    @Test fun finishedExpansionReplaysOldFullOfferBeforeChangedCurrentFactsWithoutPreparingAgain() {
        val source=HomeFixture();val request=source.seedExpansion();val original=source.expansionOriginals[request.requestId]!!
        source.pending=null;source.money=GameMoney(777);source.completedCustomers=0
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(1,source.originalReads);assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertEquals(HostCheckpointResult.Expand(original.second),h.host.state.original)
        assertEquals(GameMoney(500),original.first.offer.price);assertEquals(GameMoney(500),original.second.balance)
        val current=h.host.state.screen as ShopScreenState.Content
        assertEquals(GameMoney(777),current.balance);assertEquals(2,current.level)
        assertEquals(GameMoney(1500),current.expansion!!.price)
        assertEquals(original,source.expansionOriginals[request.requestId]);assertNoRecoveryWrites(source)
    }

    @Test fun finishedFacilityUsesRestoredFullDefinitionCommandAndReadsOriginalWithoutSavingOrExecuting() {
        val source=HomeFixture();val request=source.seedFacility();val original=source.facilityOriginals[request.requestId]!!
        val command=original.command
        assertNotEquals(ContentVersion(command.offer.ruleVersion.value),command.definitionVersion)
        source.pending=null;source.installed+=command.offer.facility;source.money=GameMoney(900)
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredFacility=command,restoredIdentity=source.identity);h.start()
        assertEquals(1,source.originalReads);assertEquals(command,source.originalCalls.single().third)
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertEquals(HostCheckpointResult.FacilityPurchase(original.settlement),h.host.state.original)
        assertEquals(GameMoney(930),original.settlement.balance)
        assertEquals(GameMoney(900),(h.host.state.screen as ShopScreenState.Content).balance)
        assertNull(h.host.facilityOriginalCommand);assertNull(h.host.uiState.pending);assertNoRecoveryWrites(source)
    }

    @Test fun finishedFacilityWithMissingOrWrongDefinitionMetadataFailsClosedAndPreservesUiPending() {
        for(missing in listOf(true,false)) {
            val source=HomeFixture();val request=source.seedFacility();val command=source.facilityOriginals[request.requestId]!!.command
            source.pending=null
            val restored=if(missing)null else command.copy(definitionVersion=ContentVersion("different-original-definition"))
            val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredFacility=restored,restoredIdentity=source.identity);h.start()
            assertOriginalLookupFailedWithoutCompleting(h,request);assertEquals(GameMoney(930),source.money)
        }
    }

    @Test fun finishedExpansionWithAlteredWholeOfferCannotReplayByMatchingPriceAlone() {
        val source=HomeFixture();val request=source.seedExpansion();source.pending=null
        val option=request.selection.option;val offer=(option.command as ShopCommand.Expand).offer
        val altered=request.copy(selection=request.selection.copy(option=option.copy(
            command=ShopCommand.Expand(offer.copy(requiredCustomers=offer.requiredCustomers+1)))))
        val h=HomeHarness(source,initialUi=ShopUiState(pending=altered,latestRevision=altered.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(1,source.originalReads);assertOriginalLookupFailedWithoutCompleting(h,altered)
        assertEquals(GameMoney(500),source.money)
    }

    @Test fun missingFailedWrongTypeOrAlteredFinancialOriginalNeverCreatesACompletionOrNewPurchase() {
        val template=HomeFixture();val templateRequest=template.seedExpansion()
        val original=template.expansionOriginals[templateRequest.requestId]!!.second
        val responses=listOf<ContractResult<HostCheckpointResult?>>(
            ContractResult.Success(null),ContractResult.Failure(ContractError.STORAGE_FAILURE),
            ContractResult.Success(HostCheckpointResult.FacilityPurchase(original)),
            ContractResult.Success(HostCheckpointResult.Expand(original.copy(transaction=original.transaction.copy(requestId=RequestId("another-original"))))),
            ContractResult.Success(HostCheckpointResult.Expand(original.copy(transaction=original.transaction.copy(
                money=MoneyChange(MoneyDirection.DEBIT,GameMoney(501)))))))
        for(response in responses) {
            val source=HomeFixture();val request=source.seedExpansion();source.pending=null;source.originalResultOverride=response
            val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
            assertEquals(1,source.originalReads);assertOriginalLookupFailedWithoutCompleting(h,request)
            assertEquals(GameMoney(500),source.money)
        }
    }

    @Test fun finishedDecorationWithDifferentRecordedProfileOrMoneyIsNotAFreeOriginal() {
        for(changeProfile in listOf(true,false)) {
            val source=HomeFixture();val request=source.seedFreeDecoration();val original=source.originals[request.requestId]!!
            val altered=if(changeProfile)original.copy(command=original.command.copy(profileId=ProfileId("another-profile")))
                else original.copy(settlement=source.financial(request.requestId,original.command.ruleVersion,GameMoney(1),GameMoney(999)))
            source.originalResultOverride=ContractResult.Success(HostCheckpointResult.Decoration(altered))
            val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
            assertEquals(1,source.originalReads);assertOriginalLookupFailedWithoutCompleting(h,request)
            assertEquals(GameMoney(1000),source.money)
        }
    }

    @Test fun finishedOriginalCompletesBeforeCurrentDefinitionsFailAndKeepsOriginalSeparateFromFailure() {
        val source=HomeFixture();val request=source.seedExpansion();val original=source.expansionOriginals[request.requestId]!!.second
        source.pending=null;source.currentLoadFailure=ContractError.DATA_UNAVAILABLE
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity);h.start()
        assertEquals(1,source.originalReads);assertTrue(source.identityReads>0)
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertEquals(HostCheckpointResult.Expand(original),h.host.state.original)
        assertTrue(h.host.state.screen is ShopScreenState.Failure)
        assertNull(h.host.uiState.pending);assertNull(h.interaction.uiState.pending);assertNoRecoveryWrites(source)
    }

    @Test fun oldOriginalLookupReplyAfterGateRegainCannotCompletePendingBeforeFreshLookup() {
        val source=HomeFixture();val request=source.seedExpansion();source.pending=null
        val h=HomeHarness(source,queued=true,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=source.identity)
        h.host.setControlsEnabled(true)
        var steps=0
        while(source.originalReads==0) {
            check(++steps<50)
            if(h.work.isNotEmpty())h.work.removeFirst().run()
            if(source.originalReads==0 && h.replies.isNotEmpty())h.replies.removeFirst().run()
        }
        assertTrue(h.completed.isEmpty())
        h.host.setControlsEnabled(false);source.originalResultOverride=ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        h.host.setControlsEnabled(true);h.drain()
        assertEquals(2,source.originalReads);assertOriginalLookupFailedWithoutCompleting(h,request)
        source.originalResultOverride=null;source.money=GameMoney(777)
        assertTrue(h.host.refresh());h.drain()
        assertEquals(3,source.originalReads);assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertEquals(GameMoney(777),(h.host.state.screen as ShopScreenState.Content).balance)
        assertNoRecoveryWrites(source)
    }

    @Test fun newFacilitySubmissionCapturesActualDefinitionCommandThenClearsMetadataAfterCompletion() {
        val offer=FacilityOffer(Facility.CAR_WASH,RuleVersion("facility-rule"),true,GameMoney(70))
        val source=HomeFixture().apply{active=true;level=3;offers=mapOf(offer.facility to offer)}
        val h=HomeHarness(source,queued=true);h.start()
        val facts=h.facility.state.facts!!;val screen=h.host.state.screen as ShopScreenState.Content
        val option=screen.facilities.single{it.facility==offer.facility}.option
        val request=ShopRequest(RequestId("capture-facility"),ShopSelection(screen.revision,option),"capture-attempt")
        h.rememberPending(request);assertTrue(h.host.submit(request))
        assertEquals(FacilityCommand.Buy(facts.identity,request.requestId,facts.definitionVersion,offer),h.host.facilityOriginalCommand)
        assertEquals(0,source.facilitySaves)
        h.drain()
        assertEquals(listOf(request to ContractResult.Success(Unit)),h.completed)
        assertNull(h.host.facilityOriginalCommand);assertNull(h.host.uiState.pending)
        assertEquals(1,source.facilitySaves);assertEquals(1,source.facilityExecutes);assertEquals(0,source.facilityFinishes)
    }

    @Test fun restoredPendingWithoutIdentityCannotUseAnyFinishedOriginalAsCurrentProfileAuthority() {
        for(kind in listOf("decoration","expansion","facility")) {
            val source=HomeFixture()
            val request=when(kind) {
                "decoration"->source.seedFreeDecoration()
                "expansion"->source.seedExpansion()
                else->source.seedFacility()
            }
            source.pending=null
            val facility=source.facilityOriginals[request.requestId]?.command
            val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),
                restoredFacility=facility,restoredIdentity=null)
            h.start()
            assertEquals("원 소속을 잃은 UI 요청은 현재 프로필의 결과로 완료하지 않는다: $kind",request,h.host.uiState.pending)
            assertEquals(request,h.interaction.uiState.pending);assertTrue(h.completed.isEmpty())
            assertTrue(h.host.state.screen is ShopScreenState.Failure)
            assertEquals(ContractError.DATA_UNAVAILABLE,h.host.state.error)
            assertEquals(0,source.originalReads);assertNull(h.host.state.original);assertNull(source.pending)
            assertNoRecoveryWrites(source)
            assertTrue(h.host.refresh());h.drain()
            assertEquals(request,h.host.uiState.pending);assertEquals(0,source.originalReads);assertTrue(h.completed.isEmpty())
        }
    }

    @Test fun restoredPendingWithoutIdentityPreservesTheDurableSlotWithoutExecutingOrFinishingIt() {
        val source=HomeFixture();val request=source.seedExpansion();val slot=source.pending
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),restoredIdentity=null)
        h.start()
        assertEquals(request,h.host.uiState.pending);assertEquals(request,h.interaction.uiState.pending)
        assertEquals(slot,source.pending);assertEquals(0,source.originalReads);assertTrue(h.completed.isEmpty())
        assertEquals(ContractError.DATA_UNAVAILABLE,h.host.state.error);assertTrue(h.host.state.screen is ShopScreenState.Failure)
        assertNoRecoveryWrites(source)
    }

    @Test fun restoredKnownSourceCannotCompleteUiFromAnotherActualSourceWithSameProfile() {
        val source=HomeFixture();val request=source.seedFreeDecoration()
        val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),
            restoredIdentity=GameIdentity(source.identity.profileId,InputSource.VEHICLE))
        h.start()
        assertEquals(ContractError.STATE_CONFLICT,h.host.state.error)
        assertEquals(request,h.host.uiState.pending);assertEquals(request,h.interaction.uiState.pending)
        assertTrue(h.completed.isEmpty());assertEquals(0,source.originalReads);assertNoRecoveryWrites(source)
    }

    @Test fun conflictingKnownFacilityMetadataCannotBeOverwrittenByAMatchingUiDurableSlot() {
        for(change in listOf("definition","profile","source")) {
            val source=HomeFixture();val request=source.seedFacility()
            val actual=source.facilityOriginals[request.requestId]!!.command
            val slot=source.pending
            val known=when(change) {
                "definition"->actual.copy(definitionVersion=ContentVersion("different-known-definition"))
                "profile"->actual.copy(identity=GameIdentity(ProfileId("different-known-profile"),actual.identity.source))
                else->actual.copy(identity=GameIdentity(actual.identity.profileId,InputSource.VEHICLE))
            }
            val h=HomeHarness(source,initialUi=ShopUiState(pending=request,latestRevision=request.selection.revision),
                restoredFacility=known,restoredIdentity=source.identity)
            assertEquals(known,h.host.facilityOriginalCommand)
            h.start()
            assertEquals("알려진 전체 시설 명령이 다른 슬롯으로 덮이면 안 된다: $change",request,h.host.uiState.pending)
            assertEquals(request,h.interaction.uiState.pending);assertEquals(slot,source.pending)
            assertEquals(known,h.host.facilityOriginalCommand);assertTrue(h.completed.isEmpty())
            assertEquals(ContractError.STATE_CONFLICT,h.host.state.error)
            assertTrue(h.host.state.screen is ShopScreenState.Failure)
            assertEquals(0,source.originalReads);assertNoRecoveryWrites(source)
        }
    }
}
