package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.OwnedVehicleId

enum class HostPage { HOME, GARAGE, COLLECTION, RESULTS, ONBOARDING, GACHA, RANKING, DELIVERY, PROGRESSION }

/** 화면 선택·준비 요청만 복원한다. 차량 허용 판정과 거래 성공은 저장하지 않는다. */
class HostNavigation(
    var page: HostPage = HostPage.HOME,
    var selectedVehicleId: OwnedVehicleId? = null,
    var pendingGarageRequest: GarageEvent.RequestAction? = null,
    var returnPage: HostPage = HostPage.HOME,
) {
    /** 초기 안내의 지연 응답. 금융/NEW 판별은 호출자의 검증된 profile에 맡긴다. */
    fun acceptInitialIntro(profile: String?, alreadyRouted: String?, automaticEntryAllowed: Boolean): String? {
        if (profile == null || profile == alreadyRouted) return alreadyRouted
        if (automaticEntryAllowed && page == HostPage.HOME) page = HostPage.ONBOARDING
        return profile
    }

    fun openAward(id: OwnedVehicleId) {
        page = HostPage.GARAGE
        selectedVehicleId = id
        returnPage = HostPage.GACHA
    }

    /** true이면 최상위 앱 숨김을 호스트가 수행한다. */
    fun back(): Boolean {
        when {
            pendingGarageRequest != null -> pendingGarageRequest = null
            page == HostPage.GARAGE && selectedVehicleId != null -> selectedVehicleId = null
            page != HostPage.HOME -> { page = returnPage; returnPage = HostPage.HOME }
            else -> return true
        }
        return false
    }
}
