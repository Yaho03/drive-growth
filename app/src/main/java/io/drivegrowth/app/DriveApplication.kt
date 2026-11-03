package io.drivegrowth.app

import android.app.Application

class DriveApplication : Application() {
    val runtime: DriveRuntime by lazy { DriveRuntime(this) }
}
