package io.drivegrowth.core.contract

import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.CompletedRental
import io.drivegrowth.core.rental.RentalReceipt

/** SIMULATION 입력/예정 결과 예제다. 저장소 구현·실제 초기화/무료권 지급 성공이 아니다. */
object ProfileContractFixtures {
    val profile = ProfileId("profile-simulation")
    val source = InputSource.SIMULATION
    val contentVersion = ContentVersion("profile-trial-v1")
    val ruleVersion = RuleVersion("profile-rule-v1")
    val evidence = NewProfileEvidence(profile, source, "verified-new-simulation", Revision(0))
    val vehicle = OwnedVehicle(OwnedVehicleId("initial-grant"), VehicleId("hyundai_casper"), 1, Revision(0),
        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
    val grant = GameChange(FirstGrant.REQUEST_ID, TransactionKind.GARAGE, source, ruleVersion,
        MoneyChange(MoneyDirection.CREDIT, FirstGrant.INITIAL_MONEY), VehicleWrite.Add(vehicle))
    val initialization = InitializationCommand(profile, evidence, contentVersion, grant, FirstGrant.INITIAL_GARAGE_CAPACITY)
    val newState = InitializationState(profile, source, contentVersion, ruleVersion, ProfileInitializationStatus.New(evidence),
        GameMoney(0), 0, FirstGrant.INITIAL_MONEY, FirstGrant.INITIAL_GARAGE_CAPACITY, FirstGrant.CANDIDATES.toSet())
    val originalGrant = Settlement(SettlementId("initial-simulation"),
        Transaction(TransactionId("initial-tx-simulation"), grant.requestId, grant.kind, source, ruleVersion, grant.money),
        FirstGrant.INITIAL_MONEY, vehicle, EpochMillis(100))
    val initialized = InitializationSettlement(initialization, originalGrant, initialization.garageCapacity,
        InitializationMarker(profile, grant.requestId, evidence))
    val existing = newState.copy(status = ProfileInitializationStatus.Existing(initialized), balance = GameMoney(17), ownedVehicleCount = 0)
    val unknown = newState.copy(status = ProfileInitializationStatus.Unknown(MissingReason.NO_DATA))
    val legacy = newState.copy(status = ProfileInitializationStatus.Existing(null))

    // 수익0도 확정된 최초 완료 영업이면 근거다. 현재 잔액을 근거로 쓰지 않는다.
    val completedRental = CompletedRental(RequestId("first-rental"), vehicle.ownedVehicleId,
        RentalReceipt(Settlement(SettlementId("rental-simulation"),
            Transaction(TransactionId("rental-tx-simulation"), RequestId("rental/settle/first-rental"), TransactionKind.RENTAL,
                source, ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(0))),
            GameMoney(1000), null, EpochMillis(200)), false, false, false, GameMoney(0), null))
    val gift = FirstRentalGiftCommand(RequestId("first-gift"), profile, source, contentVersion, ruleVersion,
        FirstRentalGiftCause(completedRental.rentalId, completedRental.receipt.settlement.settlementId), Revision(7))
    val giftState = FirstRentalGiftState(profile, source, contentVersion, ruleVersion, GameMoney(25),
        GachaProgress(Revision(7), 2, 4), completedRental, null)
    val gifted = FirstRentalGiftSettlement(gift, SettlementId("gift-simulation"),
        Transaction(TransactionId("gift-tx-simulation"), gift.requestId, TransactionKind.GARAGE, source, ruleVersion, null),
        GameMoney(25), GachaProgress(Revision(8), 2, 5), FirstRentalGiftMarker(profile, gift.requestId, gift.cause), EpochMillis(300))

    /** 조회 실패를 빈 NEW/미완료 영업으로 보정하지 않는 소비 예제. 실제 commit은 호출하지 않는다. */
    fun readInitializationPlan(store: InitializationChangeStore, command: InitializationCommand): ContractResult<InitializationPreparation> =
        when (val read = store.readInitialization(command.profileId)) {
            is ContractResult.Failure -> read
            is ContractResult.Success -> prepareInitialization(command, read.value)
        }

    fun readGiftPlan(store: FirstRentalGiftStore, command: FirstRentalGiftCommand): ContractResult<FirstRentalGiftPreparation> =
        when (val read = store.readFirstRentalGift(command.profileId)) {
            is ContractResult.Failure -> read
            is ContractResult.Success -> prepareFirstRentalGift(command, read.value)
        }
}
