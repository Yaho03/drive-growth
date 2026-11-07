package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.OwnedVehicleId

enum class HostPage { HOME, GARAGE, COLLECTION, RESULTS, ONBOARDING }

/** 화면 선택·준비 요청만 복원한다. 차량 허용 판정과 거래 성공은 저장하지 않는다. */
class HostNavigation(
    var page: HostPage = HostPage.HOME,
    var selectedVehicleId: OwnedVehicleId? = null,
    var pendingGarageRequest: GarageEvent.RequestAction? = null,
) {
    /** true이면 최상위 앱 숨김을 호스트가 수행한다. */
    fun back(): Boolean {
        when {
            pendingGarageRequest != null -> pendingGarageRequest = null
            page == HostPage.GARAGE && selectedVehicleId != null -> selectedVehicleId = null
            page != HostPage.HOME -> page = HostPage.HOME
            else -> return true
        }
        return false
    }
}
