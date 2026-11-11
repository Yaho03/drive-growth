package io.drivegrowth.app.host.shop

import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test

class HomeShopDisplayTest {
    private val rule = RuleVersion("home-display-v1")
    private val appearance = ShopAppearance("승인 간판", 0xff8fbcff.toInt())
    private val decorate = ShopOption("decoration:free", "무료 간판", "Lv1", "표시 변경", ShopAvailability.AVAILABLE,
        ShopCommand.Decorate("free", appearance), GameMoney(0), rule, true)
    private val expand = ShopExpansion(rule, true, 1, 3, 5, 3, GameMoney(500)).let {
        ShopOption("expand", "확장", "완료 고객", "공간 증가", ShopAvailability.AVAILABLE, ShopCommand.Expand(it), it.price, rule, true)
    }
    private val facility = FacilityOffer(Facility.FUEL_STATION, rule, true, GameMoney(100)).let {
        ShopOption("facility", "주유소", "5대", "시설 설치", ShopAvailability.AVAILABLE, ShopCommand.BuyFacility(it), it.price, rule, true)
    }
    private fun content(decorations: List<ShopOption> = listOf(decorate)) = ShopScreenState.Content(Revision(0), "실제 가게", 1,
        1, 3, GameMoney(1000), appearance, emptyList(), listOf(ShopFacility(Facility.FUEL_STATION, facility)), expand,
        decorations, ShopOrigin.STORED)
    private fun shown(display: HomeShopDisplay) = display.screen as ShopScreenState.Content
    private fun request(display: HomeShopDisplay, option: ShopOption = shown(display).decorations.first()) =
        ShopRequest(RequestId("original/request"), ShopSelection(shown(display).revision, option), "attempt/1")
    private fun conflict(display: HomeShopDisplay, request: ShopRequest) =
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), display.map(request))

    @Test fun differentControllerLocalZeroFramesReceiveOneIncreasingHomeRevision() {
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        val cases = listOf(HomeShopAction.EXPANSION to expand, HomeShopAction.FACILITY to facility, HomeShopAction.DECORATION to decorate)
        cases.forEachIndexed { index, (action, option) ->
            assertTrue(display.adopt(token, content(), mapOf(action to Revision(0))))
            assertEquals(Revision(index + 1), shown(display).revision)
            val mapped = display.map(request(display, option)) as ContractResult.Success
            assertEquals(Revision(0), mapped.value.selection.revision)
        }
    }

    @Test fun gateLossAndRegainRejectOldEpochWithoutRepackagingItsContent() {
        val display = HomeShopDisplay(); val old = display.setControlsEnabled(true)
        assertTrue(display.adopt(old, content(), mapOf(HomeShopAction.DECORATION to Revision(2))))
        val previous = request(display)
        val restricted = display.setControlsEnabled(false)
        assertTrue(restricted > old); assertFalse(display.enabled); assertEquals(ShopScreenState.Loading, display.screen)
        assertEquals(ContractResult.Failure(ContractError.UX_RESTRICTED), display.map(previous))
        val current = display.setControlsEnabled(true)
        assertTrue(current > restricted); assertFalse(display.adopt(old, content(), mapOf(HomeShopAction.DECORATION to Revision(2))))
        assertEquals(ShopScreenState.Loading, display.screen)
        assertTrue(display.adopt(current, content(), mapOf(HomeShopAction.DECORATION to Revision(3))))
        assertEquals(Revision(2), shown(display).revision); conflict(display, previous)
    }

    @Test fun nonContentInvalidatesPreviouslyAcceptedRoutes() {
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        assertTrue(display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(9))))
        val old = request(display)
        assertTrue(display.adopt(token, ShopScreenState.Failure("실제 조회 실패")))
        assertEquals(ShopScreenState.Failure("실제 조회 실패"), display.screen); conflict(display, old)
        assertTrue(display.adopt(token, content()))
        assertEquals(ShopAvailability.UNKNOWN, shown(display).decorations.single().availability)
        conflict(display, request(display))
    }

    @Test fun entirePriceRuleAndAppearanceMustMatchTheCurrentlyDisplayedOption() {
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(0)))
        val original = request(display)
        val forgeries = listOf(decorate.copy(price = GameMoney(100)), decorate.copy(ruleVersion = RuleVersion("other")),
            decorate.copy(command = ShopCommand.Decorate("free", appearance.copy(sign = "위조 간판"))))
        for (option in forgeries) conflict(display, original.copy(selection = original.selection.copy(option = option)))
        assertTrue(display.map(original) is ContractResult.Success)
    }

    @Test fun localMappingPreservesOriginalRequestAttemptAndWholeOption() {
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(71)))
        val original = request(display)
        val mapped = (display.map(original) as ContractResult.Success).value
        assertEquals(original.copy(selection = original.selection.copy(revision = Revision(71))), mapped)
        val retry = original.copy(attemptId = "attempt/2")
        assertEquals(mapped.copy(attemptId = "attempt/2"), (display.map(retry) as ContractResult.Success).value)
        assertEquals(Revision(1), original.selection.revision)
    }

    @Test fun absentRoutesDisableAvailableOptionsWhileKeepingLockedAndInstalledFacts() {
        val locked = decorate.copy(id = "locked", availability = ShopAvailability.LOCKED)
        val installed = decorate.copy(id = "installed", availability = ShopAvailability.INSTALLED)
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        display.adopt(token, content(listOf(decorate, locked, installed)))
        val screen = shown(display)
        assertEquals(decorate.copy(availability = ShopAvailability.UNKNOWN, command = null), screen.decorations[0])
        assertEquals(locked, screen.decorations[1]); assertEquals(installed, screen.decorations[2])
        assertEquals(ShopAvailability.UNKNOWN, screen.expansion!!.availability); assertNull(screen.expansion!!.command)
        assertEquals(ShopAvailability.UNKNOWN, screen.facilities.single().option.availability)
    }

    @Test fun overflowShowsFailureAndNeverWrapsOrKeepsTheLastMapping() {
        val display = HomeShopDisplay(Revision(Int.MAX_VALUE - 1)); val token = display.setControlsEnabled(true)
        assertTrue(display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(0))))
        assertEquals(Revision(Int.MAX_VALUE), shown(display).revision)
        val last = request(display)
        assertFalse(display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(1))))
        assertTrue(display.screen is ShopScreenState.Failure); conflict(display, last)
        display.setControlsEnabled(false); val next = display.setControlsEnabled(true)
        assertFalse(display.adopt(next, content(), mapOf(HomeShopAction.DECORATION to Revision(2))))
        assertTrue(display.screen is ShopScreenState.Failure)
        val exhausted = HomeShopDisplay(Revision(Int.MAX_VALUE)); val first = exhausted.setControlsEnabled(true)
        assertFalse(exhausted.adopt(first, content())); assertTrue(exhausted.screen is ShopScreenState.Failure)
    }

    @Test fun restoredLastAcceptedRevisionAndRepeatedGateArePreserved() {
        val display = HomeShopDisplay(Revision(44)); val token = display.setControlsEnabled(true)
        display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(0)))
        assertEquals(Revision(45), shown(display).revision)
        assertEquals(token, display.setControlsEnabled(true)); assertEquals(token, display.epoch)
        assertEquals(Revision(45), shown(display).revision)
        display.adopt(token, ShopScreenState.Loading)
        display.adopt(token, content(), mapOf(HomeShopAction.DECORATION to Revision(0)))
        assertEquals(Revision(46), shown(display).revision)
    }

    @Test fun missingCommandPriceRuleOrAvailableStateCannotBecomeAMappedRequest() {
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        val invalid = listOf(decorate.copy(command = null), decorate.copy(price = null), decorate.copy(ruleVersion = null),
            decorate.copy(availability = ShopAvailability.LOCKED))
        for (option in invalid) {
            display.adopt(token, content(listOf(option)), mapOf(HomeShopAction.DECORATION to Revision(0)))
            conflict(display, request(display))
        }
    }

    @Test fun acceptedScreenAndRoutesDoNotAliasCallerCollections() {
        val display = HomeShopDisplay(); val token = display.setControlsEnabled(true)
        val options = mutableListOf(decorate); val routes = mutableMapOf(HomeShopAction.DECORATION to Revision(8))
        display.adopt(token, content(options), routes); options.clear(); routes.clear()
        assertEquals(1, shown(display).decorations.size)
        assertEquals(Revision(8), (display.map(request(display)) as ContractResult.Success).value.selection.revision)
        try {
            (shown(display).decorations as MutableList<ShopOption>).clear()
            fail("수락한 표시 목록은 외부 변경을 거절해야 한다")
        } catch (_: UnsupportedOperationException) { }
    }
}
