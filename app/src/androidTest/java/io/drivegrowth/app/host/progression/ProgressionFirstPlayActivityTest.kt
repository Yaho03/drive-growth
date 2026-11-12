package io.drivegrowth.app.host.progression

import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
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
    private fun menu(a:MainActivity)=a.window.decorView.findViewWithTag<Spinner>("host:destination")
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
    private fun page(a:MainActivity,label:String) {
        gate(a);instrumentation.runOnMainSync { val m=menu(a)!!;m.setSelection((0 until m.count).single{m.getItemAtPosition(it).toString()==label}) }
    }
    private fun refresh(a:MainActivity) {
        gate(a);instrumentation.runOnMainSync {
            val b=tree(a.window.decorView).filterIsInstance<Button>().single{it.text.toString()=="새로고침"}
            assertTrue(b.isEnabled);b.performClick()
        }
    }
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun <T> read(action:(GameSession)->T):T = ok(GameSessionFactory(instrumentation.targetContext).open()).use(action)
    private fun progress(a:MainActivity)=attached<ProgressView>(a)
    private fun claimed(a:MainActivity)=progress(a)?.let{v->tree(v).filterIsInstance<TextView>().any{it.text.toString()==instrumentation.targetContext.getString(R.string.progress_claimed)}}==true
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
        page(first,"미확인 정산")
        val grantId=initial.grant.settlementId
        click(first,"results:open:${grantId.value}")
        click(first,"results:mark:${grantId.value}")
        await(first,"원래 초기 정산 열람 완료 UI") {
            attached<ResultsView>(first)?.uiState?.readStates?.get(grantId)==io.drivegrowth.app.ui.results.ResultsReadState.Done
        }
        read { game -> assertFalse(ok(game.store.listUnviewedSettlements()).any{it.settlementId==grantId});assertEquals(FirstGrant.INITIAL_MONEY,ok(game.store.balance())) }
        page(first,"퀘스트·진행")
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
        page(next, "퀘스트·진행")
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
