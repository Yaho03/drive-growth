package io.drivegrowth.core.rental

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.progression.VehicleCare
import java.math.BigDecimal

/** 미정인 정수 반올림 정책을 적용하기 전의 대여 1회 금액. 시설 보너스는 별도 표시한다. */
data class RentalQuote(val fare: BigDecimal, val facilityBonus: GameMoney, val accident: Boolean,
                       val exactMatchReviewBoost: Boolean, val carWashReviewBoost: Boolean)

data class RentalFacilities(val fuelBonusPerRental: GameMoney, val insurance: Boolean, val carWash: Boolean)

data class SimulatedCustomer(val age: Int, val licensedMonths: Int) {
    init { require(age >= 0 && licensedMonths >= 0) }
    fun accidentPercent(): Int {
        val base = when { licensedMonths < 12 -> 8; licensedMonths < 36 -> 4; else -> 3 }
        return base + if (age < 25) 5 else 0
    }
}

fun AssignmentPlan.Candidate.quote(
    hourlyFare: GameMoney, priorAccidents: Int, customer: SimulatedCustomer,
    accidentRoll: Int, facilities: RentalFacilities,
): RentalQuote {
    require(accidentRoll in 0..99 && priorAccidents >= 0)
    val accident = accidentRoll < customer.accidentPercent()
    val accidentPercent = when { !accident -> 100; facilities.insurance -> 75; else -> 50 }
    val rarityPercent = when (vehicle.rarity) {
        Rarity.COMMON -> 100
        Rarity.ADVANCED -> 110
        Rarity.RARE -> 120
        Rarity.LEGEND -> 130
    }
    val carePercent = VehicleCare().rentPercent(vehicle.level, priorAccidents.coerceAtMost(5))
    val fare = hourlyFare.units.toBigDecimal() * request.hours.toBigDecimal() *
        match.percent.toBigDecimal() * rarityPercent.toBigDecimal() * carePercent.toBigDecimal() *
        accidentPercent.toBigDecimal()
    return RentalQuote(fare.movePointLeft(8), facilities.fuelBonusPerRental, accident,
        match == RentalMatch.EXACT, facilities.carWash)
}
