package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class CheckpointGachaSourceTest {
    private class Journal : GachaCommandJournal {
        var saved:GachaCommand?=null
        var saveFailure=false
        var finishFailure=false
        var executes=0
        var finishes=0
        lateinit var result:GachaSettlement
        override fun cancelUnstarted(command:GachaCommand):ContractResult<Unit> = ContractResult.Failure(ContractError.STATE_CONFLICT)
        override fun pending()=ContractResult.Success(saved)
        override fun save(command:GachaCommand):ContractResult<Unit> {
            if(saveFailure) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            if(saved!=null && saved!=command) return ContractResult.Failure(ContractError.STATE_CONFLICT)
            saved=command;return ContractResult.Success(Unit)
        }
        override fun execute(command:GachaCommand):ContractResult<GachaSettlement> {
            assertEquals(command,saved);executes++;return ContractResult.Success(result)
        }
        override fun finish(command:GachaCommand):ContractResult<GachaSettlement> {
            finishes++;if(finishFailure)return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            saved=null;return ContractResult.Success(result)
        }
    }
    private val command=GachaCommand(RequestId("draw"),InputSource.SIMULATION,ContentVersion("v"),RuleVersion("r"),
        Revision(0),1,3,0,GachaTicketUse.PURCHASED,GachaAward(
            OwnedVehicle(OwnedVehicleId("owned"),VehicleId("ioniq-5"),1,Revision(0),VehicleLifecycle.OWNED,
                Rarity.COMMON,0,GameMoney(500),VehicleAcquisitionSource.GACHA),1,false))
    private fun receipt()=GachaSettlement(command,Settlement(SettlementId("s"),Transaction(TransactionId("t"),command.requestId,
        TransactionKind.GARAGE,command.source,command.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(500))),
        GameMoney(500),command.award.vehicle,EpochMillis(1)),GachaProgress(Revision(1),1,0))
    private fun fixture():Pair<CheckpointGachaSource,Journal> {
        val j=Journal();j.result=receipt()
        val source=object:GachaHostSource {
            override fun read(paid:RequestId,free:RequestId):ContractResult<GachaOffers> = error("복구는 현재 조회를 하지 않는다")
            override fun prepare(input:GachaDrawInput):ContractResult<GachaCommand> = error("복구는 재추첨하지 않는다")
            override fun commit(command:GachaCommand):ContractResult<GachaSettlement> = error("직접 원장 commit 우회 금지")
        }
        return CheckpointGachaSource(source,j) to j
    }
    @Test fun `저장 실패는 실행과 finish를 호출하지 않는다`() {
        val (s,j)=fixture();j.saveFailure=true
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),s.commit(command));assertEquals(0,j.executes);assertEquals(0,j.finishes)
    }
    @Test fun `finish 실패 뒤 재시작은 보관한 같은 명령과 당시 결과만 재생한다`() {
        val(s,j)=fixture();j.finishFailure=true
        assertTrue(s.commit(command) is ContractResult.Success);assertEquals(0,j.finishes)
        assertTrue(s.finish(command) is ContractResult.Failure);assertEquals(command,j.saved)
        j.finishFailure=false
        assertEquals(command,(s.pending() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(receipt()),s.commit(command));assertEquals(command,j.saved)
        assertEquals(ContractResult.Success(receipt()),s.finish(command));assertNull(j.saved);assertEquals(2,j.executes)
    }
    @Test fun `다른 명령은 충돌하며 원래 요청을 지우지 않는다`() {
        val(s,j)=fixture();j.saved=command
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),s.commit(command.copy(requestId=RequestId("other"))))
        assertEquals(command,j.saved);assertEquals(0,j.executes)
    }
    @Test fun `잘못된 실제 결과는 finish 전에 거절한다`() {
        val(s,j)=fixture();j.result=receipt().copy(command=command.copy(requestId=RequestId("wrong")))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),s.commit(command));assertEquals(command,j.saved);assertEquals(0,j.finishes)
    }
}
