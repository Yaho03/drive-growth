package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion

/** 지원하는 전체 원명령을 보존한다. Garage의 콘텐츠 버전도 호출자가 명시한다. */
sealed interface HostCheckpointCommand {
    data class Gacha(val value: GachaCommand) : HostCheckpointCommand
    data class Garage(val contentVersion: ContentVersion, val value: GameChange) : HostCheckpointCommand
    data class Material(val value: MaterialCommand) : HostCheckpointCommand
    data class Repair(val value: RepairCommand) : HostCheckpointCommand
    data class Expand(val requestId: RequestId, val source: InputSource,
        val definitionVersion: ContentVersion, val offer: ShopExpansion) : HostCheckpointCommand
}

enum class HostCheckpointPhase { SAVED, STARTED }
data class HostCommandCheckpoint(val profileId: ProfileId, val command: HostCheckpointCommand, val phase: HostCheckpointPhase)

/** 실제 원장의 기존 결과다. checkpoint 자체는 금액/개체 결과를 생성하지 않는다. */
sealed interface HostCheckpointResult {
    data class Gacha(val value: GachaSettlement) : HostCheckpointResult
    data class Garage(val value: Settlement) : HostCheckpointResult
    data class Material(val value: MaterialSettlement) : HostCheckpointResult
    data class Repair(val value: RepairSettlement) : HostCheckpointResult
    data class Expand(val value: Settlement) : HostCheckpointResult
}

internal fun HostCheckpointCommand.validFor(source: InputSource): Boolean {
    if (origin != source || requestId.value.startsWith("delivery/") || requestId.value.startsWith("drive/")) return false
    return when (this) {
        is HostCheckpointCommand.Garage -> value.kind == TransactionKind.GARAGE &&
            (value.vehicle as? VehicleWrite.Update)?.lifecycle in setOf(VehicleLifecycle.OWNED, VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED)
        is HostCheckpointCommand.Gacha -> value.shopLevel > 0 && value.garageCapacity >= 0 &&
            value.drawsSinceGuarantee >= 0 && value.award.nextCounter >= 0
        is HostCheckpointCommand.Material -> true
        is HostCheckpointCommand.Expand -> true
        is HostCheckpointCommand.Repair -> value.expectedAccidentCount >= 0
    }
}
internal val HostCheckpointCommand.origin: InputSource get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.source
    is HostCheckpointCommand.Garage -> value.source
    is HostCheckpointCommand.Material -> value.source
    is HostCheckpointCommand.Repair -> value.source
    is HostCheckpointCommand.Expand -> source
}
internal val HostCheckpointCommand.content: ContentVersion get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.contentVersion
    is HostCheckpointCommand.Garage -> contentVersion
    is HostCheckpointCommand.Material -> value.contentVersion
    is HostCheckpointCommand.Repair -> value.contentVersion
    is HostCheckpointCommand.Expand -> definitionVersion
}
internal val HostCheckpointCommand.rule: RuleVersion get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.ruleVersion
    is HostCheckpointCommand.Garage -> value.ruleVersion
    is HostCheckpointCommand.Material -> value.ruleVersion
    is HostCheckpointCommand.Repair -> value.ruleVersion
    is HostCheckpointCommand.Expand -> offer.ruleVersion
}
internal val HostCheckpointCommand.requestId: RequestId get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.requestId
    is HostCheckpointCommand.Garage -> value.requestId
    is HostCheckpointCommand.Material -> value.requestId
    is HostCheckpointCommand.Repair -> value.requestId
    is HostCheckpointCommand.Expand -> requestId
}
