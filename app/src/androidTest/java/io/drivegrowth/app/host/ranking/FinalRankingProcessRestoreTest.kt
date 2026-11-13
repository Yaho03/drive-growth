package io.drivegrowth.app.host.ranking

import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.LedgerJson
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.progression.FactoryProgressionSource
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.ranking.RankingMode
import io.drivegrowth.app.ui.ranking.RankingView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleId
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.FutureTask

/** 같은 설치의 두 selector 사이에 시험 앱 프로세스를 종료한다. */
@Suppress("DEPRECATION")
class FinalRankingProcessRestoreTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val database get() = context.getDatabasePath("unified-simulation.db")
    private val evidence get() = context.filesDir.resolve("ranking-process-restore.json")
    private val asOf = SimulationTimeline().clock.epoch
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to database.name), InputSource.SIMULATION, {
            when (val definitions = AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure -> definitions
                is ContractResult.Success -> definitions.value.questDefinitions(ZoneId.of("UTC"))
            }
        }, { asOf.value })
    }

    fun testRecordAndDemoBeforeProcessStop() {
        requireIsolated()
        assertFalse("새 격리 원장이 필요합니다.", database.exists())
        assertFalse("이전 단계의 인계 파일이 없어야 합니다.", evidence.exists())
        for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"), R.string.onboarding_review,
            R.string.onboarding_confirm, R.string.onboarding_to_shop)) {
            click("첫 차량 지급", activityRoot) { it.tag == tag }
        }
        prepareEarnedIncome()
        val ledger = readLedger()
        val screen = inspectRanking(ledger.privateIds)
        assertEquals("랭킹 열람 중 기존 원장 보존", ledger.digest, readLedger().digest)
        evidence.writeText(JSONObject()
            .put("beforePid", Process.myPid())
            .put("ledgerDigest", ledger.digest)
            .put("myRecord", JSONArray(screen.myRecord))
            .put("demo", JSONArray(screen.demo)).toString())
        Log.i(TAG, "BEFORE pid=${Process.myPid()} source=SIMULATION metrics=6 demoRows=4 balance=1100 earned=100")
    }

    fun testRecordAndDemoAfterProcessStop() {
        requireIsolated()
        assertTrue("이전 selector의 시연 원장이 필요합니다.", database.exists())
        assertTrue("이전 selector의 계측 기록이 필요합니다.", evidence.isFile)
        val before = JSONObject(evidence.readText())
        assertTrue("새 OS 프로세스에서 실행해야 합니다.", before.getInt("beforePid") != Process.myPid())
        val ledger = readLedger()
        assertEquals("같은 프로필·개체·원정산과 잔액을 재개방", before.getString("ledgerDigest"), ledger.digest)
        val screen = inspectRanking(ledger.privateIds)
        assertEquals(before.getJSONArray("myRecord").toString(), JSONArray(screen.myRecord).toString())
        assertEquals(before.getJSONArray("demo").toString(), JSONArray(screen.demo).toString())
        assertEquals("새 프로세스의 랭킹 열람도 원장 보존", ledger.digest, readLedger().digest)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        evidence.writeText(before.put("afterPid", Process.myPid()).toString())
        Log.i(TAG, "AFTER pid=${Process.myPid()} previousPid=${before.getInt("beforePid")} sameLedger=true metrics=6 demoRows=4")
    }

    /** 초기 지급 결과 열람과 일일 보상은 누적 수익을 현재 잔액과 구별하기 위한 준비 거래다. */
    private fun prepareEarnedIncome() {
        val first = ok(games.open()).use { game ->
            assertEquals(InputSource.SIMULATION, game.identity.source)
            assertEquals(GameMoney(1_000), ok(game.store.balance()))
            val receipt = ok(SettlementHistoryReader(game.store).read()).entries.single()
            ok(game.store.markViewed(receipt.settlement.settlementId))
            receipt.settlement
        }
        val quests = FactoryProgressionSource(games) { Observation.Available(asOf) }
        val ready = ok(quests.read(emptySet()))
        val command = ready.offers.values.single { it.definition.id == "daily-result-viewed" }
        assertEquals(GameMoney(100), command.definition.reward)
        val receipt = run {
            ok(quests.save(ready.identity, command))
            ok(quests.execute(ready.identity, command)).settlement
        }
        assertEquals(GameMoney(1_100), receipt.balance)
        ok(games.open()).use { game ->
            val history = ok(SettlementHistoryReader(game.store).read()).entries
            assertEquals(setOf(first, receipt), history.map { it.settlement }.toSet())
            assertTrue(history.single { it.settlement == first }.viewed)
        }
    }

    private data class Ledger(val digest: String, val privateIds: List<String>)

    private fun readLedger() = ok(games.open()).use { game ->
        assertEquals(InputSource.SIMULATION, game.identity.source)
        val history = ok(SettlementHistoryReader(game.store).read())
        assertEquals(game.identity, history.identity)
        val car = ok(game.store.listVehicleHistory()).single()
        assertEquals(VehicleId("hyundai_casper"), car.vehicleId)
        val balance = ok(game.store.balance())
        assertEquals(GameMoney(1_100), balance)
        assertEquals(2, history.entries.size)
        assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        val privateIds = listOf(game.identity.profileId.value, car.ownedVehicleId.value) +
            history.entries.flatMap { row -> listOf(row.settlement.settlementId.value,
                row.settlement.transaction.transactionId.value, row.settlement.transaction.requestId.value) }
        val snapshot = JSONArray().put(game.identity.profileId.value).put(game.identity.source.name)
            .put(balance.units).put(LedgerJson.vehicle(car))
        for (row in history.entries.sortedBy { it.settlement.settlementId.value }) {
            snapshot.put(LedgerJson.settlement(row.settlement)).put(row.viewed)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray(Charsets.UTF_8))
        Ledger(hash.joinToString("") { "%02x".format(it) }, privateIds)
    }

    private data class Screens(val myRecord: List<String>, val demo: List<String>)

    private fun inspectRanking(privateIds: List<String>): Screens {
        awaitUi("기록·랭킹 탐색") {
            val tab = activityRoot.mainPage(HostPage.RANKING) ?: return@awaitUi false
            tab.isShown && tab.isEnabled && (tab.isSelected || tab.performClick())
        }
        awaitUi("같은 모의 프로필의 내 기록 조회") { ranking()?.let { view ->
            texts(view).any { it.startsWith("모의 입력 기록 · 기준 ") }
        } == true }
        val mine = onUi {
            val view = checkNotNull(ranking())
            assertEquals(RankingMode.MY_RECORD, view.uiState.mode)
            checkNoPrivateIds(view, privateIds)
            val lines = texts(view)
            assertTrue(lines.any { it.startsWith("모의 입력 기록 · 기준 ") && it.endsWith(" 한국시간") })
            assertFalse(lines.any { it.startsWith("차량 입력 기록") || it.startsWith("확인 불가 ·") || it.startsWith("조회 실패 ·") })
            assertMetric(lines, RankingMetric.SHOP_LEVEL, "1 레벨")
            assertMetric(lines, RankingMetric.COLLECTION, "1 칸")
            assertMetric(lines, RankingMetric.OWNED, "1 대")
            assertMetric(lines, RankingMetric.EARNED, "100 게임머니")
            assertMetric(lines, RankingMetric.HIGHEST_VALUE, "1000 게임머니")
            val week = valueAfter(lines, RankingMetric.WEEKLY_DISTANCE.title)
            assertTrue(week.endsWith("Asia/Seoul · 확정 주행 기록 없음"))
            assertFalse(lines.any { it.contains("예시 가게") })
            lines
        }
        click("시연 랭킹 열기", activityRoot) { it.text.toString() == "시연용 랭킹 (예시 데이터)" }
        click("시연 누적 수익 선택", activityRoot) { it.text.toString() == RankingMetric.EARNED.title }
        val demo = onUi {
            val view = checkNotNull(ranking())
            assertEquals(RankingMode.DEMO, view.uiState.mode)
            assertEquals(RankingMetric.EARNED, view.uiState.metric)
            checkNoPrivateIds(view, privateIds)
            val lines = texts(view)
            assertTrue(lines.contains("시연용 랭킹 (예시 데이터)"))
            assertFalse(lines.any { it.startsWith("모의 입력 기록 ·") || it.startsWith("차량 입력 기록") || it == "100 게임머니" })
            assertEquals(listOf("1위 · 예시 가게 A", "2위 · 예시 가게 B", "2위 · 예시 가게 C", "4위 · 예시 가게 D"),
                lines.filter { "위 · " in it })
            for ((row, value) in listOf("1위 · 예시 가게 A" to "12500 게임머니", "2위 · 예시 가게 B" to "8500 게임머니",
                "2위 · 예시 가게 C" to "8500 게임머니", "4위 · 예시 가게 D" to "4000 게임머니")) {
                assertEquals(value, valueAfter(lines, row))
            }
            lines
        }
        return Screens(mine, demo)
    }

    private fun valueAfter(lines: List<String>, title: String): String {
        assertEquals("표시 항목 $title", 1, lines.count { it == title })
        return lines[lines.indexOf(title) + 1]
    }

    private fun assertMetric(lines: List<String>, metric: RankingMetric, expected: String) =
        assertEquals(metric.title, expected, valueAfter(lines, metric.title))

    private fun checkNoPrivateIds(view: RankingView, privateIds: List<String>) {
        val labels = texts(view) + descendants(view).mapNotNull { it.contentDescription?.toString() }.toList()
        assertFalse("랭킹에 프로필·개체·원정산 식별자를 표시하지 않습니다.",
            labels.any { label -> privateIds.any { id -> id.isNotEmpty() && id in label } })
    }

    private fun requireIsolated() {
        assertEquals("랭킹 복원 시험 전용 패키지", PACKAGE, context.packageName)
        assertTrue((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
    }

    private val activityRoot get() = activity.window.decorView
    private fun ranking() = descendants(activityRoot).filterIsInstance<RankingView>().singleOrNull()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }.toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun click(label: String, root: View, matches: (Button) -> Boolean) = awaitUi(label) {
        descendants(root).filterIsInstance<Button>().singleOrNull { it.isShown && it.isEnabled && matches(it) }
            ?.performClick() == true
    }

    private fun awaitUi(label: String, condition: () -> Boolean) {
        activityRoot
        val deadline = SystemClock.uptimeMillis() + 20_000
        do {
            if (onUi(condition)) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun <T> ok(value: ContractResult<T>): T = when (value) {
        is ContractResult.Success -> value.value
        is ContractResult.Failure -> error("원장 공개 경로 실패 ${value.error}")
    }

    private companion object {
        const val PACKAGE = "io.drivegrowth.verify.ranking28restore"
        const val TAG = "DGRankingRestore"
    }
}
