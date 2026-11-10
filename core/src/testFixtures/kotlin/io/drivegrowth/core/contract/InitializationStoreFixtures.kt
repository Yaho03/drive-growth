package io.drivegrowth.core.contract

/** 명시 초기화 시험 상태/기록이다. NEW 발급·legacy 복구 또는 영속 저장 구현이 아니다. */
class InitializationFixtureConfiguration(
    val state: ContractResult<InitializationState>,
    val marker: InitializationMarker?,
)
