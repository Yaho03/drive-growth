package io.drivegrowth.app.host.facility

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.core.contract.*

/** 같은 factory session의 E 한 슬롯만 소비한다. 금융/쉬기 쓰기와 원결과 권위는 E에 둔다. */
class CheckpointFacilityDurablePort:FacilityDurablePort {
    override fun pending(game:GameSession):ContractResult<FacilityPending> = when(val r=game.store.hostCommands.load(game.identity.profileId)) {
        is ContractResult.Failure->r
        is ContractResult.Success->ContractResult.Success(pending(game.identity,r.value))
    }
    override fun save(game:GameSession,command:FacilityCommand):ContractResult<Unit> =
        when(val r=game.store.hostCommands.save(game.identity.profileId,encode(command))) {
            is ContractResult.Failure->r
            is ContractResult.Success->if(r.value.profileId==command.identity.profileId && r.value.command==encode(command)) ContractResult.Success(Unit) else conflict()
        }
    override fun execute(game:GameSession,command:FacilityCommand):ContractResult<FacilityReceipt> =
        when(val r=game.store.hostCommands.execute(game.identity.profileId,encode(command))) {
            is ContractResult.Failure->r
            is ContractResult.Success->receipt(command,r.value)
        }
    override fun executeForInstallation(game: GameSession, command: FacilityCommand.Buy): ContractResult<FacilityPurchaseExecution> {
        val encoded = HostCheckpointCommand.FacilityPurchase(command.requestId, command.identity.source,
            command.definitionVersion, command.offer)
        return when (val applied = game.store.hostCommands.executeFacilityPurchase(game.identity.profileId, encoded)) {
            is ContractResult.Failure -> applied
            is ContractResult.Success -> when (val checked = receipt(command, applied.value.result)) {
                is ContractResult.Failure -> checked
                is ContractResult.Success -> ContractResult.Success(FacilityPurchaseExecution(checked.value, applied.value.newlyInstalled))
            }
        }
    }

    override fun finish(game:GameSession,command:FacilityCommand):ContractResult<Unit> =
        when(val r=game.store.hostCommands.finish(game.identity.profileId,encode(command))) {
            is ContractResult.Failure->r
            is ContractResult.Success->when(val checked=receipt(command,r.value)) {
                is ContractResult.Failure->checked
                is ContractResult.Success->ContractResult.Success(Unit)
            }
        }
    override fun cancelSaved(game:GameSession,command:FacilityCommand)=game.store.hostCommands.cancelUnstarted(game.identity.profileId,encode(command))

    internal fun encode(command:FacilityCommand):HostCheckpointCommand = when(command) {
        is FacilityCommand.Buy->HostCheckpointCommand.FacilityPurchase(command.requestId,command.identity.source,command.definitionVersion,command.offer)
        is FacilityCommand.Rest->HostCheckpointCommand.VehicleResting(command.requestId,command.identity.source,command.vehicle,command.expected,command.desired)
    }
    internal fun pending(identity:GameIdentity,value:HostCommandCheckpoint?):FacilityPending {
        if(value==null)return FacilityPending.Empty
        if(value.profileId!=identity.profileId)return FacilityPending.Foreign
        val command=when(val c=value.command) {
            is HostCheckpointCommand.FacilityPurchase->if(c.source==identity.source) FacilityCommand.Buy(identity,c.requestId,c.definitionVersion,c.offer) else null
            is HostCheckpointCommand.VehicleResting->if(c.source==identity.source) FacilityCommand.Rest(identity,c.requestId,c.vehicle,c.expected,c.desired) else null
            else->null
        }
        return command?.let{FacilityPending.Own(it)}?:FacilityPending.Foreign
    }
    internal fun receipt(command:FacilityCommand,result:HostCheckpointResult):ContractResult<FacilityReceipt> = when {
        command is FacilityCommand.Buy && result is HostCheckpointResult.FacilityPurchase->{
            val s=result.value;val t=s.transaction
            if(t.requestId!=command.requestId || t.source!=command.identity.source || t.kind!=TransactionKind.FACILITY ||
                t.ruleVersion!=command.offer.ruleVersion || t.money!=MoneyChange(MoneyDirection.DEBIT,command.offer.price) || s.changedVehicle!=null) conflict()
            else ContractResult.Success(FacilityReceipt.Purchased(command,s))
        }
        command is FacilityCommand.Rest && result is HostCheckpointResult.VehicleResting->{
            val r=result.value
            if(r.profileId!=command.identity.profileId || r.command!=encode(command) || r.before!=command.expected || r.after!=command.desired) conflict()
            else ContractResult.Success(FacilityReceipt.Rested(command,r.before,r.after))
        }
        else->conflict()
    }
    private fun conflict()=ContractResult.Failure(ContractError.STATE_CONFLICT)
}
