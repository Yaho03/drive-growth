package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.quest.*
import java.math.BigDecimal
import java.util.Collections

/** 표시 세대는 backend 사건 count revision과 독립이다. 지급 사실은 원장 snapshot만 사용한다. */
class ProgressionMapper {
    private var sequence = 0L
    private val revisions = linkedMapOf<ProgressKey, Long>()
    private val previous = linkedMapOf<ProgressKey, ProgressItem>()
    fun restoreFloors(values: Map<ProgressKey, Long>) {
        values.forEach { (key, value) -> if (value >= 0) revisions[key] = maxOf(revisions[key] ?: -1, value) }
    }
    fun content(read: ProgressionRead): ProgressScreenState.Content {
        val entries = linkedMapOf<ProgressKey, ProgressItem>()
        read.quests.forEach { progress ->
            val offer = read.offers[progressKey(progress)]
            entries[progressKey(progress)] = item(progress, offer, read.journalSupported && read.journal == ProgressionJournalState.Clear)
        }
        achievementItems(read, read.journalSupported && read.journal == ProgressionJournalState.Clear).forEach { entries[it.key] = it }
        val pendingAchievement = read.journal as? ProgressionJournalState.Achievement
        if (pendingAchievement != null) {
            val milestone = pendingAchievement.command.milestone
            val entry = achievementItem(AchievementProgress(milestone.definition, milestone.measure, milestone), null, false)
            entries[entry.key] = entry.copy(status = RewardStatus.PENDING, note = "저장된 업적 청구의 원결과를 확인하고 있습니다.")
        }
        read.resolvedAchievements.forEach { (reference, receipt) ->
            if (receipt != null) {
                val milestone = receipt.command.milestone
                val entry = achievementItem(AchievementProgress(milestone.definition, milestone.measure, milestone, claimed = true), null, false)
                entries[entry.key] = entry
            } else {
                val old = entries[reference.key] ?: previous[reference.key]
                entries[reference.key] = if (old?.status == RewardStatus.CLAIMED) old else
                    ProgressItem(reference.key, ProgressGroup.ACHIEVEMENT, 0, "이전 업적 청구", null, null, null,
                        RewardStatus.FAILED, "원장에 확정된 이전 업적 청구가 없습니다.")
            }
        }
        val pending = read.journal as? ProgressionJournalState.Quest
        if (pending != null) {
            val c = pending.command; val key = progressKey(c.definition, c.period)
            entries[key] = item(QuestProgress(c.profileId, c.source, c.definition, c.period, null, null, QuestStatus.UNKNOWN), null, false)
                .copy(status = RewardStatus.PENDING, note = "저장된 전체 청구 명령을 원장에서 확인·복구하고 있습니다.")
        }
        read.resolved.forEach { (reference, receipt) ->
            if (receipt != null) entries[progressKey(receipt.progress)] = item(receipt.progress.copy(status = QuestStatus.CLAIMED), null, false)
            else if (entries[reference.key]?.status != RewardStatus.CLAIMED) {
                val current = entries[reference.key] ?: previous[reference.key]?.takeIf { it.status == RewardStatus.CLAIMED }
                if (current?.status == RewardStatus.CLAIMED) { entries[reference.key] = current; return@forEach }
                entries[reference.key] = current?.copy(status = RewardStatus.FAILED, note = "원장에 확정된 이전 청구 기록이 없습니다. 현재 조건으로 다시 요청할 수 있습니다.")
                    ?: ProgressItem(reference.key, ProgressGroup.DAILY, 0, "이전 퀘스트 청구", null, null, null,
                        RewardStatus.FAILED, "원장 조회로 이전 청구의 확정 기록이 없음을 확인했습니다.")
            }
        }
        return screen(read.identity.source, entries.values.toList(), read.rest)
    }
    fun receipt(read: ProgressionRead?, result: QuestClaimSettlement): ProgressScreenState.Content {
        val base = read?.quests.orEmpty().associateBy(::progressKey).toMutableMap()
        base[progressKey(result.progress)] = result.progress.copy(status = QuestStatus.CLAIMED)
        return screen(result.command.source, base.values.map { item(it, null, false) } +
            (read?.let { achievementItems(it, false) }.orEmpty()), read?.rest)
    }
    fun receipt(read: ProgressionRead, result: AchievementClaimSettlement): ProgressScreenState.Content {
        val milestone = result.command.milestone
        val earned = achievementItem(AchievementProgress(milestone.definition, milestone.measure, milestone, claimed = true), null, false)
        val items = achievementItems(read, false).filter { it.key != earned.key } + earned
        return screen(read.identity.source, read.quests.map { item(it, null, false) } + items, read.rest)
    }

    private fun achievementItems(read: ProgressionRead, allowClaim: Boolean): List<ProgressItem> =
        when (val values = read.achievements) {
            null -> emptyList()
            is ContractResult.Failure -> listOf(ProgressItem(ProgressKey("unavailable-achievements", "lifetime"),
                ProgressGroup.ACHIEVEMENT, 0, "업적 조회", null, null, null, RewardStatus.FAILED,
                "업적 기록을 확인할 수 없습니다. (${values.error})"))
            is ContractResult.Success -> values.value.map { progress ->
                achievementItem(progress, read.achievementOffers[achievementKey(progress.definition.metric)], allowClaim)
            }
        }

    private fun achievementItem(progress: AchievementProgress, command: AchievementClaimCommand?, allowClaim: Boolean): ProgressItem {
        val definition = progress.milestone?.definition ?: progress.definition
        val measure = progress.milestone?.measure ?: progress.measure
        val status = when (progress.status) {
            AchievementStatus.UNKNOWN -> RewardStatus.LOCKED
            AchievementStatus.IN_PROGRESS -> RewardStatus.IN_PROGRESS
            AchievementStatus.CLAIMABLE -> RewardStatus.CLAIMABLE
            AchievementStatus.CLAIMED -> RewardStatus.CLAIMED
        }
        val title = when (definition.metric) {
            AchievementMetric.OWNED -> "보유 차량 수"
            AchievementMetric.CUSTOMERS -> "누적 고객 수"
            AchievementMetric.SHOP -> "가게 레벨"
            AchievementMetric.COLLECTION -> "도감 완성률"
        }
        val ratio = measure?.let { value ->
            val total = value.total
            if (total == null) minOf(100L, value.count.toLong() * 100 / definition.goal).toInt()
            else minOf(100L, value.count.toLong() * 10000 / (total.toLong() * definition.goal)).toInt()
        }
        val label = measure?.let { value ->
            val total = value.total
            if (total == null) "${value.count} / ${definition.goal}"
            else "${value.count} / ${total}칸 · 목표 ${definition.goal}%"
        } ?: "진행 확인 불가"
        val claim = command?.takeIf { progress.status == AchievementStatus.CLAIMABLE }?.let {
            ClaimOffer(it.requestId.value, allowClaim, if (allowClaim) null else "다른 미확정 청구를 먼저 확인해 주세요.")
        }
        return ProgressItem(achievementKey(definition.metric), ProgressGroup.ACHIEVEMENT, 0, title, label,
            ratio, "${definition.reward.units} 게임머니", status,
            if (definition.trial) "조정 가능한 시험 정의 · ${definition.version.value}" else definition.version.value, claim)
    }

    private fun screen(source: InputSource, entries: List<ProgressItem>,
        rest: ContractResult<RestDrivingProjection>?): ProgressScreenState.Content {
        val unsupported = ProgressItem(ProgressKey("unsupported-achievements", "unavailable"), ProgressGroup.ACHIEVEMENT, 0,
            "업적 조회", null, null, null, RewardStatus.UNSUPPORTED, "실제 업적 조회 연결 미지원")
        val displayed = (if (entries.any { it.group == ProgressGroup.ACHIEVEMENT }) entries else entries + unsupported).map { value ->
            val signature = value.copy(revision = 0)
            val last = revisions[value.key] ?: -1
            val revision = if (previous[value.key] == signature) maxOf(last, 0) else Math.addExact(last, 1)
            revisions[value.key] = revision; previous[value.key] = signature
            value.copy(revision = revision)
        }
        return ProgressScreenState.Content(++sequence, Collections.unmodifiableList(displayed),
            if (source == InputSource.VEHICLE) "차량 입력 프로필 · 원장 기록" else "모의 입력 프로필 · 원장 기록",
            "Quest 정의에 명시된 기간·시간대 기준", restPresentation(source, rest), "채택 이벤트 조회 연결 미지원")
    }

    private fun restPresentation(source: InputSource, read: ContractResult<RestDrivingProjection>?): RestPresentation =
        when (read) {
            null -> RestPresentation(null, BuffPhase.UNSUPPORTED, note = "휴식 원장 조회 연결 미지원")
            is ContractResult.Failure -> RestPresentation(null, BuffPhase.UNCONFIRMED, note = when (read.error) {
                ContractError.DATA_UNAVAILABLE -> "확인 가능한 휴식 기록이 없습니다."
                ContractError.STORAGE_FAILURE -> "저장된 휴식 상태를 읽지 못했습니다."
                else -> "휴식 상태를 확인하지 못했습니다. (${read.error})"
            })
            is ContractResult.Success -> {
                val stored = read.value
                when {
                    stored.pendingDrives != 0 -> RestPresentation(null, BuffPhase.UNCONFIRMED, note = "휴식 원장의 확정을 기다리고 있습니다.")
                    stored.confirmedDrives == 0L -> RestPresentation(null, BuffPhase.UNCONFIRMED, note = "확정된 휴식 원장 기록이 없습니다.")
                    else -> {
                        val confirmed = stored.confirmed
                        val remaining = BigDecimal.valueOf(confirmed.remainingMs, 3).stripTrailingZeros().toPlainString()
                        RestPresentation(null, if (confirmed.isActive) BuffPhase.ACTIVE else BuffPhase.INACTIVE,
                            remainingLabel = "원장 잔여 ${remaining}초",
                            note = if (source == InputSource.SIMULATION) "모의 입력 · 저장된 확정 상태" else "차량 입력 · 저장된 확정 상태")
                    }
                }
            }
        }

    private fun item(p: QuestProgress, command: QuestClaimCommand?, allowClaim: Boolean): ProgressItem {
        val known = p.status != QuestStatus.UNKNOWN && p.count != null
        val percent = if (known) ((p.count!!.coerceAtMost(p.definition.goal).toLong() * 100) / p.definition.goal).toInt() else null
        val status = when (p.status) {
            QuestStatus.LOCKED, QuestStatus.UNKNOWN -> RewardStatus.LOCKED
            QuestStatus.IN_PROGRESS -> RewardStatus.IN_PROGRESS
            QuestStatus.CLAIMABLE -> RewardStatus.CLAIMABLE
            QuestStatus.CLAIMED -> RewardStatus.CLAIMED
            QuestStatus.EXPIRED -> RewardStatus.EXPIRED
        }
        val action = when (p.definition.action) {
            QuestAction.RESULT_VIEWED -> "결과 확인하기"
            QuestAction.VEHICLE_LEVEL_UP -> "차량 레벨 올리기"
            QuestAction.RENTAL_COMPLETED -> "영업 완료하기"
        }
        val note = if (!known) "기간 또는 사건 기록 완전성 확인 대기 · 조작 불가"
            else "${p.period.start} · ${p.period.zone.id}${if (p.definition.trial) " · 조정 가능한 시험 정의" else ""}"
        return ProgressItem(progressKey(p), if (p.definition.cadence == QuestCadence.DAILY) ProgressGroup.DAILY else ProgressGroup.WEEKLY,
            0, action, if (known) "${p.count} / ${p.definition.goal}회" else "진행 확인 불가", percent,
            "${p.definition.reward.units} 게임머니", status, note,
            command?.takeIf { known && p.status == QuestStatus.CLAIMABLE }?.let {
                ClaimOffer(it.requestId.value, allowClaim, if (allowClaim) null else "공통 청구 저장 연결 또는 다른 미확정 요청을 확인해 주세요.")
            })
    }
}
