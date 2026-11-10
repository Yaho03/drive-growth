package io.drivegrowth.app.host.progression

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.progress.ClaimReference
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.quest.*
import java.time.ZoneId
import java.util.UUID

/** 실제 공통 factory에 정의를 넣는 조립 시험. 명시 SIMULATION/UTC 격리DB이며 기기 실행 전이다. */
@Suppress("DEPRECATION")
class ProgressionSessionBindingTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource,String>
    private lateinit var games: GameSessionFactory
    private fun <T> ok(r:ContractResult<T>):T { assertTrue(r.toString(),r is ContractResult.Success); return (r as ContractResult.Success).value }
    override fun setUp() {
        super.setUp(); val id=UUID.randomUUID()
        names=InputSource.entries.associateWith { "progress-main-$id-${it.name}.db" }
        games=GameSessionFactory(context,names,InputSource.SIMULATION) {
            when(val r=AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure -> r
                is ContractResult.Success -> r.value.questDefinitions(ZoneId.of("UTC"))
            }
        }
        val onboarding=StoredOnboardingHostSource(games);val state=ok(onboarding.read())
        ok(onboarding.choose(state.identity,FirstGrant.CANDIDATES.first()))
    }
    override fun tearDown() { names.values.forEach(context::deleteDatabase);super.tearDown() }
    private fun source()=FactoryProgressionSource(games){Observation.Available(EpochMillis(System.currentTimeMillis()))}
    fun testSharedFactoryPreservesInitialGrantAndSixActualQuestDefinitions() {
        val before=ok(games.open()).use { game ->
            assertTrue(ok(game.store.readInitialization(game.identity.profileId)).status is ProfileInitializationStatus.Existing)
            assertEquals(1,ok(game.store.listOwnedVehicles()).size)
            assertEquals(GameMoney(1000),ok(game.store.balance()))
            game.identity
        }
        val read=ok(source().read(emptySet()))
        assertEquals(before,read.identity);assertEquals(6,read.quests.size);assertTrue(read.journalSupported)
        assertTrue(read.quests.all{it.profileId==before.profileId && it.source==before.source && it.definition.zone==ZoneId.of("UTC")})
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testActualResultViewingHookClaimAndOriginalLookupStayInSameFactory() {
        ok(games.open()).use { game ->
            val grant=ok(game.store.listUnviewedSettlements()).single()
            ok(game.store.markViewed(grant.settlementId)) // 실제 초기 지급 결과 열람. 가짜 Quest 사건을 넣지 않는다.
        }
        val src=source();val read=ok(src.read(emptySet()))
        val command=read.offers.values.first { it.definition.id=="daily-result-viewed" }
        ok(src.save(read.identity,command));val receipt=ok(src.execute(read.identity,command))
        assertEquals(GameMoney(1000+command.definition.reward.units),receipt.settlement.balance)
        val reference=ClaimReference(progressKey(receipt.progress),100,command.requestId.value)
        val replay=ok(source().read(setOf(reference)))
        assertEquals(receipt,replay.resolved[reference])
        ok(games.open()).use { game ->
            assertEquals(read.identity,game.identity);assertEquals(receipt.settlement.balance,ok(game.store.balance()))
            assertEquals(1,ok(game.store.listOwnedVehicles()).size);assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        }
    }
}
