package io.drivegrowth.app.host

import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import io.drivegrowth.core.rental.RentalReceipt
import org.junit.Assert.*
import org.junit.Test

class ResultsHistoryMapperTest {
    private val receipt=ResultsHostTestData.settlement().let{it.copy(transaction=it.transaction.copy(kind=TransactionKind.RENTAL),changedVehicle=null)}
    private val identity=GameIdentity(ProfileId("actual"),receipt.transaction.source)
    private val rental=CompletedRental(RequestId("rental"),OwnedVehicleId("rented"),RentalReceipt(receipt,true,false,true,GameMoney(7),null))
    @Test fun suppliedRentalFieldsAndViewedFlagKeepOriginalValuesWithoutCurrentBalanceOrAggregation() {
        val input=SettlementHistorySnapshot(identity,listOf(SettlementHistoryEntry(receipt,true,rental,VehicleId("model"))),mapOf(VehicleId("model") to "대여 차량"))
        val output=ResultsHostMapper().history(ContractResult.Success(input)) as ResultsScreenState.Content
        assertTrue(output.historyAvailable);val row=output.results.single();assertSame(receipt,row.settlement);assertSame(rental,row.rental)
        assertTrue(row.viewed);assertEquals("대여 차량",row.rentalVehicleName);assertNull(row.rental!!.receipt.regularCustomer);assertEquals(GameMoney(7),row.rental!!.receipt.facilityBonus)
    }
    @Test fun missingNamesRemainUnknownAndForeignOrMislinkedRentalIsError() {
        val input=SettlementHistorySnapshot(identity,listOf(SettlementHistoryEntry(receipt,false,rental)))
        val mapper=ResultsHostMapper();assertNull((mapper.history(ContractResult.Success(input)) as ResultsScreenState.Content).results.single().rentalVehicleName)
        val wrong=rental.copy(receipt=rental.receipt.copy(settlement=receipt.copy(settlementId=SettlementId("foreign"))))
        assertTrue(mapper.history(ContractResult.Success(input.copy(entries=listOf(SettlementHistoryEntry(receipt,false,wrong))))) is ResultsScreenState.Error)
        assertTrue(mapper.history(ContractResult.Success(input.copy(identity=identity.copy(source=InputSource.VEHICLE)))) is ResultsScreenState.Error)
    }
}
