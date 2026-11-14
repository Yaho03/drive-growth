package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import java.util.Collections

internal fun mapShopExpansion(profile: ProfileId, source: InputSource, definition: ShopExpansionDefinition,
    shop: ShopState, completed: Int, balance: GameMoney, vehicles: List<ShopVehicle>): ShopExpansionSnapshot {
    val offer=definition.offers.singleOrNull { it.fromLevel==shop.progress.shopLevel }
    val expansion=if(offer==null) ShopOption("expand", "추가 확장 정보 없음",
        "현재 레벨 ${shop.progress.shopLevel} · 공간 ${shop.capacity}칸", "다음 단계의 확장 조건을 확인할 수 없어요.",ShopAvailability.UNKNOWN)
    else {
        val capacityMatches=shop.capacity==offer.currentCapacity
        val available=offer.preview(shop.progress.copy(servedCustomers=completed),shop.capacity,balance) is ContractResult.Success
        ShopOption("expand","레벨 ${offer.fromLevel} → ${offer.fromLevel+1} · 공간 ${offer.currentCapacity} → ${offer.nextCapacity}",
            "완료 대여 ${completed}/${offer.requiredCustomers}건 · 현재 공간 ${shop.capacity}칸"+
                (if(!capacityMatches) " · 현재 공간과 확장 조건이 맞지 않아요" else if(balance.units<offer.price.units) " · 잔액 부족" else ""),
            "확정 후 공간 ${offer.nextCapacity}칸",
            if(!capacityMatches)ShopAvailability.UNKNOWN else if(available)ShopAvailability.AVAILABLE else ShopAvailability.LOCKED,
            ShopCommand.Expand(offer),offer.price,offer.ruleVersion,offer.trial)
    }
    val facilities=Facility.entries.map { facility ->
        val installed=facility in shop.facilities
        ShopFacility(facility,ShopOption("facility-${facility.name}",when(facility) {
            Facility.FUEL_STATION -> "주유소";Facility.INSURANCE -> "보험 시설";Facility.CAR_WASH -> "세차장"
        },"이 화면에서는 시설을 살 수 없어요.",if(installed)"설치 완료 · 이 화면에서는 구매할 수 없어요." else "시설을 구매할 수 없어요.",
            if(installed)ShopAvailability.INSTALLED else ShopAvailability.UNKNOWN))
    }
    val screen=ShopScreenState.Content(Revision(0),"내 가게 · 저장 상태",shop.progress.shopLevel,shop.progress.ownedVehicles,
        shop.capacity,balance,ShopAppearance("기본 간판",0xff8fbcff.toInt()),Collections.unmodifiableList(ArrayList(vehicles)),
        Collections.unmodifiableList(facilities),expansion,listOf(ShopOption("decoration-unsupported","꾸미기",
            "이 화면에서는 꾸미기 품목을 볼 수 없어요.","꾸미기를 이용할 수 없어요.",ShopAvailability.UNKNOWN)),ShopOrigin.STORED)
    return ShopExpansionSnapshot(profile,source,definition.version,completed,screen)
}
