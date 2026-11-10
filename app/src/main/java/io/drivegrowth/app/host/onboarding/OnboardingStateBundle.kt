package io.drivegrowth.app.host.onboarding

import io.drivegrowth.app.host.GameIdentity

import android.os.Bundle
import io.drivegrowth.core.contract.*

/** 선택/화면만 복원한다. 완료 여부·NEW·잔액·티켓·원래 정산은 Bundle에서 만들지 않는다. */
object OnboardingStateBundle {
    fun encode(state: OnboardingHostUiState) = Bundle().apply {
        putString("profile", state.identity?.profileId?.value); putString("source", state.identity?.source?.name)
        putString("step", state.step.name); putString("selected", state.selected?.value); putString("pending", state.pending?.value)
    }
    fun decode(value: Bundle?): OnboardingHostUiState { return try {
        val profile = value?.getString("profile") ?: return OnboardingHostUiState()
        val source = InputSource.entries.firstOrNull { it.name == value.getString("source") } ?: return OnboardingHostUiState()
        val step = OnboardingStep.entries.firstOrNull { it.name == value.getString("step") } ?: return OnboardingHostUiState()
        OnboardingHostUiState(GameIdentity(ProfileId(profile), source), step,
            value.getString("selected")?.let(::VehicleId), value.getString("pending")?.let(::VehicleId))
    } catch (_: RuntimeException) { OnboardingHostUiState() }
    }
}
