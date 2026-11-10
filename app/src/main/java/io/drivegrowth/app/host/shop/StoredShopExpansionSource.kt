package io.drivegrowth.app.host.shop

import android.content.Context
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity

/** 같은 활성 profile/source/DB의 새 연결을 factory로 받는다. worker 전용이며 기본 DB/프로필을 추정하지 않는다. */
class StoredShopExpansionSource(
    context: Context, private val source: InputSource, private val profile: ProfileId,
    private val open: () -> SqliteGameChangeStore,
    private val definitions: ShopExpansionDefinitionSource = AssetShopExpansionProvider(context.assets),
) : ShopExpansionHostSource {
    private val context=context.applicationContext
    private fun <T> withStore(action: (SqliteGameChangeStore) -> ContractResult<T>): ContractResult<T> = try {
        open().use { store -> if(store.source!=source) ContractResult.Failure(ContractError.INVALID_INPUT) else action(store) }
    } catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
      catch (_: SQLiteException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }

    private fun readSnapshot(store: SqliteGameChangeStore, definition: ShopExpansionDefinition): ContractResult<ShopExpansionSnapshot> = store.atomic {
        val actual=when(val r=store.localProfileId()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        if(actual!=profile)return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val shop=when(val r=store.rental.shop()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        val count=when(val r=store.rental.completedRentalCount()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        val money=when(val r=store.balance()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        val owned=when(val r=store.listOwnedVehicles()){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
        val names=(AssetGameContent(context.assets).load() as? ContentLoad.Loaded)?.snapshot?.catalog?.vehicles?.associate { it.vehicleId to it.displayName }.orEmpty()
        val vehicles=owned.map { car ->
            val activity=when(val r=store.rental.activity(car.ownedVehicleId)){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
            ShopVehicle(car.ownedVehicleId,names[car.vehicleId] ?: "${car.vehicleId.value} · 차종명 미제공",when(activity){
                VehicleActivity.IDLE->"대기";VehicleActivity.RENTED->"대여 중";VehicleActivity.RETURNING->"반납 이동 중";VehicleActivity.NEEDS_REPAIR->"수리 필요"
            })
        }
        ContractResult.Success(mapShopExpansion(profile,source,definition,shop,count,money,vehicles))
    }
    override fun read(): ContractResult<ShopExpansionSnapshot> = when(val d=definitions.load()) {
        is ContractResult.Failure->d
        is ContractResult.Success->withStore { readSnapshot(it,d.value) }
    }
    override fun prepare(snapshot: ShopExpansionSnapshot, request: ShopRequest): ContractResult<HostCheckpointCommand.Expand> {
        val selected=request.selection
        val expand=selected.option.command as? ShopCommand.Expand ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        if(snapshot.profile!=profile || snapshot.source!=source || selected.revision!=snapshot.screen.revision ||
            selected.option!=snapshot.screen.expansion || selected.option.availability!=ShopAvailability.AVAILABLE)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val definition=when(val d=definitions.load()){is ContractResult.Failure->return d;is ContractResult.Success->d.value}
        return withStore { store -> store.atomic {
            val latest=when(val r=readSnapshot(store,definition)){is ContractResult.Failure->return@atomic r;is ContractResult.Success->r.value}
            if(latest!=snapshot.copy(screen=snapshot.screen.copy(revision=Revision(0))))return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
            val command=HostCheckpointCommand.Expand(request.requestId,source,definition.version,expand.offer)
            when(val saved=store.hostCommands.save(profile,command)) {
                is ContractResult.Failure->saved
                is ContractResult.Success->ContractResult.Success(command)
            }
        } }
    }
    override fun pending(): ContractResult<HostCheckpointCommand.Expand?> = withStore { store ->
        when(val r=store.hostCommands.load(profile)) {
            is ContractResult.Failure->r
            is ContractResult.Success->{
                val checkpoint=r.value
                if(checkpoint==null)ContractResult.Success(null) else
                    (checkpoint.command as? HostCheckpointCommand.Expand)?.let { ContractResult.Success(it) }
                        ?: ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
        }
    }
    // 저장된 원명령은 현재 asset/가격/후보를 먼저 읽지 않는다. 기존 expand가 원래 정산을 우선 재생한다.
    override fun execute(command: HostCheckpointCommand.Expand) = withStore { unwrap(it.hostCommands.execute(profile,command)) }
    override fun finish(command: HostCheckpointCommand.Expand) = withStore { unwrap(it.hostCommands.finish(profile,command)) }
    override fun cancelSaved(command: HostCheckpointCommand.Expand) = withStore { it.hostCommands.cancelUnstarted(profile,command) }
    private fun unwrap(result: ContractResult<HostCheckpointResult>): ContractResult<Settlement> = when(result) {
        is ContractResult.Failure->result
        is ContractResult.Success->(result.value as? HostCheckpointResult.Expand)?.let { ContractResult.Success(it.value) }
            ?: ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
}
