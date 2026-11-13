package io.drivegrowth.app.host.progression

import io.drivegrowth.app.exposeMainRefresh
import io.drivegrowth.app.exposeMainPage
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.host.HostPage
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.quest.*
import java.time.Instant
import java.time.ZoneId

/** cold 정상 첫 플레이를 UI로만 수행한다. DB 접근은 실제 결과의 읽기 검증이며 gate/사건/거래를 주입하지 않는다. */
@Suppress("DEPRECATION")
class ProgressionFirstPlayActivityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun tree(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{tree(view.getChildAt(it))}else emptyList()
    private fun menu(a:MainActivity)=a.window.decorView.mainNavigation()
    private inline fun <reified T:View> attached(a:MainActivity):T? = tree(a.window.decorView).filterIsInstance<T>().singleOrNull()
    private fun await(a:MainActivity,label:String,check:()->Boolean) {
        val until=SystemClock.uptimeMillis()+15000;var success=false;var gate=false
        while(!success&&SystemClock.uptimeMillis()<until) {
            instrumentation.runOnMainSync { gate=menu(a)?.let{it.isShown&&it.isEnabled}==true;success=check() }
            if(!success)SystemClock.sleep(50)
        }
        if(!success&&!gate)throw IllegalStateException("ENVIRONMENT_GATE_TIMEOUT: $label; 실제 SDK 허용 메뉴 미관측")
        assertTrue("ASSERTION_STATE: $label",success)
    }
    private fun gate(a:MainActivity)=await(a,"실제 SDK gate") { menu(a)?.let{it.isShown&&it.isEnabled}==true }
    private fun click(a:MainActivity,tag:Any) {
        gate(a)
        await(a,"활성 UI 버튼 $tag") { a.window.decorView.findViewWithTag<Button>(tag)?.let{it.isEnabled&&it.isShown}==true }
        instrumentation.runOnMainSync {
            val b=a.window.decorView.findViewWithTag<Button>(tag)!!
            assertTrue(b.isEnabled&&b.isShown)
            b.requestRectangleOnScreen(Rect(0,0,b.width,b.height),true)
            assertTrue("공개 UI 버튼 클릭",b.performClick())
        }
    }
    private fun page(a:MainActivity,target:HostPage) {
        gate(a);instrumentation.runOnMainSync { a.window.decorView.openMainPage(target) }
    }
    private fun refresh(a:MainActivity) {
        gate(a);instrumentation.runOnMainSync {
            val b=checkNotNull(a.window.decorView.exposeMainRefresh())
            assertTrue(b.isEnabled);b.performClick()
        }
    }
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun <T> read(action:(GameSession)->T):T = ok(GameSessionFactory(instrumentation.targetContext).open()).use(action)
    private fun progress(a:MainActivity)=attached<ProgressView>(a)
    private fun claimed(a:MainActivity)=progress(a)?.let{v->tree(v).filterIsInstance<TextView>().any{it.text.toString()==instrumentation.targetContext.getString(R.string.progress_claimed)}}==true
    fun testQuestDetailRemainsUnclaimedAcrossActualStationaryDriveAndPark() {
        val context=instrumentation.targetContext
        assertEquals("이번 계측의 전용 패키지", "io.drivegrowth.verify.questgatee", context.packageName)
        for(n in listOf("game-ledger-vehicle.db","game-ledger-simulation.db"))
            assertFalse("새 격리 패키지 cold 첫 플레이가 필요함. 기존 DB 삭제 금지: $n",context.getDatabasePath(n).exists())
        val definitions=ok(ok(AssetTrialDefinitionProvider(context.assets).load()).questDefinitions(ZoneId.systemDefault()))
        val definition=definitions.definitions.single{it.id=="daily-result-viewed"}
        assertEquals("이 시나리오는 중앙 정의의 결과 열람 1회 목표를 검증한다",1,definition.goal)
        val startedAt = EpochMillis(System.currentTimeMillis())
        val expectedPeriod = questPeriod(definition.cadence, definition.zone, startedAt)
        Log.i("QuestMainNewPid", "STARTED pid=${Process.myPid()} observedAt=${Instant.ofEpochMilli(startedAt.value)}")
        val first=activity
        gate(first)
        await(first,"NEW 최초 안내") { attached<OnboardingView>(first)!=null }
        click(first,R.string.onboarding_start)
        click(first,VehicleId("hyundai_casper"))
        click(first,R.string.onboarding_review)
        click(first,R.string.onboarding_confirm)
        await(first,"실제 저장 성공 뒤 가게로 버튼") { first.window.decorView.findViewWithTag<Button>(R.string.onboarding_to_shop)?.isEnabled==true }
        val initial=read { game ->
            val init=ok(game.store.readInitialization(game.identity.profileId))
            assertTrue(init.status is ProfileInitializationStatus.Existing)
            val original=(init.status as ProfileInitializationStatus.Existing).original.also { assertNotNull("실제 최초 지급 원결과",it) }!!
            assertEquals(FirstGrant.INITIAL_MONEY,ok(game.store.balance()))
            val vehicle=ok(game.store.listOwnedVehicles()).single()
            assertEquals(VehicleId("hyundai_casper"),vehicle.vehicleId)
            assertEquals(VehicleAcquisitionSource.INITIAL_GRANT,vehicle.acquisitionSource)
            assertEquals(original.grant.changedVehicle,vehicle)
            original
        }
        click(first,R.string.onboarding_to_shop)
        await(first,"저장 성공 뒤 실제 가게") { attached<ShopView>(first)!=null }
        chooseGatePage(first, HostPage.RESULTS) { attached<ResultsView>(first)?.isShown == true && first.window.decorView.findViewWithTag<Button>("results:open:${initial.grant.settlementId.value}")?.isEnabled == true }
        val grantId=initial.grant.settlementId
        click(first,"results:open:${grantId.value}")
        click(first,"results:mark:${grantId.value}")
        await(first,"원래 초기 정산 열람 완료 UI") {
            attached<ResultsView>(first)?.uiState?.readStates?.get(grantId)==io.drivegrowth.app.ui.results.ResultsReadState.Done
        }
        read { game -> assertFalse(ok(game.store.listUnviewedSettlements()).any{it.settlementId==grantId});assertEquals(FirstGrant.INITIAL_MONEY,ok(game.store.balance())) }
        chooseGatePage(first, HostPage.PROGRESSION) { first.window.decorView.findViewWithTag<Button>(progressKey(definition, expectedPeriod))?.isEnabled == true }
        if(questPeriod(definition.cadence,definition.zone,EpochMillis(System.currentTimeMillis()))!=expectedPeriod)
            throw IllegalStateException("ENVIRONMENT_PERIOD_CHANGED: 청구 전 날짜/시간대 경계 변경")
        val key=progressKey(definition,expectedPeriod)
        await(first,"실제 daily 결과 확인 목표 상세") { first.window.decorView.findViewWithTag<Button>(key)?.isEnabled==true }
        assertEquals(GameMoney(100), definition.reward)
        click(first,key)
        await(first, "same daily quest and enabled claim in P") {
            questReady(first, key)
        }
        val point = IntArray(2)
        await(first, "complete claim button inside the actual window") {
            val button = first.window.decorView.findViewWithTag<Button>("progress-claim") ?: return@await false
            if (!button.isLaidOut || button.width == 0 || button.height == 0) return@await false
            val visible = Rect()
            if (!button.getLocalVisibleRect(visible) || visible != Rect(0, 0, button.width, button.height)) {
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                return@await false
            }
            button.getLocationOnScreen(point)
            point[0] += button.width / 2
            point[1] += button.height / 2
            true
        }
        val oldScroll = awaitStableDetailScroll(first, key)
        assertTrue("SCROLL_UNPROVEN_BASELINE: detail must start with a positive offset", oldScroll.y > 0)
        Log.i("QuestGateE", "SCROLL_BEFORE $oldScroll")
        val before = gateLedger()
        assertEquals(listOf(listOf("1", "1000", "VEHICLE")), before.getValue("account"))
        assertEquals(1, before.getValue("quest_events").size)
        for (name in listOf("quest_claims", "quest_settlements", "host_command_checkpoint")) {
            assertTrue(name, before.getValue(name).isEmpty())
        }
        Log.i("QuestGateE", "REQUEST_DRIVE_STATIONARY key=$key claimPoint=${point.toList()}")
        awaitVehicleGate("D0 restriction") {
            val root = first.window.decorView
            menu(first) == null && progress(first) == null &&
                root.findViewWithTag<Button>("progress-claim") == null &&
                tree(root).filterIsInstance<TextView>().any {
                    it.isShown && it.text.toString() == "주차 후 조작이 허용되면 이용할 수 있습니다."
                }
        }
        assertEquals(before, gateLedger())
        var focused = false
        instrumentation.runOnMainSync { focused = first.window.decorView.hasWindowFocus() }
        if (focused) {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0].toFloat(), point[1].toFloat(), 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.sendPointerSync(event)
                } finally { event.recycle() }
            }
            instrumentation.waitForIdleSync()
            Log.i("QuestGateE", "RESTRICTED_WINDOW_TOUCH_INJECTED point=${point.toList()}")
        } else {
            Log.i("QuestGateE", "RESTRICTED_TOUCH_NOT_RUN no window focus")
        }
        assertEquals(before, gateLedger())
        Log.i("QuestGateE", "REQUEST_PARK")
        val restored = awaitStableDetailScroll(first, key, oldScroll.view)
        assertEquals(expectedPeriod, questPeriod(definition.cadence, definition.zone, EpochMillis(System.currentTimeMillis())))
        assertEquals(before, gateLedger())
        Log.i("QuestGateE", "P_SAME_QUEST_UNCLAIMED")
        Log.i("QuestGateE", "QUEST_GATE_LEDGER_UNCHANGED firstViewed=1 claim=0 payout=0 balance=1000")
        Log.i("QuestGateE", "SCROLL_RESTORED $restored")
        if (restored.maxY == 0) {
            Log.i("QuestGateE", "SCROLL_UNPROVEN_VIEWPORT: restored detail has no scroll range")
            fail("SCROLL_UNPROVEN_VIEWPORT: a nonzero offset cannot be tested in this viewport")
        }
        assertEquals("same laid-out detail must restore a valid old offset",
            oldScroll.y.coerceAtMost(restored.maxY), restored.y)
        SystemClock.sleep(700)
    }

    private data class ScrollPosition(val view: ProgressView, val y: Int, val maxY: Int, val viewportHeight: Int)

    private fun awaitStableDetailScroll(a: MainActivity, key: ProgressKey, expectedView: ProgressView? = null): ScrollPosition {
        var previous: ScrollPosition? = null
        awaitVehicleGate("two stable samples of the same attached, laid-out quest detail") {
            val sample = detailScrollSample(a, key, expectedView)
            val stable = sample != null && sample.view === previous?.view && sample == previous
            previous = sample
            stable
        }
        return checkNotNull(previous)
    }

    private fun detailScrollSample(a: MainActivity, key: ProgressKey, expectedView: ProgressView?): ScrollPosition? {
        val view = progress(a) ?: return null
        if (expectedView != null && view !== expectedView) return null
        if (!view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested || !questReady(a, key)) return null
        val scroll = tree(view).filterIsInstance<ScrollView>().singleOrNull()
            ?.takeIf { it.isShown && it.isLaidOut && !it.isLayoutRequested && it.height > 0 } ?: return null
        val child = scroll.getChildAt(0)?.takeIf { it.isLaidOut && !it.isLayoutRequested } ?: return null
        val claim = scroll.findViewWithTag<Button>("progress-claim")
            ?.takeIf { it.isLaidOut && !it.isLayoutRequested && it.height > 0 } ?: return null
        if (!claim.isEnabled) return null
        val maxY = (child.height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
        return ScrollPosition(view, scroll.scrollY, maxY, scroll.height)
    }

    private fun questReady(a: MainActivity, key: ProgressKey): Boolean {
        val view = progress(a) ?: return false
        val labels = tree(view).filterIsInstance<TextView>().map { it.text.toString() }
        return menu(a)?.isEnabled == true && view.isShown && view.uiState.selectedKey == key &&
            a.window.decorView.findViewWithTag<Button>("progress-claim")?.let { it.isShown && it.isEnabled } == true &&
            labels.containsAll(listOf("결과 확인하기", "1 / 1회", "100 게임머니"))
    }

    private fun chooseGatePage(a: MainActivity, name: HostPage, ready: () -> Boolean) {
        await(a, "actual destination $name") {
            if (ready()) return@await true
            if (a.window.decorView.exposeMainPage(name)?.let { it.isEnabled && it.isLaidOut && !it.isLayoutRequested } != true) return@await false
            a.window.decorView.openMainPage(name)
            false
        }
    }

    private fun awaitVehicleGate(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = ready() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("SDK_OR_VIEW_TIMEOUT: $label")
    }

    private fun gateLedger(): Map<String, List<List<String?>>> = read { game ->
        ok(game.store.read { db ->
            val queries = listOf("account", "initialization_profile", "quest_events", "quest_claims", "host_command_checkpoint")
                .associateWith { "SELECT * FROM $it ORDER BY 1" } +
                ("quest_settlements" to "SELECT * FROM settlements WHERE request_id LIKE 'quest/claim/%' ORDER BY 1")
            queries.mapValues { (_, sql) -> db.rawQuery(sql, null).use { rows ->
                buildList { while (rows.moveToNext()) add(List(rows.columnCount) { rows.getString(it) }) }
            } }
        })
    }

    fun testActualFirstGrantResultsViewQuestClaimAndRecreationNeverDoubleCredit() {
        val context=instrumentation.targetContext
        assertEquals("이번 계측의 전용 패키지", QUEST_PID_PACKAGE, context.packageName)
        for(n in listOf("game-ledger-vehicle.db","game-ledger-simulation.db"))
            assertFalse("새 격리 패키지 cold 첫 플레이가 필요함. 기존 DB 삭제 금지: $n",context.getDatabasePath(n).exists())
        val definitions=ok(ok(AssetTrialDefinitionProvider(context.assets).load()).questDefinitions(ZoneId.systemDefault()))
        val definition=definitions.definitions.single{it.id=="daily-result-viewed"}
        assertEquals("이 시나리오는 중앙 정의의 결과 열람 1회 목표를 검증한다",1,definition.goal)
        val startedAt = EpochMillis(System.currentTimeMillis())
        val expectedPeriod = questPeriod(definition.cadence, definition.zone, startedAt)
        Log.i("QuestMainNewPid", "STARTED pid=${Process.myPid()} observedAt=${Instant.ofEpochMilli(startedAt.value)}")
        val first=activity
        gate(first)
        await(first,"NEW 최초 안내") { attached<OnboardingView>(first)!=null }
        click(first,R.string.onboarding_start)
        click(first,VehicleId("hyundai_casper"))
        click(first,R.string.onboarding_review)
        click(first,R.string.onboarding_confirm)
        await(first,"실제 저장 성공 뒤 가게로 버튼") { first.window.decorView.findViewWithTag<Button>(R.string.onboarding_to_shop)?.isEnabled==true }
        val initial=read { game ->
            val init=ok(game.store.readInitialization(game.identity.profileId))
            assertTrue(init.status is ProfileInitializationStatus.Existing)
            val original=(init.status as ProfileInitializationStatus.Existing).original.also { assertNotNull("실제 최초 지급 원결과",it) }!!
            assertEquals(FirstGrant.INITIAL_MONEY,ok(game.store.balance()))
            val vehicle=ok(game.store.listOwnedVehicles()).single()
            assertEquals(VehicleId("hyundai_casper"),vehicle.vehicleId)
            assertEquals(VehicleAcquisitionSource.INITIAL_GRANT,vehicle.acquisitionSource)
            assertEquals(original.grant.changedVehicle,vehicle)
            original
        }
        click(first,R.string.onboarding_to_shop)
        await(first,"저장 성공 뒤 실제 가게") { attached<ShopView>(first)!=null }
        page(first,HostPage.RESULTS)
        val grantId=initial.grant.settlementId
        click(first,"results:open:${grantId.value}")
        click(first,"results:mark:${grantId.value}")
        await(first,"원래 초기 정산 열람 완료 UI") {
            attached<ResultsView>(first)?.uiState?.readStates?.get(grantId)==io.drivegrowth.app.ui.results.ResultsReadState.Done
        }
        read { game -> assertFalse(ok(game.store.listUnviewedSettlements()).any{it.settlementId==grantId});assertEquals(FirstGrant.INITIAL_MONEY,ok(game.store.balance())) }
        page(first,HostPage.PROGRESSION)
        if(questPeriod(definition.cadence,definition.zone,EpochMillis(System.currentTimeMillis()))!=expectedPeriod)
            throw IllegalStateException("ENVIRONMENT_PERIOD_CHANGED: 청구 전 날짜/시간대 경계 변경")
        val key=progressKey(definition,expectedPeriod)
        await(first,"실제 daily 결과 확인 목표 상세") { first.window.decorView.findViewWithTag<Button>(key)?.isEnabled==true }
        click(first,key);click(first,"progress-claim")
        await(first,"실제 보상 청구 확정 표시") { claimed(first) }
        val receipt=read { game ->
            val settlement=ok(game.store.listUnviewedSettlements()).single{it.transaction.requestId.value.startsWith("quest/claim/")}
            val original=ok(game.store.quests.settlement(settlement.transaction.requestId))!!
            assertEquals(definition,original.command.definition);assertEquals(expectedPeriod,original.command.period)
            assertEquals(settlement,original.settlement)
            assertEquals(GameMoney(FirstGrant.INITIAL_MONEY.units+definition.reward.units),ok(game.store.balance()))
            assertEquals(1,ok(game.store.listOwnedVehicles()).size)
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            original
        }
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        var recreated:MainActivity?=null
        try {
            instrumentation.runOnMainSync{first.recreate()}
            recreated=instrumentation.waitForMonitorWithTimeout(monitor,15000) as? MainActivity
            assertNotNull("새 Activity 인스턴스",recreated);val next=recreated!!;assertNotSame(first,next)
            gate(next)
            if(questPeriod(definition.cadence,definition.zone,EpochMillis(System.currentTimeMillis()))!=expectedPeriod)
                throw IllegalStateException("ENVIRONMENT_PERIOD_CHANGED: 실행 중 날짜/시간대 경계 변경")
            await(next,"복원된 같은 Quest 상세·수령 상태") { progress(next)?.uiState?.selectedKey==key&&claimed(next) }
            refresh(next)
            await(next,"재조회 뒤 중복 청구 불가") { claimed(next)&&next.window.decorView.findViewWithTag<Button>("progress-claim")?.isEnabled==false }
            gate(next);instrumentation.runOnMainSync{next.onBackPressed()}
            await(next,"첫 Back은 Quest 목록") { progress(next)?.uiState?.selectedKey==null }
            gate(next);instrumentation.runOnMainSync{next.onBackPressed()}
            await(next,"정상 첫 지급 후 두 번째 Back은 가게") { attached<ShopView>(next)!=null }
            read { game ->
                assertEquals(receipt,ok(game.store.quests.settlement(receipt.command.requestId)))
                assertEquals(receipt.settlement.balance,ok(game.store.balance()))
                assertEquals(listOf(initial.grant.changedVehicle!!),ok(game.store.listOwnedVehicles()))
                assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            recreated?.let{a->instrumentation.runOnMainSync{a.finish()}}
        }
        val saved = questSnapshot()
        assertEquals(receipt, saved.receipt)
        reportQuest("CLAIMED", saved, requireCurrentPeriod(receipt))
    }

    fun testNewProcessShowsSameDailyQuestClaimAndReplaysWithoutExtraCredit() {
        val context = instrumentation.targetContext
        assertEquals("이번 계측의 전용 패키지", QUEST_PID_PACKAGE, context.packageName)
        assertTrue("이전 selector의 VEHICLE 원장이 필요합니다", context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
        val before = questSnapshot()
        val receipt = before.receipt
        reportQuest("REOPEN_START", before, requireCurrentPeriod(receipt))

        val next = activity
        page(next, HostPage.PROGRESSION)
        val key = progressKey(receipt.command.definition, receipt.command.period)
        click(next, key)
        awaitClaimDisabled(next, key)
        refresh(next)
        awaitClaimDisabled(next, key)
        assertEquals("새 Main의 조회만으로 추가 지급할 수 없습니다", before, questSnapshot())

        // The UI offers no claim. Separately retry the exact saved command at the real SQLite boundary.
        read { game -> assertEquals(receipt, ok(game.store.quests.claim(receipt.command))) }
        val after = questSnapshot()
        assertEquals("같은 원요청 재시도는 새 보상·원정산·슬롯을 만들지 않습니다", before, after)
        reportQuest("REOPENED", after, requireCurrentPeriod(receipt))
    }

    private fun awaitClaimDisabled(main: MainActivity, key: ProgressKey) {
        val disabledText = instrumentation.targetContext.getString(R.string.progress_no_claim)
        await(main, "동일 기간 Quest 상세의 수령 상태와 청구 비활성") {
            val claim = main.window.decorView.findViewWithTag<Button>("progress-claim")
            progress(main)?.uiState?.selectedKey == key && claimed(main) &&
                claim?.isShown == true && !claim.isEnabled && claim.text.toString() == disabledText
        }
    }

    private data class QuestSnapshot(
        val receipt: QuestClaimSettlement,
        val balance: GameMoney,
        val unviewed: List<Settlement>,
        val owned: List<OwnedVehicle>,
    )

    private fun questSnapshot(): QuestSnapshot = read { game ->
        assertEquals(InputSource.VEHICLE, game.identity.source)
        assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        val request = ok(game.store.quests.claimedRequestIds(game.identity.profileId)).single()
        val receipt = checkNotNull(ok(game.store.quests.settlement(request)))
        assertEquals(game.identity.profileId, receipt.command.profileId)
        assertEquals(game.identity.source, receipt.command.source)
        assertEquals("daily-result-viewed", receipt.command.definition.id)
        assertEquals(QuestCadence.DAILY, receipt.command.period.cadence)
        assertEquals(QuestStatus.CLAIMED, receipt.progress.status)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, receipt.command.definition.reward), receipt.settlement.transaction.money)
        val balance = ok(game.store.balance())
        assertEquals(receipt.settlement.balance, balance)
        val unviewed = ok(game.store.listUnviewedSettlements())
        assertEquals(receipt.settlement, unviewed.single { it.transaction.requestId == request })
        QuestSnapshot(receipt, balance, unviewed, ok(game.store.listOwnedVehicles()))
    }

    private fun requireCurrentPeriod(receipt: QuestClaimSettlement): EpochMillis {
        val period = receipt.command.period
        val at = EpochMillis(System.currentTimeMillis())
        if (ZoneId.systemDefault() != period.zone || at.value !in period.startsAt.value until period.endsAt.value) {
            throw IllegalStateException("ENVIRONMENT_PERIOD_CHANGED: 저장 기간 $period, 현재 ${Instant.ofEpochMilli(at.value)} / ${ZoneId.systemDefault()}")
        }
        assertTrue(receipt.settlement.confirmedAt.value in period.startsAt.value until period.endsAt.value)
        return at
    }

    private fun reportQuest(phase: String, snapshot: QuestSnapshot, at: EpochMillis) {
        val (command, _, settlement) = snapshot.receipt
        val period = command.period
        Log.i("QuestMainNewPid", "$phase pid=${Process.myPid()} profile=${command.profileId.value} source=${command.source} " +
            "quest=${command.definition.id} version=${command.definition.version.value} zone=${period.zone} " +
            "period=[${Instant.ofEpochMilli(period.startsAt.value)},${Instant.ofEpochMilli(period.endsAt.value)}) " +
            "observedAt=${Instant.ofEpochMilli(at.value)} confirmedAt=${Instant.ofEpochMilli(settlement.confirmedAt.value)} " +
            "request=${command.requestId.value} settlement=${settlement.settlementId.value} " +
            "reward=${command.definition.reward.units} balance=${snapshot.balance.units}")
    }

    private companion object {
        const val QUEST_PID_PACKAGE = "io.drivegrowth.app.verify.questpid"
    }
}
