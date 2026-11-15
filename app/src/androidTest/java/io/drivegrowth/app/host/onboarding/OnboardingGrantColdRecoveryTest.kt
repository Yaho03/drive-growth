package io.drivegrowth.app.host.onboarding

import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.host.HostPage
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.SettlementHistoryCause
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileInitializationStatus
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.FirstGrant
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** 두 계측 사이에서 호스트가 자기 격리 앱의 PID를 종료한다. */
@Suppress("DEPRECATION")
class OnboardingGrantColdRecoveryTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val choice = VehicleId("hyundai_casper")
    private val readyFile get() = File(context.cacheDir, "first-grant-before-ui.json")
    private val recoveredFile get() = File(context.cacheDir, "first-grant-recovered.json")

    fun testHoldCommittedGrantBeforeUiReplyForHostStop() {
        assertIsolated()
        for (name in listOf("unified-simulation.db", "game-ledger-vehicle.db", "game-ledger-simulation.db")) {
            assertFalse("새 격리 설치가 필요합니다: $name", context.getDatabasePath(name).exists())
        }
        assertFalse(context.getSharedPreferences("unified-simulation-v1", 0).contains("stage"))
        assertFalse(readyFile.exists())
        assertFalse(recoveredFile.exists())

        val main = activity
        click(main, R.string.onboarding_start)
        click(main, choice)
        click(main, R.string.onboarding_review)
        awaitUi("최초 지급 확인") { button(main, R.string.onboarding_confirm) != null }
        val session = MainActivity::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(main) as HostSession
        val worker = HostSession::class.java.getDeclaredField("worker")
            .apply { isAccessible = true }.get(session) as Executor

        instrumentation.runOnMainSync {
            val before = session.onboarding.uiState
            assertEquals(OnboardingStep.CONFIRMING, before.step)
            assertEquals(choice, before.selected)
            assertEquals(InputSource.SIMULATION, before.identity?.source)
            assertNull(before.pending)
            assertTrue(checkNotNull(button(main, R.string.onboarding_confirm)).performClick())
            assertEquals(choice, session.onboarding.uiState.pending)

            // #205와 같은 serial worker 표식이다. Main의 큐는 이 블록 안에서 진행되지 않는다.
            var proof: Result<JSONObject>? = null
            val workerFinished = CountDownLatch(1)
            worker.execute {
                try {
                    proof = runCatching { readOriginalGrant() }
                } finally {
                    workerFinished.countDown()
                }
            }
            assertTrue("최초 지급 worker와 별도 연결의 원결과 조회", workerFinished.await(15, TimeUnit.SECONDS))
            val grant = checkNotNull(proof).getOrThrow()
            assertEquals(choice, session.onboarding.uiState.pending)
            assertNull("완료 응답은 아직 UI에 전달되지 않아야 합니다", button(main, R.string.onboarding_to_shop))
            assertTrue(context.getString(R.string.onboarding_submitting) in labels(main))
            writeMarker(readyFile, JSONObject()
                .put("phase", "committed_before_ui")
                .put("pid", Process.myPid())
                .put("pendingModel", choice.value)
                .put("completionButtonVisible", false)
                .put("grant", grant))

            // 호스트가 표식을 읽고 살아 있는 이 PID를 종료할 때까지 큐를 막는다.
            CountDownLatch(1).await(25, TimeUnit.SECONDS)
            fail("호스트가 커밋 후 UI 응답 대기 구간의 프로세스를 종료하지 않았습니다")
        }
    }

    fun testNewProcessReadsOriginalGrantWithoutAnotherWrite() {
        assertIsolated()
        assertFalse(recoveredFile.exists())
        val before = JSONObject(AtomicFile(readyFile).readFully().decodeToString())
        assertEquals("committed_before_ui", before.getString("phase"))
        val previousPid = before.getInt("pid")
        assertTrue("새 OS 프로세스가 필요합니다", previousPid != Process.myPid())
        val original = before.getJSONObject("grant").toString()
        assertEquals(original, readOriginalGrant().toString())

        val main = activity
        awaitUi("허용된 Main 탐색") {
            menu(main)?.let { it.isShown && it.isEnabled } == true
        }
        instrumentation.runOnMainSync {
            main.window.decorView.openMainPage(HostPage.ONBOARDING)
        }
        awaitUi("저장된 최초 지급 완료 화면") { button(main, R.string.onboarding_to_shop) != null }
        instrumentation.runOnMainSync {
            val visible = labels(main)
            assertTrue(context.getString(R.string.onboarding_already_granted) in visible)
            assertTrue("캐스퍼" in visible)
            assertTrue("1,000" in visible)
            assertTrue("3칸" in visible)
            assertNull(button(main, R.string.onboarding_confirm))
            captureContent(main, "first-grant-original-screen.png")
        }
        click(main, R.string.onboarding_to_shop)
        awaitUi("원결과 확인 뒤 가게 화면") {
            descendants(main.window.decorView).any { it is ShopView && it.isShown && it.width > 0 && it.height > 0 }
        }
        instrumentation.runOnMainSync { captureContent(main, "first-grant-shop-screen.png") }
        val after = readOriginalGrant()
        assertEquals(original, after.toString())
        writeMarker(recoveredFile, JSONObject()
            .put("phase", "original_shown_in_new_process")
            .put("pid", Process.myPid())
            .put("previousPid", previousPid)
            .put("originalSummaryVisible", true)
            .put("shopVisibleAfterAction", true)
            .put("grant", after))
    }

    fun testUnconfirmedSelectionLeavesNewProfileForHostStop() {
        assertFirstVehiclePackage()
        assertFalse(context.getDatabasePath("unified-simulation.db").exists())
        val main = launchFromStartScreen()
        click(main, R.string.onboarding_start)
        click(main, choice)
        click(main, R.string.onboarding_review)
        awaitUi("지급 전 확인 버튼") { button(main, R.string.onboarding_confirm) != null }
        val profile = readUninitializedProfile()
        writeMarker(File(context.cacheDir, "first-car-unconfirmed.json"), JSONObject()
            .put("pid", Process.myPid()).put("profileId", profile.value))
        awaitHostStop()
    }

    fun testColdReentryAllowsOneFirstVehicleGrant() {
        assertFirstVehiclePackage()
        val before = JSONObject(File(context.cacheDir, "first-car-unconfirmed.json").readText())
        assertTrue(before.getInt("pid") != Process.myPid())
        assertEquals(before.getString("profileId"), readUninitializedProfile().value)
        val main = launchFromStartScreen()
        click(main, R.string.onboarding_start)
        click(main, choice)
        click(main, R.string.onboarding_review)
        click(main, R.string.onboarding_confirm)
        awaitUi("최초 지급 완료 및 가게 진입 가능") { button(main, R.string.onboarding_to_shop) != null }
        val original = readOriginalGrant()
        assertEquals(before.getString("profileId"), original.getString("profileId"))
        instrumentation.runOnMainSync { captureContent(main, "first-car-granted.png") }
        click(main, R.string.onboarding_to_shop)
        awaitUi("지급 후 가게") { menu(main)?.let { it.isShown && it.isEnabled } == true }
        writeMarker(File(context.cacheDir, "first-car-granted.json"), JSONObject()
            .put("pid", Process.myPid()).put("grant", original))
        awaitHostStop()
    }

    fun testColdReentryRestoresSameFirstVehicleAndBalance() {
        assertFirstVehiclePackage()
        val before = JSONObject(File(context.cacheDir, "first-car-granted.json").readText())
        assertTrue(before.getInt("pid") != Process.myPid())
        val original = before.getJSONObject("grant").toString()
        val main = launchFromStartScreen()
        awaitUi("복귀 뒤 가게 조작 가능") { menu(main)?.let { it.isShown && it.isEnabled } == true }
        instrumentation.runOnMainSync { main.window.decorView.openMainPage(HostPage.ONBOARDING) }
        awaitUi("원래 최초 지급 안내") { button(main, R.string.onboarding_to_shop) != null }
        instrumentation.runOnMainSync {
            assertTrue(context.getString(R.string.onboarding_already_granted) in labels(main))
            assertTrue("캐스퍼" in labels(main))
            assertNull(button(main, R.string.onboarding_confirm))
            captureContent(main, "first-car-restored.png")
        }
        click(main, R.string.onboarding_to_shop)
        awaitUi("가게 지도에 동일 첫 차량") {
            main.window.decorView.findViewWithTag<View>(
                "shop:map-vehicle:${before.getJSONObject("grant").getString("ownedVehicleId")}"
            )?.isShown == true
        }
        val restored = readOriginalGrant()
        assertEquals(original, restored.toString())
        writeMarker(File(context.cacheDir, "first-car-restored.json"), JSONObject()
            .put("pid", Process.myPid()).put("grant", restored))
    }

    private fun launchFromStartScreen(): UnifiedSimulationActivity {
        setActivityIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        val main = activity
        click(main, "presentation:start")
        return main
    }

    private fun assertFirstVehiclePackage() {
        assertTrue(context.packageName in setOf(
            "io.drivegrowth.verify.prototype.firstcarcheck",
            "io.drivegrowth.verify.prototype.firstcarrecheck",
        ))
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
    }

    private fun readUninitializedProfile(): ProfileId {
        assertTrue(context.getDatabasePath("unified-simulation.db").isFile)
        val factory = GameSessionFactory(context,
            mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        return ok(factory.open()).use { game ->
            val state = ok(game.store.readInitialization(game.identity.profileId))
            assertEquals(InputSource.SIMULATION, game.identity.source)
            assertTrue(state.status is ProfileInitializationStatus.New)
            assertEquals(0, state.ownedVehicleCount)
            assertEquals(0L, state.balance.units)
            assertTrue(ok(game.store.listOwnedVehicles()).isEmpty())
            assertTrue(ok(SettlementHistoryReader(game.store).read()).entries.isEmpty())
            game.identity.profileId
        }
    }

    private fun awaitHostStop() {
        CountDownLatch(1).await(35, TimeUnit.SECONDS)
        fail("호스트에서 표식의 시험 프로세스를 종료해야 합니다")
    }

    private fun captureContent(main: MainActivity, name: String) {
        val content = main.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        try {
            content.draw(Canvas(bitmap))
            File(context.cacheDir, name).outputStream().use { stream ->
                assertTrue("현재 앱 화면의 PNG 저장", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertIsolated() {
        assertEquals("io.drivegrowth.verify.onboardingcold56", context.packageName)
    }

    private fun readOriginalGrant(): JSONObject {
        assertTrue(context.getDatabasePath("unified-simulation.db").isFile)
        val factory = GameSessionFactory(context,
            mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        return ok(factory.open()).use { game ->
            val store = game.store
            val identity = game.identity
            val state = ok(store.readInitialization(identity.profileId))
            val original = checkNotNull((state.status as? ProfileInitializationStatus.Existing)?.original)
            val car = checkNotNull(original.grant.changedVehicle)
            val shop = ok(store.rental.shop())
            assertEquals(InputSource.SIMULATION, identity.source)
            assertEquals(identity.profileId, original.marker.profileId)
            assertEquals(FirstGrant.REQUEST_ID, original.marker.requestId)
            assertEquals(FirstGrant.REQUEST_ID, original.command.grant.requestId)
            assertEquals(FirstGrant.REQUEST_ID, original.grant.transaction.requestId)
            assertEquals(choice, car.vehicleId)
            assertEquals(listOf(car), ok(store.listOwnedVehicles()))
            assertEquals(listOf(original.grant), ok(store.listUnviewedSettlements()))
            val history = ok(SettlementHistoryReader(store).read()).entries
            assertEquals(1, history.size)
            assertEquals(SettlementHistoryCause.INITIAL_GRANT, history.single().cause)
            assertEquals(original.grant, history.single().settlement)
            assertEquals(1_000L, original.grant.balance.units)
            assertEquals(1_000L, state.balance.units)
            assertEquals(1, state.ownedVehicleCount)
            assertEquals(3, original.garageCapacity)
            assertEquals(3, shop.capacity)
            assertEquals(1, shop.progress.shopLevel)
            assertNull("초기 지급은 공용 checkpoint 슬롯을 사용하지 않습니다", ok(store.hostCommands.load(identity.profileId)))
            JSONObject()
                .put("profileId", identity.profileId.value)
                .put("source", identity.source.name)
                .put("requestId", original.command.grant.requestId.value)
                .put("settlementId", original.grant.settlementId.value)
                .put("transactionId", original.grant.transaction.transactionId.value)
                .put("confirmedAt", original.grant.confirmedAt.value)
                .put("ownedVehicleId", car.ownedVehicleId.value)
                .put("vehicleId", car.vehicleId.value)
                .put("balance", state.balance.units)
                .put("capacity", shop.capacity)
        }
    }

    private fun writeMarker(file: File, value: JSONObject) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(value.toString().encodeToByteArray())
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun menu(main: MainActivity): View? = main.window.decorView.mainNavigation()
    private fun button(main: MainActivity, tag: Any): Button? =
        main.window.decorView.findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }

    private fun labels(main: MainActivity): Set<String> =
        descendants(main.window.decorView).filterIsInstance<TextView>().filter { it.isShown }
            .map { it.text.toString() }.toSet()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun awaitUi(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var shown = false
        while (!shown && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { shown = predicate() }
            if (!shown) SystemClock.sleep(40)
        }
        assertTrue("화면 대기: $label", shown)
    }

    private fun click(main: MainActivity, tag: Any) {
        awaitUi("활성 버튼 $tag") { button(main, tag) != null }
        instrumentation.runOnMainSync { assertTrue(checkNotNull(button(main, tag)).performClick()) }
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
