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

data class OnboardingChoice(val settlement: InitializationSettlement, val newlyCommitted: Boolean)

/** worker 전용. 조회 결과와 확정 결과를 분리하고 같은 프로필의 원래 명령만 재생한다. */
interface OnboardingHostSource {
    fun read(): ContractResult<OnboardingSnapshot>
    fun choose(identity: GameIdentity, vehicleId: VehicleId): ContractResult<InitializationSettlement> =
        when (val result = chooseWithOutcome(identity, vehicleId)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(result.value.settlement)
        }
    fun chooseWithOutcome(identity: GameIdentity, vehicleId: VehicleId): ContractResult<OnboardingChoice>
    fun claimGift(identity: GameIdentity): ContractResult<FirstRentalGiftSettlement>
}

/** 같은 factory/DB에서만 실행한다. 미확정 명령 메모리는 구성 변경용이며 영속 checkpoint가 아니다. */
class StoredOnboardingHostSource(
    private val sessions: GameSessionFactory,
    private val onNewOpeningGift: (FirstRentalGiftSettlement) -> Unit = {},
) : OnboardingHostSource {
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

    override fun chooseWithOutcome(identity: GameIdentity, vehicleId: VehicleId): ContractResult<OnboardingChoice> = withSession(identity) { session ->
        fun commit(command: InitializationCommand): ContractResult<OnboardingChoice> =
            when (val result = session.store.commitInitializationWithOutcome(command)) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> ContractResult.Success(OnboardingChoice(result.value.settlement, result.value.newlyCommitted))
            }

        pendingInitialization?.let { original ->
            if (original.profileId != identity.profileId || original.grant.source != identity.source ||
                (original.grant.vehicle as? VehicleWrite.Add)?.vehicle?.vehicleId != vehicleId)
                return@withSession ContractResult.Failure(ContractError.STATE_CONFLICT)
            return@withSession commit(original)
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
                else { pendingInitialization = original; commit(original) }
            }
            is ProfileInitializationStatus.New -> {
                val content = when (val loaded = session.content) {
                    is ContractResult.Failure -> return@withSession loaded
                    is ContractResult.Success -> loaded.value
                }
                var outcome: OnboardingChoice? = null
                val bridge = object : InitializationChangeStore by session.store {
                    override fun commitInitialization(command: InitializationCommand): ContractResult<InitializationSettlement> {
                        pendingInitialization = command
                        return when (val result = commit(command)) {
                            is ContractResult.Failure -> result
                            is ContractResult.Success -> {
                                outcome = result.value
                                ContractResult.Success(result.value.settlement)
                            }
                        }
                    }
                }
                when (val result = FirstGrant(content).choose(bridge, identity.profileId, vehicleId)) {
                    is ContractResult.Failure -> result
                    is ContractResult.Success -> outcome?.let { ContractResult.Success(it) }
                        ?: ContractResult.Failure(ContractError.STORAGE_FAILURE)
                }
            }
        }
    }

    override fun claimGift(identity: GameIdentity): ContractResult<FirstRentalGiftSettlement> = withSession(identity) { session ->
        fun commit(command: FirstRentalGiftCommand): ContractResult<FirstRentalGiftSettlement> =
            when (val result = session.store.commitFirstRentalGiftWithOutcome(command)) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> {
                    val outcome = result.value
                    if (outcome.newlyCommitted) onNewOpeningGift(outcome.settlement)
                    ContractResult.Success(outcome.settlement)
                }
            }

        pendingGift?.let { command ->
            if (command.profileId != identity.profileId || command.source != identity.source)
                return@withSession ContractResult.Failure(ContractError.INVALID_INPUT)
            return@withSession commit(command)
        }
        val state = when (val read = session.store.readFirstRentalGift(identity.profileId)) {
            is ContractResult.Failure -> return@withSession read
            is ContractResult.Success -> read.value
        }
        if (state.profileId != identity.profileId || state.source != identity.source)
            return@withSession ContractResult.Failure(ContractError.INVALID_INPUT)
        state.granted?.let { original ->
            pendingGift = original.command
            return@withSession commit(original.command)
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
        commit(command)
    }
}
