package io.drivegrowth.core.delivery

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class DeliveryChangeTest {
    private val rules = DeliveryFixtures.trialRules
    private val proposal = DeliveryProposal(rules)
    private val content = GameContentFixtures.loaded()
    private val changeRules = DeliveryChangeRules(RuleVersion("delivery-change-trial-v0"), cancelFeePercent = 10)
    private val change = DeliveryChange(changeRules)
    private val source = InputSource.SIMULATION
    private val today = LocalDate.of(2026, 11, 7)

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun failure(result: ContractResult<*>): ContractError = (result as ContractResult.Failure).error

    private fun proposed(seed: Int = 1) = ok(proposal.propose(
        emptyList(), DestinationKey("a"), Observation.Available(EstimatedRouteDistance(20_000, source)), content, 1, today, Random(seed),
    ))

    private fun terms(type: Class<out DeliveryOffer>, seed: Int = 1): DeliveryTerms {
        val p = proposed(seed)
        val offer = p.offers.first { type.isInstance(it) }
        val price = (offer as? DeliveryOffer.Purchase)?.price ?: GameMoney(0)
        val selected = ok(DeliverySelection.select(p, offer.id, RequestId("r1"), price, null))
        return DeliveryTerms.of(selected)
    }

    private fun changeOffer(t: DeliveryTerms, traveled: Long = 30_000, remaining: Long = 20_000, seed: Int = 7) =
        ok(proposal.proposeChange(
            t.active, Observation.Available(ObservedDistance(traveled, source)),
            Observation.Available(EstimatedRouteDistance(remaining, source)), DestinationKey("b"), content, 1, today, Random(seed),
        ))

    @Test fun `실제 30km와 예상 20km는 목록 기준 50km로 중거리 구간이다`() {
        val offer = changeOffer(terms(DeliveryOffer.Purchase::class.java))
        assertEquals(30_000L, offer.traveledMeters)
        assertEquals(50_000L, offer.combinedMeters)
        assertEquals(1, offer.proposed.list.band)
        assertEquals(20_000L, offer.proposed.estimated.meters)
        assertEquals(2, changeOffer(terms(DeliveryOffer.Purchase::class.java), traveled = 30_001).proposed.list.band)
    }

    @Test fun `변경 목록은 진행 중인 의뢰와 같은 유형만 있다`() {
        val purchase = changeOffer(terms(DeliveryOffer.Purchase::class.java)).proposed.offers
        assertEquals(3, purchase.size)
        assertTrue(purchase.all { it is DeliveryOffer.Purchase })
        val income = changeOffer(terms(DeliveryOffer.Income::class.java)).proposed.offers
        assertEquals(2, income.size)
        assertTrue(income.all { it is DeliveryOffer.Income })
    }

    @Test fun `구매 변경 후보는 희귀도를 1단계 낮추고 일반은 일반으로 둔다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val legend = DeliveryProposal(rules.copy(bands = listOf(DeliveryBand(null, mapOf(Rarity.LEGEND to 1), 100))))
        val common = DeliveryProposal(rules.copy(bands = listOf(DeliveryBand(null, mapOf(Rarity.COMMON to 1), 100))))
        fun rarities(p: DeliveryProposal) = ok(p.proposeChange(
            t.active, Observation.Available(ObservedDistance(30_000, source)), Observation.Available(EstimatedRouteDistance(20_000, source)),
            DestinationKey("b"), content, 1, today, Random(3),
        )).proposed.offers.filterIsInstance<DeliveryOffer.Purchase>()
        assertTrue(rarities(legend).all { it.rarity == Rarity.RARE })
        assertTrue(rarities(common).all { it.rarity == Rarity.COMMON })
        val prices = content.vehicles.associate { it.vehicleId to it.price }
        rarities(legend).forEach { assertEquals(rules.purchasePrice(prices.getValue(it.vehicleId), Rarity.RARE), it.price) }
    }

    @Test fun `변경 목록은 하루 목록을 바꾸지 않고 의뢰 ID가 겹치지 않는다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val before = t.active.proposed.list
        val offer = changeOffer(t)
        assertEquals(before, t.active.proposed.list)
        assertEquals(listOf("change-0", "change-1", "change-2"), offer.proposed.offers.map { it.id.value })
        assertTrue(offer.proposed.offers.none { o -> before.offers.any { it.id == o.id } })
    }

    @Test fun `거리를 얻지 못하면 사유별 오류이고 출처가 다르거나 새 거리가 0이면 거절한다`() {
        val active = terms(DeliveryOffer.Purchase::class.java).active
        val route = Observation.Available(EstimatedRouteDistance(20_000, source))
        val moved = Observation.Available(ObservedDistance(30_000, source))
        fun call(t: Observation<ObservedDistance>, r: Observation<EstimatedRouteDistance>) =
            proposal.proposeChange(active, t, r, DestinationKey("b"), content, 1, today, Random(1))
        assertEquals(ContractError.DATA_UNAVAILABLE, failure(call(Observation.Missing(MissingReason.NO_DATA), route)))
        assertEquals(ContractError.PERMISSION_DENIED, failure(call(moved, Observation.Missing(MissingReason.PERMISSION_DENIED))))
        assertEquals(ContractError.INVALID_INPUT, failure(call(Observation.Available(ObservedDistance(30_000, InputSource.VEHICLE)), route)))
        assertEquals(ContractError.INVALID_INPUT, failure(call(moved, Observation.Available(EstimatedRouteDistance(0, source)))))
        assertEquals(ContractError.INVALID_INPUT, failure(call(Observation.Available(ObservedDistance(Long.MAX_VALUE, source)), route)))
    }

    @Test fun `더 비싼 차로 바꾸면 차액만 결제하고 수수료는 없다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val target = offer.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().maxBy { it.price.units }
        val result = ok(change.change(t.copy(paid = GameMoney(target.price.units - 500)), offer, target.id, GameMoney(500)))
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), result.change?.money)
        assertEquals(RequestId("delivery/r1/change"), result.change?.requestId)
        assertEquals(TransactionKind.DELIVERY, result.change?.kind)
        assertEquals(target.price, result.terms.paid)
        assertTrue(result.terms.changeUsed)
        assertEquals(target, result.terms.active.offer)
        assertEquals(RequestId("r1"), result.terms.active.requestId)
    }

    @Test fun `더 싼 차로 바꾸면 차액을 환급한다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val target = offer.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().first()
        val result = ok(change.change(t.copy(paid = GameMoney(target.price.units + 300)), offer, target.id, GameMoney(0)))
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(300)), result.change?.money)
        assertEquals(target.price, result.terms.paid)
    }

    @Test fun `같은 금액이면 돈이 오가지 않는다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val target = offer.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().first()
        assertNull(ok(change.change(t.copy(paid = target.price), offer, target.id, GameMoney(0))).change)
    }

    @Test fun `수익 탁송 변경은 돈이 오가지 않고 보상은 새 의뢰 기준이다`() {
        val t = terms(DeliveryOffer.Income::class.java)
        val offer = changeOffer(t, traveled = 50_000, remaining = 20_000)
        val target = offer.proposed.offers.first()
        val result = ok(change.change(t, offer, target.id, GameMoney(0)))
        assertNull(result.change)
        assertEquals(target, result.terms.active.offer)
        assertEquals(GameMoney(0), result.terms.paid)
        val prices = content.vehicles.associate { it.vehicleId to it.price }
        assertEquals(rules.incomeReward(prices.getValue(target.vehicleId), rules.bandOf(70_000)), (target as DeliveryOffer.Income).reward)
    }

    @Test fun `잔액이 차액보다 1 부족하면 바꾸지 않는다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val target = offer.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().first()
        val held = t.copy(paid = GameMoney(target.price.units - 500))
        assertEquals(ContractError.INSUFFICIENT_FUNDS, failure(change.change(held, offer, target.id, GameMoney(499))))
        assertNotNull(ok(change.change(held, offer, target.id, GameMoney(500))).change)
    }

    @Test fun `변경은 한 번뿐이고 두 번째는 충돌이다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val target = offer.proposed.offers.first()
        val once = ok(change.change(t.copy(paid = (target as DeliveryOffer.Purchase).price), offer, target.id, GameMoney(0))).terms
        val again = changeOffer(once, seed = 9)
        assertEquals(ContractError.STATE_CONFLICT, failure(change.change(once, again, again.proposed.offers.first().id, GameMoney(10_000))))
    }

    @Test fun `다른 의뢰의 변경 목록이나 목록에 없는 의뢰는 거절한다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val other = offer.copy(requestId = RequestId("r2"))
        assertEquals(ContractError.STATE_CONFLICT, failure(change.change(t, other, offer.proposed.offers.first().id, GameMoney(10_000))))
        assertEquals(ContractError.INVALID_INPUT, failure(change.change(t, offer, DeliveryOfferId("none"), GameMoney(10_000))))
        val forged = offer.copy(proposed = offer.proposed.copy(offers = listOf((offer.proposed.offers.first() as DeliveryOffer.Purchase).copy(price = GameMoney(1)))))
        assertEquals(ContractError.INVALID_INPUT, failure(change.change(t, forged, offer.proposed.offers.first().id, GameMoney(10_000))))
    }

    @Test fun `유형이 다른 의뢰로는 바꿀 수 없다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val incomeList = changeOffer(terms(DeliveryOffer.Income::class.java))
        val mixed = incomeList.copy(requestId = t.active.requestId)
        assertEquals(ContractError.INVALID_INPUT, failure(change.change(t, mixed, mixed.proposed.offers.first().id, GameMoney(10_000))))
    }

    @Test fun `변경 후보가 없으면 바꿀 수 없다`() {
        val t = terms(DeliveryOffer.Income::class.java)
        val none = ok(proposal.proposeChange(
            t.active, Observation.Available(ObservedDistance(100, source)), Observation.Available(EstimatedRouteDistance(100, source)),
            DestinationKey("b"), content, 1, today, Random(1),
        ))
        assertTrue(none.proposed.offers.isEmpty())
        assertEquals(ContractError.INVALID_INPUT, failure(change.change(t, none, DeliveryOfferId("change-0"), GameMoney(0))))
    }

    @Test fun `구매 포기는 보류 대금에서 10퍼센트를 내림 공제해 환급하고 차량은 없다`() {
        val t = terms(DeliveryOffer.Purchase::class.java).let { it.copy(paid = GameMoney(3_005)) }
        val abandoned = ok(change.abandon(t))
        val refund = requireNotNull(abandoned.change)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(3_005 - 300)), refund.money) // 수수료 300.5 → 300
        assertNull(refund.vehicle)
        assertEquals(RequestId("delivery/r1/settle"), refund.requestId)
    }

    @Test fun `변경 뒤에도 포기할 수 있고 환급 기준은 바뀐 대금이다`() {
        val t = terms(DeliveryOffer.Purchase::class.java)
        val offer = changeOffer(t)
        val target = offer.proposed.offers.first() as DeliveryOffer.Purchase
        val changed = ok(change.change(t.copy(paid = target.price), offer, target.id, GameMoney(0))).terms
        val refund = ok(change.abandon(changed)).change?.money?.amount?.units
        assertEquals(target.price.units - target.price.units * 10 / 100, refund)
    }

    @Test fun `수익 포기는 벌금이 없다`() {
        assertNull(ok(change.abandon(terms(DeliveryOffer.Income::class.java))).change)
    }

    @Test fun `수수료 0과 100 경계`() {
        val t = terms(DeliveryOffer.Purchase::class.java).copy(paid = GameMoney(1_000))
        assertEquals(GameMoney(1_000), ok(DeliveryChange(changeRules.copy(cancelFeePercent = 0)).abandon(t)).change?.money?.amount)
        assertEquals(GameMoney(0), ok(DeliveryChange(changeRules.copy(cancelFeePercent = 100)).abandon(t)).change?.money?.amount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `수익 탁송에 결제액이 있으면 상태를 거절한다`() {
        val t = terms(DeliveryOffer.Income::class.java)
        t.copy(paid = GameMoney(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `범위 밖 수수료는 규칙을 거절한다`() {
        changeRules.copy(cancelFeePercent = 101)
    }
}
