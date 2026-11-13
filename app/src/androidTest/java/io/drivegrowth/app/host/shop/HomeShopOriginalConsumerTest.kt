package io.drivegrowth.app.host.shop

import android.os.Looper
import android.os.Parcel
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.facility.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.shop.decoration.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.DecorationCommand
import io.drivegrowth.core.decoration.DecorationSettlement
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.Executor

/** 실제 factory/SQLite 원결과와 HOME 소비 시험. 허용 gate·대여 시간은 명시 SIMULATION fixture다. */
@Suppress("DEPRECATION")
class HomeShopOriginalConsumerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource, String>
    private val appearances = mapOf("neutral" to ShopAppearance("기본 간판 · 시연", 0xff8fbcff.toInt()),
        "blue-sign" to ShopAppearance("간판 색상 · 시연", 0xff8fbcff.toInt()))
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun factory() = GameSessionFactory(context, names, InputSource.SIMULATION)
    private fun <T> read(games: GameSessionFactory, action: (GameSession) -> T): T = ok(games.open()).use(action)
    private fun identity(games: GameSessionFactory) = read(games) { it.identity }

    override fun setUp() {
        super.setUp()
        assertTrue("제품 패키지에서 실행하지 않는다", context.packageName != "io.drivegrowth.app")
        assertTrue("실제 factory의 worker 경계를 지킨다", Looper.myLooper() != Looper.getMainLooper())
        val prefix = "home-original-consumer-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        names.values.forEach { assertFalse(context.getDatabasePath(it).exists()) }
    }
    override fun tearDown() {
        if (::names.isInitialized) names.values.forEach(context::deleteDatabase)
        super.tearDown()
    }
    private fun initialized(): GameSessionFactory = factory().also { games ->
        val onboarding = StoredOnboardingHostSource(games)
        val before = ok(onboarding.read())
        ok(onboarding.choose(before.identity, FirstGrant.CANDIDATES.first()))
        read(games) { game ->
            assertEquals(before.identity, game.identity); assertEquals(InputSource.SIMULATION, game.identity.source)
            assertEquals(GameMoney(1000), ok(game.store.balance()))
        }
    }
    private fun rows(games: GameSessionFactory): List<String> = read(games) { game -> ok(game.store.read { db ->
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        buildList {
            db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); add("schema:${it.getInt(0)}") }
            for (table in tables) {
                add("table:$table")
                db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                    while (cursor.moveToNext()) add(table + ":" + (0 until cursor.columnCount).joinToString("|") {
                        "${cursor.getType(it)}:" + if (cursor.isNull(it)) "<null>" else cursor.getString(it)
                    })
                }
            }
        }
    }) }
    private fun financialRows(games: GameSessionFactory, id: RequestId): Long = read(games) { game -> ok(game.store.read { db ->
        db.rawQuery("SELECT count(*) FROM settlements WHERE request_id=?", arrayOf(id.value)).use {
            it.moveToFirst(); it.getLong(0)
        }
    }) }
    private fun restored(request: ShopRequest): ShopUiState {
        val original = ShopUiState(ShopMode.VEHICLES, request.selection, request,
            requestIds = mapOf(request.selection to request.requestId), latestRevision = request.selection.revision,
            message = "확정 응답을 받기 전 보관한 요청", sceneScrollY = 31, controlsScrollY = 79)
        val parcel = Parcel.obtain()
        val copy = try {
            parcel.writeBundle(ShopUiStateBundle.encode(original)); parcel.setDataPosition(0)
            ok(ShopUiStateBundle.decodePresent(parcel.readBundle(javaClass.classLoader)))
        } finally { parcel.recycle() }
        assertEquals(original, copy)
        return copy
    }
    private class TracedPort(private val actual: HomeShopPort) : HomeShopPort by actual {
        var identities = 0; var originals = 0; var currentLoads = 0
        val originalCalls = mutableListOf<Pair<GameIdentity, ShopRequest>>()
        override fun pendingIdentity(): ContractResult<HomeShopLoad> { identities++; return actual.pendingIdentity() }
        override fun load(): ContractResult<HomeShopLoad> { currentLoads++; return actual.load() }
        override fun original(identity: GameIdentity, request: ShopRequest, facility: FacilityCommand.Buy?): ContractResult<HostCheckpointResult?> {
            originals++; originalCalls.add(identity to request)
            return actual.original(identity, request, facility)
        }
    }
    private inner class Harness(games: GameSessionFactory, owner: GameIdentity, ui: ShopUiState) {
        val work = ArrayDeque<Runnable>(); val replies = ArrayDeque<Runnable>()
        private val worker = Executor { work.addLast(it) }; private val reply = Executor { replies.addLast(it) }
        val source = TracedPort(FactoryHomeShopPort(context, games))
        private val facility = FacilityHostController(FactoryFacilityHostSource(context, games, CheckpointFacilityDurablePort()), worker, reply)
        val host = HomeShopHost(source, facility, worker, reply, ui, restoredIdentity = owner)
        val interaction = ShopInteraction({})
        val completed = mutableListOf<Pair<ShopRequest, ContractResult<Unit>>>()
        init {
            interaction.restoreUiState(ui)
            host.attach({ state, enabled -> interaction.render(state.screen, enabled) },
                { request, result -> completed.add(request to result); interaction.complete(request, result) }, interaction::completeRetry)
        }
        fun start() {
            host.setControlsEnabled(true)
            assertFalse("시작이 noop이면 성공으로 세지 않는다", work.isEmpty())
            drain()
        }
        fun drain() {
            assertTrue(Looper.myLooper() != Looper.getMainLooper())
            var steps = 0
            while (work.isNotEmpty() || replies.isNotEmpty()) {
                check(++steps < 500) { "HOME 큐가 수렴하지 않는다" }
                if (work.isNotEmpty()) work.removeFirst().run()
                if (replies.isNotEmpty()) replies.removeFirst().run()
            }
        }
        fun close() { host.release(); facility.release(); drain() }
    }
    private fun preparedDecoration(games: GameSessionFactory, id: String, item: String): Triple<DecorationBackend, ShopRequest, DecorationCommand> {
        val owner = identity(games)
        val backend = FactoryHomeShopPort(context, games).decoration(owner) {}
        ok(backend.activate()) // 명시적인 fixture 도입. 복원 소비에서 자동 활성화하지 않는다.
        val facts = ok(backend.read()); val mapper = DecorationMapper(appearances); val revision = Revision(41)
        val screen = ok(mapper.map(facts, revision))
        val option = screen.decorations.single { (it.command as? ShopCommand.Decorate)?.decorationId == item }
        assertEquals(ShopAvailability.AVAILABLE, option.availability)
        val request = ShopRequest(RequestId(id), ShopSelection(revision, option), "fixture-original-attempt")
        return Triple(backend, request, ok(mapper.command(facts, revision, request)))
    }
    private fun commitDecoration(prepared: Triple<DecorationBackend, ShopRequest, DecorationCommand>): DecorationSettlement {
        val (backend, _, command) = prepared
        ok(backend.save(command)); val original = ok(backend.execute(command)); ok(backend.finish(command))
        assertEquals(command, original.command)
        return original
    }
    private fun completeRentals(games: GameSessionFactory) = read(games) { game ->
        val car = ok(game.store.listOwnedVehicles()).single()
        val catalog = ok(game.content).catalog
        for (index in 0..2) {
            val start = index * 4_500_000L; val id = RequestId("home-consumer-rental-$index")
            assertEquals(car.ownedVehicleId, ok(game.store.rental.assign(RentalRequest(id, car.vehicleId, 1), catalog,
                mapOf(car.vehicleId to RentalCategory.SMALL), mapOf(car.vehicleId to GameMoney(100)),
                RentalTerms(RuleVersion("home-consumer-explicit-rental-fixture"), SimulatedCustomer(30, 48), 99,
                    EpochMillis(start), 15, GameMoney(0), RoundingMode.DOWN, false))))
            ok(game.store.rental.account(id, EpochMillis(start), EpochMillis(start + 3_600_000), null))
            ok(game.store.rental.settle(id)); ok(game.store.rental.finishReturn(id, EpochMillis(start + 4_500_000)))
        }
        assertEquals(3, ok(game.store.rental.completedRentalCount()))
    }
    private fun assertRecovered(harness: Harness, owner: GameIdentity, request: ShopRequest, original: HostCheckpointResult) {
        assertEquals(1, harness.source.originals)
        assertEquals(listOf(owner to request), harness.source.originalCalls)
        assertTrue(harness.source.identities >= 1); assertTrue(harness.source.currentLoads >= 1)
        assertEquals(listOf(request to ContractResult.Success(Unit)), harness.completed)
        assertEquals(original, harness.host.state.original)
        assertNull(harness.host.uiState.pending); assertNull(harness.interaction.uiState.pending)
        assertTrue(harness.host.state.screen is ShopScreenState.Content)
        assertNull(harness.host.state.error)
    }

    fun testFinishedFreeDecorationRestoresParcelPendingFromActualOriginalExactlyOnce() {
        val games = initialized(); val owner = identity(games)
        val prepared = preparedDecoration(games, "consumer-free-original", "neutral-sign")
        val original = commitDecoration(prepared); val request = prepared.second
        assertNull(original.settlement); assertEquals(GameMoney(1000), original.state.balance)
        assertEquals(0L, financialRows(games, request.requestId))
        read(games) { assertNull(ok(it.store.hostCommands.load(owner.profileId))) }
        val reopened = factory(); assertEquals(owner, identity(reopened)); val before = rows(reopened)
        val harness = Harness(reopened, owner, restored(request))
        try {
            harness.start(); assertRecovered(harness, owner, request, HostCheckpointResult.Decoration(original))
            val current = harness.host.state.screen as ShopScreenState.Content
            assertEquals(GameMoney(1000), current.balance); assertEquals("기본 간판 · 시연", current.appearance.sign)
            assertTrue(harness.host.refresh()); harness.drain()
            assertRecovered(harness, owner, request, HostCheckpointResult.Decoration(original))
            assertEquals(before, rows(reopened)); assertEquals(0L, financialRows(reopened, request.requestId))
        } finally { harness.close() }
        assertEquals(before, rows(reopened))
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }

    fun testMissingOriginalPreservesRestoredPendingAndAllRowsWithoutIssuingCommands() {
        val games = initialized(); val owner = identity(games)
        val request = preparedDecoration(games, "consumer-never-committed", "neutral-sign").second
        val reopened = factory(); assertEquals(owner, identity(reopened)); val before = rows(reopened)
        val ui = restored(request); val harness = Harness(reopened, owner, ui)
        try {
            harness.start()
            repeat(2) { attempt ->
                assertEquals(attempt + 1, harness.source.originals)
                assertEquals(request, harness.host.uiState.pending); assertEquals(request, harness.interaction.uiState.pending)
                assertTrue(harness.completed.isEmpty()); assertNull(harness.host.state.original)
                assertEquals(ContractError.DATA_UNAVAILABLE, harness.host.state.error)
                assertTrue(harness.host.state.screen is ShopScreenState.Failure)
                assertEquals(before, rows(reopened)); assertEquals(0L, financialRows(reopened, request.requestId))
                read(reopened) { game ->
                    assertNull(ok(game.store.hostCommands.load(owner.profileId)))
                    val module = ok(game.store.decorations.state(owner.profileId))
                    assertEquals(Revision(0), module.revision); assertTrue(module.ownedIds.isEmpty()); assertNull(module.selectedId)
                }
                if (attempt == 0) { assertTrue(harness.host.refresh()); harness.drain() }
            }
            assertEquals(listOf(owner to request, owner to request), harness.source.originalCalls)
            assertEquals(0, harness.source.currentLoads)
        } finally { harness.close() }
        assertEquals(before, rows(reopened))
    }

    fun testFinishedPaidExpansionKeepsOriginalReceiptSeparateFromCurrentFacts() {
        val games = initialized(); val owner = identity(games); completeRentals(games)
        val expansion = FactoryHomeShopPort(context, games).expansion
        val snapshot = ok(expansion.read()); val revision = Revision(41)
        val option = checkNotNull(snapshot.screen.expansion)
        assertEquals(ShopAvailability.AVAILABLE, option.availability)
        assertEquals(GameMoney(500), option.price) // PRD의 중앙 시연 시험값. 대여 수익/잔액을 강제하지 않는다.
        val request = ShopRequest(RequestId("consumer-finished-expansion"), ShopSelection(revision, option), "expansion-original-attempt")
        val command = ok(expansion.prepare(snapshot.copy(screen = snapshot.screen.copy(revision = revision)), request))
        val original = ok(expansion.execute(command)); assertEquals(original, ok(expansion.finish(command)))
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), original.transaction.money)
        val afterExpansion = read(games) { ok(it.store.balance()) }; assertEquals(original.balance, afterExpansion)
        val later = commitDecoration(preparedDecoration(games, "consumer-later-paid-decoration", "blue-sign"))
        assertNotNull(later.settlement)
        val latest = read(games) { ok(it.store.balance()) }
        assertEquals(GameMoney(original.balance.units - later.command.expectedPrice.units), latest)
        assertFalse(original.balance == latest)
        val reopened = factory(); assertEquals(owner, identity(reopened)); val before = rows(reopened)
        val harness = Harness(reopened, owner, restored(request))
        try {
            harness.start(); assertRecovered(harness, owner, request, HostCheckpointResult.Expand(original))
            val current = harness.host.state.screen as ShopScreenState.Content
            assertEquals(latest, current.balance); assertEquals(2, current.level)
            assertEquals(GameMoney(1500), current.expansion!!.price) // 현재 중앙 정의의 다음 단계 시험값.
            assertEquals("간판 색상 · 시연", current.appearance.sign)
            assertEquals(original, (harness.host.state.original as HostCheckpointResult.Expand).value)
            assertEquals(before, rows(reopened)); assertEquals(1L, financialRows(reopened, request.requestId))
        } finally { harness.close() }
        assertEquals(before, rows(reopened))
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
}
