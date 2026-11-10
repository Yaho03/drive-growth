package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate

data class DeliveryHostData(val identity: GameIdentity, val content: GameContentSnapshot,
    val balance: GameMoney, val shopLevel: Int, val freeSlots: Int, val terms: DeliveryTerms?,
    val progress: DeliveryInProgress?, val held: List<HeldVehicle>)
/** 같은 실제 원장의 조회·목록·발견 경계. 원자 일괄 조회를 약정하지 않는다. */
interface DeliveryHostQueries {
    fun identity(): ContractResult<GameIdentity>
    fun read(identity: GameIdentity): ContractResult<DeliveryHostData>
    fun lists(identity: GameIdentity, day: LocalDate): ContractResult<List<DeliveryList>>
    fun saveList(identity: GameIdentity, list: DeliveryList): ContractResult<DeliveryList>
    fun discoverChange(identity: GameIdentity, change: DeliveryChangeOffer): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun discover(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit>
}
