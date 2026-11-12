package io.drivegrowth.app.host.facility

import android.content.Context
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.TrialDefinitionSource
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import java.util.Collections

/** 실제 E 공급 어댑터의 포트. GameSession은 같은 factory의 작업별 연결이며 호출 뒤 보관하지 않는다. */
interface FacilityDurablePort {
    fun pending(game:GameSession):ContractResult<FacilityPending>
    fun save(game:GameSession,command:FacilityCommand):ContractResult<Unit>
    fun execute(game:GameSession,command:FacilityCommand):ContractResult<FacilityReceipt>
    fun finish(game:GameSession,command:FacilityCommand):ContractResult<Unit>
    fun cancelSaved(game:GameSession,command:FacilityCommand):ContractResult<Unit>
}

/** 실제 조회만 직접 수행한다. 금융/설정 쓰기는 주입한 기존 한 슬롯 어댑터만 수행한다. */
class FactoryFacilityHostSource(
    context: Context,
    private val games: GameSessionFactory,
    private val journal: FacilityDurablePort? = null,
    private val definitions: TrialDefinitionSource = AssetTrialDefinitionProvider(context.applicationContext.assets),
) : FacilityHostSource {
    private fun <T> use(command:FacilityCommand?=null,action:(GameSession)->ContractResult<T>):ContractResult<T> =
        when(val opened=games.open()) {
            is ContractResult.Failure->opened
            is ContractResult.Success->opened.value.use { game ->
                if(command!=null && command.identity!=game.identity)ContractResult.Failure(ContractError.STATE_CONFLICT) else action(game)
            }
        }
    override fun pending()=use { game->journal?.pending(game)?:ContractResult.Success(FacilityPending.Unsupported) }
    override fun read()=use { game->read(game) }
    internal fun read(game:GameSession):ContractResult<FacilityFacts> {
        val definition=when(val r=definitions.load()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val shop=when(val r=game.store.rental.shop()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val balance=when(val r=game.store.balance()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val vehicles=when(val r=game.store.listOwnedVehicles()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val owned=vehicles.filter{it.lifecycle==VehicleLifecycle.OWNED}
        if(owned.size!=shop.progress.ownedVehicles || owned.map{it.ownedVehicleId}.distinct().size!=owned.size)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val resting=when(val r=game.store.rental.restingVehicles()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val activities=linkedMapOf<OwnedVehicleId,VehicleActivity>()
        for(car in owned)when(val r=game.store.rental.activity(car.ownedVehicleId)) {
            is ContractResult.Failure->return r
            is ContractResult.Success->activities[car.ownedVehicleId]=r.value
        }
        return ContractResult.Success(FacilityFacts(
            game.identity, definition.version, shop.copy(facilities = Collections.unmodifiableSet(HashSet(shop.facilities))),
            balance, Collections.unmodifiableList(ArrayList(owned)), Collections.unmodifiableMap(activities),
            Collections.unmodifiableSet(HashSet(resting)), definition.facilityOffers, definition.fuelBonusPerRental,
        ))
    }
    override fun save(command:FacilityCommand)=use(command) { game->
        val durable=journal?:return@use ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        // 검증된 원결과는 현재 정의/개체/revision/쉬기 상태보다 우선한다. 새 명령 조건도 E가 같은 거래에서 검사한다.
        durable.save(game,command)
    }
    override fun execute(command:FacilityCommand)=use(command){game->journal?.execute(game,command)?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
    override fun finish(command:FacilityCommand)=use(command){game->journal?.finish(game,command)?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
    override fun cancelSaved(command:FacilityCommand)=use(command){game->journal?.cancelSaved(game,command)?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
}
