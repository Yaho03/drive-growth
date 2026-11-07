package io.drivegrowth.core.delivery

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EstimatedRouteDistance
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.TransactionKind
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class DeliveryTest {
    private val rules = DeliveryFixtures.trialRules
    private val proposal = DeliveryProposal(rules)
    private val content = GameContentFixtures.loaded() // Lv1 해금: 캐스퍼 8000·아반떼 10000·쏘나타 15000·그랜저 25000
    private val destination = DestinationKey("destination-a")
    private val today = LocalDate.of(2026, 11, 7)

    private fun route(meters: Long, source: InputSource = InputSource.SIMULATION) =
        Observation.Available(EstimatedRouteDistance(meters, source))

    private fun proposed(
        meters: Long = 20_000,
        saved: List<DeliveryList> = emptyList(),
        day: LocalDate = today,
        to: DestinationKey = destination,
        seed: Int = 33,
        source: InputSource = InputSource.SIMULATION,
    ): DeliveryProposed = success(proposal.propose(saved, to, route(meters, source), content, 1, day, Random(seed)))

    private fun list(meters: Long = 20_000, seed: Int = 33): DeliveryList = proposed(meters, seed = seed).list

    private fun replaced(list: DeliveryList, offer: DeliveryOffer, seed: Int = 7): DeliveryList =
        success(proposal.replaceCompleted(list, offer.id, content, 1, Random(seed)))

    private fun <T> success(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun failure(result: ContractResult<*>): ContractError = (result as ContractResult.Failure).error
    private fun List<DeliveryOffer>.incomes() = filterIsInstance<DeliveryOffer.Income>()
    private fun List<DeliveryOffer>.purchases() = filterIsInstance<DeliveryOffer.Purchase>()

    @Test fun `같은 seed는 같은 목록을 만든다`() {
        assertEquals(list(), list())
    }

    @Test fun `수익 2개와 구매 3개를 서로 다른 ID로 제시한다`() {
        val offers = list().offers
        assertEquals(2, offers.incomes().size)
        assertEquals(3, offers.purchases().size)
        assertEquals(5, offers.map { it.id }.toSet().size)
        assertEquals(2, offers.incomes().map { it.vehicleId }.toSet().size)
        assertEquals(3, offers.purchases().map { it.vehicleId }.toSet().size)
    }

    @Test fun `저장 목록은 다른 난수로도 재추첨하지 않는다`() {
        val saved = list(seed = 1)
        assertTrue((2..50).all { proposed(saved = listOf(saved), seed = it).list === saved })
    }

    @Test fun `같은 구간이면 목적지와 거리가 달라도 같은 목록이다`() {
        val saved = list(meters = 20_000, seed = 1)
        val other = proposed(meters = 49_000, saved = listOf(saved), to = DestinationKey("destination-b"), seed = 2)
        assertSame(saved, other.list)
        assertEquals(DestinationKey("destination-b"), other.destination)
        assertEquals(49_000L, other.estimated.meters)
    }

    @Test fun `구간마다 따로 저장 목록을 찾아 하루 최대 구간 수만큼만 만든다`() {
        val distances = listOf(5_000L, 20_000L, 60_000L)
        var saved = emptyList<DeliveryList>()
        distances.forEachIndexed { i, meters -> saved = saved + proposed(meters, saved = saved, seed = i).list }
        assertEquals(listOf(0, 1, 2), saved.map { it.band })
        (100..120).forEach { seed ->
            distances.forEachIndexed { i, meters ->
                assertSame(saved[i], proposed(meters, saved = saved, seed = seed).list)
            }
        }
    }

    @Test fun `다음 날이나 입력 출처가 달라지면 새로 만든다`() {
        val saved = listOf(list(seed = 1))
        assertEquals(today.plusDays(1), proposed(saved = saved, day = today.plusDays(1), seed = 2).list.createdOn)
        assertEquals(InputSource.VEHICLE, proposed(saved = saved, source = InputSource.VEHICLE, seed = 2).list.source)
    }

    @Test fun `규칙 버전이 바뀌면 저장 목록을 쓰지 않는다`() {
        val saved = list(seed = 1)
        val changed = DeliveryProposal(rules.copy(ruleVersion = RuleVersion("delivery-trial-v1")))
        val made = success(changed.propose(listOf(saved), destination, route(20_000), content, 1, today, Random(2)))
        assertNotSame(saved, made.list)
        assertEquals(RuleVersion("delivery-trial-v1"), made.list.ruleVersion)
    }

    @Test fun `거리 구간 경계는 상한을 포함한다`() {
        assertEquals(0, list(meters = 1).band)
        assertEquals(0, list(meters = 10_000).band)
        assertEquals(1, list(meters = 10_001).band)
        assertEquals(1, list(meters = 50_000).band)
        assertEquals(2, list(meters = 50_001).band)
    }

    @Test fun `최소 거리 미만은 같은 목록에서 수익 의뢰만 가린다`() {
        val saved = list(meters = 5_000, seed = 1)
        val short = proposed(meters = 999, saved = listOf(saved))
        assertSame(saved, short.list)
        assertTrue(short.offers.incomes().isEmpty())
        assertEquals(saved.offers.purchases(), short.offers.purchases())
        assertEquals(saved.offers, proposed(meters = 1_000, saved = listOf(saved)).offers)
    }

    @Test fun `최소 거리 미만에서 먼저 만든 목록도 수익 의뢰를 가지고 있다`() {
        val made = proposed(meters = 999, seed = 1)
        assertEquals(2, made.list.offers.incomes().size)
        assertEquals(made.list.offers, proposed(meters = 1_000, saved = listOf(made.list)).offers)
    }

    @Test fun `최소 거리 미만에서는 가린 수익 의뢰를 선택할 수 없다`() {
        val short = proposed(meters = 999)
        val hidden = short.list.offers.incomes().first()
        assertEquals(ContractError.INVALID_INPUT, failure(DeliverySelection.select(short, hidden.id, RequestId("r1"), GameMoney(0), null)))
    }

    @Test fun `최소 거리는 규칙 입력이라 0이면 가리지 않는다`() {
        val open = DeliveryProposal(rules.copy(incomeMinMeters = 0))
        val made = success(open.propose(emptyList(), destination, route(1), content, 1, today, Random(1)))
        assertEquals(2, made.offers.incomes().size)
    }

    @Test fun `완료한 의뢰 자리만 같은 유형의 새 의뢰로 채운다`() {
        val before = list(seed = 1)
        val done = before.offers.purchases().first()
        val after = replaced(before, done)
        assertEquals(before.offers.size, after.offers.size)
        assertEquals(before.offers.filter { it != done }, after.offers.filter { it.id in before.offers.map { o -> o.id } })
        assertEquals(1, after.offers.count { it.id !in before.offers.map { o -> o.id } })
        assertEquals(3, after.offers.purchases().size)
        assertEquals(2, after.offers.incomes().size)
        assertFalse(after.offers.any { it.id == done.id })
    }

    @Test fun `수익 의뢰를 완료하면 수익 자리에 구간 배율 보상으로 채운다`() {
        val prices = content.vehicles.associate { it.vehicleId to it.price }
        val before = list(meters = 60_000, seed = 1)
        val done = before.offers.incomes().first()
        val fresh = replaced(before, done).offers.incomes().first { it.id !in before.offers.map { o -> o.id } }
        assertEquals(rules.incomeReward(prices.getValue(fresh.vehicleId), rules.bandOf(60_000)), fresh.reward)
    }

    @Test fun `같은 완료를 반복해도 다시 뽑지 않는다`() {
        val before = list(seed = 1)
        val done = before.offers.first()
        val after = replaced(before, done, seed = 7)
        assertTrue((8..30).all { replaced(after, done, seed = it) === after })
    }

    @Test fun `채운 의뢰는 같은 유형 안에서 차종이 겹치지 않고 ID를 다시 쓰지 않는다`() {
        (1..40).forEach { seed ->
            var current = list(seed = seed)
            val seen = current.offers.map { it.id }.toMutableSet()
            repeat(6) { round ->
                current = replaced(current, current.offers.purchases().first(), seed = seed * 100 + round)
                val fresh = current.offers.filter { it.id !in seen }
                assertEquals(1, fresh.size)
                seen += fresh.single().id
                assertEquals(3, current.offers.purchases().map { it.vehicleId }.toSet().size)
            }
        }
    }

    @Test fun `겹치지 않는 후보가 없으면 그 자리를 비운다`() {
        val full = DeliveryProposal(rules.copy(incomeOfferCount = 0, purchaseOfferCount = 4))
        val before = success(full.propose(emptyList(), destination, route(20_000), content, 1, today, Random(1))).list
        val after = success(full.replaceCompleted(before, before.offers.first().id, content, 1, Random(2)))
        assertEquals(4, after.offers.size) // 완료한 차종 자신은 다시 후보가 된다.
        val shrunk = content.copy(vehicles = content.vehicles.filter { v -> v.vehicleId in before.offers.drop(1).map { it.vehicleId } })
        val none = success(full.replaceCompleted(before, before.offers.first().id, shrunk, 1, Random(2)))
        assertEquals(3, none.offers.size)
    }

    @Test fun `규칙 버전이 다른 목록은 완료해도 바꾸지 않고 잘못된 가게 레벨은 거절한다`() {
        val before = list(seed = 1)
        val changed = DeliveryProposal(rules.copy(ruleVersion = RuleVersion("delivery-trial-v1")))
        assertSame(before, success(changed.replaceCompleted(before, before.offers.first().id, content, 1, Random(2))))
        assertEquals(ContractError.INVALID_INPUT, failure(proposal.replaceCompleted(before, before.offers.first().id, content, 0, Random(2))))
    }

    @Test fun `포기하면 목록을 그대로 다시 쓰고 같은 의뢰를 고를 수 있다`() {
        val first = proposed(seed = 1)
        val offer = first.offers.incomes().first()
        success(DeliverySelection.select(first, offer.id, RequestId("r1"), GameMoney(0), null))
        // 포기는 활성 의뢰만 지우고 목록은 건드리지 않는다.
        val again = proposed(saved = listOf(first.list), seed = 2)
        assertSame(first.list, again.list)
        assertEquals(offer, success(DeliverySelection.select(again, offer.id, RequestId("r2"), GameMoney(0), null)).active.offer)
    }

    @Test fun `수익 보상은 차종 가격·10퍼센트·거리 배율이고 희귀도와 무관하다`() {
        val casper = GameMoney(8_000)
        assertEquals(GameMoney(400), rules.incomeReward(casper, rules.bandOf(5_000)))
        assertEquals(GameMoney(800), rules.incomeReward(casper, rules.bandOf(30_000)))
        assertEquals(GameMoney(1_200), rules.incomeReward(casper, rules.bandOf(60_000)))
    }

    @Test fun `구매가는 차종 가격과 희귀도 배율로 계산한다`() {
        val sonata = GameMoney(15_000)
        assertEquals(GameMoney(15_000), rules.purchasePrice(sonata, Rarity.COMMON))
        assertEquals(GameMoney(18_000), rules.purchasePrice(sonata, Rarity.ADVANCED))
        assertEquals(GameMoney(22_500), rules.purchasePrice(sonata, Rarity.RARE))
        assertEquals(GameMoney(30_000), rules.purchasePrice(sonata, Rarity.LEGEND))
    }

    @Test fun `목록의 금액은 해당 차종 가격 산식과 일치한다`() {
        val prices = content.vehicles.associate { it.vehicleId to it.price }
        val made = list(meters = 20_000)
        made.offers.incomes().forEach { assertEquals(rules.incomeReward(prices.getValue(it.vehicleId), rules.bandOf(20_000)), it.reward) }
        made.offers.purchases().forEach { assertEquals(rules.purchasePrice(prices.getValue(it.vehicleId), it.rarity), it.price) }
    }

    @Test fun `소수 금액은 규칙의 반올림을 따른다`() {
        val price = GameMoney(1_001)
        val band = rules.bandOf(5_000) // 1001 × 10% × 0.5 = 50.05
        assertEquals(GameMoney(50), rules.incomeReward(price, band))
        val half = GameMoney(1_010) // 1010 × 10% × 0.5 = 50.5
        assertEquals(GameMoney(50), rules.incomeReward(half, band))
        assertEquals(GameMoney(51), rules.copy(rounding = DeliveryRounding.HALF_UP).incomeReward(half, band))
    }

    @Test fun `해금된 차종만 후보가 되고 해금 차량이 적으면 있는 만큼만 제시한다`() {
        val made = (1..40).map { list(seed = it) }.flatMap { it.offers }.map { it.vehicleId.value }.toSet()
        assertFalse("genesis_g90" in made)
        val few = DeliveryProposal(rules.copy(incomeOfferCount = 9, purchaseOfferCount = 9))
        val listed = success(few.propose(emptyList(), destination, route(20_000), content, 1, today, Random(1)))
        assertEquals(4, listed.offers.incomes().size)
        assertEquals(4, listed.offers.purchases().size)
    }

    @Test fun `해금 차량이 없으면 오류가 아니라 빈 목록이다`() {
        val empty = content.copy(vehicles = emptyList())
        val made = success(proposal.propose(emptyList(), destination, route(20_000), empty, 1, today, Random(1)))
        assertTrue(made.offers.isEmpty())
    }

    @Test fun `구간 가중치대로 희귀도가 나온다`() {
        val only = rules.copy(bands = listOf(
            DeliveryBand(null, mapOf(Rarity.LEGEND to 1), incomePercent = 100),
        ))
        val made = success(DeliveryProposal(only).propose(emptyList(), destination, route(20_000), content, 1, today, Random(5)))
        assertTrue(made.offers.purchases().all { it.rarity == Rarity.LEGEND })
    }

    @Test fun `구간별 희귀도는 가중치 비율에 가깝게 나온다`() {
        listOf(5_000L, 20_000L, 60_000L).forEach { meters ->
            val weights = rules.bandOf(meters).rarityWeights
            val random = Random(99)
            val rarities = (1..20_000).flatMap {
                success(proposal.propose(emptyList(), destination, route(meters), content, 1, today, random)).offers.purchases()
            }.map { it.rarity }
            Rarity.entries.forEach { rarity ->
                val expected = weights.getValue(rarity) / 1000.0
                val actual = rarities.count { it == rarity }.toDouble() / rarities.size
                assertEquals("$meters m $rarity", expected, actual, 0.01)
            }
        }
    }

    @Test fun `거리를 못 받으면 사유에 맞는 오류로 거절한다`() {
        fun missing(reason: MissingReason) =
            failure(proposal.propose(emptyList(), destination, Observation.Missing(reason), content, 1, today, Random(1)))
        assertEquals(ContractError.DATA_UNAVAILABLE, missing(MissingReason.NO_DATA))
        assertEquals(ContractError.DATA_UNAVAILABLE, missing(MissingReason.UNSUPPORTED))
        assertEquals(ContractError.DATA_UNAVAILABLE, missing(MissingReason.STALE))
        assertEquals(ContractError.PERMISSION_DENIED, missing(MissingReason.PERMISSION_DENIED))
    }

    @Test fun `거리 0과 잘못된 가게 레벨은 거절한다`() {
        assertEquals(ContractError.INVALID_INPUT, failure(proposal.propose(emptyList(), destination, route(0), content, 1, today, Random(1))))
        assertEquals(ContractError.INVALID_INPUT, failure(proposal.propose(emptyList(), destination, route(100), content, 0, today, Random(1))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `음수 거리는 입력 타입에서 거절한다`() {
        EstimatedRouteDistance(-1, InputSource.SIMULATION)
    }

    @Test fun `구매 선택은 가격만큼 결제하고 차량을 지급하지 않는다`() {
        val made = proposed()
        val offer = made.offers.purchases().first()
        val selected = success(DeliverySelection.select(made, offer.id, RequestId("r1"), offer.price, null))
        val payment = requireNotNull(selected.payment)
        assertEquals(RequestId("r1"), payment.requestId)
        assertEquals(TransactionKind.DELIVERY, payment.kind)
        assertEquals(InputSource.SIMULATION, payment.source)
        assertEquals(made.list.ruleVersion, payment.ruleVersion)
        assertEquals(MoneyDirection.DEBIT, payment.money?.direction)
        assertEquals(offer.price, payment.money?.amount)
        assertNull(payment.vehicle)
        assertEquals(offer, selected.active.offer)
        assertEquals(destination, selected.active.proposed.destination)
    }

    @Test fun `수익 선택은 돈이 오가지 않고 잔액이 0이어도 된다`() {
        val made = proposed()
        val offer = made.offers.incomes().first()
        val selected = success(DeliverySelection.select(made, offer.id, RequestId("r1"), GameMoney(0), null))
        assertNull(selected.payment)
        assertEquals(offer, selected.active.offer)
    }

    @Test fun `잔액이 1 부족하면 거절한다`() {
        val made = proposed()
        val offer = made.offers.purchases().first()
        val short = GameMoney(offer.price.units - 1)
        assertEquals(ContractError.INSUFFICIENT_FUNDS, failure(DeliverySelection.select(made, offer.id, RequestId("r1"), short, null)))
    }

    @Test fun `목록에 없는 의뢰는 거절한다`() {
        assertEquals(ContractError.INVALID_INPUT, failure(DeliverySelection.select(proposed(), DeliveryOfferId("none"), RequestId("r1"), GameMoney(0), null)))
    }

    @Test fun `활성 의뢰가 있으면 다른 요청을 거절한다`() {
        val made = proposed()
        val offer = made.offers.incomes().first()
        val active = success(DeliverySelection.select(made, offer.id, RequestId("r1"), GameMoney(0), null)).active
        assertEquals(ContractError.CAPACITY_EXCEEDED, failure(DeliverySelection.select(made, offer.id, RequestId("r2"), GameMoney(0), active)))
    }

    @Test fun `같은 요청을 반복하면 같은 결과이고 잔액이 줄어도 다시 거절하지 않는다`() {
        val made = proposed()
        val offer = made.offers.purchases().first()
        val first = success(DeliverySelection.select(made, offer.id, RequestId("r1"), offer.price, null))
        val again = success(DeliverySelection.select(made, offer.id, RequestId("r1"), GameMoney(0), first.active))
        assertEquals(first, again)
    }

    @Test fun `같은 요청 ID로 다른 의뢰를 고르면 충돌이다`() {
        val made = proposed()
        val incomes = made.offers.incomes()
        val active = success(DeliverySelection.select(made, incomes[0].id, RequestId("r1"), GameMoney(0), null)).active
        assertEquals(ContractError.STATE_CONFLICT, failure(DeliverySelection.select(made, incomes[1].id, RequestId("r1"), GameMoney(0), active)))
    }

    @Test fun `같은 요청 ID라도 다른 목적지로 다시 보내면 충돌이다`() {
        val made = proposed()
        val offer = made.offers.incomes().first()
        val active = success(DeliverySelection.select(made, offer.id, RequestId("r1"), GameMoney(0), null)).active
        val other = proposed(saved = listOf(made.list), to = DestinationKey("destination-b"))
        assertEquals(ContractError.STATE_CONFLICT, failure(DeliverySelection.select(other, offer.id, RequestId("r1"), GameMoney(0), active)))
    }

    @Test fun `시험 규칙의 구간 가중치는 천분율 합 1000이다`() {
        rules.bands.forEach { assertEquals(1000, it.rarityWeights.values.sum()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `마지막 구간에 상한이 있으면 규칙을 거절한다`() {
        rules.copy(bands = listOf(DeliveryBand(10_000, mapOf(Rarity.COMMON to 1), 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `구간 상한이 오름차순이 아니면 규칙을 거절한다`() {
        val weights = mapOf(Rarity.COMMON to 1)
        rules.copy(bands = listOf(DeliveryBand(50_000, weights, 100), DeliveryBand(10_000, weights, 100), DeliveryBand(null, weights, 100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `희귀도 가격 배율이 빠지면 규칙을 거절한다`() {
        rules.copy(rarityPricePercent = mapOf(Rarity.COMMON to 100))
    }
}
