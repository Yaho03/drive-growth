package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import org.json.JSONException

/**
 * 같은 DB·실제 localProfileId의 미확정 명령 한 개. worker 전용이며 금액/개체 원장을 소유하지 않는다.
 * SAVED의 명시 취소와 STARTED의 원래 결과 복구를 구분한다. 알 수 없는 결과는 자동 삭제하지 않는다.
 */
class HostCommandCheckpointLedger(private val store: SqliteGameChangeStore) {
    companion object {
        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE host_command_checkpoint (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, phase INTEGER NOT NULL CHECK(phase IN (0,1)))")
        }
    }

    fun load(profileId: ProfileId): ContractResult<HostCommandCheckpoint?> = store.atomic { db ->
        when (val checked = profile(profileId)) {
            is ContractResult.Failure -> checked
            is ContractResult.Success -> ContractResult.Success(read(db, checked.value))
        }
    }

    fun save(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<HostCommandCheckpoint> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId)
        if (prior != null) return@atomic if (prior.command == command) ContractResult.Success(prior) else conflict()
        if (!command.validFor(store.source)) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val encoded = HostCheckpointJson.encode(profileId, command)
        // 잘못된 호출 입력을 DB 손상으로 저장하지 않는다.
        val decoded = try { HostCheckpointJson.decode(encoded) } catch (_: RuntimeException) {
            return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        if (decoded != (profileId to command)) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        if (store.requestUnavailable(db, command.requestId)) return@atomic conflict()
        db.execSQL("INSERT INTO host_command_checkpoint VALUES(1,?,0)", arrayOf(encoded))
        ContractResult.Success(HostCommandCheckpoint(profileId, command, HostCheckpointPhase.SAVED))
    }

    /**
     * STARTED와 기존 commit을 같은 DB 거래에 확정한다. 새 시도가 확정 거절되면 SAVED도 복원된다.
     * 기존 STARTED의 결과 불명은 실패로 지우지 않는다. 현재 입력으로 재prepare하지 않는다.
     */
    fun execute(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<HostCheckpointResult> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId) ?: return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (prior.command != command) return@atomic conflict()
        db.execSQL("UPDATE host_command_checkpoint SET phase=1 WHERE id=1")
        // nested 실패는 바깥 STARTED 쓰기도 rollback한다. 성공이면 원장과 표시가 함께 남는다.
        commit(command)
    }

    /** 실제 원래 정산이 존재하고 전체 명령 재생이 검증될 때만 같은 checkpoint를 지운다. */
    fun finish(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<HostCheckpointResult> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId) ?: return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (prior.command != command || prior.phase != HostCheckpointPhase.STARTED) return@atomic conflict()
        val receiptExists = db.rawQuery("SELECT 1 FROM settlements WHERE request_id=?",arrayOf(command.requestId.value)).use { it.moveToFirst() }
        if (!receiptExists) return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        // 같은 쓰기 거래 안에서 기존 receipt 존재를 고정한 뒤 원장 자체 decoder로 검증·재생한다.
        val result = commit(command)
        if (result is ContractResult.Failure) return@atomic result
        if (db.delete("host_command_checkpoint", "id=1", null) != 1) throw SQLiteException("checkpoint 삭제 대상 누락")
        result
    }

    fun cancelUnstarted(profileId: ProfileId, command: HostCheckpointCommand): ContractResult<Unit> = store.atomic { db ->
        when (val checked = profile(profileId)) { is ContractResult.Failure -> return@atomic checked; else -> Unit }
        val prior = read(db, profileId) ?: return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (prior.command != command || prior.phase != HostCheckpointPhase.SAVED || store.requestUnavailable(db, command.requestId))
            return@atomic conflict()
        if (db.delete("host_command_checkpoint", "id=1", null) != 1) throw SQLiteException("checkpoint 취소 대상 누락")
        ContractResult.Success(Unit)
    }

    private fun profile(expected: ProfileId): ContractResult<ProfileId> = when (val actual = store.localProfileId()) {
        is ContractResult.Failure -> actual
        is ContractResult.Success -> if (actual.value == expected) actual else ContractResult.Failure(ContractError.INVALID_INPUT)
    }

    private fun read(db: SQLiteDatabase, profile: ProfileId): HostCommandCheckpoint? =
        db.rawQuery("SELECT payload,phase FROM host_command_checkpoint WHERE id=1", null).use { row ->
            if (!row.moveToFirst()) return@use null
            val (owner, command) = HostCheckpointJson.decode(row.text(0))
            if (owner != profile || command.origin != store.source) throw JSONException("checkpoint 프로필/출처 불일치")
            val phase = if (row.integer(1,0,1) == 0L) HostCheckpointPhase.SAVED else HostCheckpointPhase.STARTED
            HostCommandCheckpoint(owner, command, phase)
        }

    private fun commit(command: HostCheckpointCommand): ContractResult<HostCheckpointResult> = when (command) {
        is HostCheckpointCommand.Gacha -> wrap(store.commitGacha(command.value)) { HostCheckpointResult.Gacha(it) }
        is HostCheckpointCommand.Garage -> wrap(store.commitGameChange(command.value)) { HostCheckpointResult.Garage(it) }
        is HostCheckpointCommand.Material -> wrap(store.commitMaterial(command.value)) { HostCheckpointResult.Material(it) }
        is HostCheckpointCommand.Repair -> wrap(store.commitRepair(command.value)) { HostCheckpointResult.Repair(it) }
    }
    private inline fun <A> wrap(result: ContractResult<A>, convert: (A) -> HostCheckpointResult): ContractResult<HostCheckpointResult> =
        when (result) { is ContractResult.Failure -> result; is ContractResult.Success -> ContractResult.Success(convert(result.value)) }
    private fun conflict() = ContractResult.Failure(ContractError.STATE_CONFLICT)
}
