package io.drivegrowth.app.ui.onboarding

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class OpeningGiftPresentationTest {
    private val owner = GameIdentity(ProfileId("gift-profile"), InputSource.SIMULATION)
    private val cause = FirstRentalGiftCause(RequestId("rental"), SettlementId("rental-result"))
    private val command = FirstRentalGiftCommand(RequestId("gift"), owner.profileId, owner.source,
        ContentVersion("content-v1"), RuleVersion("rules-v1"), cause, Revision(2))
    private val receipt = FirstRentalGiftSettlement(command, SettlementId("gift-result"),
        Transaction(TransactionId("gift-transaction"), command.requestId, TransactionKind.GARAGE,
            owner.source, command.ruleVersion, null), GameMoney(0), GachaProgress(Revision(3), 0, 1),
        FirstRentalGiftMarker(owner.profileId, command.requestId, cause), EpochMillis(1))

    @Test fun waitsForAllowedMatchingProfileAndConsumesOnlyOnce() {
        val presentation = OpeningGiftPresentation()
        presentation.publish(receipt)
        assertNull(presentation.available(owner, false))
        val foreign = owner.copy(profileId = ProfileId("other"))
        assertNull(presentation.available(foreign, true))
        assertNull(presentation.available(owner.copy(source = InputSource.VEHICLE), true))
        assertFalse(presentation.consume(foreign, receipt))
        assertFalse(presentation.consume(owner, receipt.copy(settlementId = SettlementId("different"))))
        assertEquals(receipt, presentation.available(owner, true))
        assertTrue(presentation.consume(owner, receipt))
        presentation.publish(receipt)
        assertNull(presentation.available(owner, true))
        assertFalse(presentation.consume(owner, receipt))
    }

    @Test fun loadingKeepsOnlyTheSameAttachedOwnerAndNeverConsumesAnotherGift() {
        val presentation = OpeningGiftPresentation()
        presentation.publish(receipt)
        val other = owner.copy(profileId = ProfileId("other"))
        val otherSource = owner.copy(source = InputSource.VEHICLE)
        assertTrue(keepsOpeningGiftDuringLoad(owner, owner, attached = true))
        assertFalse(keepsOpeningGiftDuringLoad(owner, other, attached = true))
        assertFalse(keepsOpeningGiftDuringLoad(owner, otherSource, attached = true))
        assertFalse(keepsOpeningGiftDuringLoad(owner, null, attached = true))
        assertFalse(keepsOpeningGiftDuringLoad(owner, owner, attached = false))
        assertNull(presentation.available(owner, keepsOpeningGiftDuringLoad(owner, other, attached = true)))
        assertEquals(receipt, presentation.available(owner, true))
    }

    @Test fun newSessionHasNoPendingPresentation() {
        assertNull(OpeningGiftPresentation().available(owner, true))
    }
}
