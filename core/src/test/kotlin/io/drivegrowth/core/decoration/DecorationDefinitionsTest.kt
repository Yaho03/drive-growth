package io.drivegrowth.core.decoration

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class DecorationDefinitionsTest {
    private val rule = RuleVersion("test-definition-v1")
    private val item = DecorationDefinition("neutral-sign", "neutral", "기본 간판", 1, GameMoney(0))
    private fun state(owned: Set<String>, selected: String? = null) = DecorationState(
        ProfileId("profile"), InputSource.SIMULATION, Revision(0), GameMoney(0), 1, owned, selected)

    @Test fun `정의의 입력 목록과 노출 목록을 바꿔도 품목 권위는 바뀌지 않는다`() {
        val external = mutableListOf(item)
        val definitions = DecorationDefinitions(rule, true, external)
        external.clear()
        assertEquals(listOf(item), definitions.items)
        assertThrows(UnsupportedOperationException::class.java) { (definitions.items as MutableList).clear() }
        assertEquals(listOf(item), definitions.items)
    }

    @Test fun `소유 상태 생성과 명시 copy는 외부 집합과 노출 변경을 차단한다`() {
        val external = linkedSetOf("neutral-sign")
        val original = state(external, "neutral-sign")
        val copied = original.copy(ownedIds = external)
        external.clear()
        assertEquals(setOf("neutral-sign"), original.ownedIds)
        assertEquals(setOf("neutral-sign"), copied.ownedIds)
        assertThrows(UnsupportedOperationException::class.java) { (original.ownedIds as MutableSet).add("foreign") }
        assertThrows(UnsupportedOperationException::class.java) { (copied.ownedIds as MutableSet).clear() }
        assertEquals(original, copied)
        assertEquals(original.hashCode(), copied.hashCode())
        assertNotEquals(original, copied.copy(revision = Revision(1)))
    }

    @Test fun `빈 정의 중복 ID 공백 필드와 불가 해금 레벨은 정의 생성에서 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { DecorationDefinitions(rule, true, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { DecorationDefinitions(rule, true, listOf(item, item.copy(price = GameMoney(1)))) }
        assertThrows(IllegalArgumentException::class.java) { item.copy(id = " ") }
        assertThrows(IllegalArgumentException::class.java) { item.copy(themeKey = " ") }
        assertThrows(IllegalArgumentException::class.java) { item.copy(displayName = " ") }
        assertThrows(IllegalArgumentException::class.java) { item.copy(requiredShopLevel = 0) }
    }

    @Test fun `선택은 소유 품목이어야 하며 누락 상태를 정상 초기 상태로 보정하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) { state(emptySet(), "neutral-sign") }
        assertThrows(IllegalArgumentException::class.java) { state(setOf(" ")) }
        assertThrows(IllegalArgumentException::class.java) { state(emptySet()).copy(shopLevel = 0) }
        assertThrows(IllegalArgumentException::class.java) { DecorationCommand(RequestId("one"), ProfileId("p"), InputSource.VEHICLE,
            " ", DecorationAction.BUY_AND_APPLY, rule, Revision(0), GameMoney(0)) }
    }

    @Test fun `시연 정의를 소비한 구매는 표시 가격과 해금을 따르며 최종 규칙으로 위장하지 않는다`() {
        val definitions = trialDecorationDefinitions()
        assertTrue(definitions.trial)
        val command = DecorationCommand(RequestId("trial-buy"), ProfileId("profile"), InputSource.SIMULATION,
            "blue-sign", DecorationAction.BUY_AND_APPLY, definitions.ruleVersion, Revision(0), GameMoney(100))
        val fresh = state(emptySet()).copy(balance = GameMoney(1000))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), prepareDecorationChange(command, fresh, definitions))
        val allowed = prepareDecorationChange(command, fresh.copy(shopLevel = 2), definitions)
        assertTrue(allowed.toString(), allowed is ContractResult.Success)
        assertEquals(GameMoney(900), (allowed as ContractResult.Success).value.state.balance)
        assertEquals(setOf("blue-sign"), allowed.value.state.ownedIds)
    }
}
