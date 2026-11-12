package io.drivegrowth.app.host.shop

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcel
import android.test.InstrumentationTestRunner
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.facility.FacilityCommand
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.shop.decoration.DecorationMapper
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.app.ui.shop.ShopSceneView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.UUID

/** 각 selector는 별도 fresh package가 필요하다. 실제 Main/SDK gate와 명시 SIMULATION 준비 입력을 구분한다. */
@Suppress("DEPRECATION")
class DecorationHomeActivityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private fun <T> ok(r: ContractResult<T>): T { assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value }
    private fun <T> read(action: (GameSession) -> T) = ok(GameSessionFactory(context).open()).use(action)
    private fun tree(v: View): List<View> = listOf(v) + if(v is ViewGroup)(0 until v.childCount).flatMap{tree(v.getChildAt(it))} else emptyList()
    private fun shop(a: MainActivity) = tree(a.window.decorView).filterIsInstance<ShopView>().single()
    private fun menu(a: MainActivity) = a.window.decorView.findViewWithTag<Spinner>("host:destination")
    private fun await(a: MainActivity, label: String, check: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+30_000;var passed=false;var gate=false
        while(!passed && SystemClock.uptimeMillis()<end) {
            instrumentation.runOnMainSync { gate=menu(a)?.let{it.isShown && it.isEnabled}==true;passed=check() }
            if(!passed)SystemClock.sleep(50)
        }
        if(!passed && !gate)throw IllegalStateException("ENVIRONMENT_GATE_TIMEOUT: $label; 실제 SDK 허용을 조작하지 않는다")
        assertTrue("ASSERTION_STATE: $label",passed)
    }
    private fun click(a: MainActivity, tag: Any) {
        await(a,"활성 버튼 $tag") { a.window.decorView.findViewWithTag<Button>(tag)?.let{it.isShown && it.isEnabled}==true }
        instrumentation.runOnMainSync {
            val b=a.window.decorView.findViewWithTag<Button>(tag)!!
            b.requestRectangleOnScreen(Rect(0,0,b.width,b.height),true);assertTrue(b.performClick())
        }
    }
    private fun fresh() {
        assertTrue(context.packageName!="io.drivegrowth.app")
        for(name in listOf("game-ledger-vehicle.db","game-ledger-simulation.db","unified-simulation.db"))
            assertFalse("각 selector용 fresh package가 필요하며 기존 DB를 삭제하지 않는다: $name",context.getDatabasePath(name).exists())
    }
    private fun counts(game: GameSession)=ok(game.store.read { db ->
        listOf("decoration_requests","settlements").map { table -> db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)} }
    })
    private fun currentShopShown(a: MainActivity, id: String): Boolean {
        val children=tree(shop(a))
        val sign=when(id) { "neutral-sign"->"기본 간판 · 시연"; "blue-sign"->"간판 색상 · 시연"; else->error("시험 기대 외 품목") }
        return children.any { it is ShopSceneView && it.isShown } &&
            children.filterIsInstance<TextView>().any { it.text.toString()==sign }
    }
    private fun awaitState(a: MainActivity,id:String) {
        val end=SystemClock.uptimeMillis()+30_000;var found=false
        while(!found && SystemClock.uptimeMillis()<end) {
            found=read { game -> (game.store.decorations.state(game.identity.profileId) as? ContractResult.Success)?.value?.selectedId==id &&
                ok(game.store.hostCommands.load(game.identity.profileId))==null }
            if(!found)SystemClock.sleep(50)
        }
        assertTrue("꾸미기 확정과 finish: $id",found)
        await(a,"기존 ShopView 재조회") { a.window.decorView.findViewWithTag<View>("decoration:receipt")?.isShown==true && shop(a).uiState.pending==null && currentShopShown(a,id) }
    }
    private fun previewWithoutWrite(a:MainActivity,id:String) {
        val before=read { game -> Triple(ok(game.store.balance()),ok(game.store.decorations.state(game.identity.profileId)),counts(game)) }
        click(a,"shop:preview:decoration:$id")
        assertEquals(before,read { game -> Triple(ok(game.store.balance()),ok(game.store.decorations.state(game.identity.profileId)),counts(game)) })
        instrumentation.runOnMainSync { a.onBackPressed() }
        await(a,"Back은 미리보기만 닫음") { shop(a).uiState.selection==null && menu(a)?.isEnabled==true }
        assertEquals(before,read { game -> Triple(ok(game.store.balance()),ok(game.store.decorations.state(game.identity.profileId)),counts(game)) })
        click(a,"shop:preview:decoration:$id");click(a,"shop:cancel")
        assertEquals(before,read { game -> Triple(ok(game.store.balance()),ok(game.store.decorations.state(game.identity.profileId)),counts(game)) })
    }
    private fun recreateAndCompare(a:MainActivity,expected:Pair<GameIdentity,io.drivegrowth.core.decoration.DecorationState>) {
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false);var next:MainActivity?=null
        try {
            instrumentation.runOnMainSync { a.recreate() }
            next=instrumentation.waitForMonitorWithTimeout(monitor,15_000) as? MainActivity;assertNotNull(next)
            val current=next!!
            await(current,"같은 HOME/원결과 복원") { current.window.decorView.findViewWithTag<View>("decoration:receipt")?.isShown==true && shop(current).uiState.pending==null && currentShopShown(current,expected.second.selectedId!!) }
            assertEquals(expected,read{it.identity to ok(it.store.decorations.state(it.identity.profileId))})
            instrumentation.runOnMainSync {
                val m=menu(current)!!;m.setSelection((0 until m.count).first{m.getItemAtPosition(it).toString().contains("보유 차량")})
            }
            await(current,"차고 탐색") { menu(current)?.selectedItem.toString().contains("보유 차량") }
            instrumentation.runOnMainSync { current.onBackPressed() }
            await(current,"Back HOME") { menu(current)?.selectedItem.toString().contains("가게") && shop(current).isShown }
            assertEquals(expected,read{it.identity to ok(it.store.decorations.state(it.identity.profileId))})
        } finally {instrumentation.removeMonitor(monitor);next?.let{n->instrumentation.runOnMainSync{n.finish()}}}
    }
    fun testActualVehicleColdFreePreviewCancelConfirmAndRecreation() {
        fresh();val first=activity
        click(first,R.string.onboarding_start);click(first,VehicleId("hyundai_casper"));click(first,R.string.onboarding_review)
        click(first,R.string.onboarding_confirm);click(first,R.string.onboarding_to_shop)
        await(first,"명시 활성화 조작") { first.window.decorView.findViewWithTag<Button>("decoration:activate")?.isEnabled==true }
        val original=read { game ->
            assertEquals(InputSource.VEHICLE,game.identity.source);assertEquals(GameMoney(1000),ok(game.store.balance()))
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),game.store.decorations.state(game.identity.profileId))
            game.identity to counts(game)
        }
        click(first,"decoration:activate")
        await(first,"무료 품목 선택 가능") { first.window.decorView.findViewWithTag<Button>("shop:preview:decoration:neutral-sign")?.isEnabled==true }
        read { game ->
            val state=ok(game.store.decorations.state(game.identity.profileId));assertEquals(Revision(0),state.revision)
            assertTrue(state.ownedIds.isEmpty());assertNull(state.selectedId);assertEquals(original.second,counts(game))
        }
        previewWithoutWrite(first,"neutral-sign")
        click(first,"shop:preview:decoration:neutral-sign");click(first,"shop:confirm");awaitState(first,"neutral-sign")
        val stored=read { game ->
            assertEquals(original.first,game.identity);val state=ok(game.store.decorations.state(game.identity.profileId))
            assertEquals(GameMoney(1000),state.balance);assertEquals(setOf("neutral-sign"),state.ownedIds)
            assertEquals(listOf(original.second[0]+1,original.second[1]),counts(game))
            game.identity to state
        }
        await(first,"Lv1 유료 항목 잠김") { first.window.decorView.findViewWithTag<Button>("shop:preview:decoration:blue-sign")?.isEnabled==false }
        recreateAndCompare(first,stored)
    }
    fun testExplicitSimulationPublicLedgerPaidPurchaseAndFreeOwnedReapplyInActualMain() {
        fresh()
        // 제품 데이터나 VEHICLE 입력을 조작하지 않는다. 이 별도 selector의 격리 SIMULATION fixture다.
        val games=GameSessionFactory(context,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);val intro=ok(onboarding.read());ok(onboarding.choose(intro.identity,FirstGrant.CANDIDATES.first()))
        val initial=ok(games.open()).use { game ->
            assertEquals(InputSource.SIMULATION,game.identity.source)
            val car=ok(game.store.listOwnedVehicles()).single()
            val catalog=(game.content as ContractResult.Success).value.catalog
            val rules=RuleVersion("decoration-main-public-fixture")
            for(n in 0..2) {
                val start=n*4_500_000L;val id=RequestId("decoration-main-fixture-rental-$n")
                ok(game.store.rental.assign(RentalRequest(id,car.vehicleId,1),catalog,mapOf(car.vehicleId to RentalCategory.SMALL),
                    mapOf(car.vehicleId to GameMoney(100)),RentalTerms(rules,SimulatedCustomer(30,48),99,EpochMillis(start),15,GameMoney(0),RoundingMode.DOWN,false)))
                ok(game.store.rental.account(id,EpochMillis(start),EpochMillis(start+3_600_000),null));ok(game.store.rental.settle(id))
                ok(game.store.rental.finishReturn(id,EpochMillis(start+4_500_000)))
            }
            assertEquals(3,ok(game.store.rental.completedRentalCount()))
            val expansion=ok(AssetShopExpansionProvider(context.assets).load()).offers.first()
            ok(game.store.rental.expand(RequestId("decoration-main-fixture-expand"),expansion))
            assertEquals(2,ok(game.store.rental.shop()).progress.shopLevel)
            game.identity to ok(game.store.balance())
        }
        val first=activity
        await(first,"실제 Main의 SIMULATION 프로필 HOME") { first.window.decorView.findViewWithTag<Button>("decoration:activate")?.isEnabled==true }
        click(first,"decoration:activate")
        await(first,"시연 Lv2 유료 선택") { first.window.decorView.findViewWithTag<Button>("shop:preview:decoration:blue-sign")?.isEnabled==true }
        previewWithoutWrite(first,"blue-sign")
        click(first,"shop:preview:decoration:neutral-sign");click(first,"shop:confirm");awaitState(first,"neutral-sign")
        val beforePaid=read{ok(it.store.balance())};assertEquals(initial.second,beforePaid)
        click(first,"shop:preview:decoration:blue-sign");click(first,"shop:confirm");awaitState(first,"blue-sign")
        read { game ->assertEquals(GameMoney(beforePaid.units-100),ok(game.store.balance()));assertEquals(initial.first,game.identity) }
        val paidCounts=read{counts(it)}
        previewWithoutWrite(first,"neutral-sign")
        click(first,"shop:preview:decoration:neutral-sign");click(first,"shop:confirm");awaitState(first,"neutral-sign")
        val stored=read { game ->
            val state=ok(game.store.decorations.state(game.identity.profileId));assertEquals(Revision(3),state.revision)
            assertEquals(setOf("neutral-sign","blue-sign"),state.ownedIds);assertEquals(GameMoney(beforePaid.units-100),state.balance)
            assertEquals(listOf(paidCounts[0]+1,paidCounts[1]),counts(game));game.identity to state
        }
        recreateAndCompare(first,stored)
    }
    fun testCorruptShopBundleStaysRestrictedAcrossRetainedAndFreshMain() {
        fresh()
        val runner = instrumentation as? ShopRestoreInstrumentationRunner ?: error("복원 fixture runner 미지정")
        val games = GameSessionFactory(context)
        val onboarding = StoredOnboardingHostSource(games)
        val owner = ok(onboarding.read()).identity
        ok(onboarding.choose(owner, FirstGrant.CANDIDATES.first()))
        val backend = FactoryHomeShopPort(context, games).decoration(owner) {}
        ok(backend.activate())
        val facts = ok(backend.read())
        val mapper = DecorationMapper(mapOf("neutral" to ShopAppearance("기본 간판 · 시연", 0xff8fbcff.toInt())))
        val revision = Revision(41)
        val option = ok(mapper.map(facts, revision)).decorations.single {
            (it.command as? ShopCommand.Decorate)?.decorationId == "neutral-sign"
        }
        val request = ShopRequest(RequestId("restore-fixture-decoration"), ShopSelection(revision, option), "decoration-attempt")
        val originalCommand = ok(mapper.command(facts, revision, request))
        ok(backend.save(originalCommand))
        val original = ok(backend.execute(originalCommand))
        val slot = checkNotNull(read { ok(it.store.hostCommands.load(owner.profileId)) })
        assertEquals(HostCheckpointPhase.STARTED, slot.phase)
        assertEquals(HostCheckpointCommand.Decoration(originalCommand), slot.command)

        val offer = FacilityOffer(Facility.CAR_WASH, RuleVersion("restore-fixture-rule"), true, GameMoney(100))
        val selected = ShopSelection(revision, ShopOption("restore-facility", "시설", "조건", "효과",
            ShopAvailability.AVAILABLE, ShopCommand.BuyFacility(offer), offer.price, offer.ruleVersion, offer.trial))
        val pending = ShopRequest(RequestId("restore-fixture-facility"), selected, "original-attempt")
        val shopState = ShopUiState(mode = ShopMode.VEHICLES, pending = pending,
            requestIds = mapOf(selected to pending.requestId), latestRevision = revision)
        val broken = ShopUiStateBundle.encode(shopState).apply { putString("facilityOriginalCommand", "invalid whole command") }
        val selectedVehicle = read { ok(it.store.listOwnedVehicles()).single().ownedVehicleId.value }
        val seeded = Bundle().apply {
            putBundle("shopUi", broken); putString("page", HostPage.HOME.name)
            putString("returnPage", HostPage.GACHA.name); putString("selectedVehicle", selectedVehicle)
        }
        val baseline = restoreRows()
        val live = mutableListOf<MainActivity>()
        fun launch(state: Bundle): MainActivity {
            val nonce = UUID.randomUUID().toString()
            runner.injectOnce(nonce, parcelShopState(state))
            return (instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ShopRestoreInstrumentationRunner.NONCE, nonce))
                as MainActivity).also {
                live.add(it)
                assertTrue("실제 Main onCreate에 저장 Bundle 전달", runner.consumed(nonce))
            }
        }
        fun restricted(a: MainActivity) = tree(a.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.toString() == a.getString(R.string.shop_restricted)
        }
        fun assertRestricted(a: MainActivity, label: String) {
            await(a, label) { menu(a)?.let { it.isShown && it.isEnabled } == true && restricted(a) }
            assertFalse(onMain { tree(a.window.decorView).filterIsInstance<Button>().any {
                it.isShown && it.isEnabled && it.tag?.toString()?.let { tag ->
                    tag.startsWith("shop:preview:") || tag == "shop:confirm" || tag == "decoration:activate"
                } == true
            } })
        }
        fun save(a: MainActivity): Bundle = parcelShopState(onMain {
            Bundle().also { instrumentation.callActivityOnSaveInstanceState(a, it) }
        })
        fun checkSaved(a: MainActivity): Bundle = save(a).also { saved ->
            val raw = checkNotNull(saved.getBundle("shopUi"))
            assertEquals("손상 원문을 빈 정상 상태로 덮지 않는다", "invalid whole command", raw.getString("facilityOriginalCommand"))
            assertEquals(ContractResult.Success(shopState), ShopUiStateBundle.decodePresent(raw))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ShopUiStateBundle.decodeFacility(raw))
            assertEquals(HostPage.GACHA.name, saved.getString("returnPage"))
            assertEquals(selectedVehicle, saved.getString("selectedVehicle"))
        }
        fun finish(a: MainActivity) {
            onMain { a.finish() }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!onMain { a.isDestroyed } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("다음 인스턴스 전에 이전 Activity 종료", onMain { a.isDestroyed })
        }
        fun recreate(a: MainActivity): MainActivity {
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            return try {
                onMain { a.recreate() }
                (instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? MainActivity)
                    ?.also(live::add) ?: error("실제 재생성 Activity 미도달")
            } finally { instrumentation.removeMonitor(monitor) }
        }
        try {
            val first = launch(seeded)
            assertRestricted(first, "첫 손상 복원 제한")
            assertEquals(baseline, restoreRows())
            val firstSaved = checkSaved(first) // RED는 이 단언에서 끝나며 다음 Activity를 열지 않는다.
            val retainedHost = onMain { first.onRetainNonConfigurationInstance() }
            val second = recreate(first)
            assertNotSame(first, second)
            assertSame("실제 시스템 재생성의 같은 HostSession", retainedHost, onMain { second.onRetainNonConfigurationInstance() })
            assertRestricted(second, "retained HostSession 제한")
            val secondSaved = checkSaved(second)
            assertEquals(firstSaved.getBundle("shopUi")?.getString("facilityOriginalCommand"),
                secondSaved.getBundle("shopUi")?.getString("facilityOriginalCommand"))
            finish(second)

            val fresh = launch(secondSaved)
            assertNotSame(retainedHost, onMain { fresh.onRetainNonConfigurationInstance() })
            assertRestricted(fresh, "새 HostSession 제한")
            checkSaved(fresh)
            onMain {
                val spinner = checkNotNull(menu(fresh))
                spinner.setSelection((0 until spinner.count).first { spinner.getItemAtPosition(it).toString() == "보유 차량" })
            }
            await(fresh, "다른 화면 탐색과 gate") { menu(fresh)?.selectedItem.toString() == "보유 차량" }
            assertEquals(HostPage.GARAGE.name, save(fresh).getString("page"))
            finish(fresh)

            val validFacility = FacilityCommand.Buy(owner, pending.requestId, ContentVersion("restore-fixture-definition"), offer)
            val healthy = launch(Bundle(seeded).apply {
                putString("page", HostPage.GARAGE.name)
                putBundle("shopUi", ShopUiStateBundle.encode(shopState, validFacility))
            })
            await(healthy, "정상 Bundle의 차고 진입") { menu(healthy)?.selectedItem.toString() == "보유 차량" }
            val healthyShop = save(healthy).getBundle("shopUi")
            assertEquals(ContractResult.Success(shopState), ShopUiStateBundle.decodePresent(healthyShop))
            assertEquals(ContractResult.Success(validFacility), ShopUiStateBundle.decodeFacility(healthyShop))

            val healthyHost = onMain { healthy.onRetainNonConfigurationInstance() }
            val healthyNonce = checkNotNull(healthy.intent.getStringExtra(ShopRestoreInstrumentationRunner.NONCE))
            runner.injectOnce(healthyNonce, parcelShopState(seeded))
            val firstCorruption = recreate(healthy)
            assertTrue("정상 세션의 실제 재생성에서 손상 Bundle 전달", onMain { runner.consumed(healthyNonce) })
            assertSame("손상을 처음 받는 정상 retained HostSession", healthyHost,
                onMain { firstCorruption.onRetainNonConfigurationInstance() })
            assertEquals("정상 retained 세션에서 처음 받은 손상 원문을 보존한다", "invalid whole command",
                save(firstCorruption).getBundle("shopUi")?.getString("facilityOriginalCommand"))
            assertRestricted(firstCorruption, "정상 retained 세션의 첫 손상 제한")
            checkSaved(firstCorruption)
            val retainedAgain = recreate(firstCorruption)
            assertSame(healthyHost, onMain { retainedAgain.onRetainNonConfigurationInstance() })
            assertRestricted(retainedAgain, "첫 손상 이후 같은 retained 세션 제한")
            checkSaved(retainedAgain)
            finish(retainedAgain)
            assertEquals("원명령·꾸미기·정산 행과 schema18 보존", baseline, restoreRows())
            read { game ->
                assertEquals(owner, game.identity)
                assertEquals(slot, ok(game.store.hostCommands.load(owner.profileId)))
                assertEquals(original, ok(ShopOriginalResultReader(game.store).decoration(owner.profileId, request.requestId)))
            }
        } finally {
            runner.clearInjection()
            onMain { live.filterNot { it.isDestroyed }.forEach { it.finish() } }
        }
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun parcelShopState(state: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state); parcel.setDataPosition(0)
            checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }

    private fun restoreRows() = read { game -> ok(game.store.read { db ->
        val tables = listOf("host_command_checkpoint", "decoration_requests", "settlements")
        buildList {
            db.rawQuery("PRAGMA user_version", null).use {
                it.moveToFirst(); assertEquals(20, it.getInt(0)); add("schema:${it.getInt(0)}")
            }
            for (table in tables) db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) add(table + ":" + (0 until cursor.columnCount).joinToString("|") {
                    "${cursor.getType(it)}:" + if (cursor.isNull(it)) "<null>" else cursor.getString(it)
                })
            }
        }
    }) }

}


/** 같은 APK의 비교 시험에서 실제 Main의 첫 onCreate에만 fixture Bundle을 전달한다. */
@Suppress("DEPRECATION")
class ShopRestoreInstrumentationRunner : InstrumentationTestRunner() {
    @Volatile private var next: Pair<String, Bundle>? = null
    @Volatile private var lastConsumed: String? = null

    fun injectOnce(nonce: String, state: Bundle) {
        check(next == null)
        lastConsumed = null
        next = nonce to state
    }

    fun consumed(nonce: String) = lastConsumed == nonce
    fun clearInjection() { next = null }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val prepared = next
        if (activity is MainActivity && prepared != null && activity.intent.getStringExtra(NONCE) == prepared.first) {
            next = null
            super.callActivityOnCreate(activity, prepared.second)
            lastConsumed = prepared.first
        } else super.callActivityOnCreate(activity, icicle)
    }

    companion object {
        const val NONCE = "io.drivegrowth.restore.fixture.NONCE"
    }
}
