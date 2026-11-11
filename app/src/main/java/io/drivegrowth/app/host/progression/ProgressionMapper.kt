package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
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
        return screen(result.command.source, base.values.map { item(it, null, false) }, read?.rest)
    }
    private fun screen(source: InputSource, entries: List<ProgressItem>,
        rest: ContractResult<RestDrivingProjection>?): ProgressScreenState.Content {
        val unsupported = ProgressItem(ProgressKey("unsupported-achievements", "unavailable"), ProgressGroup.ACHIEVEMENT, 0,
            "업적 조회", null, null, null, RewardStatus.UNSUPPORTED, "실제 업적 조회 연결 미지원")
        val displayed = (entries + unsupported).map { value ->
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
