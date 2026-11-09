package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.GachaPull

/**
 * #111 GachaPull이 만든 명령을 #136 SQLite 가챠 저장소로 확정하는 통합 시험.
 * 콘텐츠는 앱에 들어가는 20종 assets를 쓰고, 권위 상태는 그 콘텐츠와 같은 snapshot으로 시험 주입한다(제품 초기값 아님).
 */
@Suppress("DEPRECATION")
class GachaPullSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var content: GameContentSnapshot
    private lateinit var authority: GachaAuthority
    private lateinit var gacha: GachaPull
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun open(freeTickets: Long = 1) = SqliteGameChangeStore(context, source, name,
        gachaAuthority = { ContractResult.Success(authority) },
        initialGachaProgress = GachaProgress(Revision(0), 0, freeTickets))
    private fun reopen() { store.close(); store = open() }
    private fun prepare(id: String, ticket: GachaTicketUse = GachaTicketUse.PURCHASED) =
        gacha.prepare(store, RequestId(id), ticket)

    override fun setUp() {
        super.setUp()
        content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        authority = GachaAuthority(source, content.catalog.contentVersion, content.ruleVersion, 1, 3, GameMoney(500),
            content.gacha.pity, content.gacha.guaranteedFrom, content.unlockedAt(1).map { it.vehicleId }.toSet(),
            Rarity.entries.filter { (content.gacha.weights[it] ?: 0) > 0 }.toSet())
        gacha = GachaPull(content)
        name = "gacha-pull-" + System.nanoTime() + ".db"
        store = open()
        ok(store.commitGameChange(GameChange(RequestId("fund"), TransactionKind.GARAGE, source, content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(2_000)))))
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    fun testPreparedCommandCommitsAndReplaysAfterReopen() {
        val command = ok(prepare("draw"))
        val first = ok(store.commitGacha(command))
        assertEquals(GameMoney(1_500), first.settlement.balance)
        assertEquals(command.award.vehicle, first.settlement.changedVehicle)
        assertTrue(command.award.vehicle.vehicleId in content.unlockedAt(1).map { it.vehicleId })
        assertEquals(GachaProgress(Revision(1), command.award.nextCounter, 1), first.progress)

        reopen() // DB를 닫았다 다시 열어도
        assertEquals(first, ok(store.commitGacha(command)))           // 같은 명령은 처음 결과
        assertEquals(first.progress, ok(store.readGachaState()).progress)
        assertEquals(listOf(command.award.vehicle), ok(store.listOwnedVehicles()))
        // 재생은 추가 차감이 없다: 다음 뽑기 후 잔액은 1,000
        assertEquals(GameMoney(1_000), ok(store.commitGacha(ok(prepare("next")))).settlement.balance)
    }

    fun testSameRequestWithDifferentInputConflictsWithoutChange() {
        val first = ok(store.commitGacha(ok(prepare("same"))))
        val again = ok(prepare("same")) // 진행 상태가 바뀌어 다른 명령
        assertFalse(first.command == again)
        val owned = ok(store.listOwnedVehicles())
        val progress = ok(store.readGachaState()).progress
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(again))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(first.command.copy(ticket = GachaTicketUse.FREE)))
        assertEquals(owned, ok(store.listOwnedVehicles()))
        assertEquals(progress, ok(store.readGachaState()).progress)
    }

    fun testLastSlotCommandsFromSameStateCommitOnlyOnce() {
        ok(store.commitGacha(ok(prepare("one"))))
        ok(store.commitGacha(ok(prepare("two"))))
        val a = ok(prepare("race-a"))
        val b = ok(prepare("race-b")) // 같은 상태(보유 2·칸 3)로 계획
        ok(store.commitGacha(a))
        assertTrue(store.commitGacha(b) is ContractResult.Failure)
        assertEquals(3, ok(store.listOwnedVehicles()).size)
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), prepare("race-c"))
    }

    fun testFreeTicketIsUsedOnceWithoutMoney() {
        val free = ok(store.commitGacha(ok(prepare("free", GachaTicketUse.FREE))))
        assertNull(free.settlement.transaction.money)
        assertEquals(GameMoney(2_000), free.settlement.balance)
        assertEquals(GameMoney(0), free.settlement.changedVehicle!!.acquisitionCost)
        assertEquals(0L, free.progress.freeTickets)
        reopen()
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), prepare("free-2", GachaTicketUse.FREE))
    }
}
