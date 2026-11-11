package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*

/** backend가 현재 공통 원장에서 공급한 사실. UI가 잔액/소유/가게 단계를 만들어 채우지 않는다. */
data class DecorationFacts(val state: DecorationState, val definitions: DecorationDefinitions, val shop: ShopScreenState.Content)

/** 공용 한 슬롯의 라우팅 결과이며 도메인 명령·원장 모델을 복제하지 않는다. */
sealed interface DecorationPending {
    data object Empty : DecorationPending
    data object Foreign : DecorationPending
    data object Unsupported : DecorationPending
    data class Own(val command: DecorationCommand) : DecorationPending
}

/** 직렬 worker 전용. E의 같은 factory/hostcheckpoint adapter를 주입한다. 실패/불명은 정상 null로 바꾸지 않는다. */
interface DecorationBackend {
    /** E의 명시 activate만 연결한다. read/preview/retry가 자동 호출해서는 안 된다. */
    fun activate(): ContractResult<Unit>
    fun pending(): ContractResult<DecorationPending>
    fun read(): ContractResult<DecorationFacts>
    fun save(command: DecorationCommand): ContractResult<Unit>
    fun execute(command: DecorationCommand): ContractResult<DecorationSettlement>
    fun finish(command: DecorationCommand): ContractResult<Unit>
    fun cancelSaved(command: DecorationCommand): ContractResult<Unit>
}

/** receipt는 당시 원결과, current/screen은 별도 현재 조회다. 둘을 서로의 기본값으로 사용하지 않는다. */
data class DecorationHostState(
    val displayRevision: Revision = Revision(0),
    val screen: ShopScreenState = ShopScreenState.Loading,
    val current: DecorationState? = null,
    val pending: DecorationCommand? = null,
    val receipt: DecorationSettlement? = null,
    val error: ContractError? = null,
    val busy: Boolean = false,
    val foreign: Boolean = false,
    val unsupported: Boolean = false,
)
