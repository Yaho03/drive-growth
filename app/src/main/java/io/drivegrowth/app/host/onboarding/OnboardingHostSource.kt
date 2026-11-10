package io.drivegrowth.app.host.onboarding

import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant

data class OnboardingSnapshot(
    val identity: GameIdentity,
    val initialization: InitializationState,
    val content: ContractResult<GameContentSnapshot>,
    val gift: ContractResult<FirstRentalGiftState>?,
)

/** worker 전용. 조회 결과와 확정 결과를 분리하고 같은 프로필의 원래 명령만 재생한다. */
interface OnboardingHostSource {
    fun read(): ContractResult<OnboardingSnapshot>
    fun choose(identity: GameIdentity, vehicleId: VehicleId): ContractResult<InitializationSettlement>
    fun claimGift(identity: GameIdentity): ContractResult<FirstRentalGiftSettlement>
}

/** 같은 factory/DB에서만 실행한다. 미확정 명령 메모리는 구성 변경용이며 영속 checkpoint가 아니다. */
class StoredOnboardingHostSource(private val sessions: GameSessionFactory) : OnboardingHostSource {
    private var pendingInitialization: InitializationCommand? = null
    private var pendingGift: FirstRentalGiftCommand? = null

    private fun <T> withSession(expected: GameIdentity? = null, action: (GameSession) -> ContractResult<T>): ContractResult<T> =
        when (val opened = sessions.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> opened.value.use { session ->
                if (expected != null && expected != session.identity) ContractResult.Failure(ContractError.INVALID_INPUT)
                else action(session)
            }
        }

    override fun read(): ContractResult<OnboardingSnapshot> = withSession { session ->
        val initialization = when (val read = session.store.readInitialization(session.identity.profileId)) {
            is ContractResult.Failure -> return@withSession read
            is ContractResult.Success -> read.value
        }
        if (initialization.profileId != session.identity.profileId || initialization.source != session.identity.source)
            return@withSession ContractResult.Failure(ContractError.INVALID_INPUT)
        val gift = if (initialization.status is ProfileInitializationStatus.Existing)
            session.store.readFirstRentalGift(session.identity.profileId) else null
        if (gift is ContractResult.Success && (gift.value.profileId != session.identity.profileId || gift.value.source != session.identity.source))
            return@withSession ContractResult.Failure(ContractError.INVALID_INPUT)
        ContractResult.Success(OnboardingSnapshot(session.identity, initialization, session.content, gift))
    }

    override fun choose(identity: GameIdentity, vehicleId: VehicleId): ContractResult<InitializationSettlement> = withSession(identity) { session ->
        pendingInitialization?.let { original ->
            if (original.profileId != identity.profileId || original.grant.source != identity.source ||
                (original.grant.vehicle as? VehicleWrite.Add)?.vehicle?.vehicleId != vehicleId)
                return@withSession ContractResult.Failure(ContractError.STATE_CONFLICT)
            return@withSession session.store.commitInitialization(original)
        }
        val state = when (val read = session.store.readInitialization(identity.profileId)) {
            is ContractResult.Failure -> return@withSession read
            is ContractResult.Success -> read.value
        }
        when (val status = state.status) {
            is ProfileInitializationStatus.Unknown -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ProfileInitializationStatus.Existing -> {
                val original = status.original?.command ?: return@withSession ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                if ((original.grant.vehicle as? VehicleWrite.Add)?.vehicle?.vehicleId != vehicleId)
                    ContractResult.Failure(ContractError.STATE_CONFLICT)
                else { pendingInitialization = original; session.store.commitInitialization(original) }
            }
            is ProfileInitializationStatus.New -> {
                val content = when (val loaded = session.content) {
                    is ContractResult.Failure -> return@withSession loaded
                    is ContractResult.Success -> loaded.value
                }
                val bridge = object : InitializationChangeStore by session.store {
                    override fun commitInitialization(command: InitializationCommand): ContractResult<InitializationSettlement> {
                        pendingInitialization = command
                        return session.store.commitInitialization(command)
                    }
                }
                FirstGrant(content).choose(bridge, identity.profileId, vehicleId)
            }
        }
    }

    override fun claimGift(identity: GameIdentity): ContractResult<FirstRentalGiftSettlement> = withSession(identity) { session ->
        pendingGift?.let { command ->
            if (command.profileId != identity.profileId || command.source != identity.source)
                return@withSession ContractResult.Failure(ContractError.INVALID_INPUT)
            return@withSession session.store.commitFirstRentalGift(command)
        }
        val state = when (val read = session.store.readFirstRentalGift(identity.profileId)) {
            is ContractResult.Failure -> return@withSession read
            is ContractResult.Success -> read.value
        }
        if (state.profileId != identity.profileId || state.source != identity.source)
            return@withSession ContractResult.Failure(ContractError.INVALID_INPUT)
        state.granted?.let { original ->
            pendingGift = original.command
            return@withSession session.store.commitFirstRentalGift(original.command)
        }
        val content = when (val read = session.content) {
            is ContractResult.Failure -> return@withSession read
            is ContractResult.Success -> read.value
        }
        val command = when (val prepared = FirstGrant(content).prepareOpeningGift(session.store, identity.profileId, identity.source)) {
            is ContractResult.Failure -> return@withSession prepared
            is ContractResult.Success -> prepared.value
        }
        pendingGift = command
        session.store.commitFirstRentalGift(command)
    }
}
