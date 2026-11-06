package io.drivegrowth.app.vehicle

import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation

/** 신호 하나의 조회 실패를 원인별 누락으로 바꾼다. 한 신호의 실패가 다른 신호를 지우지 않는다. */
internal inline fun <T> readSignal(read: () -> Observation<T>): Observation<T> =
    try {
        read()
    } catch (_: SecurityException) {
        Observation.Missing(MissingReason.PERMISSION_DENIED)
    } catch (_: IllegalArgumentException) {
        Observation.Missing(MissingReason.UNSUPPORTED)
    } catch (_: RuntimeException) {
        Observation.Missing(MissingReason.NO_DATA)
    }
