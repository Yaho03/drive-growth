package io.drivegrowth.app.ui.results

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

/** 독립 반례: 실제 조회가 열람 완료를 먼저 알려도 진행 중 응답 연결을 잃지 않는다. */
class ResultsHistoryIndependentTest {
    private fun row(id:String,viewed:Boolean=false)=ResultPresentation(Settlement(SettlementId(id),
        Transaction(TransactionId("tx-$id"),RequestId("request-$id"),TransactionKind.GARAGE,
            InputSource.SIMULATION,RuleVersion("original"),null),GameMoney(17),null,EpochMillis(1000)),viewed=viewed)
    @Test fun viewedRefreshBeforeAcknowledgementMustNotLeaveGlobalPendingLock() {
        val first=row("first");val second=row("second");val state=ResultsInteraction{}
        val before=ResultsScreenState.Content(listOf(first,second),ResultsOrigin.STORED,true)
        state.render(before,true);state.select(first.settlement.settlementId)
        assertTrue(state.requestMarkViewed(first.settlement.settlementId))
        state.render(before.copy(results=listOf(first.copy(viewed=true),second)),true)
        state.completeMarkViewed(first.settlement.settlementId,ContractResult.Success(Unit))
        assertFalse("확정 열람의 완료 응답 뒤 전역 Pending 잠금이 남으면 안 됨",
            state.uiState.readStates.values.any{it==ResultsReadState.Pending})
        state.select(second.settlement.settlementId)
        assertTrue(state.requestMarkViewed(second.settlement.settlementId))
    }
}
