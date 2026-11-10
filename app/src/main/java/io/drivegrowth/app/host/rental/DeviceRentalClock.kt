package io.drivegrowth.app.host.rental

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.contract.*

/** 같은 기기의 실제 OS boot counter/UTC/elapsedRealtime. receiver·UUID·기본0으로 부팅을 만들지 않는다. */
class DeviceRentalClock(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    @Volatile private var boot: String? = null
    fun read(): ContractResult<RentalClock> {
        val elapsed = SystemClock.elapsedRealtime()
        val utc = System.currentTimeMillis()
        return try {
            val id = boot ?: Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).let {
                if (it < 0) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                "android-boot-count:$it".also { actual -> boot = actual }
            }
            if (utc < 0 || elapsed < 0) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            else ContractResult.Success(RentalClock(id, EpochMillis(utc), elapsed))
        } catch (_: Settings.SettingNotFoundException) { ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
          catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
    }
}
