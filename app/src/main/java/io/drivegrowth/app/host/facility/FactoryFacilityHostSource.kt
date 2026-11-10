package io.drivegrowth.app.host.facility

import android.content.Context
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
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
class FactoryFacilityHostSource(context:Context,private val games:GameSessionFactory,
    private val journal:FacilityDurablePort?=null):FacilityHostSource {
    private val definitions=AssetTrialDefinitionProvider(context.applicationContext.assets)
    private fun <T> use(command:FacilityCommand?=null,action:(GameSession)->ContractResult<T>):ContractResult<T> =
        when(val opened=games.open()) {
            is ContractResult.Failure->opened
            is ContractResult.Success->opened.value.use { game ->
                if(command!=null && command.identity!=game.identity)ContractResult.Failure(ContractError.STATE_CONFLICT) else action(game)
            }
        }
    override fun pending()=use { game->journal?.pending(game)?:ContractResult.Success(FacilityPending.Unsupported) }
    override fun read()=use { game->read(game) }
    private fun read(game:GameSession):ContractResult<FacilityFacts> {
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
        return ContractResult.Success(FacilityFacts(game.identity,definition.version,shop.copy(facilities=Collections.unmodifiableSet(HashSet(shop.facilities))),balance,
            Collections.unmodifiableList(ArrayList(owned)),Collections.unmodifiableMap(activities),Collections.unmodifiableSet(HashSet(resting)),definition.facilityOffers))
    }
    override fun save(command:FacilityCommand)=use(command) { game->
        val durable=journal?:return@use ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        // UI의 가격/개체/설정 사본은 권위가 아니다. E가 같은 거래 안에서도 최종 조건을 재검사한다.
        val actual=when(val r=read(game)){is ContractResult.Failure->return@use r;is ContractResult.Success->r.value}
        val valid=when(command) {
            is FacilityCommand.Buy->command.definitionVersion==actual.definitionVersion && actual.offers[command.offer.facility]==command.offer
            is FacilityCommand.Rest->actual.vehicles.any{it.ownedVehicleId==command.vehicle.ownedVehicleId && it.revision==command.vehicle.revision} &&
                (command.vehicle.ownedVehicleId in actual.resting)==command.expected && command.expected!=command.desired
        }
        if(!valid)ContractResult.Failure(ContractError.STATE_CONFLICT) else durable.save(game,command)
    }
    override fun execute(command:FacilityCommand)=use(command){game->journal?.execute(game,command)?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
    override fun finish(command:FacilityCommand)=use(command){game->journal?.finish(game,command)?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
    override fun cancelSaved(command:FacilityCommand)=use(command){game->journal?.cancelSaved(game,command)?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
}
