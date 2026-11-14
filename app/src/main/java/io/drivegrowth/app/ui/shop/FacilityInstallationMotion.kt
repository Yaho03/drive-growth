package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.FacilityReceipt
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.SettlementId
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.rental.Facility

/** 상호작용이 시작된 화면의 현재 프로필과 원요청에만 확정 연출을 연결한다. */
internal class FacilityInstallationMotion {
    private var submission: Pair<GameIdentity, ShopRequest>? = null
    private val presented = mutableSetOf<Pair<GameIdentity, SettlementId>>()

    fun submit(identity: GameIdentity?, request: ShopRequest, visible: Boolean): Boolean {
        if (identity == null || !visible || request.selection.option.availability != ShopAvailability.AVAILABLE ||
            request.selection.option.command !is ShopCommand.BuyFacility) return false
        submission = identity to request
        return true
    }

    fun confirm(request: ShopRequest, receipt: FacilityReceipt.Purchased, visible: Boolean): Facility? {
        val (identity, pending) = submission ?: return null
        if (pending != request) return null
        submission = null
        val offer = (request.selection.option.command as? ShopCommand.BuyFacility)?.offer ?: return null
        val command = receipt.command
        val settlement = receipt.settlement
        val transaction = settlement.transaction
        if (!visible || identity != command.identity || command.requestId != request.requestId || command.offer != offer ||
            transaction.source != identity.source || transaction.requestId != request.requestId ||
            transaction.kind != TransactionKind.FACILITY || transaction.ruleVersion != offer.ruleVersion ||
            transaction.money != MoneyChange(MoneyDirection.DEBIT, offer.price) || settlement.changedVehicle != null ||
            !presented.add(identity to settlement.settlementId)) return null
        return offer.facility
    }

    fun complete(request: ShopRequest) {
        if (submission?.second == request) clear()
    }

    fun clear() { submission = null }
}

/** 설치 여부는 purchase adapter가 같은 쓰기 거래에서 확인한 단일 시설에만 적용된다. */
internal class InstalledFacilityEffect(val facility: Facility, val revision: Revision, private val confirmedAt: Long) {
    private var startedAt: Long? = null

    fun canDraw(now: Long): Boolean = startedAt?.let { now - it < DURATION_MS } ?: (now - confirmedAt < 1_800L)

    fun progress(now: Long): Float {
        val start = startedAt ?: now.also { startedAt = it }
        return ((now - start).toFloat() / DURATION_MS).coerceIn(0f, 1f)
    }

    private companion object { const val DURATION_MS = 1_600L }
}
