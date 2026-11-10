package io.drivegrowth.app.host.shop

import android.content.Context
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.ui.shop.ShopRequest
import io.drivegrowth.core.contract.*

/** 작업마다 같은 factory의 새 연결을 얻는다. 장수 store/별도 DB를 공급하지 않는다. */
class SessionShopExpansionSource(context: Context, private val games: GameSessionFactory) : ShopExpansionHostSource {
    private val context = context.applicationContext
    @Volatile var identity: GameIdentity? = null
        private set
    private fun <T> use(action: (StoredShopExpansionSource) -> ContractResult<T>): ContractResult<T> =
        when (val opened = games.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> {
                val game = opened.value
                identity = game.identity
                var transferred = false
                try {
                    action(StoredShopExpansionSource(context, game.identity.source, game.identity.profileId, {
                        check(!transferred) { "작업별 연결은 한 번만 전달한다" }
                        transferred = true
                        game.store
                    }))
                } finally {
                    // 전달한 연결은 E의 withStore/use가 닫는다. 정의 조기 실패는 여기서 닫는다.
                    if (!transferred) game.close()
                }
            }
        }
    override fun read() = use { it.read() }
    override fun prepare(snapshot: ShopExpansionSnapshot, request: ShopRequest) = use { it.prepare(snapshot, request) }
    override fun pending() = use { it.pending() }
    override fun execute(command: HostCheckpointCommand.Expand) = use { it.execute(command) }
    override fun finish(command: HostCheckpointCommand.Expand) = use { it.finish(command) }
    override fun cancelSaved(command: HostCheckpointCommand.Expand) = use { it.cancelSaved(command) }
}

/** Activity의 실제 허용 전이를 연결한다. controller의 busy/원명령 정책은 그대로 사용한다. */
class ShopExpansionAccess(private val host: ShopExpansionHostController) {
    private var enabled = false
    fun setControlsEnabled(value: Boolean) {
        val gained = value && !enabled
        enabled = value
        host.setControlsEnabled(value)
        if (gained) host.load()
    }
}
