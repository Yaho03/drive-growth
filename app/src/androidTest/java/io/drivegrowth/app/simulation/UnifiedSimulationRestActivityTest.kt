package io.drivegrowth.app.simulation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostNavigation
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.host.progression.ProgressionFrame
import io.drivegrowth.app.ui.delivery.DeliveryCommand
import io.drivegrowth.app.ui.delivery.DeliveryInteraction
import io.drivegrowth.app.ui.delivery.DeliveryRequest
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.delivery.DeliveryView
import io.drivegrowth.app.ui.progress.ProgressScreenState
import io.drivegrowth.app.ui.progress.ProgressView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 일반 Debug Main의 명시 휴식 입력을 같은 프로필 원장과 기존 진행 카드에서 검증한다. */
@Suppress("DEPRECATION")
class UnifiedSimulationRestActivityTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {

    fun testExplicitRestRunPaysAndShowsConfirmedStateInTheMainProfile() {
        val context = instrumentation.targetContext
        assertTrue("새 격리 시험 패키지가 필요합니다", context.packageName.startsWith("io.drivegrowth.verify."))
        for (name in listOf("unified-simulation.db", "game-ledger-vehicle.db", RestSimulationActivity.DATABASE)) {
            assertFalse("기존 원장을 보존합니다: $name", context.getDatabasePath(name).exists())
        }
        val main = activity
        val root = main.window.decorView
        click(root, R.string.onboarding_start)
        click(root, VehicleId("hyundai_casper"))
        click(root, R.string.onboarding_review)
        click(root, R.string.onboarding_confirm)
        click(root, R.string.onboarding_to_shop)

        val games = GameSessionFactory(
            context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION,
        )
        val identity = ok(games.open()).use { it.identity }
        assertEquals(InputSource.SIMULATION, identity.source)
        assertNull(ok(readRestDemo(games)))

        choosePage(root, "탁송")
        click(root, "simulation:destination")
        var offer: Any? = null
        await("미확정 탁송 제안이 보여야 합니다") {
            offer = tree(root).filterIsInstance<Button>()
                .firstOrNull { it.isEnabled && (it.tag as? String)?.startsWith("delivery:offer:") == true }?.tag
            offer != null
        }
        click(root, checkNotNull(offer))
        await("탁송 확정과 휴식 시작이 모두 선택 가능한 상태여야 합니다") {
            root.findViewWithTag<Button>("delivery:confirm")?.isEnabled == true &&
                root.findViewWithTag<Button>("simulation:rest-start")?.isEnabled == true
        }
        assertNoStoredDeliveryRequest(context, "beforeClick")
        val host = MainActivity::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(main) as HostSession
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val afterClick = CountDownLatch(1)
        host.expansionWorker {
            entered.countDown()
            release.await()
        }
        try {
            assertTrue("휴식 사전 확인을 실행하기 전에 worker를 고정합니다", entered.await(5, TimeUnit.SECONDS))
            onUi {
                val start = checkNotNull(root.findViewWithTag<Button>("simulation:rest-start"))
                val staleConfirm = checkNotNull(root.findViewWithTag<Button>("delivery:confirm"))
                val delivery = tree(root).filterIsInstance<DeliveryView>().single()
                val request = selectedDeliveryRequest(delivery)
                assertTrue(start.performClick())
                start.performClick()
                assertEquals("휴식 준비 동안 worker는 계속 대기해야 합니다", 1L, release.count)
                assertFalse("현재 화면에서는 탁송 확정을 선택할 수 없습니다",
                    root.findViewWithTag<Button>("delivery:confirm")?.isEnabled == true)
                assertTrue("휴식 준비 중 이전 버튼의 실제 View 클릭 리스너를 호출합니다",
                    staleConfirm.performClick())
                assertMainCallbackBlocksWhilePreparing(main, host, delivery, request)
            }
            host.expansionWorker { afterClick.countDown() }
        } finally {
            release.countDown()
        }
        assertTrue("휴식 준비와 클릭까지 큐에 들어간 작업이 끝나야 합니다",
            afterClick.await(5, TimeUnit.SECONDS))
        assertNoStoredDeliveryRequest(context, "afterQueuedWork")
        val monitor = instrumentation.addMonitor(UnifiedSimulationActivity::class.java.name, null, false)
        val reopened = try {
            recreateDuringRestParking(main, root)
            requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000)) as UnifiedSimulationActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        try {
            val nextRoot = reopened.window.decorView
            val end = SystemClock.uptimeMillis() + 90_000
            var saved: RestDemoSaved? = null
            while (SystemClock.uptimeMillis() < end) {
                saved = (readRestDemo(games) as? ContractResult.Success)?.value
                if (saved != null) break
                SystemClock.sleep(100)
            }
            val result = requireNotNull(saved) { "같은 프로필에 휴식 포함 주행 두 건이 확정되어야 합니다" }
            assertEquals(identity, result.original.context.identity)
            assertEquals(2L, result.projection.confirmedDrives)
            assertEquals(0, result.projection.pendingDrives)
            assertEquals(100_000L, result.original.price.boostedMs)
            assertEquals(1_100_000L, result.projection.confirmed.remainingMs)
            assertEquals(GameMoney(15), result.original.observed.commit.amount)

            await("시연 완료 후 기존 진행 메뉴가 다시 허용되어야 합니다") {
                nextRoot.findViewWithTag<Spinner>("host:destination")?.isEnabled == true
            }
            val pageField = MainActivity::class.java.getDeclaredField("navigation").apply { isAccessible = true }
            val generationField = MainActivity::class.java.getDeclaredField("navigationGeneration").apply { isAccessible = true }
            val frameField = MainActivity::class.java.getDeclaredField("progressionFrame").apply { isAccessible = true }
            val expectedLabels = listOf(context.getString(R.string.progress_buff_active),
                "원장 잔여 1100초", "모의 입력 · 저장된 확정 상태")
            choosePage(nextRoot, "퀘스트·진행") { menu ->
                val page = (pageField.get(reopened) as HostNavigation).page
                Log.i("UnifiedRestActivityTest", "progressSelect page=$page generation=${generationField.getLong(reopened)} " +
                    "menu=${System.identityHashCode(menu)} selected=${menu.selectedItem} target=퀘스트·진행")
            }
            var lastProgressState: String? = null
            await("기존 진행 카드가 같은 원장의 확정 잔여 시간을 표시해야 합니다") {
                val card = tree(nextRoot).filterIsInstance<ProgressView>().singleOrNull()
                val labels = card?.let(::tree).orEmpty().filterIsInstance<TextView>().map { it.text.toString() }
                val menu = nextRoot.findViewWithTag<Spinner>("host:destination")
                val page = (pageField.get(reopened) as HostNavigation).page
                val frame = frameField.get(reopened) as? ProgressionFrame
                val screen = frame?.screen
                val rest = (screen as? ProgressScreenState.Content)?.rest
                val found = expectedLabels.map { it in labels }
                val state = "page=$page generation=${generationField.getLong(reopened)} " +
                    "menu=${menu?.let { System.identityHashCode(it) }} selected=${menu?.selectedItem} " +
                    "controls=${frame?.controlsEnabled} frame=${screen?.javaClass?.simpleName} " +
                    "rest=${rest?.buffPhase}/${rest?.remainingLabel}/${rest?.note} " +
                    "cardAttached=${card?.isAttachedToWindow} cardShown=${card?.isShown} " +
                    "detail=${card?.uiState?.selectedKey} expectedLabels=$found viewLabels=${labels.take(8)}"
                if (lastProgressState != state) {
                    Log.i("UnifiedRestActivityTest", "progressCard $state")
                    lastProgressState = state
                }
                found.all { it }
            }
            assertEquals(result, ok(readRestDemo(games)))
            assertNull(ok(games.open()).use { ok(it.store.delivery.inProgress()) })
            assertFalse(context.getDatabasePath(RestSimulationActivity.DATABASE).exists())
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        } finally {
            onUi { reopened.finish() }
        }
    }

    private fun selectedDeliveryRequest(delivery: DeliveryView): DeliveryRequest {
        val state = delivery.saveUiState()
        val frame = checkNotNull(state.frame)
        val offers = frame.screen as DeliveryScreen.Offers
        val selectedId = checkNotNull(state.selectedOfferId)
        val interaction = DeliveryInteraction(state)
        check(interaction.render(frame, true))
        return checkNotNull(interaction.request(DeliveryCommand.Select(offers.proposal.proposed, selectedId)))
    }

    private fun assertMainCallbackBlocksWhilePreparing(
        main: MainActivity, host: HostSession, delivery: DeliveryView, request: DeliveryRequest,
    ) {
        val ticket = MainActivity::class.java.getDeclaredField("restStartTicket").apply { isAccessible = true }
        assertNotNull("휴식 사전 확인이 끝나기 전의 Main 콜백을 검사합니다", ticket.get(main))
        @Suppress("UNCHECKED_CAST")
        val callback = DeliveryView::class.java.getDeclaredField("onRequest").apply { isAccessible = true }
            .get(delivery) as (DeliveryRequest) -> Unit
        val previousUi = host.deliveryUi
        // Force the actual Main callback separately from the stale View click. Its unguarded path replaces deliveryUi.
        callback(request)
        assertSame("휴식 준비 중 Main 콜백은 탁송 UI 상태를 넘기지 않아야 합니다", previousUi, host.deliveryUi)
        Log.i("UnifiedRestActivityTest", "forcedMainCallback blocked frame=${request.frameVersion}")
    }

    private fun assertNoStoredDeliveryRequest(context: Context, phase: String) {
        val path = context.getDatabasePath("unified-simulation.db").path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (table in listOf("delivery_requests", "host_command_checkpoint")) {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    val count = rows.getLong(0)
                    Log.i("UnifiedRestActivityTest", "$phase $table=$count")
                    assertEquals("저장된 탁송 요청 또는 원명령: $table", 0L, count)
                }
            }
        }
    }

    private fun recreateDuringRestParking(main: UnifiedSimulationActivity, root: View) {
        val restStage = Regex("""단계 (\d+)/72\b""")
        // 입력을 소비한 뒤 다음 250ms 대기를 시작한다. 처음 62번의 대기만으로도 15.5초다.
        val deadline = SystemClock.uptimeMillis() + 90_000
        var lastStatus = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val recreated = onUi {
                lastStatus = root.findViewWithTag<TextView>("simulation:status")?.text?.toString().orEmpty()
                val stage = restStage.find(lastStatus)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    lastStatus.contains("모의 입력 처리 보류") ->
                        fail("주차 중 재생성 전에 시연 입력 처리가 보류됐습니다. 상태: $lastStatus")
                    stage != null && stage >= 68 ->
                        fail("재생성 전에 시험 장소 주차 구간을 지나쳤습니다. 상태: $lastStatus")
                }
                if (stage != null && stage in 63..67) {
                    main.recreate()
                    true
                } else {
                    false
                }
            }
            if (recreated) return
            SystemClock.sleep(50)
        }
        fail("90초 안에 시험 장소 주차 구간에 도달하지 못했습니다. 마지막 상태: $lastStatus")
    }

    private fun choosePage(root: View, label: String, beforeSelection: ((Spinner) -> Unit)? = null) {
        await("페이지 메뉴가 허용되어야 합니다") { root.findViewWithTag<Spinner>("host:destination")?.isEnabled == true }
        onUi {
            val menu = checkNotNull(root.findViewWithTag<Spinner>("host:destination"))
            beforeSelection?.invoke(menu)
            menu.setSelection((0 until menu.count).single { menu.getItemAtPosition(it).toString() == label })
        }
    }

    private fun tree(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()

    private fun click(root: View, tag: Any) {
        await("활성 버튼 $tag") { root.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled } == true }
        assertTrue(onUi { root.findViewWithTag<Button>(tag)?.performClick() == true })
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            if (onUi(condition)) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }
}
