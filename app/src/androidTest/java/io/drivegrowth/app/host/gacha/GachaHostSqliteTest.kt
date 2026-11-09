package io.drivegrowth.app.host.gacha

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*

/** #145와 같은 assets/SQLite 경로를 새 호스트로 호출한다. SIMULATION 권위 주입은 제품 초기화가 아니다. */
@Suppress("DEPRECATION")
class GachaHostSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private val content get() = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
    private val authority get() = GachaAuthority(InputSource.SIMULATION, content.catalog.contentVersion, content.ruleVersion,
        1, 3, GameMoney(500), content.gacha.pity, content.gacha.guaranteedFrom,
        content.unlockedAt(1).map { it.vehicleId }.toSet(), Rarity.entries.filter { content.gacha.weights.getValue(it) > 0 }.toSet())
    private fun open() = SqliteGameChangeStore(context, InputSource.SIMULATION, name,
        gachaAuthority = { ContractResult.Success(authority) }, initialGachaProgress = GachaProgress(Revision(0), 0, 1))
    private fun <T> ok(result: ContractResult<T>): T { assertTrue(result.toString(), result is ContractResult.Success); return (result as ContractResult.Success).value }
    private fun source() = StoredGachaHostSource(context, InputSource.SIMULATION, ::open)
    override fun setUp() {
        super.setUp();name = "gacha-host-${System.nanoTime()}.db"
        open().use { ok(it.commitGameChange(GameChange(RequestId("host-fund"), TransactionKind.GARAGE,
            InputSource.SIMULATION, content.ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(2000))))) }
    }
    override fun tearDown() { context.deleteDatabase(name);super.tearDown() }

    fun testControllerUsesRealAssetsAndStoredResultAndRestoresOriginalCommand() {
        val frames = mutableListOf<GachaFrame>()
        var checkpoint = GachaHostState()
        val source = source()
        val host = GachaHostController(source, { it() }, { it() }, { checkpoint = it;ContractResult.Success(Unit) },
            { frame, _ -> frames += frame }, { _, _ -> }, {})
        host.setControlsEnabled(true);assertTrue(host.load())
        val offers = (frames.last().screen as GachaScreen.Offers).value
        assertTrue(host.draw(GachaRequest(frames.last().generation, GachaDrawInput(offers,offers.options[0]), "draw")))
        val first = (frames.last().screen as GachaScreen.Result).value.settlement
        assertEquals(GameMoney(1500), first.balance)
        val command = checkpoint.pending!!.command!!
        assertEquals(command.award.vehicle, first.changedVehicle)
        // 이후 별도 요청으로 현재 상태가 바뀌어도 복원은 당시 결과를 재생한다.
        val next = ok(source.read(RequestId("next-paid"), RequestId("next-free")))
        ok(source.commit(ok(source.prepare(GachaDrawInput(next,next.options[0])))))
        val replayFrames = mutableListOf<GachaFrame>()
        val restored = GachaHostController(source(), { it() }, { it() }, { ContractResult.Success(Unit) },
            { frame, _ -> replayFrames += frame }, { _, _ -> }, {}, host.snapshot())
        restored.setControlsEnabled(true);assertTrue(restored.retry("restore"))
        assertEquals(first, (replayFrames.last().screen as GachaScreen.Result).value.settlement)
        open().use { assertEquals(GameMoney(1000),ok(it.balance()));assertEquals(2,ok(it.listOwnedVehicles()).size) }
    }

    fun testFreeSourceAndMissingAuthorityDoNotInjectProductDefaults() {
        val source = source();val value = ok(source.read(RequestId("paid"),RequestId("free")))
        val command = ok(source.prepare(GachaDrawInput(value,value.options[1])))
        val result = ok(source.commit(command))
        assertNull(result.settlement.transaction.money);assertEquals(GameMoney(0),result.settlement.changedVehicle!!.acquisitionCost)
        val next = ok(source.read(RequestId("paid-2"),RequestId("free-2")))
        assertEquals(GachaAvailability.INSUFFICIENT_TICKETS,next.options[1].availability)
        // 같은 격리 DB도 권위 공급자 없이 열면 정상값을 만들어내지 않는다.
        val unavailable = StoredGachaHostSource(context,InputSource.SIMULATION) { SqliteGameChangeStore(context,InputSource.SIMULATION,name) }
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),unavailable.read(RequestId("x"),RequestId("y")))
        assertEquals(result,ok(source.commit(command)))
        assertEquals(result,ok(unavailable.commit(command)))
    }
    fun testGuaranteedRatioKeepsCoreDrawAndStoredReplay() {
        val guaranteedName="$name-guarantee"
        val factory={ SqliteGameChangeStore(context,InputSource.SIMULATION,guaranteedName,
            gachaAuthority={ ContractResult.Success(authority) },initialGachaProgress=GachaProgress(Revision(0),29,1)) }
        try {
            factory().use { ok(it.commitGameChange(GameChange(RequestId("guaranteed-fund"),TransactionKind.GARAGE,
                InputSource.SIMULATION,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(2000))))) }
            val source=StoredGachaHostSource(context,InputSource.SIMULATION,factory)
            val frames=mutableListOf<GachaFrame>()
            val host=GachaHostController(source,{ it() },{ it() },{ ContractResult.Success(Unit) },
                { frame,_->frames+=frame },{ _,_-> },{})
            host.setControlsEnabled(true);assertTrue(host.load())
            val offers=(frames.last().screen as GachaScreen.Offers).value
            assertNull(offers.appliedPercent);assertEquals(53L,offers.appliedRatio!!.denominator)
            val expected=factory().use { ok(io.drivegrowth.core.garage.GachaPull(content).prepare(it,
                offers.options[0].requestId!!,GachaTicketUse.PURCHASED)) }
            assertTrue(host.draw(GachaRequest(frames.last().generation,GachaDrawInput(offers,offers.options[0]),"guaranteed")))
            val command=host.snapshot().pending!!.command!!
            assertEquals(expected,command);assertTrue(command.award.guaranteed)
            assertTrue(command.award.vehicle.rarity>=Rarity.RARE)
            val original=(frames.last().screen as GachaScreen.Result).value.settlement
            val restored=GachaHostController(source,{ it() },{ it() },{ ContractResult.Success(Unit) },
                { frame,_->frames+=frame },{ _,_-> },{},host.snapshot())
            restored.setControlsEnabled(true);assertTrue(restored.retry("same-guaranteed"))
            assertEquals(original,(frames.last().screen as GachaScreen.Result).value.settlement)
            factory().use { assertEquals(GameMoney(1500),ok(it.balance()));assertEquals(1,ok(it.listOwnedVehicles()).size) }
        } finally { context.deleteDatabase(guaranteedName) }
    }

}
