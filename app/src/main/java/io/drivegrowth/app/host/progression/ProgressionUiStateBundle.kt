package io.drivegrowth.app.host.progression

import android.os.Bundle
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*

/** 화면 선택/참조/revision만 보존한다. 지급 원문·허용 상태·금융 결과의 저장소가 아니다. */
@Suppress("DEPRECATION")
object ProgressionUiStateBundle {
    fun encode(state: ProgressUiState): Bundle = Bundle().apply {
        putInt("version", 1); putString("group", state.group.name)
        state.selectedKey?.let { putBundle("selected", key(it)) }
        putStringArrayList("requested", ArrayList(state.requestedKeys)); putBoolean("retry", state.retryPending)
        state.pending?.let { putBundle("pending", reference(it)) }
        putParcelableArrayList("conflicts", ArrayList(state.pendingConflicts.map(::reference)))
        putParcelableArrayList("revisions", ArrayList(state.acceptedRevisions.map { (k, r) -> key(k).apply { putLong("revision", r) } }))
    }
    fun decode(bundle: Bundle?): ContractResult<ProgressUiState> {
        if (bundle == null) return ContractResult.Success(ProgressUiState())
        return try {
            require(bundle.get("version") == 1)
            val revisions = entries(bundle, "revisions").associate { readKey(it) to revision(it) }
            val conflicts = entries(bundle, "conflicts").map(::readReference).toSet()
            val requested = bundle.get("requested") as? ArrayList<*> ?: error("요청 참조 누락")
            require(requested.all { it is String && it.isNotBlank() })
            val retry = bundle.get("retry") as? Boolean ?: error("조회 상태 누락")
            ContractResult.Success(ProgressUiState(ProgressGroup.valueOf(string(bundle, "group")),
                nested(bundle, "selected")?.let(::readKey), requested.filterIsInstance<String>().toSet(),
                nested(bundle, "pending")?.let(::readReference), retry, revisions, conflicts))
        } catch (_: RuntimeException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
    }
    private fun key(value: ProgressKey) = Bundle().apply { putString("id", value.id); putString("period", value.periodId) }
    private fun reference(value: ClaimReference) = key(value.key).apply { putLong("revision", value.revision); putString("request", value.requestKey) }
    private fun readKey(value: Bundle) = ProgressKey(string(value, "id"), string(value, "period"))
    private fun readReference(value: Bundle) = ClaimReference(readKey(value), revision(value), string(value, "request"))
    private fun revision(value: Bundle): Long { val n = value.get("revision"); require(n is Long && n in 0 until Long.MAX_VALUE); return n }
    private fun string(value: Bundle, key: String): String { val s = value.get(key); require(s is String && s.isNotBlank()); return s }
    private fun nested(value: Bundle, key: String): Bundle? { if (!value.containsKey(key)) return null; return value.get(key) as? Bundle ?: error("화면 상태 형식 오류") }
    private fun entries(value: Bundle, key: String): List<Bundle> {
        val rows = value.get(key) as? ArrayList<*> ?: error("화면 상태 목록 누락")
        require(rows.all { it is Bundle }); return rows.filterIsInstance<Bundle>()
    }
}
