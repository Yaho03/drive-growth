package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion
import org.junit.Assert.*
import org.junit.Test

class ShopOriginalResultReaderTest {
    private val id = RequestId("original-expand")
    private val source = InputSource.SIMULATION
    private val offer = ShopExpansion(RuleVersion("original-rule"), true, 1, 3, 5, 0, GameMoney(500))
    private val original = Settlement(SettlementId("original-settlement"), Transaction(TransactionId("original-tx"), id,
        TransactionKind.FACILITY, source, offer.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(500))),
        GameMoney(500), null, EpochMillis(123))
    private fun accepts(s: Settlement) = matchesExpansionReceipt(id, source, offer, s)

    @Test fun originalReceiptIsAcceptedWithoutCurrentBalanceOrDefinitions() {
        assertTrue(accepts(original))
        assertTrue(accepts(original.copy(balance = GameMoney(0))))
    }
    @Test fun anotherRequestCannotBePresentedAsThisExpansion() {
        assertFalse(accepts(original.copy(transaction = original.transaction.copy(requestId = RequestId("other")))))
    }
    @Test fun foreignSourceCannotBePresentedAsLocalResult() {
        assertFalse(accepts(original.copy(transaction = original.transaction.copy(source = InputSource.VEHICLE))))
    }
    @Test fun anotherFeatureCannotBePresentedAsExpansion() {
        assertFalse(accepts(original.copy(transaction = original.transaction.copy(kind = TransactionKind.GARAGE))))
    }
    @Test fun anotherRuleCannotBePresentedAsTheOriginalOffer() {
        assertFalse(accepts(original.copy(transaction = original.transaction.copy(ruleVersion = RuleVersion("other")))))
    }
    @Test fun wrongDirectionAmountAndAbsentMoneyAreRejected() {
        for (money in listOf(null, MoneyChange(MoneyDirection.CREDIT, GameMoney(500)), MoneyChange(MoneyDirection.DEBIT, GameMoney(499)))) {
            assertFalse(accepts(original.copy(transaction = original.transaction.copy(money = money))))
        }
    }
    @Test fun expansionCannotCarryAnUnrelatedVehicleWrite() {
        val car = OwnedVehicle(OwnedVehicleId("other"), VehicleId("model"), 1, Revision(0), VehicleLifecycle.OWNED,
            Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        assertFalse(accepts(original.copy(changedVehicle = car)))
    }
}
