package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeliverySettlementTest {
    private val rules = SettlementFixtures.trialRules
    private val settle = DeliverySettlement(rules)
    private val source = InputSource.SIMULATION
    private val day = 24 * 60 * 60 * 1000L
    private val start = EpochMillis(1_000_000)
    private val route = RouteId("route-1")
    private val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), VehicleId("hyundai_casper"), GameMoney(400))
    private val purchase = DeliveryOffer.Purchase(DeliveryOfferId("offer-1"), VehicleId("hyundai_sonata"), Rarity.RARE, GameMoney(3_001))
    private val list = DeliveryList(1, source, LocalDate.of(2026, 11, 7), ContentVersion("c"), RuleVersion("delivery-trial-v0"), listOf(income, purchase), 2)
    private val proposed = DeliveryProposed(DestinationKey("d"), EstimatedRouteDistance(20_000, source), list, list.offers)

    private fun progress(offer: DeliveryOffer, id: String = "r1") =
        DeliveryInProgress(ActiveDelivery(RequestId(id), proposed, offer), route, start)

    private fun arrival(at: Long = start.value + 60_000, r: RouteId = route, s: InputSource = source) =
        Observation.Available(DeliveryArrivalEvidence(r, s, EpochMillis(at)))

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value

    private fun resolve(offer: DeliveryOffer, evidence: Observation<DeliveryArrivalEvidence>, now: Long = start.value + 120_000, slots: Int = 1) =
        ok(settle.resolve(progress(offer), evidence, EpochMillis(now), slots))

    @Test fun `수익 탁송 도착은 보상을 탁송 거래로 지급한다`() {
        val outcome = resolve(income, arrival()) as DeliveryOutcome.IncomeCompleted
        val change = outcome.change
        assertEquals(RequestId("delivery/r1/settle"), change.requestId)
        assertEquals(TransactionKind.DELIVERY, change.kind)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(400)), change.money)
        assertNull(change.vehicle)
        assertEquals(source, change.source)
    }

    @Test fun `구매 탁송 도착은 결제액과 희귀도를 보존한 차량을 지급하고 돈은 움직이지 않는다`() {
        val change = (resolve(purchase, arrival()) as DeliveryOutcome.VehicleDelivered).change
        assertNull(change.money)
        val vehicle = (change.vehicle as VehicleWrite.Add).vehicle
        assertEquals(OwnedVehicleId("delivery/r1"), vehicle.ownedVehicleId)
        assertEquals(VehicleId("hyundai_sonata"), vehicle.vehicleId)
        assertEquals(Rarity.RARE, vehicle.rarity)
        assertEquals(GameMoney(3_001), vehicle.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.PURCHASE_DELIVERY, vehicle.acquisitionSource)
        assertEquals(1, vehicle.level)
        assertEquals(Revision(0), vehicle.revision)
    }

    @Test fun `같은 입력은 같은 결과라 원장에서 한 번만 확정된다`() {
        assertEquals(resolve(purchase, arrival()), resolve(purchase, arrival()))
        assertEquals(resolve(income, arrival()), resolve(income, arrival()))
    }

    @Test fun `증거 누락과 다른 경로·다른 출처의 증거는 도착이 아니다`() {
        listOf(
            Observation.Missing(MissingReason.NO_DATA),
            Observation.Missing(MissingReason.PERMISSION_DENIED),
            arrival(r = RouteId("route-old")),
            arrival(s = InputSource.VEHICLE),
        ).forEach {
            assertEquals(DeliveryOutcome.AwaitingEvidence(EpochMillis(start.value + day)), resolve(purchase, it))
            assertEquals(DeliveryOutcome.AwaitingEvidence(EpochMillis(start.value + day)), resolve(income, it))
        }
    }

    @Test fun `의뢰 시작 전에 받은 증거와 기한 이후에 받은 증거는 도착이 아니다`() {
        assertTrue(resolve(purchase, arrival(at = start.value - 1)) is DeliveryOutcome.AwaitingEvidence)
        assertTrue(resolve(purchase, arrival(at = start.value)) is DeliveryOutcome.VehicleDelivered)
        assertTrue(resolve(purchase, arrival(at = start.value + day - 1), now = start.value + day) is DeliveryOutcome.VehicleDelivered)
        assertTrue(resolve(purchase, arrival(at = start.value + day), now = start.value + day) is DeliveryOutcome.PurchaseRefunded)
    }

    @Test fun `기한 전에는 기다리고 기한이 지나면 구매는 전액 환급, 수익은 벌금 없이 끝난다`() {
        val missing = Observation.Missing(MissingReason.NO_DATA)
        assertTrue(resolve(purchase, missing, now = start.value + day - 1) is DeliveryOutcome.AwaitingEvidence)
        val refund = (resolve(purchase, missing, now = start.value + day) as DeliveryOutcome.PurchaseRefunded).change
        assertEquals(RequestId("delivery/r1/settle"), refund.requestId)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(3_001)), refund.money)
        assertNull(refund.vehicle)
        assertEquals(DeliveryOutcome.IncomeExpired, resolve(income, missing, now = start.value + day))
    }

    @Test fun `완료와 환급은 같은 요청 ID라 원장에서 둘 다 확정될 수 없다`() {
        val delivered = (resolve(purchase, arrival()) as DeliveryOutcome.VehicleDelivered).change
        val refunded = (resolve(purchase, Observation.Missing(MissingReason.NO_DATA), now = start.value + day) as DeliveryOutcome.PurchaseRefunded).change
        assertEquals(delivered.requestId, refunded.requestId)
        assertNotEquals(delivered, refunded)
    }

    @Test fun `차고가 가득 차면 돈과 차량을 바꾸지 않고 도착 시각부터 임시 보관한다`() {
        val held = (resolve(purchase, arrival(at = start.value + 60_000), slots = 0) as DeliveryOutcome.VehicleHeld).held
        assertEquals(EpochMillis(start.value + 60_000), held.heldAt)
        assertEquals(EpochMillis(start.value + 60_000 + day), held.deadline)
        assertEquals(GameMoney(3_001), held.price)
        assertEquals(Rarity.RARE, held.vehicle.rarity)
        assertEquals(RequestId("r1"), held.requestId)
    }

    @Test fun `수익 탁송은 차고가 가득 차도 바로 완료된다`() {
        assertTrue(resolve(income, arrival(), slots = 0) is DeliveryOutcome.IncomeCompleted)
    }

    @Test fun `보관 차량은 칸이 생기면 받고 없으면 계속 보관한다`() {
        val held = (resolve(purchase, arrival(), slots = 0) as DeliveryOutcome.VehicleHeld).held
        assertEquals(HoldOutcome.StillHeld(held), ok(settle.resolveHold(held, EpochMillis(held.deadline.value - 1), 0)))
        val claimed = (ok(settle.resolveHold(held, EpochMillis(held.deadline.value - 1), 1)) as HoldOutcome.Claimed).change
        assertEquals(RequestId("delivery/r1/hold"), claimed.requestId)
        assertEquals(held.vehicle, (claimed.vehicle as VehicleWrite.Add).vehicle)
        assertNull(claimed.money)
    }

    @Test fun `보관 기한이 지나면 수수료를 내림 공제해 환급하고 차량은 주지 않는다`() {
        val held = (resolve(purchase, arrival(), slots = 0) as DeliveryOutcome.VehicleHeld).held
        val refund = (ok(settle.resolveHold(held, held.deadline, 5)) as HoldOutcome.Refunded).change
        assertEquals(RequestId("delivery/r1/hold"), refund.requestId)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(3_001 - 150)), refund.money) // 수수료 150.05 → 150
        assertNull(refund.vehicle)
    }

    @Test fun `수수료 0과 100 경계`() {
        val held = (resolve(purchase, arrival(), slots = 0) as DeliveryOutcome.VehicleHeld).held
        fun refundWith(percent: Int) = ((ok(DeliverySettlement(rules.copy(holdRefundFeePercent = percent)).resolveHold(held, held.deadline, 0))
            as HoldOutcome.Refunded).change.money)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(3_001)), refundWith(0))
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(0)), refundWith(100))
    }

    @Test fun `음수 빈 칸과 기한 계산 넘침은 거절한다`() {
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), settle.resolve(progress(income), arrival(), EpochMillis(0), -1))
        val late = DeliveryInProgress(ActiveDelivery(RequestId("r1"), proposed, income), route, EpochMillis(Long.MAX_VALUE - 1))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), settle.resolve(late, arrival(), EpochMillis(0), 1))
        val held = (resolve(purchase, arrival(), slots = 0) as DeliveryOutcome.VehicleHeld).held
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), settle.resolveHold(held, EpochMillis(0), -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `0 이하 기간과 범위 밖 수수료는 규칙을 거절한다`() {
        rules.copy(holdRefundFeePercent = 101)
    }
}
