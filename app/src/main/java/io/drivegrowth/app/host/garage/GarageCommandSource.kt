package io.drivegrowth.app.host.garage

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef

/** 호스트의 기존 명령 분기일 뿐 새 원장/직렬화 형식이 아니다. 공용 checkpoint adapter가 원래 core 명령을 보존한다. */
sealed interface GarageCommand {
    data class Single(val value: GameChange) : GarageCommand
    data class Material(val value: MaterialCommand) : GarageCommand
    data class Repair(val value: RepairCommand) : GarageCommand
    val requestId: RequestId get() = when(this) { is Single -> value.requestId; is Material -> value.requestId; is Repair -> value.requestId }
}

data class GaragePending(val request: GarageEvent.RequestAction, val contentVersion: ContentVersion, val command: GarageCommand,
    val balance: GameMoney? = null, val vehicle: OwnedVehicle? = null, val name: String? = null,
    val materialName: String? = null)
sealed interface GaragePreparation {
    data class Ready(val pending: GaragePending) : GaragePreparation
    data class Materials(val request: GarageEvent.RequestAction, val candidates: List<OwnedVehicle>,
        val candidateNames: Map<OwnedVehicleId, String>) : GaragePreparation
}
sealed interface GarageReceipt {
    data class Single(val value: Settlement) : GarageReceipt
    data class Material(val value: MaterialSettlement) : GarageReceipt
    data class Repair(val value: RepairSettlement) : GarageReceipt
}
interface GarageCommandSource {
    fun prepare(request: GarageEvent.RequestAction, id: RequestId, material: VehicleRef? = null): ContractResult<GaragePreparation>
    fun commit(command: GarageCommand): ContractResult<GarageReceipt>
}

/** worker 전용. 같은 DB/프로필/출처에 결합한 F 공용 checkpoint의 adapter 경계. 메모리 구현을 제품에 주입하지 않는다. */
interface GarageCommandJournal {
    fun load(): ContractResult<GaragePending?>
    fun save(pending: GaragePending): ContractResult<Unit>
    fun clear(pending: GaragePending): ContractResult<Unit>
    fun cancelUnstarted(pending: GaragePending): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
}
