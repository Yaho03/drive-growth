package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.gameGachaAuthority
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GachaProgress
import io.drivegrowth.core.contract.GachaTicketUse
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.GachaPull
import java.util.UUID

@Suppress("DEPRECATION")
class PackagedContentAuthoritySqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private val casper = VehicleId("hyundai_casper")

    fun testReopenedLedgerUsesPackagedAuthorityAndPreservesFirstGachaResult() {
        val database = "content-version-${UUID.randomUUID()}.db"
        val databases = mapOf(source to database)
        try {
            val oldContent = previousContent()
            assertEquals(ContentVersion("trial-content-2026-11-07"), oldContent.catalog.contentVersion)
            assertEquals(GameMoney(8_000), casperPrice(oldContent))
            val oldPull = GachaPull(oldContent)
            val firstRequest = RequestId("content-version-first-draw")
            val nextRequest = RequestId("content-version-next-draw")
            val (identity, original, staleCommand) = SqliteGameChangeStore(
                context, source, database,
                gachaAuthority = { gameGachaAuthority(oldContent, source, 1, 3) },
                initialGachaProgress = GachaProgress(Revision(0), 0, 0),
                initializationAuthority = {
                    ContractResult.Success(InitializationAuthority(source, oldContent, FirstGrant.CANDIDATES.toSet()))
                },
            ).use { store ->
                val profile = ok(store.localProfileId())
                ok(FirstGrant(oldContent).choose(store, profile, casper))
                val command = ok(oldPull.prepare(store, firstRequest, GachaTicketUse.PURCHASED))
                val receipt = ok(store.commitGacha(command))
                val stale = ok(oldPull.prepare(store, nextRequest, GachaTicketUse.PURCHASED))
                assertEquals(GameMoney(500), receipt.settlement.balance)
                assertEquals(GameMoney(500), receipt.settlement.changedVehicle?.acquisitionCost)
                Triple(GameIdentity(profile, source), receipt, stale)
            }

            val next = ok(GameSessionFactory(context, databases, source).open()).use { game ->
                assertEquals(identity, game.identity)
                val current = ok(game.content)
                val newVersion = ContentVersion("trial-content-2026-11-09")
                assertEquals(newVersion, current.catalog.contentVersion)
                assertEquals(oldContent.ruleVersion, current.ruleVersion)
                assertEquals(GameMoney(1_000), casperPrice(current))
                val store = game.store
                val state = ok(store.readGachaState())
                assertEquals(newVersion, state.authority.contentVersion)
                assertEquals(GameMoney(500), state.authority.purchasePrice)
                assertEquals(original.progress, state.progress)
                val owned = ok(store.listOwnedVehicles())
                val settlements = ok(store.listUnviewedSettlements())
                assertEquals(2, owned.size)
                assertEquals(2, settlements.size)
                assertTrue(original.settlement in settlements)

                val conflict = ContractResult.Failure(ContractError.STATE_CONFLICT)
                assertEquals(conflict, oldPull.prepare(store, nextRequest, GachaTicketUse.PURCHASED))
                assertEquals(conflict, store.commitGacha(staleCommand))
                assertEquals(conflict, store.commitGacha(original.command.copy(contentVersion = newVersion)))
                assertEquals(original, ok(store.commitGacha(original.command)))
                val preserved = ok(store.readGachaState())
                assertEquals(newVersion, preserved.authority.contentVersion)
                assertEquals(state.progress, preserved.progress)
                assertEquals(GameMoney(500), ok(store.balance()))
                assertEquals(owned, ok(store.listOwnedVehicles()))
                assertEquals(settlements, ok(store.listUnviewedSettlements()))

                val currentCommand = ok(GachaPull(current).prepare(store, nextRequest, GachaTicketUse.PURCHASED))
                // PR #152는 기준액과 콘텐츠 버전만 바꿨다. 기존 계획과 새 명령의 차이는 버전뿐이다.
                assertEquals(staleCommand.copy(contentVersion = newVersion), currentCommand)
                ok(store.commitGacha(currentCommand))
            }

            ok(GameSessionFactory(context, databases, source).open()).use { game ->
                assertEquals(identity, game.identity)
                val store = game.store
                assertEquals(GameMoney(0), ok(store.balance()))
                assertEquals(next.progress, ok(store.readGachaState()).progress)
                assertEquals(original, ok(store.commitGacha(original.command)))
                assertEquals(next, ok(store.commitGacha(next.command)))
                assertEquals(GameMoney(0), ok(store.balance()))
                assertEquals(next.progress, ok(store.readGachaState()).progress)
                val owned = ok(store.listOwnedVehicles())
                assertEquals(3, owned.size)
                assertTrue(original.settlement.changedVehicle in owned)
                assertTrue(next.settlement.changedVehicle in owned)
                val settlements = ok(store.listUnviewedSettlements())
                assertEquals(3, settlements.size)
                assertTrue(original.settlement in settlements)
                assertTrue(next.settlement in settlements)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    private fun previousContent(): GameContentSnapshot = ok(AssetGameContent { path ->
        val packaged = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (path == "content/game-content.csv") {
            // APK의 CSV에서 PR #152가 바꾼 두 메타데이터를 이전 값으로 되돌린다.
            packaged.replaceFirst("# content_version=trial-content-2026-11-09", "# content_version=trial-content-2026-11-07")
                .replaceFirst("# price_unit=1000\n", "# price_unit=8000\n")
        } else packaged
    }.load().toResult())

    private fun casperPrice(content: GameContentSnapshot): GameMoney =
        content.vehicles.single { it.vehicleId == casper }.price

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
