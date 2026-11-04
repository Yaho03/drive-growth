package io.drivegrowth.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import io.drivegrowth.app.ui.DashboardView
import io.drivegrowth.app.ui.UiModel

class MainActivity : Activity() {
    private val runtime get() = (application as DriveApplication).runtime
    private lateinit var dashboard: DashboardView
    private var lastModel: UiModel? = null
    private val observer: (UiModel) -> Unit = { model ->
        if (lastModel != model) { dashboard.render(model); lastModel = model }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dashboard = DashboardView(this) { runtime.act(it) }
        setContentView(dashboard)
        val permissions = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1)
        startForegroundService(Intent(this, RecordingService::class.java))
    }

    override fun onStart() { super.onStart(); runtime.observe(observer) }
    override fun onStop() { runtime.removeObserver(observer); super.onStop() }
}
