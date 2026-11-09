package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.GachaPull
import io.drivegrowth.core.garage.Garage

/** worker 전용. 기존 GachaPull과 같은 저장소를 쓰며 자체 잔액/원장을 만들지 않는다. */
class GachaStoreHostSource(
    private val content: GameContentSnapshot,
    private val store: GachaChangeStore,
    private val balance: () -> ContractResult<GameMoney>,
    private val origin: GachaOrigin,
) : GachaHostSource {
    override fun read(paid: RequestId, free: RequestId): ContractResult<GachaOffers> {
        val state = when (val result = store.readGachaState()) { is ContractResult.Failure -> return result; is ContractResult.Success -> result.value }
        val money = when (val result = balance()) { is ContractResult.Failure -> return result; is ContractResult.Success -> result.value }
        val owned = when (val result = store.listOwnedVehicles()) { is ContractResult.Failure -> return result; is ContractResult.Success -> result.value }
        val validated = when (val result = Garage(content.catalog).owned(owned)) { is ContractResult.Failure -> return result; is ContractResult.Success -> result.value }
        return mapGachaOffers(content, state, money, validated.size, paid, free, origin)
    }

    override fun prepare(input: GachaDrawInput): ContractResult<GachaCommand> {
        val offers = input.offers
        val option = input.option
        if (offers.displayProblem() != null || option.displayProblem() != null || option !in offers.options)
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        val paid = offers.options.singleOrNull { it.kind == GachaOptionKind.PAID }?.requestId
        val free = offers.options.singleOrNull { it.kind == GachaOptionKind.FREE }?.requestId
        if (paid == null || free == null) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val latest = when (val result = read(paid, free)) { is ContractResult.Failure -> return result; is ContractResult.Success -> result.value }
        if (latest != offers) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val command = when (val result = GachaPull(content).prepare(store, option.requestId!!,
            if (option.kind == GachaOptionKind.PAID) GachaTicketUse.PURCHASED else GachaTicketUse.FREE)) {
            is ContractResult.Failure -> return result; is ContractResult.Success -> result.value
        }
        if (command.award.vehicle.acquisitionCost != option.moneyCost || command.garageCapacity != offers.space?.capacity ||
            command.drawsSinceGuarantee != content.gacha.pity - offers.guarantee!!.remaining!!)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return ContractResult.Success(command)
    }

    override fun commit(command: GachaCommand): ContractResult<GachaSettlement> = store.commitGacha(command)
}
