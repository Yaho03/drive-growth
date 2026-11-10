package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*

/** worker 전용. 원장은 기존 공용 DB가 소유하며 전체 명령 한 개만 보존한다. */
interface GachaCommandJournal {
    fun pending(): ContractResult<GachaCommand?>
    fun save(command: GachaCommand): ContractResult<Unit>
    fun execute(command: GachaCommand): ContractResult<GachaSettlement>
    fun finish(command: GachaCommand): ContractResult<GachaSettlement>
    fun cancelUnstarted(command: GachaCommand): ContractResult<Unit>
}

/** UI checkpoint는 메모리 사본만 다룬다. 실제 save/execute/finish는 이 worker 경계에서 수행한다. */
class CheckpointGachaSource(private val source: GachaHostSource, private val journal: GachaCommandJournal) : GachaHostSource {
    fun pending() = journal.pending()
    override fun read(paid: RequestId, free: RequestId) = source.read(paid, free)
    override fun prepare(input: GachaDrawInput): ContractResult<GachaCommand> {
        when (val pending = journal.pending()) {
            is ContractResult.Failure -> return pending
            is ContractResult.Success -> if (pending.value != null) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        return when (val prepared = source.prepare(input)) {
            is ContractResult.Failure -> prepared
            is ContractResult.Success -> when (val saved = journal.save(prepared.value)) {
                is ContractResult.Failure -> saved
                is ContractResult.Success -> prepared
            }
        }
    }
    override fun commit(command: GachaCommand): ContractResult<GachaSettlement> {
        when (val saved = journal.save(command)) { is ContractResult.Failure -> return saved; else -> Unit }
        return when (val result = journal.execute(command)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> when (val checked = storedGachaResult(command, result.value)) {
                is ContractResult.Failure -> checked
                is ContractResult.Success -> result
            }
        }
    }
    /** 결과를 실제 표시한 뒤 사용자의 다음 이동/조회에서 해소한다. 실패하면 원명령을 유지한다. */
    fun finish(command: GachaCommand) = journal.finish(command)
    fun cancelUnstarted(command: GachaCommand) = journal.cancelUnstarted(command)
}
