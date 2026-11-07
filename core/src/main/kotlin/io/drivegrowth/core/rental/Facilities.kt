package io.drivegrowth.core.rental

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.GameMoney

data class ShopProgress(val ownedVehicles: Int, val servedCustomers: Int, val shopLevel: Int) {
    init { require(ownedVehicles >= 0 && servedCustomers >= 0 && shopLevel >= 1) }
}

enum class Facility {
    FUEL_STATION, INSURANCE, CAR_WASH;

    fun unlocked(shop: ShopProgress): Boolean = when (this) {
        FUEL_STATION -> shop.ownedVehicles >= 5
        INSURANCE -> shop.servedCustomers >= 100
        CAR_WASH -> shop.shopLevel >= 3
    }
}

/** 구입 확정된 시설만 효과를 제공한다. 금액은 버전별 시설 정의의 값을 전달한다. */
fun rentalFacilities(installed: Set<Facility>, fuelBonusPerRental: GameMoney) = RentalFacilities(
    if (Facility.FUEL_STATION in installed) fuelBonusPerRental else GameMoney(0),
    Facility.INSURANCE in installed, Facility.CAR_WASH in installed,
)

data class FacilityOffer(
    val facility: Facility,
    val ruleVersion: RuleVersion,
    val trial: Boolean,
    val price: GameMoney,
)

fun FacilityOffer.planPurchase(shop: ShopProgress, installed: Set<Facility>, balance: GameMoney):
    ContractResult<FacilityOffer> = when {
    facility in installed || !facility.unlocked(shop) -> ContractResult.Failure(ContractError.STATE_CONFLICT)
    balance.units < price.units -> ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
    else -> ContractResult.Success(this)
}

/** 단계별 금액·공간은 합의된 표나 명시적인 시험 표에서 받는다. */
data class ShopExpansion(
    val ruleVersion: RuleVersion,
    val trial: Boolean,
    val fromLevel: Int,
    val currentCapacity: Int,
    val nextCapacity: Int,
    val requiredCustomers: Int,
    val price: GameMoney,
) {
    init {
        require(fromLevel in 1 until Int.MAX_VALUE && currentCapacity > 0 && nextCapacity > currentCapacity)
        require(requiredCustomers >= 0)
    }

    fun preview(shop: ShopProgress, capacity: Int, balance: GameMoney): ContractResult<ShopExpansion> = when {
        shop.shopLevel != fromLevel || capacity != currentCapacity || shop.servedCustomers < requiredCustomers ->
            ContractResult.Failure(ContractError.STATE_CONFLICT)
        balance.units < price.units -> ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
        else -> ContractResult.Success(this)
    }
}
