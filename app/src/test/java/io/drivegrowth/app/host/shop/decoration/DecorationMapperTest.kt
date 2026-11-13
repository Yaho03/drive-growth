package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.junit.Assert.*
import org.junit.Test

internal fun decorationFacts() = DecorationFacts(
    DecorationState(ProfileId("shop-owner"), InputSource.SIMULATION, Revision(41), GameMoney(1000), 2, emptySet(), null),
    trialDecorationDefinitions(),
    ShopScreenState.Content(Revision(77), "기존 가게", 2, 0, 5, GameMoney(1000),
        ShopAppearance("기존 간판", 0xff58a6ff.toInt()), emptyList(), emptyList(), null, emptyList(), ShopOrigin.STORED),
)
internal fun decorationMapper() = DecorationMapper(mapOf(
    "neutral" to ShopAppearance("기본 간판", 0xff58a6ff.toInt()),
    "blue-sign" to ShopAppearance("간판 색상", 0xff58a6ff.toInt()),
))
internal fun <T> success(result: ContractResult<T>): T {
    assertTrue(result.toString(), result is ContractResult.Success)
    return (result as ContractResult.Success).value
}
internal fun request(screen: ShopScreenState.Content, id: String = "blue-sign", attempt: String = "attempt-1") =
    ShopRequest(RequestId("decoration/original"), ShopSelection(screen.revision,
        screen.decorations.single { (it.command as? ShopCommand.Decorate)?.decorationId == id }), attempt)

class DecorationMapperTest {
    @Test fun `표시 revision과 원장 revision은 다르며 가격 버전을 원명령에 고정한다`() {
        val facts = decorationFacts()
        val mapper = decorationMapper()
        val screen = success(mapper.map(facts, Revision(3)))
        val command = success(mapper.command(facts, Revision(3), request(screen)))
        assertEquals(Revision(3), screen.revision)
        assertEquals(Revision(41), command.expectedRevision)
        assertEquals(GameMoney(100), command.expectedPrice)
        assertEquals(facts.definitions.ruleVersion, command.ruleVersion)
        assertEquals(facts.state.profileId, command.profileId)
        assertEquals(InputSource.SIMULATION, command.source)
        assertEquals(DecorationAction.BUY_AND_APPLY, command.action)
        assertEquals("기존 간판", screen.appearance.sign)
        assertEquals(GameMoney(1000), screen.balance)
        assertTrue(facts.state.ownedIds.isEmpty())
    }

    @Test fun `소유 선택과 잠김은 core 판정을 표시하며 소유 재적용은 무료다`() {
        val facts = decorationFacts()
        val mapper = decorationMapper()
        val selected = facts.copy(state = facts.state.copy(ownedIds = setOf("neutral-sign", "blue-sign"), selectedId = "neutral-sign"))
        val screen = success(mapper.map(selected, Revision(4)))
        assertEquals("기본 간판", screen.appearance.sign)
        assertEquals(ShopAvailability.INSTALLED, screen.decorations.single { it.id == "decoration:neutral-sign" }.availability)
        val apply = success(mapper.command(selected, Revision(4), request(screen)))
        assertEquals(DecorationAction.APPLY_OWNED, apply.action)
        assertEquals(GameMoney(0), apply.expectedPrice)
        val locked = facts.copy(state = facts.state.copy(shopLevel = 1), shop = facts.shop.copy(level = 1))
        assertEquals(ShopAvailability.LOCKED, success(mapper.map(locked, Revision(5))).decorations.single { it.id == "decoration:blue-sign" }.availability)
        val poor = facts.copy(state = facts.state.copy(balance = GameMoney(99)), shop = facts.shop.copy(balance = GameMoney(99)))
        assertEquals(ShopAvailability.LOCKED, success(mapper.map(poor, Revision(6))).decorations.single { it.id == "decoration:blue-sign" }.availability)
    }

    @Test fun `옛 표시 세대 가격 버전 외형 payload 중 하나라도 바뀐 선택은 거절한다`() {
        val facts = decorationFacts(); val mapper = decorationMapper()
        val original = request(success(mapper.map(facts, Revision(3))))
        val option = original.selection.option
        val bad = listOf(original.copy(selection = original.selection.copy(revision = Revision(2))),
            original.copy(selection = original.selection.copy(option = option.copy(price = GameMoney(0)))),
            original.copy(selection = original.selection.copy(option = option.copy(ruleVersion = RuleVersion("old")))),
            original.copy(selection = original.selection.copy(option = option.copy(command = ShopCommand.Decorate("blue-sign", ShopAppearance("변조", 0))))))
        bad.forEach { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), mapper.command(facts, Revision(3), it)) }
    }

    @Test fun `미승인 theme은 행동을 만들지 않고 선택된 theme 누락은 정상 외형으로 덮지 않는다`() {
        val facts = decorationFacts(); val mapper = DecorationMapper(emptyMap())
        assertTrue(success(mapper.map(facts, Revision(1))).decorations.all { it.availability == ShopAvailability.UNKNOWN && it.command == null })
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), mapper.map(
            facts.copy(state = facts.state.copy(ownedIds = setOf("blue-sign"), selectedId = "blue-sign")), Revision(1)))
    }

    @Test fun `현재 잔액과 가게 단계의 불일치는 영이나 receipt 값으로 보정하지 않는다`() {
        val facts = decorationFacts(); val mapper = decorationMapper()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), mapper.map(facts.copy(shop = facts.shop.copy(balance = GameMoney(900))), Revision(1)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), mapper.map(facts.copy(shop = facts.shop.copy(level = null)), Revision(1)))
    }

    @Test fun `승인 표현의 외부 map 변경과 노출 목록 변경이 기존 화면을 바꾸지 못한다`() {
        val external = mutableMapOf("neutral" to ShopAppearance("기본", 7), "blue-sign" to ShopAppearance("기존", 7))
        val mapper = DecorationMapper(external); external.clear()
        val screen = success(mapper.map(decorationFacts(), Revision(1)))
        assertEquals("기존", (screen.decorations.last().command as ShopCommand.Decorate).appearance.sign)
        assertThrows(UnsupportedOperationException::class.java) { (screen.decorations as MutableList).clear() }
    }
}
