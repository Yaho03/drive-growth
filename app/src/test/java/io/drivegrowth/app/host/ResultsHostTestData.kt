package io.drivegrowth.app.host

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.GachaRules

internal object ResultsHostTestData {
    val model = VehicleId("model")
    val vehicle = OwnedVehicle(OwnedVehicleId("owned"), model, 2, Revision(4), VehicleLifecycle.OWNED,
        Rarity.RARE, 30, GameMoney(500), VehicleAcquisitionSource.GACHA)
    fun settlement(id: String = "result") = Settlement(SettlementId(id), Transaction(TransactionId("tx-$id"),
        RequestId("request-$id"), TransactionKind.GARAGE, InputSource.SIMULATION, RuleVersion("rule"),
        MoneyChange(MoneyDirection.DEBIT, GameMoney(500))), GameMoney(100), vehicle, EpochMillis(123))
    fun snapshot(vararg results: Settlement) = HostSnapshot(GameContentSnapshot(
        VehicleCatalogSnapshot(ContentVersion("test"), listOf(VehicleDefinition(model, "당시 차량"))),
        RuleVersion("rule"), true, emptyList(), GachaRules()), InputSource.SIMULATION, GameMoney(900),
        listOf(vehicle.copy(level = 9, revision = Revision(12))), results.toList())
}
