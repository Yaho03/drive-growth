package io.drivegrowth.app.host.garage

import android.content.Context
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.MaterialVersions
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.CompletedRentalSource
import java.util.Collections

interface GarageCommandLedger : MaterialChangeStore, RepairChangeStore, CompletedRentalSource {
    val source: InputSource
    fun balance(): ContractResult<GameMoney>
    fun activity(id: OwnedVehicleId): ContractResult<VehicleActivity>
}

/** core 계획을 그대로 사용한다. 사전 조회는 최종 저장 검사나 원자 snapshot이 아니다. */
class GarageStoreCommandSource(private val content: GameContentSnapshot, private val ledger: GarageCommandLedger) : GarageCommandSource {
    override fun prepare(request: GarageEvent.RequestAction, id: RequestId, material: VehicleRef?): ContractResult<GaragePreparation> {
        val owned = when (val read = ledger.listOwnedVehicles()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val target = when (val read = Garage(content.catalog).find(owned, request.id)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (target.revision != request.revision) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val balance = when (val read = ledger.balance()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val ref = VehicleRef(request.id, request.revision)
        val upgrade = VehicleUpgrade(content)
        val planned: ContractResult<GarageCommand> = when (request.action) {
            VehicleAction.LEVEL_UP -> upgrade.planLevelUp(owned, id, ledger.source, ref, ledger::activity).map { GarageCommand.Single(it) }
            VehicleAction.SELL -> VehicleDisposal(content.catalog, content.ruleVersion).planSale(owned, id, ledger.source, ref, ledger, ledger::activity).map { GarageCommand.Single(it) }
            VehicleAction.SCRAP -> VehicleDisposal(content.catalog, content.ruleVersion).planScrap(owned, id, ledger.source, ref, ledger::activity).map { GarageCommand.Single(it) }
            VehicleAction.REPAIR -> VehicleRepair(content).prepare(ledger, id, ledger.source, ref).map { GarageCommand.Repair(it) }
            VehicleAction.USE_MATERIAL -> {
                if (material == null) {
                    val activity = when (val read = ledger.activity(target.ownedVehicleId)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
                    if (activity != VehicleActivity.IDLE) return ContractResult.Failure(ContractError.STATE_CONFLICT)
                    if (target.level >= MAX_VEHICLE_LEVEL) return ContractResult.Failure(ContractError.INVALID_INPUT)
                    val candidates = ArrayList<OwnedVehicle>()
                    for (car in owned.filter { it.ownedVehicleId != target.ownedVehicleId && it.vehicleId == target.vehicleId && it.lifecycle == VehicleLifecycle.OWNED }) {
                        val current = when (val read = ledger.activity(car.ownedVehicleId)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
                        if (current == VehicleActivity.IDLE) candidates.add(car)
                    }
                    return ContractResult.Success(GaragePreparation.Materials(request, Collections.unmodifiableList(candidates)))
                }
                upgrade.prepareMaterial(ledger, id, ledger.source, ref, material).map { GarageCommand.Material(it) }
            }
        }
        return planned.map { command -> GaragePreparation.Ready(GaragePending(request, content.catalog.contentVersion, command,
            balance, target, content.catalog.vehicles.single { it.vehicleId == target.vehicleId }.displayName)) }
    }

    override fun commit(command: GarageCommand): ContractResult<GarageReceipt> = when(command) {
        is GarageCommand.Single -> ledger.commitGameChange(command.value).map { GarageReceipt.Single(it) }
        is GarageCommand.Material -> ledger.commitMaterial(command.value).map { GarageReceipt.Material(it) }
        is GarageCommand.Repair -> ledger.commitRepair(command.value).map { GarageReceipt.Repair(it) }
    }
}

/** 실제 assets와 기존 같은 원장. 모든 호출은 host worker에서 수행한다. */
class StoredGarageCommandSource(context: Context, private val databaseName: String? = null) : GarageCommandSource {
    private val context = context.applicationContext
    private fun content(): ContractResult<GameContentSnapshot> = when (val load = AssetGameContent(context.assets).load()) {
        is ContentLoad.Loaded -> ContractResult.Success(load.snapshot)
        else -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    }
    private fun open(): SqliteGameChangeStore {
        val versions = { content().map { MaterialVersions(it.catalog.contentVersion, it.ruleVersion) } }
        return if (databaseName == null) SqliteGameChangeStore(context, InputSource.SIMULATION, materialVersions = versions, repairContent = ::content)
        else SqliteGameChangeStore(context, InputSource.SIMULATION, databaseName, materialVersions = versions, repairContent = ::content)
    }
    override fun prepare(request: GarageEvent.RequestAction, id: RequestId, material: VehicleRef?): ContractResult<GaragePreparation> = guarded {
        when(val loaded = content()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> open().use { GarageStoreCommandSource(loaded.value, SqliteGarageLedger(it)).prepare(request, id, material) }
        }
    }
    // 재생 전에 현재 콘텐츠 성공을 요구하지 않는다. 필요한 권위 callback은 저장소가 신규 확정 때만 읽는다.
    override fun commit(command: GarageCommand): ContractResult<GarageReceipt> = guarded {
        open().use { store -> when(command) {
            is GarageCommand.Single -> store.commitGameChange(command.value).map { GarageReceipt.Single(it) }
            is GarageCommand.Material -> store.commitMaterial(command.value).map { GarageReceipt.Material(it) }
            is GarageCommand.Repair -> store.commitRepair(command.value).map { GarageReceipt.Repair(it) }
        } }
    }
}
private class SqliteGarageLedger(private val store: SqliteGameChangeStore) : GarageCommandLedger,
    MaterialChangeStore by store, RepairChangeStore by store, CompletedRentalSource by store.rental {
    override val source get() = store.source
    override fun commitGameChange(change: GameChange) = store.commitGameChange(change)
    override fun listOwnedVehicles() = store.listOwnedVehicles()
    override fun balance() = store.balance()
    override fun activity(id: OwnedVehicleId) = store.rental.activity(id)
}
private fun <T,R> ContractResult<T>.map(f: (T) -> R): ContractResult<R> = when(this) { is ContractResult.Failure -> this; is ContractResult.Success -> ContractResult.Success(f(value)) }
private fun <T> guarded(action: () -> ContractResult<T>): ContractResult<T> = try { action() }
    catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
    catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
