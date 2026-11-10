package io.drivegrowth.app.host.facility

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test

/** typed 경계가 종류/전체명령/프로필/원결과를 바꾸면 실패한다. DB 실행은 Android 시험으로 분리한다. */
class CheckpointFacilityDurablePortTest {
    private val identity=GameIdentity(ProfileId("issued"),InputSource.SIMULATION)
    private val rest=FacilityCommand.Rest(identity,RequestId("on"),VehicleRef(OwnedVehicleId("car"),Revision(7)),false,true)
    private val buy=FacilityCommand.Buy(identity,RequestId("buy"),ContentVersion("central"),FacilityOffer(Facility.CAR_WASH,RuleVersion("trial"),true,GameMoney(1000)))
    private val port=CheckpointFacilityDurablePort()
    @Test fun wholeRestingCommandAndOwnerSurviveSavedAndStartedRecovery() {
        val typed=port.encode(rest)
        assertEquals(HostCheckpointCommand.VehicleResting(rest.requestId,identity.source,rest.vehicle,false,true),typed)
        for(phase in HostCheckpointPhase.entries)assertEquals(FacilityPending.Own(rest),port.pending(identity,HostCommandCheckpoint(identity.profileId,typed,phase)))
        assertEquals(FacilityPending.Empty,port.pending(identity,null))
    }
    @Test fun wholeCentralOfferSurvivesPurchaseMapping() {
        val typed=HostCheckpointCommand.FacilityPurchase(buy.requestId,identity.source,buy.definitionVersion,buy.offer)
        assertEquals(typed,port.encode(buy));assertEquals(FacilityPending.Own(buy),port.pending(identity,HostCommandCheckpoint(identity.profileId,typed,HostCheckpointPhase.SAVED)))
    }
    @Test fun anotherFeatureOrForeignIdentityRemainsForeign() {
        val expand=HostCheckpointCommand.Expand(RequestId("expand"),identity.source,ContentVersion("definition"),ShopExpansion(RuleVersion("rule"),true,1,3,5,3,GameMoney(500)))
        assertEquals(FacilityPending.Foreign,port.pending(identity,HostCommandCheckpoint(identity.profileId,expand,HostCheckpointPhase.SAVED)))
        val own=HostCommandCheckpoint(identity.profileId,port.encode(rest),HostCheckpointPhase.STARTED)
        assertEquals(FacilityPending.Foreign,port.pending(identity,own.copy(profileId=ProfileId("foreign"))))
        assertEquals(FacilityPending.Foreign,port.pending(identity.copy(source=InputSource.VEHICLE),own))
    }
    @Test fun restingMapsRecordedBeforeAfterWithoutFinancialSettlement() {
        val typed=port.encode(rest) as HostCheckpointCommand.VehicleResting
        val actual=HostCheckpointResult.VehicleResting(VehicleRestingReceipt(identity.profileId,typed,false,true))
        assertEquals(ContractResult.Success(FacilityReceipt.Rested(rest,false,true)),port.receipt(rest,actual))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),port.receipt(rest,actual.copy(value=actual.value.copy(profileId=ProfileId("foreign")))))
        val changed = actual.copy(value=actual.value.copy(command=typed.copy(vehicle=typed.vehicle.copy(revision=Revision(8)))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),port.receipt(rest,changed))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),port.receipt(rest,actual.copy(value=actual.value.copy(after=false))))
    }
    @Test fun purchaseKeepsOriginalSettlementAndRejectsWrongSourceOrRequest() {
        val settled=Settlement(SettlementId("original"),Transaction(TransactionId("transaction"),buy.requestId,TransactionKind.FACILITY,identity.source,buy.offer.ruleVersion,MoneyChange(MoneyDirection.DEBIT,buy.offer.price)),GameMoney(123),null,EpochMillis(900))
        assertEquals(ContractResult.Success(FacilityReceipt.Purchased(buy,settled)),port.receipt(buy,HostCheckpointResult.FacilityPurchase(settled)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),port.receipt(buy,HostCheckpointResult.FacilityPurchase(settled.copy(transaction=settled.transaction.copy(source=InputSource.VEHICLE)))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),port.receipt(buy,HostCheckpointResult.FacilityPurchase(settled.copy(transaction=settled.transaction.copy(requestId=RequestId("different"))))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),port.receipt(rest,HostCheckpointResult.FacilityPurchase(settled)))
    }
}
