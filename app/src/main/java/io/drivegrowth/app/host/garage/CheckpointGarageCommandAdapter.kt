package io.drivegrowth.app.host.garage

import android.content.Context
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleAction

/** F의 같은 DB checkpoint를 source/journal 양쪽으로 사용한다. 저장 형식/프로필/표를 만들지 않는다. */
class CheckpointGarageCommandAdapter(context: Context, private val databaseName: String? = null, private val sessions: GameSessionFactory? = null) : GarageCommandSource, GarageCommandJournal {
    private val context = context.applicationContext
    private val plans = StoredGarageCommandSource(this.context, databaseName, sessions)
    private fun content() = when(val loaded = AssetGameContent(context.assets).load()) {
        is ContentLoad.Loaded -> ContractResult.Success(loaded.snapshot)
        else -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    }
    private fun open(): SqliteGameChangeStore {
        val versions = { content().convert { MaterialVersions(it.catalog.contentVersion,it.ruleVersion) } }
        return if(databaseName == null) SqliteGameChangeStore(context,InputSource.SIMULATION,materialVersions=versions,repairContent=::content)
        else SqliteGameChangeStore(context,InputSource.SIMULATION,databaseName,materialVersions=versions,repairContent=::content)
    }
    private fun <T> withLedger(action:(HostCommandCheckpointLedger,ProfileId)->ContractResult<T>):ContractResult<T> = try {
        val factory = sessions
        if (factory != null) {
            when (val opened = factory.open()) {
                is ContractResult.Failure -> opened
                is ContractResult.Success -> opened.value.use { game -> action(game.store.hostCommands, game.identity.profileId) }
            }
        } else open().use { store -> when(val profile=store.localProfileId()) {
            is ContractResult.Failure -> profile
            is ContractResult.Success -> action(store.hostCommands,profile.value)
        } }
    } catch (_:SecurityException) {ContractResult.Failure(ContractError.PERMISSION_DENIED)}
      catch (_:RuntimeException) {ContractResult.Failure(ContractError.STORAGE_FAILURE)}

    override fun prepare(request:GarageEvent.RequestAction,id:RequestId,material:io.drivegrowth.core.garage.VehicleRef?) = plans.prepare(request,id,material)
    override fun load():ContractResult<GaragePending?> = withLedger { ledger,profile ->
        when(val saved=ledger.load(profile)) {
            is ContractResult.Failure -> saved
            is ContractResult.Success -> if(saved.value == null) ContractResult.Success(null) else restoreGaragePending(saved.value!!.command)
        }
    }
    override fun save(pending:GaragePending):ContractResult<Unit> = withLedger { ledger,profile -> ledger.save(profile,pending.checkpoint()).convert { Unit } }
    override fun commit(command:GarageCommand):ContractResult<GarageReceipt> = withLedger { ledger,profile ->
        val saved=when(val read=ledger.load(profile)) {is ContractResult.Failure -> return@withLedger read;is ContractResult.Success -> read.value}
            ?: return@withLedger ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val original=when(val read=restoreGaragePending(saved.command)) {is ContractResult.Failure -> return@withLedger read;is ContractResult.Success -> read.value}
        if(original.command != command) return@withLedger ContractResult.Failure(ContractError.STATE_CONFLICT)
        // F execute가 STARTED와 원장 확정을 같은 거래로 처리한다. 직접 store.commit으로 우회하지 않는다.
        ledger.execute(profile,saved.command).receipt()
    }
    override fun clear(pending:GaragePending):ContractResult<Unit> = withLedger { ledger,profile -> ledger.finish(profile,pending.checkpoint()).convert { Unit } }
    override fun cancelUnstarted(pending:GaragePending):ContractResult<Unit> = withLedger { ledger,profile -> ledger.cancelUnstarted(profile,pending.checkpoint()) }
}

internal fun GaragePending.checkpoint():HostCheckpointCommand = when(val c=command) {
    is GarageCommand.Single -> HostCheckpointCommand.Garage(contentVersion,c.value)
    is GarageCommand.Material -> HostCheckpointCommand.Material(c.value)
    is GarageCommand.Repair -> HostCheckpointCommand.Repair(c.value)
}
/** 원래 core 명령에서만 복구한다. 현재 차량/가격/잔액으로 결과나 예전 확인값을 합성하지 않는다. */
internal fun restoreGaragePending(command:HostCheckpointCommand):ContractResult<GaragePending> {
    val request:GarageEvent.RequestAction
    val content:ContentVersion
    val original:GarageCommand
    when(command) {
        is HostCheckpointCommand.Decoration, is HostCheckpointCommand.FacilityPurchase, is HostCheckpointCommand.VehicleResting, is HostCheckpointCommand.Gacha, is HostCheckpointCommand.Expand, is HostCheckpointCommand.Delivery, is HostCheckpointCommand.Quest, is HostCheckpointCommand.Achievement -> return ContractResult.Failure(ContractError.STATE_CONFLICT)
        is HostCheckpointCommand.Garage -> {
            val write=command.value.vehicle as? VehicleWrite.Update ?: return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            val action=when(write.lifecycle) {
                VehicleLifecycle.OWNED -> VehicleAction.LEVEL_UP
                VehicleLifecycle.SOLD -> VehicleAction.SELL
                VehicleLifecycle.SCRAPPED -> VehicleAction.SCRAP
                VehicleLifecycle.CONSUMED -> return ContractResult.Failure(ContractError.STORAGE_FAILURE)
            }
            request=GarageEvent.RequestAction(write.ownedVehicleId,write.expectedRevision,action)
            content=command.contentVersion;original=GarageCommand.Single(command.value)
        }
        is HostCheckpointCommand.Material -> {request=GarageEvent.RequestAction(command.value.target.ownedVehicleId,command.value.target.expectedRevision,VehicleAction.USE_MATERIAL);content=command.value.contentVersion;original=GarageCommand.Material(command.value)}
        is HostCheckpointCommand.Repair -> {request=GarageEvent.RequestAction(command.value.target.ownedVehicleId,command.value.target.revision,VehicleAction.REPAIR);content=command.value.contentVersion;original=GarageCommand.Repair(command.value)}
    }
    return ContractResult.Success(GaragePending(request,content,original))
}
private fun ContractResult<HostCheckpointResult>.receipt():ContractResult<GarageReceipt> = when(this) {
    is ContractResult.Failure -> this
    is ContractResult.Success -> when(val r=value) {
        is HostCheckpointResult.Garage -> ContractResult.Success(GarageReceipt.Single(r.value))
        is HostCheckpointResult.Material -> ContractResult.Success(GarageReceipt.Material(r.value))
        is HostCheckpointResult.Repair -> ContractResult.Success(GarageReceipt.Repair(r.value))
        is HostCheckpointResult.Decoration, is HostCheckpointResult.FacilityPurchase, is HostCheckpointResult.VehicleResting, is HostCheckpointResult.Gacha, is HostCheckpointResult.Expand, is HostCheckpointResult.Delivery, is HostCheckpointResult.Quest, is HostCheckpointResult.Achievement -> ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
}
private fun <T,R> ContractResult<T>.convert(f:(T)->R):ContractResult<R> = when(this) {is ContractResult.Failure -> this;is ContractResult.Success -> ContractResult.Success(f(value))}
