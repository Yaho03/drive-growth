package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.FacilityCommand
import io.drivegrowth.app.host.facility.FacilityReceipt
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.FacilityOffer
import org.junit.Assert.*
import org.junit.Test

class FacilityInstallationMotionTest {
    private val identity = GameIdentity(ProfileId("profile"), InputSource.SIMULATION)

    @Test fun `세 시설의 새 원정산은 각 한 번만 재생한다`() {
        val motion = FacilityInstallationMotion()
        for (facility in Facility.entries) {
            val (request, receipt) = purchase(facility)
            assertNull(motion.confirm(request, receipt, visible = true))
            assertTrue(motion.submit(identity, request, visible = true))
            assertEquals(facility, motion.confirm(request, receipt, visible = true))
            assertNull(motion.confirm(request, receipt, visible = true))
            motion.submit(identity, request, visible = true)
            assertNull(motion.confirm(request, receipt, visible = true))
        }
    }

    @Test fun `이탈과 실패 및 모션 비활성 뒤 늦은 응답은 재생하지 않는다`() {
        val (request, receipt) = purchase(Facility.INSURANCE)
        val motion = FacilityInstallationMotion()
        assertFalse(motion.submit(identity, request, visible = false))
        assertNull(motion.confirm(request, receipt, visible = true))
        motion.submit(identity, request, visible = true)
        assertNull(motion.confirm(request, receipt, visible = false))
        assertNull(motion.confirm(request, receipt, visible = true))
        motion.submit(identity, request, visible = true)
        motion.clear()
        assertNull(motion.confirm(request, receipt, visible = true))
        motion.submit(identity, request, visible = true)
        motion.complete(request)
        assertNull(motion.confirm(request, receipt, visible = true))
    }

    @Test fun `타 프로필과 다른 시설 및 다른 요청의 원정산을 받지 않는다`() {
        val (request, original) = purchase(Facility.CAR_WASH)
        val (_, fuel) = purchase(Facility.FUEL_STATION)
        val mismatches = listOf(fuel, original.copy(command = original.command.copy(identity = identity.copy(profileId = ProfileId("other")))),
            original.copy(settlement = original.settlement.copy(transaction = original.settlement.transaction.copy(requestId = RequestId("foreign")))))
        for (receipt in mismatches) {
            val motion = FacilityInstallationMotion()
            assertTrue(motion.submit(identity, request, visible = true))
            assertNull(motion.confirm(request, receipt, visible = true))
        }
    }

    @Test fun `같은 효과를 다시 붙여도 시간은 초기화되지 않고 오래된 예약은 만료된다`() {
        val effect = InstalledFacilityEffect(Facility.FUEL_STATION, Revision(1), confirmedAt = 100L)
        assertTrue(effect.canDraw(300L))
        assertEquals(0f, effect.progress(300L))
        assertEquals(.5f, effect.progress(1_100L))
        assertFalse(effect.canDraw(1_900L))
        assertFalse(InstalledFacilityEffect(Facility.FUEL_STATION, Revision(1), 100L).canDraw(1_900L))
    }

    private fun purchase(facility: Facility): Pair<ShopRequest, FacilityReceipt.Purchased> {
        val id = RequestId("purchase-$facility")
        val offer = FacilityOffer(facility, RuleVersion("facility-rules"), true, GameMoney(100))
        val option = ShopOption("facility-$facility", facility.name, "조건", "효과", ShopAvailability.AVAILABLE,
            ShopCommand.BuyFacility(offer), offer.price, offer.ruleVersion, offer.trial)
        val request = ShopRequest(id, ShopSelection(Revision(1), option), "attempt")
        val settlement = Settlement(SettlementId("settlement-$facility"), Transaction(TransactionId("transaction-$facility"),
            id, TransactionKind.FACILITY, identity.source, offer.ruleVersion, MoneyChange(MoneyDirection.DEBIT, offer.price)),
            GameMoney(300), null, EpochMillis(100))
        return request to FacilityReceipt.Purchased(FacilityCommand.Buy(identity, id, ContentVersion("content"), offer), settlement)
    }
}
