package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 core 준비와 UI/호스트 흐름을 실행한다. 저장 경계 응답은 통제 입력이며 SQLite 시험과 구분한다. */
class GachaGuaranteedHostTest {
    @Test fun `정확한 보장 비율로 기존 추첨을 준비하고 보관 명령의 원래 결과만 재생한다`() {
        val content=(AssetGameContent { File("src/main/assets/$it").readText() }.load() as ContentLoad.Loaded).snapshot
        val authority=GachaAuthority(InputSource.SIMULATION,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),
            content.gacha.pity,content.gacha.guaranteedFrom,content.unlockedAt(1).map { it.vehicleId }.toSet(),
            Rarity.entries.filter { content.gacha.weights.getValue(it)>0 }.toSet())
        val commands=mutableListOf<GachaCommand>();var prepares=0
        var receipt:GachaSettlement?=null
        val store=object:GachaChangeStore {
            override fun readGachaState()=ContractResult.Success(GachaState(authority,GachaProgress(Revision(0),29,1)))
            override fun listOwnedVehicles()=ContractResult.Success(emptyList<OwnedVehicle>())
            override fun commitGameChange(change:GameChange):ContractResult<Settlement> = error("일반 거래로 나누면 안 됨")
            override fun commitGacha(command:GachaCommand):ContractResult<GachaSettlement> {
                commands+=command
                val original=receipt
                if(original!=null) { assertEquals(original.command,command);return ContractResult.Success(original) }
                val result=GachaSettlement(command,Settlement(SettlementId("guaranteed"),Transaction(TransactionId("tx"),command.requestId,
                    TransactionKind.GARAGE,command.source,command.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(500))),
                    GameMoney(500),command.award.vehicle,EpochMillis(100)),GachaProgress(Revision(1),0,1))
                receipt=result;return ContractResult.Success(result)
            }
        }
        val actual=GachaStoreHostSource(content,store,{ ContractResult.Success(GameMoney(1000)) },GachaOrigin.FIXTURE)
        val source=object:GachaHostSource by actual {
            override fun prepare(input:GachaDrawInput):ContractResult<GachaCommand> { prepares++;return actual.prepare(input) }
        }
        lateinit var host:GachaHostController
        val ui=GachaInteraction({ event->if(event is GachaEvent.Draw) host.draw(event.request) })
        host=GachaHostController(source,{ it() },{ it() },{ ContractResult.Success(Unit) },
            { frame,gate->ui.render(frame,gate) },{ request,result->ui.complete(request,result) },{})
        host.setControlsEnabled(true);assertTrue(host.load())
        val offers=(ui.frame!!.screen as GachaScreen.Offers).value
        assertEquals(53L,offers.appliedRatio!!.denominator);assertNull(offers.appliedPercent)
        assertTrue(ui.select("paid"));assertTrue(ui.confirm())
        val first=(ui.frame!!.screen as GachaScreen.Result).value.settlement
        val command=commands.single();assertTrue(command.award.guaranteed)
        assertTrue(command.award.vehicle.rarity>=Rarity.RARE);assertEquals(0,command.award.nextCounter)
        assertEquals(command.award.vehicle,first.changedVehicle)
        val frames=mutableListOf<GachaFrame>()
        val restored=GachaHostController(source,{ it() },{ it() },{ ContractResult.Success(Unit) },
            { frame,_->frames+=frame },{ _,_-> },{},host.snapshot())
        restored.setControlsEnabled(true);assertTrue(restored.retry("restore"))
        assertEquals(1,prepares);assertEquals(listOf(command,command),commands)
        assertEquals(first,(frames.last().screen as GachaScreen.Result).value.settlement)
    }
}
