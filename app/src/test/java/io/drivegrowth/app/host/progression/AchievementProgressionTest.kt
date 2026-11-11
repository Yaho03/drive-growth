package io.drivegrowth.app.host.progression

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.pendingHostPage
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class AchievementProgressionTest {
    private val identity = GameIdentity(ProfileId("test"), InputSource.SIMULATION)
    private val definition = AchievementDefinition(AchievementMetric.OWNED, RuleVersion("trial"), 2, GameMoney(100), true)
    private val at = EpochMillis(1)
    private val milestone = AchievementMilestone(identity.profileId, identity.source, definition, AchievementMeasure(2), at)
    private val command = AchievementClaimCommand(RequestId(ACHIEVEMENT_CLAIM_PREFIX + "earned"), milestone)
    private val key = achievementKey(definition.metric)
    private val earned = AchievementProgress(definition, AchievementMeasure(1), milestone)
    private val receipt = AchievementClaimSettlement(command, Settlement(SettlementId("first"),
        Transaction(TransactionId("first"), command.requestId, TransactionKind.GARAGE, identity.source, definition.version,
            MoneyChange(MoneyDirection.CREDIT, definition.reward)), GameMoney(100), null, at))

    @Test fun `재실행 시 공통 체크포인트가 진행 페이지로 연결된다`() {
        assertEquals(ContractResult.Success(HostPage.PROGRESSION), pendingHostPage(HostCheckpointCommand.Achievement(command)))
    }

    @Test fun `제한 직전 저장한 업적 명령은 다시 허용된 뒤 원래 한 건만 실행한다`() {
        val source = FakeSource()
        val worker = Queue()
        val main = Queue()
        val controller = ProgressionController(worker, main, source)
        var screen: ProgressScreenState = ProgressScreenState.Loading
        controller.attach { screen = it.screen }
        fun drain() {
            while (worker.jobs.isNotEmpty() || main.jobs.isNotEmpty()) {
                if (worker.jobs.isNotEmpty()) worker.jobs.removeFirst().run()
                if (main.jobs.isNotEmpty()) main.jobs.removeFirst().run()
            }
        }

        controller.setControlsEnabled(true)
        drain()
        val item = (screen as ProgressScreenState.Content).entries.single { it.key == key }
        val event = ProgressEvent.ClaimReward(key, item.revision, requireNotNull(item.claim).requestKey)
        controller.handle(event)
        controller.handle(event)
        worker.jobs.removeFirst().run() // durable save, UI 응답은 아직
        controller.setControlsEnabled(false)
        drain()
        assertEquals(1, source.saves)
        assertEquals(0, source.executions)
        controller.setControlsEnabled(true)
        drain()
        assertEquals(1, source.executions)
        assertEquals(RewardStatus.CLAIMED, (screen as ProgressScreenState.Content).entries.single { it.key == key }.status)
    }

    @Test fun `업적만 확인할 수 없을 때 조회 실패를 명시하고 Quest를 표시한다`() {
        val zone = java.time.ZoneId.of("UTC")
        val quest = QuestDefinition("daily-result-viewed", RuleVersion("existing"), QuestCadence.DAILY, QuestAction.RESULT_VIEWED,
            1, GameMoney(50), zone, true)
        val states = listOf(QuestProgress(identity.profileId, identity.source, quest,
            questPeriod(quest.cadence, zone, at), 0, Revision(0), QuestStatus.IN_PROGRESS))
        val read = ProgressionRead(identity, states, emptyMap(), ProgressionJournalState.Clear, true,
            achievements = ContractResult.Failure(ContractError.INVALID_INPUT))
        val screen = ProgressionMapper().content(read)
        assertEquals(RewardStatus.IN_PROGRESS, screen.entries.single { it.group == ProgressGroup.DAILY }.status)
        val failed = screen.entries.single { it.group == ProgressGroup.ACHIEVEMENT }
        assertEquals(RewardStatus.FAILED, failed.status)
        assertNull(failed.claim)
    }

    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
    }

    private inner class FakeSource : ProgressionSource {
        var saves = 0
        var executions = 0
        private var journal: ProgressionJournalState = ProgressionJournalState.Clear
        override fun read(references: Set<ClaimReference>) = ContractResult.Success(
            ProgressionRead(identity, emptyList(), emptyMap(), journal, true,
                achievements = ContractResult.Success(listOf(earned.copy(claimed = executions > 0))),
                achievementOffers = if (executions == 0) mapOf(key to command) else emptyMap()))
        override fun save(identity: GameIdentity, command: QuestClaimCommand) = error("unexpected Quest save")
        override fun execute(identity: GameIdentity, command: QuestClaimCommand) = error("unexpected Quest execute")
        override fun cancelSaved(identity: GameIdentity, command: QuestClaimCommand) = error("unexpected Quest cancel")

        override fun save(identity: GameIdentity, command: AchievementClaimCommand): ContractResult<Unit> {
            assertEquals(this@AchievementProgressionTest.command, command)
            saves++
            journal = ProgressionJournalState.Achievement(command, ProgressionPhase.SAVED)
            return ContractResult.Success(Unit)
        }

        override fun execute(identity: GameIdentity, command: AchievementClaimCommand): ContractResult<AchievementClaimSettlement> {
            assertEquals(this@AchievementProgressionTest.command, command)
            executions++
            journal = ProgressionJournalState.Clear
            return ContractResult.Success(receipt)
        }
    }
}
