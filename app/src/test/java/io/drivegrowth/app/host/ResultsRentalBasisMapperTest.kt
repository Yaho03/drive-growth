package io.drivegrowth.app.host

import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import io.drivegrowth.core.rental.RentalReceipt
import org.junit.Assert.*
import org.junit.Test

/** 원래 시간 근거가 타 정산/프로필/출처에 붙어 정상 상세로 노출되는 회귀를 막는다. */
class ResultsRentalBasisMapperTest {
    private val settlement = ResultsHostTestData.settlement().let { it.copy(
        transaction = it.transaction.copy(kind = TransactionKind.RENTAL, requestId = RequestId("rental/settle/r"),
            money = MoneyChange(MoneyDirection.CREDIT, GameMoney(348))), balance = GameMoney(448), changedVehicle = null) }
    private val identity = GameIdentity(ProfileId("original-profile"), settlement.transaction.source)
    private val rental = CompletedRental(RequestId("r"), OwnedVehicleId("rented"), RentalReceipt(settlement, false, false, false, GameMoney(0), false))
    private val period = RentalIncomePeriod(EpochMillis(0), EpochMillis(1001), RentalIncomeKind.DRIVING,
        null, 1001, 0, 0, java.math.BigDecimal(1001), null)
    private fun basis(rentalId: RequestId = rental.rentalId, settlementId: SettlementId = settlement.settlementId,
        profileId: ProfileId = identity.profileId, source: InputSource = identity.source,
        rule: RuleVersion = settlement.transaction.ruleVersion) = RentalResultBasis(rentalId, settlementId, profileId,
        source, rule, EpochMillis(0), EpochMillis(1001), listOf(period))
    private fun map(basis: RentalResultBasis, rental: CompletedRental? = this.rental) = ResultsHostMapper().history(
        ContractResult.Success(SettlementHistorySnapshot(identity, listOf(SettlementHistoryEntry(settlement, false, rental,
            rentalBasis = Observation.Available(basis))))))

    @Test fun foreignRentalSettlementProfileSourceOrRuleNeverBecomesNormalDetail() {
        for (wrong in listOf(basis(rentalId = RequestId("foreign")), basis(settlementId = SettlementId("foreign")),
            basis(profileId = ProfileId("foreign")), basis(source = InputSource.VEHICLE), basis(rule = RuleVersion("foreign")))) {
            assertTrue("다른 원본을 정상 상세로 결합하면 안 된다", map(wrong) is ResultsScreenState.Error)
        }
    }
    @Test fun availableBasisWithoutItsOriginalRentalNeverBecomesNormalDetail() {
        assertTrue(map(basis(), null) is ResultsScreenState.Error)
    }
    @Test fun originalAvailableBasisAndReceiptAreForwardedWithoutNewTimeOrMoney() {
        val original = basis()
        val output = map(original) as ResultsScreenState.Content
        val row = output.results.single()
        assertSame(original, (row.rentalBasis as Observation.Available).value)
        assertSame(settlement, row.settlement); assertSame(rental, row.rental)
        assertEquals(1001L, (row.rentalBasis as Observation.Available).value.normalMillis)
        assertEquals(GameMoney(448), row.settlement.balance)
    }
    @Test fun missingBasisRemainsMissingAndHistoryFailureNeverBecomesNormalDetail() {
        val observation = Observation.Missing(MissingReason.NO_DATA)
        val entry = SettlementHistoryEntry(settlement, true, rental, rentalBasis = observation)
        val read = ContractResult.Success(SettlementHistorySnapshot(identity, listOf(entry)))
        val mapper = ResultsHostMapper()
        repeat(2) {
            val row = (mapper.history(read) as ResultsScreenState.Content).results.single()
            assertEquals(observation, row.rentalBasis); assertSame(settlement, row.settlement); assertTrue(row.viewed)
        }
        assertTrue(mapper.history(ContractResult.Failure(ContractError.STORAGE_FAILURE)) is ResultsScreenState.Error)
        assertTrue(mapper.history(ContractResult.Failure(ContractError.DATA_UNAVAILABLE)) is ResultsScreenState.Missing)
    }
    @Test fun basisCannotHideForeignOriginalRequestOrNonRentalKind() {
        for (transaction in listOf(settlement.transaction.copy(requestId = RequestId("rental/settle/foreign")),
            settlement.transaction.copy(kind = TransactionKind.DRIVING))) {
            val changed = settlement.copy(transaction = transaction)
            val wrongRental = rental.copy(receipt = rental.receipt.copy(settlement = changed))
            val snapshot = SettlementHistorySnapshot(identity, listOf(SettlementHistoryEntry(changed, false, wrongRental,
                rentalBasis = Observation.Available(basis()))))
            assertTrue(ResultsHostMapper().history(ContractResult.Success(snapshot)) is ResultsScreenState.Error)
        }
    }

}
