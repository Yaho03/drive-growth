package io.drivegrowth.core.contract

import io.drivegrowth.core.rental.CompletedRental

/** 같은 프로필/출처의 검증된 최초 완료 조회를 명시 주입한다. 실제 RentalLedger/live 공급이 아니다. */
class FirstRentalGiftFixtureConfiguration(
    val profileId: ProfileId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val firstCompletedRental: ContractResult<CompletedRental?>,
    val granted: FirstRentalGiftSettlement? = null,
    val marker: FirstRentalGiftMarker? = null,
    /** 최초 결과 연결 검증용 당시 상태다. 현재 지갑/가챠 진행을 복제하는 권위 상태가 아니다. */
    val originalState: FirstRentalGiftState? = null,
)
