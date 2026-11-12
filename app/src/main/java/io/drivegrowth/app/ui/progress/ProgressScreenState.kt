package io.drivegrowth.app.ui.progress

import io.drivegrowth.core.driving.RestOutcome
import java.util.Collections

/** 표시용 식별자. 기간은 호스트가 결정하며 View는 날짜·만료를 계산하지 않는다. */
data class ProgressKey(val id: String, val periodId: String)
enum class ProgressGroup { DAILY, WEEKLY, ACHIEVEMENT, EVENT }
enum class RewardStatus { LOCKED, IN_PROGRESS, COMPLETE, CLAIMABLE, PENDING, CLAIMED, EXPIRED, FAILED, UNSUPPORTED }
/** 호스트가 발급하는 시도 식별자. 동일 요청의 재시도·원자 저장은 호스트 책임이다. */
data class ClaimOffer(val requestKey: String, val enabled: Boolean, val reason: String? = null) {
    init { require(requestKey.isNotBlank()) }
}
data class ProgressItem(
    val key: ProgressKey,
    val group: ProgressGroup,
    val revision: Long,
    val title: String,
    val progressLabel: String?,
    /** 이미 계산된 표시값. null이면 진행 막대를 만들지 않는다. */
    val progressPercent: Int?,
    val rewardLabel: String?,
    val status: RewardStatus,
    val note: String? = null,
    val claim: ClaimOffer? = null,
) {
    init { require(revision >= 0); require(progressPercent == null || progressPercent in 0..100) }
}
enum class BuffPhase { INACTIVE, NEXT_DEPARTURE, ACTIVE, UNCONFIRMED, UNSUPPORTED }
data class RestCondition(val label: String, val satisfied: Boolean?)
/** 계산 결과와 저장/출발 연결을 확인한 호스트만 buffPhase를 공급한다. View가 RestBonus를 실행하지 않는다. */
data class RestPresentation(
    val recognition: RestOutcome?,
    val buffPhase: BuffPhase,
    val conditions: List<RestCondition> = emptyList(),
    val effectLabel: String? = null,
    val remainingLabel: String? = null,
    val note: String? = null,
)
sealed interface ProgressScreenState {
    data object Loading : ProgressScreenState
    data class Content(
        /** 같은 View의 범위 안에서 증가하는 조회 세대. 같은 세대는 같은 snapshot이다. */
        val sequence: Long,
        val entries: List<ProgressItem>,
        val sourceLabel: String,
        val periodLabel: String? = null,
        val rest: RestPresentation? = null,
        val eventsNote: String = "표시할 채택 이벤트 정보가 없어요",
    ) : ProgressScreenState {
        init {
            require(sequence >= 0)
            require(entries.map { it.key }.distinct().size == entries.size) { "목표·기간 ID 중복" }
            val keys = entries.mapNotNull { it.claim?.requestKey }
            require(keys.distinct().size == keys.size) { "청구 시도 ID 중복" }
        }
    }
    data class Empty(val message: String) : ProgressScreenState
    data class Failure(val message: String, val retryAllowed: Boolean) : ProgressScreenState
    data class Unsupported(val message: String) : ProgressScreenState
}
data class ClaimReference(val key: ProgressKey, val revision: Long, val requestKey: String)
data class ProgressUiState(
    val group: ProgressGroup = ProgressGroup.DAILY,
    val selectedKey: ProgressKey? = null,
    val requestedKeys: Set<String> = emptySet(),
    val pending: ClaimReference? = null,
    val retryPending: Boolean = false,
    /** 같은 프로필에서 수락한 목표·기간별 최대 revision. 목록 소멸로 지우지 않는다. */
    val acceptedRevisions: Map<ProgressKey, Long> = emptyMap(),
    /** 서로 다른 미확정 요청은 각각 조회로 해소할 때까지 함께 보존한다. */
    val pendingConflicts: Set<ClaimReference> = emptySet(),
)
sealed interface ProgressEvent {
    data class Select(val key: ProgressKey) : ProgressEvent
    data object CloseDetail : ProgressEvent
    data class ClaimReward(val key: ProgressKey, val revision: Long, val requestKey: String) : ProgressEvent
    data object Retry : ProgressEvent
}

/** 입력·선택·요청 중복만 관리한다. 완료/수령 상태와 보상은 외부 snapshot만 표시한다. */
class ProgressInteraction(private val onEvent: (ProgressEvent) -> Unit) {
    var state: ProgressScreenState = ProgressScreenState.Loading; private set
    var uiState = ProgressUiState(); private set
    var controlsEnabled = false; private set
    private var latest: ProgressScreenState.Content? = null
    val selectedItem: ProgressItem? get() = (state as? ProgressScreenState.Content)?.entries?.find { it.key == uiState.selectedKey }

    fun render(value: ProgressScreenState, enabled: Boolean): Boolean {
        controlsEnabled = enabled // 오래된 결과여도 제한 입력은 먼저 적용한다.
        if (value is ProgressScreenState.Content) {
            val previous = latest
            if (previous != null && (value.sequence < previous.sequence ||
                    value.sequence == previous.sequence && value != previous)) return false
            if (containsStaleRevision(value)) return false
            val frozen = value.copy(entries = Collections.unmodifiableList(ArrayList(value.entries)),
                rest = value.rest?.copy(conditions = Collections.unmodifiableList(ArrayList(value.rest.conditions))))
            val pending = uiState.pending
            val updated = frozen.entries.find { it.key == pending?.key }
            if (uiState.pendingConflicts.isEmpty() && pending != null && updated != null && updated.revision > pending.revision &&
                updated.status in listOf(RewardStatus.CLAIMED, RewardStatus.FAILED, RewardStatus.EXPIRED, RewardStatus.UNSUPPORTED)) {
                update(uiState.copy(pending = null))
            }
            update(uiState.copy(acceptedRevisions = uiState.acceptedRevisions + frozen.entries.associate { it.key to it.revision }))
            latest = frozen; state = if (uiState.pendingConflicts.isEmpty()) frozen else ProgressScreenState.Loading
        } else state = value
        return true
    }
    fun restoreUiState(value: ProgressUiState) {
        val revisions = LinkedHashMap(uiState.acceptedRevisions)
        value.acceptedRevisions.forEach { (key, revision) ->
            revisions[key] = maxOf(revisions[key] ?: -1L, revision)
        }
        val requests = (listOfNotNull(uiState.pending, value.pending) + uiState.pendingConflicts + value.pendingConflicts).toSet()
        val conflicts = if (requests.size > 1 || uiState.pendingConflicts.isNotEmpty() || value.pendingConflicts.isNotEmpty()) requests else emptySet()
        update(value.copy(acceptedRevisions = revisions,
            pending = uiState.pending ?: value.pending ?: requests.firstOrNull(), pendingConflicts = conflicts,
            requestedKeys = uiState.requestedKeys + value.requestedKeys + requests.map { it.requestKey },
            retryPending = uiState.retryPending || value.retryPending))
        // 복원이 첫 render 뒤에 와도 낡은 표시를 남기지 않는다. 이력만으로 결과를 만들지 않는다.
        val current = state as? ProgressScreenState.Content
        if (uiState.pendingConflicts.isNotEmpty() || current != null && containsStaleRevision(current)) state = ProgressScreenState.Loading
    }
    fun select(key: ProgressKey) {
        if (!controlsEnabled || (state as? ProgressScreenState.Content)?.entries?.none { it.key == key } != false) return
        update(uiState.copy(selectedKey = key)); onEvent(ProgressEvent.Select(key))
    }
    fun changeGroup(group: ProgressGroup) {
        if (controlsEnabled && group in listOf(ProgressGroup.DAILY, ProgressGroup.WEEKLY)) update(uiState.copy(group = group))
    }
    fun closeDetail(): Boolean {
        if (!controlsEnabled || uiState.selectedKey == null) return false
        update(uiState.copy(selectedKey = null)); onEvent(ProgressEvent.CloseDetail); return true
    }
    fun isPending(item: ProgressItem): Boolean = item.status == RewardStatus.PENDING || uiState.pending?.key == item.key ||
        uiState.pendingConflicts.any { it.key == item.key }
    fun canClaim(): Boolean = selectedItem?.let(::canClaim) == true

    internal fun canClaim(item: ProgressItem): Boolean {
        val entries = (state as? ProgressScreenState.Content)?.entries ?: return false
        val offer = item.claim ?: return false
        if (item !in entries || item.revision < (uiState.acceptedRevisions[item.key] ?: -1L)) return false
        return controlsEnabled && uiState.pending == null && uiState.pendingConflicts.isEmpty() &&
            entries.none { it.status == RewardStatus.PENDING } && offer.enabled && offer.requestKey !in uiState.requestedKeys &&
            item.rewardLabel != null && item.status in listOf(RewardStatus.CLAIMABLE, RewardStatus.FAILED)
    }
    fun claim(expected: ClaimReference? = selectedItem?.let { item -> item.claim?.let { ClaimReference(item.key, item.revision, it.requestKey) } }): Boolean {
        val current = selectedItem ?: return false
        if (expected != current.claim?.let { ClaimReference(current.key, current.revision, it.requestKey) } || !canClaim()) return false
        val item = selectedItem!!; val offer = item.claim!!
        update(uiState.copy(pending = ClaimReference(item.key, item.revision, offer.requestKey), requestedKeys = uiState.requestedKeys + offer.requestKey))
        onEvent(ProgressEvent.ClaimReward(item.key, item.revision, offer.requestKey)); return true
    }
    fun canRetry(): Boolean = controlsEnabled && !uiState.retryPending &&
        ((state as? ProgressScreenState.Failure)?.retryAllowed == true ||
            state == ProgressScreenState.Loading && uiState.pendingConflicts.isNotEmpty())
    fun retry(): Boolean {
        if (!canRetry()) return false
        update(uiState.copy(retryPending = true)); onEvent(ProgressEvent.Retry); return true
    }
    fun completeRetry() { update(uiState.copy(retryPending = false)) }
    /** 호스트가 각 요청의 실제 조회/복구 완료를 확인한 뒤 호출한다. 수령 결과는 새 Content로 별도 공급한다. */
    fun completePendingRecovery(resolved: Set<ClaimReference>) {
        if (uiState.pendingConflicts.isEmpty()) return
        val confirmed = resolved.intersect(uiState.pendingConflicts)
        val remaining = uiState.pendingConflicts - confirmed
        update(uiState.copy(pending = uiState.pending?.takeUnless { it in confirmed } ?: remaining.firstOrNull(),
            pendingConflicts = remaining))
    }
    private fun containsStaleRevision(value: ProgressScreenState.Content): Boolean =
        value.entries.any { it.revision < (uiState.acceptedRevisions[it.key] ?: -1L) }
    private fun update(value: ProgressUiState) {
        uiState = value.copy(requestedKeys = Collections.unmodifiableSet(LinkedHashSet(value.requestedKeys)),
            acceptedRevisions = Collections.unmodifiableMap(LinkedHashMap(value.acceptedRevisions)),
            pendingConflicts = Collections.unmodifiableSet(LinkedHashSet(value.pendingConflicts)))
    }
}
