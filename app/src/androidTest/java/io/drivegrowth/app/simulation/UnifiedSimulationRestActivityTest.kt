package io.drivegrowth.app.simulation

import io.drivegrowth.app.mainGameControls
import io.drivegrowth.app.exposeMainPage
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.host.HostPage
import android.app.ActivityOptions
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.os.Bundle
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.host.GameIdentity
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
class UnifiedSimulationRestActivityTest : InstrumentationTestCase() {

    fun testExplicitRestRunPaysAndShowsConfirmedStateInTheMainProfile() = exerciseRestRun(checkViewport = false)

    fun testConfirmedRestCanBeReadInTheShallowMainWindow() = exerciseRestRun(checkViewport = true)

    fun testRestSceneStacksInNarrowWindow() = exerciseRestRun(checkViewport = false, narrowScene = true)

    private fun exerciseRestRun(checkViewport: Boolean, narrowScene: Boolean = false) {
        val context = instrumentation.targetContext
        assertTrue("새 격리 시험 패키지가 필요합니다", context.packageName.startsWith("io.drivegrowth.verify."))
        for (name in listOf("unified-simulation.db", "game-ledger-vehicle.db", RestSimulationActivity.DATABASE)) {
            assertFalse("기존 원장을 보존합니다: $name", context.getDatabasePath(name).exists())
        }
        val display = if (checkViewport) null else context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.name == "Emulator 2D Display" && it.displayId != 0 }
        val main = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), display?.let {
            ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle()
        }) as UnifiedSimulationActivity
        if (narrowScene && display != null) {
            val task = onUi { main.taskId }
            var current = main
            val resized = observeReplacement(main) { current = it }
            onUi { main.application.registerActivityLifecycleCallbacks(resized) }
            try {
                val requested = instrumentation.uiAutomation.executeShellCommand("am task resize $task 0 0 600 760")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(requested).use { it.readBytes() }
                await("시연 창의 너비가 600px이어야 합니다") { current.window.decorView.width == 600 && !current.isDestroyed }
                verifyRestRun(context, onUi { current }, checkViewport)
            } finally {
                onUi { main.application.unregisterActivityLifecycleCallbacks(resized) }
                val restored = instrumentation.uiAutomation.executeShellCommand("am task resize $task 0 0 1200 800")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(restored).use { it.readBytes() }
            }
        } else {
            assertFalse("좁은 창 계측에는 복제 AVD 보조 디스플레이가 필요합니다", narrowScene)
            verifyRestRun(context, main, checkViewport)
        }
    }

    private fun verifyRestRun(context: Context, main: UnifiedSimulationActivity, checkViewport: Boolean) {
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

        choosePage(root, HostPage.DELIVERY)
        click(root, "simulation:destination")
        var offer: Any? = null
        await("미확정 탁송 제안이 보여야 합니다") {
            offer = tree(root).filterIsInstance<Button>()
                .firstOrNull { it.isEnabled && (it.tag as? String)?.startsWith("delivery:offer:") == true }?.tag
            offer != null
        }
        click(root, checkNotNull(offer))
        onUi { root.showSimulationControls(main) }
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
        assertRestScene(main, "휴게소 도착 · P", "rest-arrived")
        val beforeRecreation = assertRestScene(main, "휴게소 · 쉬는 중", "rest-parking")
        var replacement: UnifiedSimulationActivity? = null
        val lifecycle = observeReplacement(main) { replacement = it }
        onUi { main.application.registerActivityLifecycleCallbacks(lifecycle) }
        val reopened = try {
            recreateDuringRestParking(main, root)
            await("휴식 도중 새 Activity가 시작되어야 합니다") { replacement != null }
            onUi { checkNotNull(replacement) }
        } finally {
            onUi { main.application.unregisterActivityLifecycleCallbacks(lifecycle) }
        }
        verifyAfterRecreation(context, games, identity, reopened, beforeRecreation, checkViewport)
    }

    private fun observeReplacement(old: UnifiedSimulationActivity, onResumed: (UnifiedSimulationActivity) -> Unit) =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is UnifiedSimulationActivity && activity !== old) onResumed(activity)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    private fun verifyAfterRecreation(context: Context, games: GameSessionFactory,
        identity: GameIdentity, reopened: UnifiedSimulationActivity,
        beforeRecreation: Int, checkViewport: Boolean) {
        try {
            val nextRoot = reopened.window.decorView
            await("재생성된 시연 창이 Android에 배치되어야 합니다") {
                nextRoot.isShown && nextRoot.width > 0
            }
            // 다중 화면에서 재진입하는 사용자의 첫 터치. 주차 장면에는 이 좌표에 조작이 없다.
            val displayId = onUi { checkNotNull(reopened.display).displayId }
            if (!onUi { reopened.hasWindowFocus() }) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("input -d $displayId tap 24 80")
                ).use { it.readBytes() }
            }
            await("재생성된 시연 창에 포커스가 돌아와야 합니다") { reopened.hasWindowFocus() }
            assertTrue("같은 정차의 실제 관측량을 줄이지 않는다",
                assertRestScene(reopened, "휴게소 · 쉬는 중", "rest-recreated") >= beforeRecreation)
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
                nextRoot.mainGameControls()?.isEnabled == true
            }
            val pageField = MainActivity::class.java.getDeclaredField("navigation").apply { isAccessible = true }
            val frameField = MainActivity::class.java.getDeclaredField("progressionFrame").apply { isAccessible = true }
            val expectedLabels = listOf(context.getString(R.string.progress_buff_active),
                "보너스 남은 시간 1100초")
            choosePage(nextRoot, HostPage.PROGRESSION) { menu ->
                val page = (pageField.get(reopened) as HostNavigation).page
                Log.i("UnifiedRestActivityTest", "progressSelect page=$page " +
                    "menu=${System.identityHashCode(menu)} selected=${menu.isSelected} target=퀘스트·진행")
            }
            var lastProgressState: String? = null
            await("기존 진행 카드가 같은 원장의 확정 잔여 시간을 표시해야 합니다") {
                val card = tree(nextRoot).filterIsInstance<ProgressView>().singleOrNull()
                val labels = card?.let(::tree).orEmpty().filterIsInstance<TextView>().map { it.text.toString() }
                val menu = nextRoot.mainNavigation()
                val page = (pageField.get(reopened) as HostNavigation).page
                val frame = frameField.get(reopened) as? ProgressionFrame
                val screen = frame?.screen
                val rest = (screen as? ProgressScreenState.Content)?.rest
                val found = expectedLabels.map { it in labels }
                val state = "page=$page " +
                    "menu=${menu?.let { System.identityHashCode(it) }} navShown=${menu?.isShown} " +
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
            if (checkViewport) assertRestVisibleAfterTouchScroll(nextRoot, context, expectedLabels)
            assertEquals(result, ok(readRestDemo(games)))
            assertNull(ok(games.open()).use { ok(it.store.delivery.inProgress()) })
            assertFalse(context.getDatabasePath(RestSimulationActivity.DATABASE).exists())
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        } finally {
            onUi { reopened.finish() }
        }
    }

    private fun assertRestScene(activity: UnifiedSimulationActivity, title: String, image: String): Int {
        val sceneTag = "simulation:rest-area-scene"
        await("휴식 연출 $title", 90_000) {
            activity.hasWindowFocus() && activity.window.decorView.findViewWithTag<View>(sceneTag)?.let { scene ->
                scene.isShown && tree(scene).filterIsInstance<TextView>().any { it.text == title }
            } == true
        }
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        var progressValue = 0
        val bitmap = onUi {
            val root = activity.window.decorView
            val scene = checkNotNull(root.findViewWithTag<View>(sceneTag))
            val labels = tree(scene).filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse("게임 장면의 모드 표기 $labels", "모의 입력 · SIMULATION" in labels)
            assertTrue("출발 전에는 확인 대기를 표시한다", labels.any { "휴식 인정 확인 대기" in it })
            assertFalse(labels.any { "휴식 보너스 준비됨" in it })
            val progress = tree(scene).filterIsInstance<ProgressBar>().single()
            assertTrue(progress.isShown)
            progressValue = progress.progress
            assertTrue(if (title.endsWith("· P")) progressValue == 0 else progressValue in 1..progress.max)
            assertFalse("쉬는 연출에서 게임 메뉴로 진입할 수 없다", root.mainNavigation()?.isShown == true)
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { buffer ->
                PixelCopy.request(activity.window, buffer, { status -> result = status; copied.countDown() },
                    android.os.Handler(activity.mainLooper))
            }
        }
        try {
            assertTrue("휴식 연출 창 캡처 응답", copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            instrumentation.targetContext.openFileOutput("$image.png", Context.MODE_PRIVATE).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
        return progressValue
    }

    private fun assertRestVisibleAfterTouchScroll(root: View, context: Context, expected: List<String>) {
        fun currentScroll(): ScrollView? = tree(root).filterIsInstance<ProgressView>().singleOrNull()
            ?.let(::tree)?.filterIsInstance<ScrollView>()?.singleOrNull()

        fun touchBounds(scroll: ScrollView): Rect {
            val bounds = Rect()
            check(scroll.getLocalVisibleRect(bounds)) { "REST_WINDOW: no visible scroll area" }
            val origin = IntArray(2)
            scroll.getLocationOnScreen(origin)
            bounds.offset(origin[0], origin[1])
            return bounds
        }

        await("REST_WINDOW: the progress overview must be laid out at the top") {
            val scroll = currentScroll()
            scroll?.isLaidOut == true && !scroll.isLayoutRequested && scroll.scrollY == 0 && root.hasWindowFocus()
        }
        val window = onUi { touchBounds(checkNotNull(currentScroll())) }
        val density = context.resources.displayMetrics.density
        Log.i("RestViewport", "REST_WINDOW viewport=$window density=$density dpi=${context.resources.displayMetrics.densityDpi}")
        assertTrue("REST_WINDOW: expected an 80–150dp shallow viewport and a narrow ProgressView",
            window.height() / density in 80f..150f &&
                onUi { tree(root).filterIsInstance<ProgressView>().single().width / density < 1000f })

        val seen = mutableSetOf<String>()
        for (step in 0..6) {
            instrumentation.waitForIdleSync()
            val (position, visible) = onUi {
                val scroll = checkNotNull(currentScroll())
                scroll.scrollY to tree(scroll).filterIsInstance<TextView>().filter { view ->
                    val bounds = Rect()
                    view.text.toString() in expected && view.isShown && view.getLocalVisibleRect(bounds) &&
                        bounds == Rect(0, 0, view.width, view.height)
                }.map { it.text.toString() }
            }
            val withinBudget = position <= window.height() * 2
            val newlyVisible = if (withinBudget) visible.filter(seen::add) else emptyList()
            Log.i("RestViewport", "REST_STEP step=$step y=$position visible=$visible newlyVisible=$newlyVisible")
            if (step == 0 || newlyVisible.isNotEmpty()) {
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    try {
                        context.openFileOutput("rest-viewport-$step.png", Context.MODE_PRIVATE).use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { screenshot.recycle() }
                }
            }
            if (seen.containsAll(expected)) return
            if (!withinBudget || step == 6) break
            swipeRestViewport(window)
            await("REST_TOUCH: swipe must advance the actual Main ScrollView") {
                (currentScroll()?.scrollY ?: 0) > position
            }
        }
        fail("REST_VISIBLE_BUDGET: fully visible within two viewports=$seen; missing=${expected - seen}")
    }

    private fun swipeRestViewport(bounds: Rect) {
        val x = bounds.exactCenterX()
        val bottom = bounds.top + bounds.height() * 0.85f
        val top = bounds.top + bounds.height() * 0.15f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("REST_TOUCH: Android must accept the touchscreen event",
                    instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, bottom)
        for (move in 1..8) {
            SystemClock.sleep(45)
            send(MotionEvent.ACTION_MOVE, bottom + (top - bottom) * move / 8)
        }
        SystemClock.sleep(150) // Prevent a fling from skipping a text row.
        send(MotionEvent.ACTION_MOVE, top)
        send(MotionEvent.ACTION_UP, top)
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
        val progress = main.getSharedPreferences("unified-simulation-v1", Context.MODE_PRIVATE)
        val session = main.onRetainNonConfigurationInstance() as HostSession
        // 입력을 소비한 뒤 다음 250ms 대기를 시작한다. 처음 62번의 대기만으로도 15.5초다.
        val deadline = SystemClock.uptimeMillis() + 90_000
        var lastStage = -1
        while (SystemClock.uptimeMillis() < deadline) {
            val recreated = onUi {
                lastStage = progress.getInt("stage", -1)
                when {
                    session.simulation?.canRetry == true ->
                        error("주차 중 재생성 전에 시연 입력 처리가 보류됐습니다. 단계: $lastStage")
                    lastStage >= 68 ->
                        error("재생성 전에 시험 장소 주차 구간을 지나쳤습니다. 단계: $lastStage")
                    lastStage in 63..67 && root.isShown -> {
                        main.recreate()
                        true
                    }
                    else -> false
                }
            }
            if (recreated) return
            SystemClock.sleep(50)
        }
        fail("90초 안에 시험 장소 주차 구간에 도달하지 못했습니다. 마지막 단계: $lastStage")
    }

    private fun choosePage(root: View, page: HostPage, beforeSelection: ((Button) -> Unit)? = null) {
        await("페이지 메뉴가 허용되어야 합니다") { root.mainGameControls()?.isEnabled == true }
        onUi {
            val menu = checkNotNull(root.exposeMainPage(page))
            beforeSelection?.invoke(menu)
            root.openMainPage(page)
        }
    }

    private fun tree(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()

    private fun click(root: View, tag: Any) {
        await("활성 버튼 $tag") { root.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled } == true }
        assertTrue(onUi { root.findViewWithTag<Button>(tag)?.performClick() == true })
    }

    private fun await(message: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMillis
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
