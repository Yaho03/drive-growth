package io.drivegrowth.core.garage

import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.FirstRentalGiftCause
import io.drivegrowth.core.contract.FirstRentalGiftCommand
import io.drivegrowth.core.contract.FirstRentalGiftPreparation
import io.drivegrowth.core.contract.FirstRentalGiftSettlement
import io.drivegrowth.core.contract.FirstRentalGiftState
import io.drivegrowth.core.contract.FirstRentalGiftStore
import io.drivegrowth.core.contract.GachaChangeStore
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InitializationChangeStore
import io.drivegrowth.core.contract.InitializationCommand
import io.drivegrowth.core.contract.InitializationPreparation
import io.drivegrowth.core.contract.InitializationSettlement
import io.drivegrowth.core.contract.InitializationState
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.ProfileContractFixtures
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.ProfileInitializationStatus
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.SettlementId
import io.drivegrowth.core.contract.Transaction
import io.drivegrowth.core.contract.TransactionId
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.prepareFirstRentalGift
import io.drivegrowth.core.contract.prepareInitialization
import org.junit.Assert.*
import org.junit.Test

/** #121 초기화·첫 영업 선물 계약 소비. 실제 commit 구현(메모리·SQLite)은 아직 없어 계약의 prepare로 확정을 흉내 내는 시험 저장소를 쓴다. */
class FirstGrantProfileTest {
    private val content = GameContentFixtures.loaded()
    private val firstGrant = FirstGrant(content)
    private val f = ProfileContractFixtures
    private val profile = f.profile
    private val casper = VehicleId("hyundai_casper")
    private val avante = VehicleId("hyundai_avante")
    private val newState = f.newState.copy(contentVersion = content.catalog.contentVersion, ruleVersion = content.ruleVersion)
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)

    /** 읽은 상태로 계약 prepare를 실행해 Ready면 정산을 만들어 돌려준다. 원자 저장·재시작 검증이 아니다. */
    private class ProfileStore(var state: ContractResult<InitializationState>) :
        InitializationChangeStore, GameChangeStore by InMemoryGameChangeStore(emptyList()) {
        val commands = mutableListOf<InitializationCommand>()
        override fun readInitialization(profileId: ProfileId) = state
        override fun commitInitialization(command: InitializationCommand): ContractResult<InitializationSettlement> {
            commands += command
            val current = (state as? ContractResult.Success)?.value ?: return state as ContractResult.Failure
            return when (val prepared = prepareInitialization(command, current)) {
                is ContractResult.Failure -> prepared
                is ContractResult.Success -> when (val p = prepared.value) {
                    is InitializationPreparation.Replay -> ContractResult.Success(p.result)
                    is InitializationPreparation.Ready -> ContractResult.Success(InitializationSettlement(command,
                        Settlement(SettlementId("init"), Transaction(TransactionId("init-tx"), command.grant.requestId,
                            command.grant.kind, command.grant.source, command.grant.ruleVersion, command.grant.money),
                            p.balance, p.vehicle, EpochMillis(1)), p.garageCapacity, p.marker))
                }
            }
        }
    }

    private class GiftStore(private val state: ContractResult<FirstRentalGiftState>) :
        FirstRentalGiftStore, GachaChangeStore by InMemoryGameChangeStore(emptyList()) {
        override fun readFirstRentalGift(profileId: ProfileId) = state
        override fun commitFirstRentalGift(command: FirstRentalGiftCommand): ContractResult<FirstRentalGiftSettlement> =
            throw UnsupportedOperationException()
    }

    @Test fun `첫 차 후보는 NEW 근거가 있을 때만 보이고 기존 프로필은 다시 고르지 않으며 UNKNOWN은 무지급이다`() {
        assertEquals(listOf(casper, avante), ok(firstGrant.choices(ProfileStore(ContractResult.Success(newState)), profile)))
        val onlyAvante = newState.copy(availableCandidates = setOf(avante, VehicleId("kia_ray")))
        assertEquals(listOf(avante), ok(firstGrant.choices(ProfileStore(ContractResult.Success(onlyAvante)), profile)))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE),
            firstGrant.choices(ProfileStore(ContractResult.Success(newState.copy(availableCandidates = emptySet()))), profile))
        for (existing in listOf(ProfileInitializationStatus.Existing(null), ProfileInitializationStatus.Existing(f.initialized)))
            assertEquals(emptyList<VehicleId>(), ok(firstGrant.choices(ProfileStore(ContractResult.Success(newState.copy(status = existing))), profile)))
        val unknown = newState.copy(status = ProfileInitializationStatus.Unknown(MissingReason.NO_DATA))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), firstGrant.choices(ProfileStore(ContractResult.Success(unknown)), profile))
        assertEquals(fail(ContractError.STORAGE_FAILURE), firstGrant.choices(ProfileStore(fail(ContractError.STORAGE_FAILURE)), profile))
    }

    @Test fun `선택 확정은 NEW 근거와 초기 공간으로 한 번 지급하고 같은 선택 재요청은 처음 결과 다른 차는 충돌이다`() {
        val store = ProfileStore(ContractResult.Success(newState))
        val first = ok(firstGrant.choose(store, profile, casper))
        assertEquals(f.evidence, first.command.evidence)
        assertEquals(FirstGrant.INITIAL_GARAGE_CAPACITY, first.garageCapacity)
        assertEquals(FirstGrant.INITIAL_MONEY, first.grant.balance)
        val car = first.grant.changedVehicle!!
        assertEquals(listOf<Any>(casper, VehicleAcquisitionSource.INITIAL_GRANT, GameMoney(0)), listOf(car.vehicleId, car.acquisitionSource, car.acquisitionCost))

        // 확정 뒤(응답 유실 포함) 저장소가 EXISTING으로 바뀌어도 같은 선택은 원래 결과, 다른 차는 거절
        store.state = ContractResult.Success(newState.copy(status = ProfileInitializationStatus.Existing(first), balance = GameMoney(1000), ownedVehicleCount = 1))
        assertEquals(first, ok(firstGrant.choose(store, profile, casper)))
        assertEquals(fail(ContractError.STATE_CONFLICT), firstGrant.choose(store, profile, avante))
    }

    @Test fun `이미 받은 프로필은 콘텐츠·규칙 버전이 바뀌거나 원래 차종이 빠져도 원래 명령을 그대로 재제출한다`() {
        val store = ProfileStore(ContractResult.Success(newState))
        val first = ok(firstGrant.choose(store, profile, casper))
        // 앱 업데이트로 콘텐츠·규칙 버전이 바뀌고 캐스퍼가 콘텐츠에서 빠진 상황
        val v1 = GameContentFixtures.content.replace("hyundai_casper,100,1\n", "")
            .replace("fixture-content-v0", "fixture-content-v1").replace("fixture-rule-v0", "fixture-rule-v1")
        val updated = FirstGrant((GameContentLoader.load(GameContentFixtures.facts, v1) as ContentLoad.Loaded).snapshot)
        store.state = ContractResult.Success(newState.copy(status = ProfileInitializationStatus.Existing(first),
            contentVersion = ContentVersion("fixture-content-v1"), ruleVersion = RuleVersion("fixture-rule-v1"),
            balance = GameMoney(1000), ownedVehicleCount = 1))

        assertEquals(first, ok(firstGrant.choose(store, profile, casper)))
        assertEquals(first, ok(updated.choose(store, profile, casper)))
        assertEquals(fail(ContractError.STATE_CONFLICT), updated.choose(store, profile, avante))
        assertEquals(fail(ContractError.INVALID_INPUT), updated.choose(store, ProfileId("other-profile"), casper))
        assertEquals(List(3) { first.command }, store.commands) // 처음 1회 + 재생 2회 모두 원래 명령, 거절은 제출 안 함
    }

    @Test fun `NEW는 후보 밖 차와 저장 측 후보에 없는 차를 지급하지 않는다`() {
        val store = ProfileStore(ContractResult.Success(newState))
        assertEquals(fail(ContractError.INVALID_INPUT), firstGrant.choose(store, profile, VehicleId("hyundai_sonata")))
        val casperOnly = ProfileStore(ContractResult.Success(newState.copy(availableCandidates = setOf(casper))))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), firstGrant.choose(casperOnly, profile, avante))
    }

    @Test fun `원래 결과 없는 기존 프로필과 UNKNOWN과 조회 실패는 지급을 시도하지 않는다`() {
        for (status in listOf(ProfileInitializationStatus.Existing(null), ProfileInitializationStatus.Unknown(MissingReason.NO_DATA))) {
            val store = ProfileStore(ContractResult.Success(newState.copy(status = status)))
            assertEquals(fail(ContractError.DATA_UNAVAILABLE), firstGrant.choose(store, profile, casper))
            assertTrue(store.commands.isEmpty())
        }
        val broken = ProfileStore(fail(ContractError.STORAGE_FAILURE))
        assertEquals(fail(ContractError.STORAGE_FAILURE), firstGrant.choose(broken, profile, casper))
        assertTrue(broken.commands.isEmpty())
    }

    private val giftState = f.giftState.copy(contentVersion = content.catalog.contentVersion, ruleVersion = content.ruleVersion)
    private fun gift(state: FirstRentalGiftState = giftState) =
        firstGrant.prepareOpeningGift(GiftStore(ContractResult.Success(state)), profile, f.source)

    @Test fun `개업 선물은 최초 완료 영업을 근거로 무료권 1장 명령을 만들고 이미 받았으면 원래 명령을 돌려준다`() {
        val command = ok(gift())
        assertEquals(FirstRentalGiftCommand(FirstGrant.GIFT_REQUEST_ID, profile, f.source, content.catalog.contentVersion,
            content.ruleVersion, FirstRentalGiftCause(RequestId("first-rental"), SettlementId("rental-simulation")), Revision(7)), command)
        val ready = ok(prepareFirstRentalGift(command, giftState)) as FirstRentalGiftPreparation.Ready
        assertEquals(5L, ready.progress.freeTickets) // 4장 → 5장
        assertEquals(Revision(8), ready.progress.revision)
        assertEquals(giftState.balance, ready.balance) // 돈은 그대로

        val original = f.gifted.copy(command = command)
        val granted = giftState.copy(granted = original, progress = original.progress)
        assertEquals(command, ok(gift(granted))) // 다시 제출하면 저장소가 처음 결과 재생
        assertEquals(FirstRentalGiftPreparation.Replay(original), ok(prepareFirstRentalGift(command, granted)))
    }

    @Test fun `완료 영업 없음·버전 불일치·조회 실패는 선물 명령을 만들지 않는다`() {
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), gift(giftState.copy(firstCompletedRental = null)))
        assertEquals(fail(ContractError.STATE_CONFLICT), gift(giftState.copy(contentVersion = ContentVersion("other"))))
        for (error in listOf(ContractError.DATA_UNAVAILABLE, ContractError.STORAGE_FAILURE))
            assertEquals(fail(error), firstGrant.prepareOpeningGift(GiftStore(fail(error)), profile, f.source))
    }
    @Test fun `개업 선물 준비는 이미 받은 경우에도 호출자의 다른 출처를 거절한다`() {
        for (state in listOf(giftState, giftState.copy(granted = f.gifted, progress = f.gifted.progress))) {
            assertEquals(fail(ContractError.INVALID_INPUT), firstGrant.prepareOpeningGift(
                GiftStore(ContractResult.Success(state)), profile, io.drivegrowth.core.contract.InputSource.VEHICLE))
        }
    }

    @Test fun `개업 선물 준비는 이미 받은 경우에도 호출자의 다른 프로필을 거절한다`() {
        for (state in listOf(giftState, giftState.copy(granted = f.gifted, progress = f.gifted.progress))) {
            assertEquals(fail(ContractError.INVALID_INPUT), firstGrant.prepareOpeningGift(
                GiftStore(ContractResult.Success(state)), ProfileId("other-profile"), f.source))
        }
    }

}
