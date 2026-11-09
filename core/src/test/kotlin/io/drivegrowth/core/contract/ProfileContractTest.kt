package io.drivegrowth.core.contract

import io.drivegrowth.core.content.GameContentFixtures
import org.junit.Assert.*
import org.junit.Test

class ProfileContractTest {
    private val f = ProfileContractFixtures
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    @Test fun `검증된 NEW는 기존 첫 지급 명령과 공간 마커를 한 예정 결과로 연결한다`() {
        val plan = ok(prepareInitialization(f.initialization, f.newState)) as InitializationPreparation.Ready
        assertEquals(GameMoney(1000), plan.balance)
        assertEquals(f.vehicle, plan.vehicle)
        assertEquals(3, plan.garageCapacity)
        assertEquals(f.evidence, plan.marker.evidence)
    }
    @Test fun `빈 차고와 초기 지급 부재는 UNKNOWN과 legacy를 NEW로 바꾸지 않는다`() {
        for (state in listOf(f.unknown, f.legacy))
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), prepareInitialization(f.initialization, state))
    }
    @Test fun `기존 초기화는 현재 잔액과 버전이 바뀌어도 최초 전체 결과를 재생한다`() {
        val current = f.existing.copy(ruleVersion=RuleVersion("new-rule"))
        assertEquals(InitializationPreparation.Replay(f.initialized), ok(prepareInitialization(f.initialization, current)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            prepareInitialization(f.initialization.copy(garageCapacity=4), current))
    }
    @Test fun `선물은 완료 영업 근거로 무료권과 revision만 증가하고 잔액과 보장 카운터는 유지한다`() {
        val plan = ok(prepareFirstRentalGift(f.gift, f.giftState)) as FirstRentalGiftPreparation.Ready
        assertEquals(GachaProgress(Revision(8), 2, 5), plan.progress)
        assertEquals(GameMoney(25), plan.balance)
        assertEquals(f.gift.cause, plan.marker.cause)
        assertEquals(4, f.giftState.progress.freeTickets)
    }
    @Test fun `미완료 영업과 다른 원인과 오래된 가챠 revision과 overflow를 구분한다`() {
        val cases = listOf(
            f.giftState.copy(firstCompletedRental=null) to ContractError.DATA_UNAVAILABLE,
            f.giftState.copy(progress=f.giftState.progress.copy(revision=Revision(8))) to ContractError.STATE_CONFLICT,
            f.giftState.copy(progress=f.giftState.progress.copy(freeTickets=Long.MAX_VALUE)) to ContractError.INVALID_INPUT,
        )
        for ((state, error) in cases) assertEquals(ContractResult.Failure(error), prepareFirstRentalGift(f.gift,state))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), prepareFirstRentalGift(
            f.gift.copy(cause=f.gift.cause.copy(rentalId=RequestId("other-rental"))),f.giftState))
    }
    @Test fun `지급된 프로필은 동일 명령만 최초 전체 결과를 재생하고 다른 요청은 충돌이다`() {
        val current=f.giftState.copy(granted=f.gifted,progress=GachaProgress(Revision(20),0,0),firstCompletedRental=null)
        assertEquals(FirstRentalGiftPreparation.Replay(f.gifted),ok(prepareFirstRentalGift(f.gift,current)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareFirstRentalGift(f.gift.copy(requestId=RequestId("again")),current))
    }

    @Test fun `초기화에 가짜 0원 지급을 넣으면 거절한다`() {
        val command=f.initialization.copy(grant=f.grant.copy(money=MoneyChange(MoneyDirection.CREDIT,GameMoney(0))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepareInitialization(command,f.newState.copy(initialMoney=GameMoney(0))))
    }
    @Test fun `완료 영업의 원래 거래 요청이 다른 영업이면 저장 오류다`() {
        val original=f.completedRental.receipt.settlement
        val corrupt=f.completedRental.copy(receipt=f.completedRental.receipt.copy(settlement=original.copy(
            transaction=original.transaction.copy(requestId=RequestId("rental/settle/other")))))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),prepareFirstRentalGift(f.gift,f.giftState.copy(firstCompletedRental=corrupt)))
    }
    @Test fun `FirstGrant 연결은 전용 명령을 사용하고 실제 저장 실패를 그대로 반환한다`() {
        val content=GameContentFixtures.loaded()
        var submittedCommand: InitializationCommand?=null
        val store=object:InitializationChangeStore {
            override fun readInitialization(profileId:ProfileId):ContractResult<InitializationState> = error("이 연결은 조회하지 않음")
            override fun listOwnedVehicles():ContractResult<List<OwnedVehicle>> = error("빈 차고로 신규 판별 금지")
            override fun commitGameChange(change:GameChange):ContractResult<Settlement> = error("단일 legacy 쓰기로 우회 금지")
            override fun commitInitialization(command:InitializationCommand):ContractResult<InitializationSettlement> {
                submittedCommand=command
                return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            }
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),commitFirstGrantInitialization(store,f.evidence,content,
            VehicleId("hyundai_avante"),3))
        val submitted=checkNotNull(submittedCommand)
        assertEquals(GameMoney(1000),submitted.grant.money?.amount)
        assertEquals(VehicleId("hyundai_avante"),(submitted.grant.vehicle as VehicleWrite.Add).vehicle.vehicleId)
        assertEquals(content.catalog.contentVersion,submitted.contentVersion)
        assertEquals(f.evidence,submitted.evidence)
    }
    @Test fun `초기화의 프로필 출처 근거 버전 공간과 새 원장의 불일치는 거절한다`() {
        for (command in listOf(f.initialization.copy(profileId=ProfileId("other")),f.initialization.copy(grant=f.grant.copy(source=InputSource.VEHICLE))))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepareInitialization(command,f.newState))
        for (command in listOf(f.initialization.copy(evidence=f.evidence.copy(revision=Revision(1))),
            f.initialization.copy(contentVersion=ContentVersion("other")),f.initialization.copy(grant=f.grant.copy(ruleVersion=RuleVersion("other"))),
            f.initialization.copy(garageCapacity=4)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareInitialization(command,f.newState))
        for (state in listOf(f.newState.copy(balance=GameMoney(1)),f.newState.copy(ownedVehicleCount=1)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareInitialization(f.initialization,state))
    }
    @Test fun `초기 지급 종류 금액과 개체의 성장 취득 정보를 바꾸면 거절한다`() {
        val invalid=listOf(f.grant.copy(requestId=RequestId("other")),f.grant.copy(kind=TransactionKind.DRIVING),
            f.grant.copy(money=MoneyChange(MoneyDirection.CREDIT,GameMoney(999))),
            f.grant.copy(vehicle=VehicleWrite.Add(f.vehicle.copy(level=2))),
            f.grant.copy(vehicle=VehicleWrite.Add(f.vehicle.copy(revision=Revision(1)))),
            f.grant.copy(vehicle=VehicleWrite.Add(f.vehicle.copy(acquisitionSource=VehicleAcquisitionSource.GACHA))))
        for (grant in invalid) assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepareInitialization(f.initialization.copy(grant=grant),f.newState))
    }
    @Test fun `선물의 프로필 출처 버전과 정산 식별자가 다르면 거절한다`() {
        for (command in listOf(f.gift.copy(profileId=ProfileId("other")),f.gift.copy(source=InputSource.VEHICLE)))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepareFirstRentalGift(command,f.giftState))
        for (command in listOf(f.gift.copy(contentVersion=ContentVersion("other")),f.gift.copy(ruleVersion=RuleVersion("other")),
            f.gift.copy(cause=f.gift.cause.copy(settlementId=SettlementId("other")))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareFirstRentalGift(command,f.giftState))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepareFirstRentalGift(f.gift.copy(expectedProgressRevision=Revision(Int.MAX_VALUE)),
            f.giftState.copy(progress=f.giftState.progress.copy(revision=Revision(Int.MAX_VALUE)))))
    }
    @Test fun `무료권이 0이어도 선물 원인이 있으면 1이 되고 소비에 필요한 revision이 증가한다`() {
        val plan=ok(prepareFirstRentalGift(f.gift,f.giftState.copy(progress=f.giftState.progress.copy(freeTickets=0)))) as FirstRentalGiftPreparation.Ready
        assertEquals(GachaProgress(Revision(8),2,1),plan.progress)
    }
    @Test fun `선물 원래 정산의 종류나 출처 손상은 정상 지급 근거가 아니다`() {
        val original=f.completedRental.receipt.settlement
        for (transaction in listOf(original.transaction.copy(kind=TransactionKind.DRIVING),original.transaction.copy(source=InputSource.VEHICLE))) {
            val rental=f.completedRental.copy(receipt=f.completedRental.receipt.copy(settlement=original.copy(transaction=transaction)))
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),prepareFirstRentalGift(f.gift,f.giftState.copy(firstCompletedRental=rental)))
        }
    }

    @Test fun `초기화와 선물 조회의 저장 권한 미지원 오류는 빈 정상값으로 바뀌지 않는다`() {
        for (failure in listOf(ContractError.STORAGE_FAILURE,ContractError.PERMISSION_DENIED,ContractError.DATA_UNAVAILABLE,ContractError.POLICY_UNRESOLVED)) {
            val store=ProfileReadFailure(failure)
            assertEquals(ContractResult.Failure(failure),f.readInitializationPlan(store,f.initialization))
            assertEquals(ContractResult.Failure(failure),f.readGiftPlan(store,f.gift))
        }
    }
    @Test fun `초기화의 다른 선택과 근거는 기존 프로필의 원래 지급을 덮지 않는다`() {
        val chosen=f.initialization.copy(grant=f.grant.copy(vehicle=VehicleWrite.Add(f.vehicle.copy(vehicleId=VehicleId("hyundai_avante")))))
        val proof=f.initialization.copy(evidence=f.evidence.copy(proofId="another-proof"))
        for (command in listOf(chosen,proof))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareInitialization(command,f.existing))
        assertEquals(f.vehicle,f.initialized.grant.changedVehicle)
    }
    @Test fun `같은 선물 요청의 원인과 revision 변경도 전체 명령 충돌이다`() {
        val state=f.giftState.copy(granted=f.gifted)
        for (command in listOf(f.gift.copy(cause=f.gift.cause.copy(settlementId=SettlementId("other"))),
            f.gift.copy(expectedProgressRevision=Revision(8)),f.gift.copy(ruleVersion=RuleVersion("other"))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareFirstRentalGift(command,state))
    }
    @Test fun `FirstGrant 연결은 저장소가 확정한 전체 결과를 그대로 반환한다`() {
        val content=GameContentFixtures.loaded()
        var stored:InitializationSettlement?=null
        val store=object:InitializationChangeStore {
            override fun readInitialization(profileId:ProfileId):ContractResult<InitializationState> = error("조회 없음")
            override fun listOwnedVehicles():ContractResult<List<OwnedVehicle>> = error("legacy 조회 없음")
            override fun commitGameChange(change:GameChange):ContractResult<Settlement> = error("legacy 확정 없음")
            override fun commitInitialization(command:InitializationCommand):ContractResult<InitializationSettlement> {
                // 연결 시험의 응답 fixture다. 메모리 원장/실제 지급 참조 구현이 아니다.
                val grant=f.originalGrant.copy(transaction=f.originalGrant.transaction.copy(ruleVersion=content.ruleVersion))
                val original=InitializationSettlement(command,grant,3,InitializationMarker(f.profile,command.grant.requestId,f.evidence))
                stored=original
                return ContractResult.Success(original)
            }
        }
        val result=ok(commitFirstGrantInitialization(store,f.evidence,content,VehicleId("hyundai_casper"),3))
        assertSame(stored,result)
        assertEquals(SettlementId("initial-simulation"),result.grant.settlementId)
        assertEquals(f.vehicle,result.grant.changedVehicle)
    }
    @Test fun `현재 콘텐츠에서 확인하지 못한 첫 차를 버전 문자열만 믿고 지급 준비하지 않는다`() {
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),prepareInitialization(f.initialization,
            f.newState.copy(availableCandidates=setOf(VehicleId("hyundai_avante")))))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),prepareInitialization(f.initialization,
            f.newState.copy(availableCandidates=emptySet())))
    }

}

/** 조회 실패 전파만 확인하는 probe. 성공/확정/재생을 구현한 저장소가 아니다. */
private class ProfileReadFailure(private val failure:ContractError):InitializationChangeStore,FirstRentalGiftStore {
    override fun readInitialization(profileId:ProfileId):ContractResult<InitializationState> = ContractResult.Failure(failure)
    override fun readFirstRentalGift(profileId:ProfileId):ContractResult<FirstRentalGiftState> = ContractResult.Failure(failure)
    override fun readGachaState():ContractResult<GachaState> = error("읽지 않음")
    override fun commitInitialization(command:InitializationCommand):ContractResult<InitializationSettlement> = error("확정 없음")
    override fun commitFirstRentalGift(command:FirstRentalGiftCommand):ContractResult<FirstRentalGiftSettlement> = error("확정 없음")
    override fun commitGacha(command:GachaCommand):ContractResult<GachaSettlement> = error("확정 없음")
    override fun commitGameChange(change:GameChange):ContractResult<Settlement> = error("확정 없음")
    override fun listOwnedVehicles():ContractResult<List<OwnedVehicle>> = error("빈 목록으로 대체 금지")
}
