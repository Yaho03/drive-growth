package io.drivegrowth.app.host.progression

import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.achievement.AchievementClaimCommand
import io.drivegrowth.core.quest.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** UI 진입/main 응답, worker 원장 접근. 읽기·save·execute 사이마다 현재 세대/gate를 확인한다. */
class ProgressionController(private val worker: Executor, private val main: Executor, private val source: ProgressionSource) {
    private val generation = AtomicLong()
    private val enabled = AtomicBoolean()
    private val released = AtomicBoolean()
    private val mapper = ProgressionMapper()
    private var listener: ((ProgressionFrame) -> Unit)? = null
    private var busy = false
    private var requested = false
    private var latest: ProgressionRead? = null
    private var content: ProgressScreenState.Content? = null
    private val references = linkedSetOf<ClaimReference>()
    fun attach(listener: (ProgressionFrame) -> Unit) { detach(); if (!released.get()) { this.listener = listener; emit(ProgressScreenState.Loading, false) } }
    fun detach() { generation.incrementAndGet(); enabled.set(false); requested = false; listener = null; content = null }
    fun release() { detach(); released.set(true) }
    fun restoreUiState(value: ProgressUiState) {
        mapper.restoreFloors(value.acceptedRevisions)
        references.addAll(value.pendingConflicts); value.pending?.let { references.add(it) }
        mapper.restoreFloors(references.groupBy { it.key }.mapValues { (_, refs) -> refs.maxOf { it.revision } })
        if (enabled.get()) { content = null; refresh() }
    }
    fun setControlsEnabled(value: Boolean) {
        if (released.get() || enabled.getAndSet(value) == value) return
        generation.incrementAndGet()
        if (value) refresh() else { requested = false; emit(ProgressScreenState.Loading, false) }
    }
    fun refresh() {
        if (!enabled.get() || released.get()) return
        generation.incrementAndGet(); requested = true; emit(ProgressScreenState.Loading, true); launchRead()
    }
    fun handle(event: ProgressEvent) {
        if (!enabled.get() || released.get()) return
        when (event) {
            ProgressEvent.Retry -> refresh()
            is ProgressEvent.ClaimReward -> {
                if (busy || requested) return
                val read = latest ?: return
                val item = content?.entries?.find { it.key == event.key } ?: return
                if (item.revision != event.revision || item.claim?.enabled != true || item.claim.requestKey != event.requestKey) return
                val quest = read.offers[event.key]
                val achievement = read.achievementOffers[event.key]
                if ((quest?.requestId ?: achievement?.requestId)?.value != event.requestKey) return
                val token = generation.incrementAndGet(); references.add(ClaimReference(event.key, event.revision, event.requestKey))
                emit(ProgressScreenState.Loading, true)
                if (quest != null) {
                    job(token, { source.save(read.identity, quest) }) { result ->
                        when (result) { is ContractResult.Failure -> failed(); is ContractResult.Success -> execute(token, read, quest) }
                    }
                } else if (achievement != null) {
                    job(token, { source.save(read.identity, achievement) }) { result ->
                        when (result) { is ContractResult.Failure -> failed(); is ContractResult.Success -> execute(token, read, achievement) }
                    }
                }
            }
            else -> Unit // 상세 선택/닫기는 기존 View가 소유한다.
        }
    }
    val canCancelSaved: Boolean
        get() = enabled.get() && !released.get() && !busy && !requested && content == null &&
            when (val pending = latest?.journal) {
                is ProgressionJournalState.Quest -> pending.phase == ProgressionPhase.SAVED
                is ProgressionJournalState.Achievement -> pending.phase == ProgressionPhase.SAVED
                else -> false
            }

    /** B의 명시적인 미시작 청구 취소 행동에서만 호출한다. 상세 Back/페이지 이동은 취소가 아니다. */
    fun cancelSaved() {
        if (!canCancelSaved) return
        val read = latest ?: return
        val cancel: () -> ContractResult<Unit> = when (val pending = read.journal) {
            is ProgressionJournalState.Quest -> {
                if (pending.phase != ProgressionPhase.SAVED) return
                ({ source.cancelSaved(read.identity, pending.command) })
            }
            is ProgressionJournalState.Achievement -> {
                if (pending.phase != ProgressionPhase.SAVED) return
                ({ source.cancelSaved(read.identity, pending.command) })
            }
            else -> return
        }
        val token = generation.incrementAndGet()
        job(token, cancel) { result ->
            when (result) { is ContractResult.Failure -> failed(); is ContractResult.Success -> refresh() }
        }
    }
    private fun launchRead() {
        if (busy || !requested || !enabled.get() || released.get()) return
        requested = false; val token = generation.get(); val refs = references.toSet()
        job(token, { source.read(refs) }) { result ->
            when (result) {
                is ContractResult.Failure -> failed()
                is ContractResult.Success -> {
                    val read = result.value
                    if (latest != null && latest!!.identity != read.identity) { content = null; failed(); return@job }
                    latest = read
                    val next = mapper.content(read); content = next
                    val resolved = read.resolved.keys + read.resolvedAchievements.keys
                    references.removeAll(resolved)
                    emit(next, true, resolved)
                    when (val pending = read.journal) {
                        is ProgressionJournalState.Quest -> execute(token, read, pending.command)
                        is ProgressionJournalState.Achievement -> execute(token, read, pending.command)
                        else -> if (resolved.isNotEmpty()) refresh()
                    }
                }
            }
        }
    }
    private fun execute(token: Long, read: ProgressionRead, command: QuestClaimCommand) {
        job(token, { source.execute(read.identity, command) }) { result ->
            when (result) {
                is ContractResult.Failure -> failed()
                is ContractResult.Success -> {
                    val receipt = result.value
                    if (receipt.command != command || receipt.command.profileId != read.identity.profileId || receipt.command.source != read.identity.source) { failed(); return@job }
                    val next = mapper.receipt(read, receipt); content = next
                    val resolved = references.filter { it.requestKey == command.requestId.value && it.key == progressKey(receipt.progress) }.toSet()
                    references.removeAll(resolved); emit(next, true, resolved); refresh()
                }
            }
        }
    }
    private fun execute(token: Long, read: ProgressionRead, command: AchievementClaimCommand) {
        job(token, { source.execute(read.identity, command) }) { result ->
            when (result) {
                is ContractResult.Failure -> failed()
                is ContractResult.Success -> {
                    val receipt = result.value
                    if (receipt.command != command || command.profileId != read.identity.profileId ||
                        command.source != read.identity.source) { failed(); return@job }
                    val next = mapper.receipt(read, receipt); content = next
                    val key = achievementKey(command.milestone.definition.metric)
                    val resolved = references.filter { it.requestKey == command.requestId.value && it.key == key }.toSet()
                    references.removeAll(resolved)
                    emit(next, true, resolved)
                    refresh()
                }
            }
        }
    }
    private fun <T> job(token: Long, action: () -> ContractResult<T>, receive: (ContractResult<T>) -> Unit) {
        if (!valid(token) || busy) return
        busy = true
        worker.execute {
            val result = if (!valid(token)) null else try { action() }
                catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
                catch (_: Exception) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
            main.execute {
                busy = false
                if (result != null && valid(token)) receive(result)
                launchRead()
            }
        }
    }
    private fun valid(token: Long) = !released.get() && enabled.get() && token == generation.get()
    private fun failed() { content = null; emit(ProgressScreenState.Failure("진행 기록·청구 결과를 확인할 수 없습니다. 다시 조회해 주세요.", true), true) }
    private fun emit(screen: ProgressScreenState, controls: Boolean, resolved: Set<ClaimReference> = emptySet()) {
        listener?.invoke(ProgressionFrame(screen, controls && enabled.get(), resolved, screen !is ProgressScreenState.Loading))
    }
}
