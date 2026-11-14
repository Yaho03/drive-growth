package io.drivegrowth.app.ui.onboarding

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.FirstRentalGiftSettlement

/** 메인 스레드의 이번 실행 알림. DB 조회나 Activity Bundle에서 재구성하지 않는다. */
internal class OpeningGiftPresentation {
    private var pending: FirstRentalGiftSettlement? = null
    private var seen: FirstRentalGiftSettlement? = null

    fun publish(newlyCommitted: FirstRentalGiftSettlement) {
        if (seen?.settlementId == newlyCommitted.settlementId || pending != null) return
        pending = newlyCommitted
    }

    fun available(identity: GameIdentity, allowed: Boolean): FirstRentalGiftSettlement? =
        pending?.takeIf { allowed && it.command.profileId == identity.profileId && it.command.source == identity.source }

    fun consume(identity: GameIdentity, value: FirstRentalGiftSettlement): Boolean {
        if (available(identity, true) != value) return false
        seen = value
        pending = null
        return true
    }
}

internal fun keepsOpeningGiftDuringLoad(identity: GameIdentity, owner: GameIdentity?, attached: Boolean) =
    attached && identity == owner
