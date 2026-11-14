package io.drivegrowth.app.ui.garage

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.progression.*

/** debug 전용 고정 입력. DB·실차·제품 가격표에서 읽은 값이 아니다. */
object GaragePreviewStates {
    private val names = listOf(
        "hyundai_casper" to "캐스퍼", "hyundai_venue" to "베뉴", "hyundai_kona" to "코나",
        "hyundai_avante" to "아반떼", "hyundai_sonata" to "쏘나타", "hyundai_tucson" to "투싼",
        "hyundai_staria_tourer" to "스타리아 투어러", "hyundai_casper_electric" to "캐스퍼 일렉트릭",
        "hyundai_grandeur" to "그랜저", "hyundai_santafe" to "싼타페", "hyundai_staria_lounge" to "스타리아 라운지",
        "hyundai_ioniq5" to "아이오닉 5", "hyundai_ioniq6" to "아이오닉 6", "hyundai_nexo" to "넥쏘",
        "genesis_g80" to "G80", "hyundai_palisade" to "팰리세이드", "genesis_gv70" to "GV70",
        "hyundai_ioniq9" to "아이오닉 9", "genesis_g90" to "G90", "genesis_gv80" to "GV80",
    )
    private fun vehicle(index: Int, activity: VehicleActivity?) = VehiclePresentation(
        vehicle = OwnedVehicle(OwnedVehicleId("preview-$index"), VehicleId("hyundai_casper"), listOf(2, 3, 4, 5, 7)[index], Revision(index), VehicleLifecycle.OWNED,
            rarity = Rarity.entries[index % 4], levelProgressPercent = listOf(0, 25, 50, 75, 0)[index],
            acquisitionCost = GameMoney(500), acquisitionSource = VehicleAcquisitionSource.GACHA),
        name = "캐스퍼", instanceLabel = "#${index + 1}",
        activity = activity, accidentLabel = "${index}회 · 고정 시험값", modelPriceLabel = null,
        // 아래 허용/불가도 규칙 계산이 아닌 고정 UI 시험 입력이다.
        actions = VehicleAction.entries.associateWith {
            ActionPresentation(
                when (index) {
                    0 -> it in setOf(VehicleAction.LEVEL_UP, VehicleAction.USE_MATERIAL, VehicleAction.SELL)
                    3 -> it in setOf(VehicleAction.REPAIR, VehicleAction.SCRAP)
                    else -> false
                }, "고정 시험 입력 · 실제 명령 미연결",
            )
        },
    )
    val normal = GarageScreenState.Content(GarageContent(
        listOf(
            vehicle(0, VehicleActivity.IDLE), vehicle(1, VehicleActivity.RENTED),
            vehicle(2, VehicleActivity.RETURNING).copy(activityDetail = "반납 시각 확인 불가"),
            vehicle(3, VehicleActivity.NEEDS_REPAIR),
            vehicle(4, null).copy(name = "긴 차종명 배치 시험용 캐스퍼 일렉트릭 추가 설명",
                accidentLabel = null, actions = emptyMap(),
                informationNote = "활동·사고 이력·차종 가격 미지원 · 현재 연결에서 제공하지 않아요"),
        ), "보유 5대 / 공간 8칸 · 고정 시험값", "고정 시험 데이터 · 저장·차량 입력 아님",
    ))
    val empty = GarageScreenState.Content(GarageContent(emptyList(), "보유 0대 / 공간 3칸 · 고정 시험값", "고정 시험 데이터"))
    val loading: GarageScreenState = GarageScreenState.Loading
    val error = GarageScreenState.Error("차고를 읽지 못했어요. 다시 시도해 주세요.")
    /** normal에서 preview-0 선택 후 이 상태를 render하면 선택 개체 소멸을 재현한다. */
    val removed = normal.copy(data = normal.data.copy(vehicles = normal.data.vehicles.drop(1), capacityLabel = "보유 4대 / 공간 8칸 · 고정 시험값"))
    val collection = CollectionScreenState.Content(names.flatMapIndexed { index, (id, name) ->
        Rarity.entries.map { rarity -> CollectionPresentation(
            CollectionEntry(CollectionKey(VehicleId(id), rarity), when (rarity) {
                Rarity.COMMON -> CollectionState.OWNED
                Rarity.ADVANCED -> CollectionState.DISCOVERED
                else -> CollectionState.UNDISCOVERED
            }), name, unlockShopLevel = index / 5 + 1,
        ) }
    }, "고정 시험 데이터 · 과거 보유 포함 · 실제 기록 아님")
    val emptyCollection = CollectionScreenState.Content(emptyList(), "고정 시험 데이터")
    val collectionError = CollectionScreenState.Error("수집 기록을 읽지 못했어요.")
}
