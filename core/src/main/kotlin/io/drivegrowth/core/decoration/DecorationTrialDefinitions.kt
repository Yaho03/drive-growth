package io.drivegrowth.core.decoration

import io.drivegrowth.core.contract.*

/** 조정 가능한 시연 시험 정의다. 최종 경제·미술 채택이 아니며 app/저장에서 수치를 복제하지 않는다. */
fun trialDecorationDefinitions() = DecorationDefinitions(RuleVersion("decoration-trial-v1"), true, listOf(
    DecorationDefinition("neutral-sign", "neutral", "기본 간판", 1, GameMoney(0)),
    DecorationDefinition("blue-sign", "blue-sign", "간판 색상", 2, GameMoney(100)),
))
