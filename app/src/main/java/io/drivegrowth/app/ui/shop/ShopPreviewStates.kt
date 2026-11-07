package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*

/** 고정 표시 입력. 잠금·비용·효과는 시험 공급값이며 UI 경제 계산/실제 저장이 아니다. */
object ShopPreviewStates {
    fun normal(): ShopScreenState.Content {
        val rule = RuleVersion("shop-ui-fixture-v1")
        val appearance = ShopAppearance("드라이브 가게", 0xff8fbcff.toInt())
        val expansion = ShopExpansion(rule, true, 2, 5, 7, 10, GameMoney(1500))
        val fuel = FacilityOffer(Facility.FUEL_STATION, rule, true, GameMoney(500))
        return ShopScreenState.Content(
            Revision(12), "시험 가게", 2, 2, 5, GameMoney(2400), appearance,
            listOf(ShopVehicle(OwnedVehicleId("fixture-car-1"), "캐스퍼 · 시험 표시", "대여 가능 · 공급값"),
                ShopVehicle(OwnedVehicleId("fixture-car-2"), "아반떼 · 시험 표시", "대여 중 · 공급값")),
            listOf(
                ShopFacility(Facility.FUEL_STATION, ShopOption("fuel", "주유소", "보유 차량 5대 필요 · 현재 2대", "구입 후 대여 추가 수익 · 금액 미제공",
                    ShopAvailability.LOCKED, ShopCommand.BuyFacility(fuel), fuel.price, rule, true)),
                ShopFacility(Facility.INSURANCE, ShopOption("insurance", "보험 시설", "완료 고객 100명 필요 · 현재 기록 미제공", "사고 손실 완화 · 공급값",
                    ShopAvailability.UNKNOWN)),
                ShopFacility(Facility.CAR_WASH, ShopOption("wash", "세차장", "가게 레벨 3 필요 · 현재 레벨 2", "구입 후 리뷰 효과 · 공급값",
                    ShopAvailability.LOCKED)),
            ),
            ShopOption("expand", "레벨 2 → 3 · 공간 5 → 7", "완료 고객 10명 필요 · 현재 12명", "레벨 3 · 공간 7칸",
                ShopAvailability.AVAILABLE, ShopCommand.Expand(expansion), expansion.price, rule, true),
            listOf(ShopOption("blue-sign", "파란 간판 · 승인된 시험안", "시험용 승인 표시 입력 · 실제 품목/보유 정책 미연결", "간판 문구와 표시 색상 변경",
                ShopAvailability.AVAILABLE, ShopCommand.Decorate("blue-sign", appearance.copy(sign = "파란 가게", accentArgb = 0xffa8cfff.toInt())),
                GameMoney(0), rule, true)), ShopOrigin.FIXTURE,
        )
    }
    fun missing() = normal().copy(level = null, ownedCount = null, capacity = null, balance = null,
        vehicles = emptyList(), facilities = emptyList(), expansion = null, decorations = emptyList())
}
