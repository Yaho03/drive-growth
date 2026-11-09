package io.drivegrowth.app.host.gacha

import android.content.Context
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*

/**
 * 실제 assets→GachaPull→기존 SQLite 진입점. worker에서 호출한다.
 * 기본 저장소에 권위/진행이 없으면 DATA_UNAVAILABLE다. 초기 재화/무료권/권위값을 주입하지 않는다.
 * factory는 이미 구성된 같은 프로필/출처 저장소를 열어야 하며 호출마다 닫는다.
 */
class StoredGachaHostSource(
    context: Context,
    source: InputSource,
    open: (() -> SqliteGameChangeStore)? = null,
) : GachaHostSource {
    private val context = context.applicationContext
    private val open = open ?: { SqliteGameChangeStore(this.context, source) }
    private fun <T> withSource(action: (GachaStoreHostSource) -> ContractResult<T>): ContractResult<T> = try {
        val loaded = AssetGameContent(context.assets).load()
        if (loaded !is ContentLoad.Loaded) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        else open().use { action(GachaStoreHostSource(loaded.snapshot, it, it::balance, GachaOrigin.STORED)) }
    } catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
      catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }

    override fun read(paid: RequestId, free: RequestId) = withSource { it.read(paid, free) }
    override fun prepare(input: GachaDrawInput) = withSource { it.prepare(input) }
    // 재제출에 콘텐츠/현재 권위 조회를 먼저 요구하지 않는다. 저장소가 과거 명령 재생을 우선 처리한다.
    override fun commit(command: GachaCommand): ContractResult<GachaSettlement> = try {
        open().use { it.commitGacha(command) }
    } catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
      catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
}
