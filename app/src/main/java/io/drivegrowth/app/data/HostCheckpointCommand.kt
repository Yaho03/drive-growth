package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion
import io.drivegrowth.core.rental.FacilityOffer
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.quest.*

/** 지원하는 전체 원명령을 보존한다. Garage의 콘텐츠 버전도 호출자가 명시한다. */
sealed interface HostCheckpointCommand {
    data class Gacha(val value: GachaCommand) : HostCheckpointCommand
    data class Garage(val contentVersion: ContentVersion, val value: GameChange) : HostCheckpointCommand
    data class Material(val value: MaterialCommand) : HostCheckpointCommand
    data class Repair(val value: RepairCommand) : HostCheckpointCommand
    data class Quest(val value:QuestClaimCommand):HostCheckpointCommand
    data class Delivery(val contentVersion:ContentVersion,val source:InputSource,val operation:DeliveryCheckpointOperation):HostCheckpointCommand
    data class Expand(val requestId: RequestId, val source: InputSource,
        val definitionVersion: ContentVersion, val offer: ShopExpansion) : HostCheckpointCommand
    data class FacilityPurchase(val requestId:RequestId,val source:InputSource,
        val definitionVersion:ContentVersion,val offer:FacilityOffer):HostCheckpointCommand
    data class VehicleResting(val requestId:RequestId,val source:InputSource,val vehicle:VehicleRef,
        val expected:Boolean,val desired:Boolean):HostCheckpointCommand
}

/** 설정 원결과이며 재화 이동/금융 Settlement가 아니다. */
data class VehicleRestingReceipt(val profileId:ProfileId,val command:HostCheckpointCommand.VehicleResting,
    val before:Boolean,val after:Boolean)

enum class HostCheckpointPhase { SAVED, STARTED }
data class HostCommandCheckpoint(val profileId: ProfileId, val command: HostCheckpointCommand, val phase: HostCheckpointPhase)

/** 실제 원장의 기존 결과다. checkpoint 자체는 금액/개체 결과를 생성하지 않는다. */
sealed interface HostCheckpointResult {
    data class Gacha(val value: GachaSettlement) : HostCheckpointResult
    data class Garage(val value: Settlement) : HostCheckpointResult
    data class Material(val value: MaterialSettlement) : HostCheckpointResult
    data class Repair(val value: RepairSettlement) : HostCheckpointResult
    data class Quest(val value:QuestClaimSettlement):HostCheckpointResult
    data class Delivery(val value:DeliveryCheckpointReceipt):HostCheckpointResult
    data class Expand(val value: Settlement) : HostCheckpointResult
    data class FacilityPurchase(val value:Settlement):HostCheckpointResult
    data class VehicleResting(val value:VehicleRestingReceipt):HostCheckpointResult
}

internal fun HostCheckpointCommand.validFor(source: InputSource): Boolean {
    if (origin != source || requestId.value.startsWith("delivery/") || requestId.value.startsWith("drive/") || (requestId.value.startsWith("quest/") && this !is HostCheckpointCommand.Quest)) return false
    return when (this) {
        is HostCheckpointCommand.Garage -> value.kind == TransactionKind.GARAGE &&
            (value.vehicle as? VehicleWrite.Update)?.lifecycle in setOf(VehicleLifecycle.OWNED, VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED)
        is HostCheckpointCommand.Gacha -> value.shopLevel > 0 && value.garageCapacity >= 0 &&
            value.drawsSinceGuarantee >= 0 && value.award.nextCounter >= 0
        is HostCheckpointCommand.Delivery -> when(val op=operation) {
            is DeliveryCheckpointOperation.Select -> op.at.value>=0 && op.proposed.list.source==source && op.proposed.estimated.source==source && op.proposed.list.contentVersion==contentVersion
            is DeliveryCheckpointOperation.Change -> op.offer.proposed.list.source==source && op.offer.proposed.estimated.source==source && op.offer.proposed.list.contentVersion==contentVersion
            is DeliveryCheckpointOperation.Arrival -> op.now.value>=0 && op.expected.source==source && op.replacement.source==source && op.expected.contentVersion==contentVersion && op.replacement.contentVersion==contentVersion
            is DeliveryCheckpointOperation.Hold -> op.now.value>=0
            is DeliveryCheckpointOperation.Abandon -> true
        }
        is HostCheckpointCommand.Quest -> value.source==source
        is HostCheckpointCommand.Material -> true
        is HostCheckpointCommand.Expand -> true
        is HostCheckpointCommand.FacilityPurchase -> true
        is HostCheckpointCommand.VehicleResting -> expected!=desired
        is HostCheckpointCommand.Repair -> value.expectedAccidentCount >= 0
    }
}
internal val HostCheckpointCommand.origin: InputSource get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.source
    is HostCheckpointCommand.Garage -> value.source
    is HostCheckpointCommand.Material -> value.source
    is HostCheckpointCommand.Repair -> value.source
    is HostCheckpointCommand.Quest -> value.source
    is HostCheckpointCommand.Delivery -> source
    is HostCheckpointCommand.Expand -> source
    is HostCheckpointCommand.FacilityPurchase -> source
    is HostCheckpointCommand.VehicleResting -> source
}
internal val HostCheckpointCommand.content: ContentVersion get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.contentVersion
    is HostCheckpointCommand.Garage -> contentVersion
    is HostCheckpointCommand.Material -> value.contentVersion
    is HostCheckpointCommand.Repair -> value.contentVersion
    // Quest는 차량 콘텐츠를 소비하지 않으므로 envelope에는 실제 Quest 정의 버전을 교차 대조한다.
    is HostCheckpointCommand.Quest -> ContentVersion(value.definition.version.value)
    is HostCheckpointCommand.Delivery -> contentVersion
    is HostCheckpointCommand.Expand -> definitionVersion
    is HostCheckpointCommand.FacilityPurchase -> definitionVersion
    is HostCheckpointCommand.VehicleResting -> ContentVersion("vehicle-resting-v1") // 비금융 설정 codec 버전
}
internal val HostCheckpointCommand.rule: RuleVersion get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.ruleVersion
    is HostCheckpointCommand.Garage -> value.ruleVersion
    is HostCheckpointCommand.Material -> value.ruleVersion
    is HostCheckpointCommand.Repair -> value.ruleVersion
    is HostCheckpointCommand.Quest -> value.definition.version
    is HostCheckpointCommand.Delivery -> when(val op=operation) {
        is DeliveryCheckpointOperation.Select->op.proposed.list.ruleVersion
        is DeliveryCheckpointOperation.Change->op.rules.ruleVersion
        is DeliveryCheckpointOperation.Abandon->op.rules.ruleVersion
        is DeliveryCheckpointOperation.Arrival->op.rules.ruleVersion
        is DeliveryCheckpointOperation.Hold->op.rules.ruleVersion
    }
    is HostCheckpointCommand.Expand -> offer.ruleVersion
    is HostCheckpointCommand.FacilityPurchase -> offer.ruleVersion
    is HostCheckpointCommand.VehicleResting -> RuleVersion("vehicle-resting-v1")
}
internal val HostCheckpointCommand.requestId: RequestId get() = when(this) {
    is HostCheckpointCommand.Gacha -> value.requestId
    is HostCheckpointCommand.Garage -> value.requestId
    is HostCheckpointCommand.Material -> value.requestId
    is HostCheckpointCommand.Repair -> value.requestId
    is HostCheckpointCommand.Quest -> value.requestId
    is HostCheckpointCommand.Delivery -> operation.requestId
    is HostCheckpointCommand.Expand -> requestId
    is HostCheckpointCommand.FacilityPurchase -> requestId
    is HostCheckpointCommand.VehicleResting -> requestId
}
