package io.drivegrowth.app.host.shop

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.Facility
import java.util.UUID

/** 공개 첫 지급을 사용하는 격리 SIMULATION fixture. 제품 실행/SDK 입력 시험이 아니다. */
@Suppress("DEPRECATION")
class SessionHomeShopReaderTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource, String>
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    override fun setUp() {
        super.setUp()
        val prefix = "home-reader-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
    }

    override fun tearDown() {
        names.values.forEach(context::deleteDatabase)
        super.tearDown()
    }

    private fun initialized(): GameSessionFactory {
        val games = GameSessionFactory(context, names, InputSource.SIMULATION)
        val onboarding = StoredOnboardingHostSource(games)
        val before = ok(onboarding.read())
        ok(onboarding.choose(before.identity, FirstGrant.CANDIDATES.first()))
        return games
    }

    private fun connection(game: GameSession): SQLiteDatabase = ok(game.store.atomic { ContractResult.Success(it) })

    fun testActualFactsShareBorrowedConnectionAndReadCreatesNoMoneyVehicleOrCheckpoint() {
        val games = initialized()
        val reader = SessionHomeShopReader(context, games)
        val identity = ok(games.open()).use { game ->
            val db = connection(game)
            val vehicle = ok(game.store.listOwnedVehicles()).single()
            val facts = ok(reader.read(game))
            assertTrue("호출자의 연결을 닫지 않는다", db.isOpen)
            assertSame(db, connection(game))
            assertEquals(game.identity, facts.identity)
            assertEquals(InputSource.SIMULATION, facts.identity.source)
            assertEquals(GameMoney(1000), facts.screen.balance)
            assertEquals(1, facts.screen.level)
            assertEquals(1, facts.screen.ownedCount)
            assertEquals(3, facts.screen.capacity)
            assertEquals(listOf(vehicle.ownedVehicleId), facts.screen.vehicles.map { it.id })
            assertEquals(Facility.entries.toSet(), facts.screen.facilities.map { it.facility }.toSet())
            assertEquals(ok(game.store.rental.shop()), facts.facility.shop)
            assertEquals(GameMoney(1000), ok(game.store.balance()))
            assertEquals(listOf(vehicle), ok(game.store.listOwnedVehicles()))
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                game.store.decorations.state(game.identity.profileId))
            assertEquals(0, facts.expansion.completedRentals)
            assertEquals(facts, ok(reader.read(game)))
            game.identity
        }
        ok(games.open()).use { game ->
            assertEquals(identity, game.identity)
            assertEquals(GameMoney(1000), ok(game.store.balance()))
        }
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }

    fun testWrongProfileIsRejectedAndBorrowedConnectionRemainsUsable() {
        val games = initialized()
        val reader = SessionHomeShopReader(context, games)
        ok(games.open()).use { game ->
            val db = connection(game)
            val wrong = GameSession(GameIdentity(ProfileId("wrong-profile"), game.identity.source), game.store, game.content)
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), reader.read(wrong))
            assertTrue(db.isOpen)
            assertSame(db, connection(game))
            assertEquals(GameMoney(1000), ok(game.store.balance()))
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        }
    }

    fun testWrongSourceIdentityIsRejectedWithoutOpeningAnotherModeOrClosingStore() {
        val games = initialized()
        val reader = SessionHomeShopReader(context, games)
        ok(games.open()).use { game ->
            val db = connection(game)
            val wrong = GameSession(GameIdentity(game.identity.profileId, InputSource.VEHICLE), game.store, game.content)
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), reader.read(wrong))
            assertTrue(db.isOpen)
            assertSame(db, connection(game))
            assertEquals(GameMoney(1000), ok(game.store.balance()))
            assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
        }
    }
}
