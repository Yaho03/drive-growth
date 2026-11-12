package io.drivegrowth.app.ui.pixel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import io.drivegrowth.core.contract.VehicleId
import java.io.FileNotFoundException

/** 한 화면의 차종 그림을 재사용한다. 개체 ID와 거래 상태는 화면에서 관리한다. */
internal class VehicleSprites(context: Context) {
    private val assets = context.assets
    private val resources = context.resources
    private val bitmaps = mutableMapOf<VehicleId, Bitmap?>()

    fun bitmap(vehicleId: VehicleId): Bitmap? {
        if (vehicleId !in bitmaps) {
            bitmaps[vehicleId] = try {
                assets.open("pixel/vehicles/${vehicleId.value}_se.png").use { BitmapFactory.decodeStream(it) }
                    ?.apply { density = Bitmap.DENSITY_NONE }
            } catch (_: FileNotFoundException) {
                null
            }
        }
        return bitmaps[vehicleId]
    }

    fun drawable(vehicleId: VehicleId): BitmapDrawable? =
        bitmap(vehicleId)?.let { bitmap ->
            BitmapDrawable(resources, bitmap).apply { setFilterBitmap(false) }
        }
}
